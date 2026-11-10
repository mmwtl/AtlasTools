#!/bin/sh
# Starts a localhost-only root shell inside an Android emulator so AtlasTools can be tested in
# Telnet mode (host "localhost", port 2323). The emulator's adbd never listens on TCP inside the
# guest, so ADB mode cannot be exercised there.
#
# Usage: scripts/emulator-telnet-shell.sh [start|stop|status] [port]
#   ANDROID_SERIAL selects the device; otherwise the only connected emulator is used.
#
# Refuses to run on anything that is not an emulator: a root shell listener must never be left
# on a real head unit.

set -eu

ACTION="${1:-start}"
PORT="${2:-2323}"
ADB_BIN="${ADB:-}"

if [ -z "$ADB_BIN" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB_BIN="adb"
    else
        ADB_BIN="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
    fi
fi
if ! "$ADB_BIN" version >/dev/null 2>&1; then
    echo "adb not found; set ADB or ANDROID_HOME" >&2
    exit 1
fi

case "$PORT" in
    ''|*[!0-9]*) echo "invalid port: $PORT" >&2; exit 1 ;;
esac
if [ "$PORT" -lt 1024 ] || [ "$PORT" -gt 65535 ]; then
    echo "port must be within 1024..65535" >&2
    exit 1
fi

if [ -z "${ANDROID_SERIAL:-}" ]; then
    EMULATORS="$("$ADB_BIN" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')"
    COUNT="$(printf '%s\n' "$EMULATORS" | grep -c . || true)"
    if [ "$COUNT" -ne 1 ]; then
        echo "expected exactly one running emulator, found $COUNT; set ANDROID_SERIAL" >&2
        exit 1
    fi
    ANDROID_SERIAL="$EMULATORS"
fi
export ANDROID_SERIAL

adb_shell() {
    "$ADB_BIN" shell "$@" | tr -d '\r'
}

QEMU="$(adb_shell getprop ro.kernel.qemu)$(adb_shell getprop ro.boot.qemu)"
case "$QEMU" in
    *1*) ;;
    *) echo "$ANDROID_SERIAL is not an emulator; refusing" >&2; exit 1 ;;
esac

# Matches only the listener this script starts.
LISTENER_PATTERN="nc -s 127.0.0.1 -p $PORT -L sh"

listener_pid() {
    adb_shell "ps -A -o PID,ARGS | grep '[n]c -s 127.0.0.1 -p $PORT -L sh' | awk '{ print \$1 }'"
}

probe() {
    adb_shell "printf 'id -u\n' | toybox nc -w 2 127.0.0.1 $PORT 2>/dev/null"
}

case "$ACTION" in
    start)
        if [ "$(adb_shell id -u)" != "0" ]; then
            "$ADB_BIN" root >/dev/null
            "$ADB_BIN" wait-for-device
            if [ "$(adb_shell id -u)" != "0" ]; then
                echo "adb root is not available on this emulator image" >&2
                exit 1
            fi
        fi
        if [ -z "$(listener_pid)" ]; then
            adb_shell "setsid toybox $LISTENER_PATTERN </dev/null >/dev/null 2>&1 &"
        fi
        attempt=0
        until [ "$(probe)" = "0" ]; do
            attempt=$((attempt + 1))
            if [ "$attempt" -ge 10 ]; then
                echo "root shell on 127.0.0.1:$PORT did not answer" >&2
                exit 1
            fi
            sleep 1
        done
        echo "root shell ready on $ANDROID_SERIAL at 127.0.0.1:$PORT"
        echo "AtlasTools: Подключение -> Telnet, адрес localhost, порт $PORT"
        ;;
    stop)
        PIDS="$(listener_pid)"
        if [ -n "$PIDS" ]; then
            for pid in $PIDS; do
                adb_shell "kill $pid"
            done
            echo "stopped listener on 127.0.0.1:$PORT"
        else
            echo "no listener on 127.0.0.1:$PORT"
        fi
        ;;
    status)
        if [ "$(probe)" = "0" ]; then
            echo "root shell is answering on 127.0.0.1:$PORT (pid $(listener_pid))"
        else
            echo "no root shell on 127.0.0.1:$PORT"
            exit 1
        fi
        ;;
    *)
        echo "usage: $0 [start|stop|status] [port]" >&2
        exit 1
        ;;
esac
