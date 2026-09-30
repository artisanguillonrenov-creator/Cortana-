#!/usr/bin/env python3
"""Compiles every regular expression of the Android app with ICU4C (D-20260928-066).

On Android, java.util.regex.Pattern is backed by ICU4C (libcore: RegexPattern::compile with the
flags CASE_INSENSITIVE|COMMENTS|MULTILINE|DOTALL|UNIX_LINES and UREGEX_ERROR_ON_UNKNOWN_ESCAPES).
The unit tests run on the JVM, whose engine accepts patterns ICU rejects — 2.0.0-rc1 crashed at
startup on "\\{\\{([a-zA-Z0-9_]+)}}" (bare "}"). This tool compiles each pattern with the real
ICU4C library of the build machine instead.

  python3 tools/check_android_regex.py               check; exit 1 if ICU rejects a pattern
  python3 tools/check_android_regex.py --list        also print every pattern checked
  python3 tools/check_android_regex.py --write-asset rewrite the androidTest asset (on-device check)
  python3 tools/check_android_regex.py --check-asset exit 1 if that asset is stale

Sources: app/src/main and contracts/src/main (both run on the device), plus the patterns shipped as
data in app assets (model_caps.json). Call sites: Regex(...),
Pattern.compile(...), .toRegex(...), String.matches("..."). The pattern argument is evaluated from
string literals (plain or raw, Kotlin escapes applied) joined by "+"; templates naming a string
constant of the same file are replaced by its value; other templates and non-literal operands become
a neutral "x" and the pattern is marked "dynamic" (its literal skeleton is still compiled). An argument without any literal is "runtime" input (owner or data), compiled on the
device where it is used, inside error handling.
"""
import bisect, ctypes, glob, json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = ["app/src/main", "contracts/src/main"]
ASSET = "app/src/androidTest/assets/regex_patterns.json"

# java.util.regex.Pattern flag values (identical to ICU's UREGEX_* for the ones Android forwards).
FLAGS = {"UNIX_LINES": 1, "CASE_INSENSITIVE": 2, "IGNORE_CASE": 2, "COMMENTS": 4, "MULTILINE": 8,
         "LITERAL": 16, "DOTALL": 32, "DOT_MATCHES_ALL": 32, "UNICODE_CASE": 64, "CANON_EQ": 128}
ANDROID_FORWARDED = 1 | 2 | 4 | 8 | 32
UREGEX_ERROR_ON_UNKNOWN_ESCAPES = 512


# ------------------------------------------------------------------ ICU4C

class Icu:
    def __init__(self):
        lib = os.environ.get("ICU_I18N") or next(iter(sorted(glob.glob("/usr/lib/*/libicui18n.so.*") + glob.glob("/usr/lib/libicui18n.so.*") + glob.glob("/usr/lib64/libicui18n.so.*"), reverse=True)), None)
        if not lib:
            sys.exit("ICU4C (libicui18n) introuvable : installez libicu (paquet libicu*) ou définissez ICU_I18N")
        self.i18n = ctypes.CDLL(lib)
        self.uc = ctypes.CDLL(lib.replace("libicui18n", "libicuuc"))
        major = re.search(r"\.so\.(\d+)", lib).group(1)
        self.version = re.search(r"\.so\.([\d.]+)", lib).group(1)
        self.open = getattr(self.i18n, f"uregex_open_{major}")
        self.close = getattr(self.i18n, f"uregex_close_{major}")
        self.err = getattr(self.uc, f"u_errorName_{major}")
        self.err.restype = ctypes.c_char_p
        self.open.restype = ctypes.c_void_p
        self.open.argtypes = [ctypes.c_char_p, ctypes.c_int32, ctypes.c_uint32, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]
        self.close.argtypes = [ctypes.c_void_p]

    def compile(self, pattern, java_flags):
        """Returns None if ICU compiles it as Android would, else the ICU error and offset."""
        if java_flags & FLAGS["LITERAL"]:
            return None  # Android quotes a LITERAL pattern: always valid
        u16 = pattern.encode("utf-16-le")
        parse_error = ctypes.create_string_buffer(72)  # UParseError: line, offset, preContext[16], postContext[16]
        status = ctypes.c_int(0)
        flags = (java_flags & ANDROID_FORWARDED) | UREGEX_ERROR_ON_UNKNOWN_ESCAPES
        h = self.open(u16, len(u16) // 2, flags, parse_error, ctypes.byref(status))
        if h:
            self.close(h)
        if status.value > 0:
            offset = int.from_bytes(parse_error.raw[4:8], "little", signed=True)
            return f"{self.err(status.value).decode()} à la position {offset}"
        return None


# ------------------------------------------------------------------ Kotlin source scanning

ESC = {"t": "\t", "b": "\b", "n": "\n", "r": "\r", "'": "'", '"': '"', "\\": "\\", "$": "$"}
IDENT = re.compile(r"[A-Za-z_]\w*")


def scan(text):
    """Splits Kotlin source into code (strings replaced by markers, comments removed) and literals.
    Also returns, for each character of that code, the source line it comes from."""
    out, where, lits, i, n = [], [], [], 0, len(text)
    newlines = [k for k, ch in enumerate(text) if ch == "\n"]

    def emit(s, at):
        line = bisect.bisect_left(newlines, at) + 1
        out.append(s); where.extend([line] * len(s))

    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i); i = n if j < 0 else j
        elif text.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if text.startswith("/*", i): depth += 1; i += 2
                elif text.startswith("*/", i): depth -= 1; i += 2
                else: i += 1
        elif c == '"':
            value, dynamic, j = read_string(text, i)
            emit(f"\x00{len(lits)}\x00", i); lits.append((value, dynamic)); i = j
        elif c == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            emit("'c'", i); i = j + 1
        else:
            emit(c, i); i += 1
    return "".join(out), where, lits


