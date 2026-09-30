#!/usr/bin/env python3
"""Builds and signs the update manifest of a release APK (phase 30, D-20260928-060).

  CORTANA_KEYSTORE=keystore/cortana-keystore.jks CORTANA_STORE_PASS=... \
  python3 tools/make_update_manifest.py path/to/cortana-release.apk --notes "…" [--out dir]

The manifest (JSON text) is signed with SHA256withRSA by the key that signs the APK, so the
installed app verifies it with its own signing certificate. Secrets come from the environment
only; the private key goes from openssl to openssl through a pipe and never touches the disk.
"""
import argparse, base64, hashlib, json, os, re, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/opt/android-sdk"


def tool(name):
    bt = os.path.join(SDK, "build-tools")
    for v in sorted(os.listdir(bt), reverse=True):
        p = os.path.join(bt, v, name)
        if os.path.exists(p):
            return p
    sys.exit(f"{name} introuvable dans {bt}")


def const(path, pattern):
    m = re.search(pattern, open(os.path.join(ROOT, path), encoding="utf-8").read())
    if not m:
        sys.exit(f"constante introuvable dans {path}")
    return m.group(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--notes", default="")
    ap.add_argument("--apk-url", default=None, help="URL ou chemin relatif au manifeste (défaut : nom du fichier)")
    ap.add_argument("--min-upgrade-from", type=int, default=1)
    ap.add_argument("--out", default=".")
    a = ap.parse_args()
    ks, pw = os.environ.get("CORTANA_KEYSTORE"), os.environ.get("CORTANA_STORE_PASS")
    if not ks or not pw:
        sys.exit("CORTANA_KEYSTORE et CORTANA_STORE_PASS doivent être définis (jamais en argument ni dans un fichier suivi)")

    badging = subprocess.run([tool("aapt2"), "dump", "badging", a.apk], capture_output=True, text=True, check=True).stdout
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    min_sdk = int(re.search(r"minSdkVersion:'(\d+)'", badging).group(1))
    certs = subprocess.run([tool("apksigner"), "verify", "--print-certs", a.apk], capture_output=True, text=True, check=True).stdout
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", certs)
    if len(digests) != 1:
        sys.exit(f"un seul signataire attendu, trouvé : {digests}")
    history = json.load(open(os.path.join(ROOT, "release", "released.json"), encoding="utf-8"))
    if digests[0] != history["signingCertSha256"]:
        sys.exit("l'APK n'est pas signé par le certificat de Cortana : refus")
    data = open(a.apk, "rb").read()

    manifest = {
        "format": "cortana-update", "formatVersion": 1,
        "packageName": pkg.group(1), "versionCode": int(pkg.group(2)), "versionName": pkg.group(3),
        "apkUrl": a.apk_url or os.path.basename(a.apk),
        "sha256": hashlib.sha256(data).hexdigest(), "size": len(data), "minSdk": min_sdk,
        "signingCertSha256": digests[0], "minUpgradeFromVersionCode": a.min_upgrade_from,
        "compatibility": {
            "appVersion": pkg.group(3), "appVersionCode": int(pkg.group(2)),
            "dbSchema": int(const("app/src/main/java/io/github/artisanguillonrenov/cortana/core/memory/CortanaDatabase.kt", r"const val VERSION = (\d+)")),
            "contractsVersion": const("contracts/src/main/kotlin/io/github/artisanguillonrenov/cortana/contracts/Core.kt", r'CONTRACTS_SCHEMA_VERSION = "([^"]+)"'),
            "workerProtocol": const("contracts/src/main/kotlin/io/github/artisanguillonrenov/cortana/contracts/Workspaces.kt", r'object WorkerProtocol \{\s*const val VERSION = "([^"]+)"'),
            "backupFormat": 1, "minRestorableSchema": 2,
        },
        "releaseNotes": a.notes, "publishedAt": int(os.path.getmtime(a.apk) * 1000),
    }
    text = json.dumps(manifest, ensure_ascii=False, separators=(",", ":"))
    with tempfile.TemporaryDirectory() as tmp:
        body = os.path.join(tmp, "manifest.json")
        open(body, "w", encoding="utf-8").write(text)
        env = dict(os.environ)
        key = subprocess.run(["openssl", "pkcs12", "-in", ks, "-nocerts", "-nodes", "-passin", "env:CORTANA_STORE_PASS"], capture_output=True, check=True, env=env).stdout
        sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", "/dev/stdin", body], input=key, capture_output=True, check=True).stdout
        del key
        cert = subprocess.run(["openssl", "pkcs12", "-in", ks, "-nokeys", "-clcerts", "-passin", "env:CORTANA_STORE_PASS"], capture_output=True, check=True, env=env).stdout
        pub = subprocess.run(["openssl", "x509", "-pubkey", "-noout"], input=cert, capture_output=True, check=True).stdout
        pubf = os.path.join(tmp, "pub.pem"); open(pubf, "wb").write(pub)
        sigf = os.path.join(tmp, "sig.bin"); open(sigf, "wb").write(sig)
        ok = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pubf, "-signature", sigf, body], capture_output=True, text=True)
        if "Verified OK" not in ok.stdout:
            sys.exit("vérification de la signature impossible : refus")
        der = subprocess.run(["openssl", "x509", "-outform", "DER"], input=cert, capture_output=True, check=True).stdout
        if hashlib.sha256(der).hexdigest() != digests[0]:
            sys.exit("la clé du magasin ne correspond pas au certificat de l'APK : refus")
    envelope = {"manifest": text, "signature": base64.b64encode(sig).decode(), "algorithm": "SHA256withRSA"}
    os.makedirs(a.out, exist_ok=True)
    out = os.path.join(a.out, "cortana-update.json")
    json.dump(envelope, open(out, "w", encoding="utf-8"), ensure_ascii=False)
    print(f"{out} : {manifest['versionName']} ({manifest['versionCode']}), SHA-256 {manifest['sha256']}, signature vérifiée")


if __name__ == "__main__":
    main()
