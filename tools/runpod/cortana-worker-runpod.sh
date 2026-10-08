#!/usr/bin/env bash
# Cortana worker on a RunPod pod (docs/RUNPOD_WORKER.md).
#
#   bash cortana-worker-runpod.sh            install if needed, start, print the pairing line
#   bash cortana-worker-runpod.sh pair       new pairing line (10 minutes, single use)
#   bash cortana-worker-runpod.sh status     worker state, paired devices, public address
#   bash cortana-worker-runpod.sh logs       last lines of the worker log
#   bash cortana-worker-runpod.sh stop       stops the worker
#
# Everything lives on the persistent volume (/workspace): the TLS key and the paired devices survive a
# pod restart, so the tablet stays paired. The container disk is wiped at restart: run this script again
# after each restart (it reinstalls Java if needed and restarts the worker, without re-pairing).
#
# The worker is reached through a RunPod *TCP* port (Expose TCP Ports: 8765), never through the HTTPS
# proxy (*.proxy.runpod.net): the tablet pins the worker's own certificate, which the proxy would replace.
set -euo pipefail

JAR_URL="${CORTANA_WORKER_JAR_URL:-https://github.com/artisanguillonrenov-creator/Cortana-/releases/download/v2.0.0-rc8/cortana-worker.jar}"
JAR_SHA256="${CORTANA_WORKER_JAR_SHA256:-e31e8a59adbdb12189163158ca4c69badf774b5790935157d4be2c3e8db620ff}"
PORT="${CORTANA_WORKER_PORT:-8765}"
BASE="${CORTANA_WORKER_HOME:-/workspace/cortana-worker}"
DATA="$BASE/data"          # TLS key, paired devices, project copies, jobs (0700)
BIN="$BASE/bin"
JAR="$BIN/cortana-worker.jar"
LOG="$BASE/worker.log"
PIDFILE="$BASE/worker.pid"
# Accented output whatever the pod locale (Java 17 reads sun.stdout.encoding, 18+ stdout.encoding).
JAVA=(java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dstderr.encoding=UTF-8)

say() { printf '%s\n' "$*"; }
die() { printf 'Erreur : %s\n' "$*" >&2; exit 1; }

need_java() {
    if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -Eq 'version "(1[7-9]|[2-9][0-9])'; then return; fi
    command -v apt-get >/dev/null 2>&1 || die "Java 17 ou plus est absent et apt-get est indisponible : installez un JDK 17+."
    say "Installation de Java 17 (environ une minute)…"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq >/dev/null
    apt-get install -y -qq openjdk-17-jre-headless >/dev/null
}

need_jar() {
    mkdir -p "$BIN"
    if [ -f "$JAR" ] && printf '%s  %s\n' "$JAR_SHA256" "$JAR" | sha256sum -c --status; then return; fi
    say "Téléchargement du worker Cortana…"
    curl -fsSL --retry 3 -o "$JAR.part" "$JAR_URL" || die "téléchargement impossible : $JAR_URL"
    if ! printf '%s  %s\n' "$JAR_SHA256" "$JAR.part" | sha256sum -c --status; then
        rm -f "$JAR.part"
        die "empreinte SHA-256 du worker incorrecte : fichier rejeté."
    fi
    mv "$JAR.part" "$JAR"
}

# RunPod sets RUNPOD_PUBLIC_IP and RUNPOD_TCP_PORT_<port> in the container environment; a terminal or
# SSH session does not always inherit them, so they are also read from the first process.
runpod_env() {
    local v="${!1:-}"
    if [ -z "$v" ] && [ -r /proc/1/environ ]; then
        v="$({ tr '\0' '\n' </proc/1/environ; } 2>/dev/null | sed -n "s/^$1=//p" | head -n1 || true)"
    fi
    printf '%s' "$v"
}

running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