def read_string(text, i):
    raw = text.startswith('"""', i)
    i += 3 if raw else 1
    buf, dynamic = [], False
    while i < len(text):
        c = text[i]
        if raw and text.startswith('"""', i):
            while text.startswith('""""', i): buf.append('"'); i += 1  # trailing quotes belong to the string
            return "".join(buf), dynamic, i + 3
        if not raw and c == '"':
            return "".join(buf), dynamic, i + 1
        if not raw and c == "\\":
            e = text[i + 1]
            if e == "u": buf.append(chr(int(text[i + 2:i + 6], 16))); i += 6
            else: buf.append(ESC.get(e, e)); i += 2
            continue
        if c == "$" and i + 1 < len(text) and text[i + 1] == "{":
            depth, j = 1, i + 2
            while depth:
                if text[j] == '"': _, _, j = read_string(text, j); continue
                depth += {"{": 1, "}": -1}.get(text[j], 0); j += 1
            inner = text[i + 2:j - 1].strip()
            if inner == "'$'": buf.append("$")
            else:
                name = inner if IDENT.fullmatch(inner) else ""
                buf.append("\x01" + name + "\x02"); dynamic = True
            i = j
            continue
        if c == "$" and i + 1 < len(text) and (text[i + 1].isalpha() or text[i + 1] == "_"):
            j = i + 1
            while j < len(text) and (text[j].isalnum() or text[j] == "_"): j += 1
            buf.append(f"\x01{text[i + 1:j]}\x02"); dynamic = True; i = j
            continue
        buf.append(c); i += 1
    raise ValueError("chaîne non terminée")


def args_of(code, start):
    """Top-level arguments of the call whose "(" is at start."""
    depth, cur, args, i = 0, [], [], start
    while i < len(code):
        c = code[i]
        if c in "([{": depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                args.append("".join(cur[1:]) if not args else "".join(cur)); return [a.strip() for a in args], i
        if c == "," and depth == 1:
            args.append("".join(cur[1:]) if not args else "".join(cur)); cur = []; i += 1; continue
        cur.append(c); i += 1
    return [], i


def split_plus(expr):
    parts, depth, cur = [], 0, []
    for c in expr:
        if c in "([{": depth += 1
        elif c in ")]}": depth -= 1
        if c == "+" and depth == 0: parts.append("".join(cur).strip()); cur = []
        else: cur.append(c)
    parts.append("".join(cur).strip())
    return parts


MARK = re.compile(r"^\x00(\d+)\x00$")
TEMPLATE = re.compile(r"\x01(\w*)\x02")
CONST = re.compile(r"\bval\s+(\w+)\s*(?::\s*String\s*)?=\s*\x00(\d+)\x00(?!\s*(?:\.|\+|\())")


def resolve(value, lits, consts, depth=0):
    """Replaces templates naming a string constant of the same file by its value; others by "x"."""
    dynamic = False

    def sub(m):
        nonlocal dynamic
        name = m.group(1)
        if name in consts and depth < 5:
            v, d = resolve(lits[consts[name]][0], lits, consts, depth + 1)
            dynamic = dynamic or d
            return v
        dynamic = True
        return "x"
    return TEMPLATE.sub(sub, value), dynamic


