#!/bin/bash
# Wrapper to compile (if needed) and run AutoKudo.
# Used by the launchd LaunchAgent at login.

set -e

PROJECT_DIR="/Users/a0b0377/git/junk/strava-auto-kudo"
SRC="$PROJECT_DIR/src/AutoKudo.java"
OUT="$PROJECT_DIR/out"
LOG_DIR="$PROJECT_DIR/logs"

mkdir -p "$OUT" "$LOG_DIR"

# Make sure java/javac are on PATH (launchd has a minimal PATH).
export PATH="/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin:$PATH"

# Recompile if source is newer than the class file.
if [ ! -f "$OUT/AutoKudo.class" ] || [ "$SRC" -nt "$OUT/AutoKudo.class" ]; then
    /usr/bin/env javac -d "$OUT" "$SRC"
fi

# Brief wait for network (max 20s).
sleep 5
for i in $(seq 1 8); do
    if /usr/bin/curl -sk -o /dev/null -w "%{http_code}" --max-time 3 https://www.strava.com/ | grep -qE "^(200|301|302)$"; then
        break
    fi
    sleep 2
done

# Run — pass any args through.
# Prefer Walmart proxy (needed on corp/VPN, harmless on home wifi if reachable).
# Retry a few times so a transient DNS blip doesn't misclassify the network.
JAVA_PROXY_ARGS=""
PROXY_OK=0
for i in 1 2 3; do
    if /usr/bin/curl -sS --max-time 10 -o /dev/null -x http://proxy-intlho.wal-mart.com:8080 https://www.strava.com/ 2>/dev/null; then
        PROXY_OK=1; break
    fi
    sleep 3
done

if [ "$PROXY_OK" = "1" ]; then
    JAVA_PROXY_ARGS="-Dhttps.proxyHost=proxy-intlho.wal-mart.com -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy-intlho.wal-mart.com -Dhttp.proxyPort=8080"
    echo "Using corp proxy proxy-intlho.wal-mart.com:8080"
    exec /usr/bin/env java \
        $JAVA_PROXY_ARGS \
        -Djavax.net.ssl.trustStoreType=KeychainStore \
        -Djavax.net.ssl.trustStore=NONE \
        -cp "$OUT" AutoKudo --max-pages 15 "$@"
elif /usr/bin/curl -sS --max-time 8 -o /dev/null https://www.strava.com/ 2>/dev/null; then
    echo "Corp proxy unreachable; going direct"
    exec /usr/bin/env java \
        -cp "$OUT" AutoKudo --max-pages 15 "$@"
else
    echo "No network to Strava. Aborting."
#    exit 0
fi

#exec /usr/bin/env java \
#    $JAVA_PROXY_ARGS \
#    -Djavax.net.ssl.trustStoreType=KeychainStore \
#    -Djavax.net.ssl.trustStore=NONE \
#    -cp "$OUT" AutoKudo --max-pages 15 "$@"
