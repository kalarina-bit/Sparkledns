#!/usr/bin/env bash
# Builds signed release APKs of two revisions for scripts/bench.sh.
# Usage: scripts/bench-build.sh <base-ref> <head-ref> <out-dir>
set -euo pipefail

base="$(git rev-parse "$1")"
head="$(git rev-parse "$2")"
out="$(mkdir -p "$3" && cd "$3" && pwd)"
work="${RUNNER_TEMP:-/tmp}/bench-src"

keystore="$out/bench.jks"
[[ -f "$keystore" ]] || keytool -genkeypair -keystore "$keystore" -storepass benchpass -keypass benchpass \
    -alias bench -keyalg RSA -keysize 2048 -validity 365 -dname "CN=bench" >/dev/null 2>&1
apksigner="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -n 1)apksigner"

build() {
    local rev="$1" name="$2" dir="$work-$2" apk
    rm -rf "$dir"
    git worktree add -f --detach "$dir" "$rev" >/dev/null
    (cd "$dir" && ./gradlew :app:assembleRelease -q)
    apk="$(ls "$dir"/app/build/outputs/apk/release/*.apk | head -n 1)"
    echo "$name,apk_bytes,$(stat -c %s "$apk")" >> "$out/sizes.csv"
    echo "::notice title=APK size ($name ${rev:0:7})::$(stat -c %s "$apk") bytes"
    "$apksigner" sign --ks "$keystore" --ks-pass pass:benchpass --out "$out/$name.apk" "$apk"
}

rm -f "$out/sizes.csv"
build "$base" base
build "$head" head
