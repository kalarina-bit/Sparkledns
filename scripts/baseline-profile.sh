#!/usr/bin/env bash
# Records which classes and methods the app uses on startup and in each tab, in the text format
# of app/src/main/baseline-prof.txt. Needs a rooted Android 13+ device or emulator (no Play Store).
# Usage: scripts/baseline-profile.sh <non-minified release apk> <output file>
set -euo pipefail

PKG=cc.skysparkle.sparkledns
ACT="$PKG/.MainActivity"
APK="$1"
OUT="$2"

adb root >/dev/null 2>&1 && sleep 3
adb wait-for-device
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -g "$APK" >/dev/null
read -r W H < <(adb shell wm size | tr -d '\r' | awk -F'[ x]' '/Physical/ {print $3, $4}')

tap_text() {
    local xy
    for _ in 1 2 3 4 5; do
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
        xy="$(adb shell cat /sdcard/ui.xml | python3 -c '
import re, sys
xml, text = sys.stdin.read(), sys.argv[1]
for node in re.findall(r"<node [^>]*>", xml):
    # Compose merges a clickable row into one node: "System, 3 of 5"
    if re.search(r"(text|content-desc)=\"%s(\"|, )" % re.escape(text), node):
        x1, y1, x2, y2 = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1")"
        if [[ -n "$xy" ]]; then
            adb shell input tap $xy
            return 0
        fi
        # Lazy lists only show what is on screen; with "scroll", look further down
        [[ "${2:-}" == scroll ]] && adb shell input swipe $((W / 2)) $((H * 2 / 3)) $((W / 2)) $((H / 3)) 300
        sleep 1
    done
    echo "::warning::'$1' not found on screen"
}

swipe() {
    adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 200
    sleep 1
    adb shell input swipe $((W / 2)) $((H / 4)) $((W / 2)) $((H * 3 / 4)) 200
    sleep 1
}

for _ in 1 2 3; do
    adb shell am force-stop "$PKG"
    adb shell am start-activity -W -n "$ACT" >/dev/null
    sleep 3
    swipe
    tap_text "Monitor"; sleep 2
    tap_text "Apps"; sleep 3
    tap_text "System" scroll; sleep 2
    swipe
    tap_text "Blocklist"; sleep 2
    swipe
    tap_text "About"; sleep 2
    tap_text "Home"; sleep 2
    # Makes ART write the profile now instead of on its own schedule
    adb shell "kill -10 \$(pidof $PKG)"
    sleep 5
done

adb shell pm dump-profiles --dump-classes-and-methods "$PKG"
sleep 2
adb pull "/data/misc/profman/$PKG-primary.prof.txt" "$OUT" >/dev/null
echo "Profile lines: $(wc -l < "$OUT")"