def evaluate(expr, lits, consts):
    """(pattern, kind): kind is static, dynamic or runtime."""
    value, literal, dynamic = [], False, False
    for p in split_plus(expr):
        m = MARK.match(p)
        index = int(m.group(1)) if m else consts.get(p) if re.fullmatch(r"\w+", p) else None
        if index is not None:
            v, d = resolve(lits[index][0], lits, consts); value.append(v); literal = True; dynamic = dynamic or d
        else:
            value.append("x"); dynamic = True
    if not literal:
        return None, "runtime"
    return "".join(value), "dynamic" if dynamic else "static"


# Regular expressions shipped as data and compiled by the app (a refused one would be dropped silently).
ASSET_REGEXES = {"app/src/main/assets/configs/model_caps.json": lambda d: [r["match"] for r in d["rules"]]}


def flags_of(opts):
    f = 0
    for name, v in FLAGS.items():
        if re.search(rf"\b{name}\b", opts or ""): f |= v
    if f & FLAGS["IGNORE_CASE"] and "RegexOption" in (opts or ""): f |= FLAGS["UNICODE_CASE"]
    return f


CALLS = re.compile(r"(?<![\w.])(?:kotlin\.text\.)?Regex\s*\(|\bPattern\.compile\s*\(|\.toRegex\s*\(|\.matches\s*\(")


def patterns():
    for base in SOURCES:
        for path in sorted(glob.glob(os.path.join(ROOT, base, "**", "*.kt"), recursive=True)):
            text = open(path, encoding="utf-8").read()
            code, where, lits = scan(text)
            consts = {m.group(1): int(m.group(2)) for m in CONST.finditer(code)}
            for m in CALLS.finditer(code):
                call = m.group(0)
                args, _ = args_of(code, m.end() - 1)
                if call.startswith(".toRegex"):
                    before = code[:m.start()].rstrip()
                    mk = re.search(r"\x00(\d+)\x00$", before)
                    expr, opts = (mk.group(0) if mk else "?"), (args[0] if args else "")
                elif call.startswith(".matches"):
                    if not args or not MARK.match(args[0]): continue  # matches(Regex) or a CharSequence: not a pattern string
                    expr, opts = args[0], ""
                else:
                    if not args: continue
                    expr, opts = args[0], (args[1] if len(args) > 1 else "")
                pattern, kind = evaluate(expr, lits, consts) if expr != "?" else (None, "runtime")
                line = where[m.start()]
                yield {"file": os.path.relpath(path, ROOT), "line": line, "pattern": pattern, "flags": flags_of(opts), "kind": kind}
    for rel, extract in ASSET_REGEXES.items():
        for k, pattern in enumerate(extract(json.load(open(os.path.join(ROOT, rel), encoding="utf-8")))):
            yield {"file": rel, "line": k + 1, "pattern": pattern, "flags": 0, "kind": "static"}


def main():
    icu = Icu()
    found = list(patterns())
    errors, checked = [], []
    for p in found:
        if p["kind"] == "runtime":
            continue
        e = icu.compile(p["pattern"], p["flags"])
        checked.append(p)
        if e:
            errors.append((p, e))
    if "--list" in sys.argv:
        for p in found:
            print(f"{p['kind']:8} {p['file']}:{p['line']}  {p['pattern']!r}  flags={p['flags']}")
    counts = {k: sum(1 for p in found if p["kind"] == k) for k in ("static", "dynamic", "runtime")}
    print(f"ICU4C {icu.version} : {len(checked)} expressions compilées (statiques {counts['static']}, dynamiques {counts['dynamic']}) ; "
          f"{counts['runtime']} construites à l'exécution (non vérifiables ici) ; {len(errors)} refusée(s) par ICU")
    for p, e in errors:
        print(f"  REFUSÉE {p['file']}:{p['line']}  {p['pattern']!r} : {e}")
    # No line numbers in the asset: it only goes stale when a pattern changes, not when code moves.
    unique = {(p["file"], p["pattern"], p["flags"], p["kind"]) for p in checked}
    asset = [{"file": f, "pattern": pat, "flags": fl, "kind": k} for f, pat, fl, k in sorted(unique)]
    text = json.dumps(asset, ensure_ascii=False, indent=1) + "\n"
    target = os.path.join(ROOT, ASSET)
    if "--write-asset" in sys.argv:
        os.makedirs(os.path.dirname(target), exist_ok=True)
        open(target, "w", encoding="utf-8").write(text)
        print(f"{ASSET} écrit ({len(asset)} expressions)")
    if "--check-asset" in sys.argv:
        current = open(target, encoding="utf-8").read() if os.path.exists(target) else ""
        if current != text:
            print(f"{ASSET} n'est plus à jour : python3 tools/check_android_regex.py --write-asset")
            return 1
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