listening() { (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; }

start() {
    need_java
    need_jar
    mkdir -p "$DATA"
    chmod 700 "$BASE" "$DATA"
    # Keep a copy of this script on the volume, to run it again after a pod restart.
    if [ -f "$0" ] && [ "$(readlink -f "$0")" != "$(readlink -f "$BIN/cortana-worker-runpod.sh")" ]; then
        cp -f "$0" "$BIN/cortana-worker-runpod.sh"
        chmod 700 "$BIN/cortana-worker-runpod.sh"
    fi
    if running; then
        say "Le worker tourne déjà (PID $(cat "$PIDFILE"))."
    else
        # A small supervisor restarts the worker if it stops; nohup keeps it alive after the terminal closes.
        nohup bash -c 'while true; do
                java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "$1" serve --data "$2" --port "$3"
                echo "[$(date -Is)] worker arrêté (code $?), redémarrage dans 5 s"
                sleep 5
            done' _ "$JAR" "$DATA" "$PORT" >>"$LOG" 2>&1 &
        echo $! >"$PIDFILE"
        say "Démarrage du worker (la première fois, création de sa clé TLS)…"
        for _ in $(seq 1 60); do listening && break; sleep 1; done
        listening || die "le worker n'écoute pas sur le port $PORT : voir $LOG"
        say "Worker à l'écoute sur le port $PORT."
    fi
    pair
}

pair() {
    need_java
    need_jar
    [ -d "$DATA" ] || die "worker jamais démarré : lancez d'abord « bash $0 »."
    local line host port
    line="$("${JAVA[@]}" -jar "$JAR" pair --data "$DATA" --port "$PORT" 2>/dev/null | grep -m1 '^cortana-worker://')" \
        || die "aucune ligne d'appairage produite (voir « bash $0 logs »)."
    host="${CORTANA_PUBLIC_HOST:-$(runpod_env RUNPOD_PUBLIC_IP)}"
    port="${CORTANA_PUBLIC_PORT:-$(runpod_env "RUNPOD_TCP_PORT_$PORT")}"
    say ""
    if [ -n "$host" ] && [ -n "$port" ]; then
        line="$(printf '%s' "$line" | sed -E "s#^cortana-worker://[^?]+\\?#cortana-worker://$host:$port?#")"
        say "Dans Cortana → Appareils → Appairer, collez cette ligne (valable 10 minutes, usage unique) :"
        say ""
        say "  $line"
        say ""
        say "Adresse publique du worker : $host:$port (port TCP RunPod → $PORT)."
    else
        say "ATTENTION : aucun port TCP public trouvé pour le port $PORT."
        say "Dans RunPod : Pods → ce pod → Edit Pod → « Expose TCP Ports » : ajoutez $PORT, puis enregistrez"
        say "(le pod redémarre ; relancez ensuite ce script : bash $BIN/cortana-worker-runpod.sh)."
        say "Ou indiquez l'adresse vous-même : CORTANA_PUBLIC_HOST=IP CORTANA_PUBLIC_PORT=PORT bash $0 pair"
        say "(IP et port externe : Connect → « TCP Port Mappings »)."
        say ""
        say "Ligne brute (adresse à remplacer) : $line"
    fi
}

status() {
    if running; then say "Superviseur : actif (PID $(cat "$PIDFILE"))"; else say "Superviseur : arrêté"; fi
    if listening; then say "Port $PORT : à l'écoute"; else say "Port $PORT : fermé"; fi
    local host port
    host="$(runpod_env RUNPOD_PUBLIC_IP)"
    port="$(runpod_env "RUNPOD_TCP_PORT_$PORT")"
    say "Adresse publique : ${host:-?}:${port:-? (port TCP $PORT non exposé)}"
    if [ -f "$JAR" ] && [ -d "$DATA" ] && command -v java >/dev/null 2>&1; then
        "${JAVA[@]}" -jar "$JAR" status --data "$DATA" --port "$PORT" 2>/dev/null
        say "Appareils :"
        "${JAVA[@]}" -jar "$JAR" devices --data "$DATA" --port "$PORT" 2>/dev/null
    fi
}

stop() {
    if running; then
        local pid kids
        pid="$(cat "$PIDFILE")"
        kids="$(pgrep -P "$pid" || true)"
        kill "$pid" 2>/dev/null || true
        [ -n "$kids" ] && kill $kids 2>/dev/null || true
        say "Worker arrêté."
    else
        say "Le worker ne tournait pas."
    fi
    rm -f "$PIDFILE"
}

case "${1:-start}" in
    start) start ;;
    pair) pair ;;
    status) status ;;
    logs) tail -n "${2:-50}" "$LOG" 2>/dev/null || say "Pas encore de journal." ;;
    stop) stop ;;
    *) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
