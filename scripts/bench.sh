#!/usr/bin/env bash
# Benchmarks two APKs of SparkleDNS on one device or emulator and prints medians side by side:
# cold start, memory, CPU while in the background and scroll jank on the Apps tab.
# Rounds alternate the order of the APKs so device drift affects both alike.
# Needs a rooted device (an emulator image without Play Store) to drop the page cache.
# Usage: scripts/bench.sh <base.apk> <head.apk> [rounds]
set -uo pipefail

PKG=cc.skysparkle.sparkledns
ACT="$PKG/.MainActivity"
BASE_APK="$1"
HEAD_APK="$2"
ROUNDS="${3:-2}"
STARTS="${STARTS:-10}"
OUT="${OUT:-$(dirname "$BASE_APK")}"
RAW="$OUT/raw.csv"
: > "$RAW"

adb root >/dev/null 2>&1 && sleep 3
adb wait-for-device
read -r W H < <(adb shell wm size | tr -d '\r' | awk -F'[ x]' '/Physical/ {print $3, $4}')

record() { echo "$1,$2,$3" >> "$RAW"; } # name,metric,value

install() {
    adb uninstall "$PKG" >/dev/null 2>&1
    adb install -g "$1" >/dev/null
    adb shell am start -W -n "$ACT" >/dev/null
    sleep 2
    adb shell am force-stop "$PKG"
    # Compiles with the APK's baseline profile only, as the device does after an install,
    # without what the JIT learned during the first run (same as Macrobenchmark's Partial mode)
    adb shell cmd package compile --reset "$PKG" >/dev/null
    adb shell am broadcast -f 0x20 -a androidx.profileinstaller.action.INSTALL_PROFILE \
        "$PKG/androidx.profileinstaller.ProfileInstallReceiver" | tr -d '\r' | grep -o 'result=[0-9-]*'
    sleep 2
    adb shell cmd package compile -f -m speed-profile "$PKG" >/dev/null
}

launch() {
    adb shell am start-activity -W -n "$ACT" | tr -d '\r'
}

cold_starts() {
    local t
    for _ in $(seq "$STARTS"); do
        adb shell am force-stop "$PKG"
        adb shell 'sync; echo 3 > /proc/sys/vm/drop_caches'
        sleep 2
        t="$(launch | awk '/TotalTime/ {print $2}')"
        [[ -n "$t" ]] && record "$1" cold_start_ms "$t"
        sleep 3
    done
}

memory() {
    local pss
    for _ in 1 2 3; do
        adb shell am force-stop "$PKG"
        launch >/dev/null
        sleep 10
        pss="$(adb shell dumpsys meminfo "$PKG" | tr -d '\r' | awk '/TOTAL PSS:/ {print $3; exit}')"
        [[ -n "$pss" ]] && record "$1" pss_kb "$pss"
    done
}

# CPU time and scheduler wakeups of all app threads during 60 s on the home screen
background_cpu() {
    local pid a b
    adb shell am force-stop "$PKG"
    launch >/dev/null
    sleep 5
    adb shell input keyevent 3
    sleep 5
    pid="$(adb shell pidof "$PKG" | tr -d '\r')"
    [[ -z "$pid" ]] && return
    a="$(adb shell "cat /proc/$pid/task/*/schedstat" | awk '{t += $1; n += $3} END {print t, n}')"
    sleep 60
    b="$(adb shell "cat /proc/$pid/task/*/schedstat" | awk '{t += $1; n += $3} END {print t, n}')"
    read -r t1 n1 <<< "$a"
    read -r t2 n2 <<< "$b"
    record "$1" bg_cpu_ms_per_min "$(( (t2 - t1) / 1000000 ))"
    record "$1" bg_wakeups_per_min "$(( n2 - n1 ))"
}

# Memory still held 30 s after leaving the app from the Apps tab, with every icon loaded once
background_memory() {
    local pss
    adb shell am force-stop "$PKG"
    launch >/dev/null
    sleep 3
    tap_text "Apps" || return
    sleep 2
    tap_text "System" scroll
    sleep 2
    for _ in 1 2 3 4; do
        adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 200
        sleep 1
    done
    sleep 3
    adb shell input keyevent 3
    sleep 30
    pss="$(adb shell dumpsys meminfo "$PKG" | tr -d '\r' | awk '/TOTAL PSS:/ {print $3; exit}')"
    [[ -n "$pss" ]] && record "$1" bg_pss_after_apps_kb "$pss"
}

tap_text() {
    local xy
    for _ in 1 2 3 4 5; do
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
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
    return 1
}

jank() {
    local g total janky
    adb shell am force-stop "$PKG"
    launch >/dev/null
    sleep 3
    tap_text "Apps" || return
    sleep 2
    tap_text "System" scroll # the only group collapsed by default, listed last
    sleep 2
    for _ in 1 2 3; do
        adb shell input swipe $((W / 2)) $((H / 4)) $((W / 2)) $((H * 3 / 4)) 100
    done
    sleep 2
    adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
    for dir in down down down down down up up up up up; do
        if [[ $dir == down ]]; then
            adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 200
        else
            adb shell input swipe $((W / 2)) $((H / 4)) $((W / 2)) $((H * 3 / 4)) 200
        fi
        sleep 1.2
    done
    g="$(adb shell dumpsys gfxinfo "$PKG" | tr -d '\r')"
    total="$(awk -F': ' '/Total frames rendered/ {print $2; exit}' <<< "$g")"
    janky="$(awk '/Janky frames:/ {print $3; exit}' <<< "$g")"
    if [[ -n "$total" && "$total" -gt 0 ]]; then
        record "$1" frames "$total"
        record "$1" janky_pct "$(awk -v j="$janky" -v t="$total" 'BEGIN {printf "%.2f", j * 100 / t}')"
        for p in 50 90 99; do
            record "$1" "frame_p${p}_ms" "$(awk "/${p}th percentile/ {gsub(\"ms\", \"\", \$3); print \$3; exit}" <<< "$g")"
        done
    fi
}

measure() {
    echo "::group::$1"
    install "$2"
    cold_starts "$1"
    memory "$1"
    background_cpu "$1"
    background_memory "$1"
    jank "$1"
    jank "$1"
    echo "::endgroup::"
}

for r in $(seq "$ROUNDS"); do
    if (( r % 2 )); then
        measure base "$BASE_APK"
        measure head "$HEAD_APK"
    else
        measure head "$HEAD_APK"
        measure base "$BASE_APK"
    fi
done

[[ -f "$OUT/sizes.csv" ]] && cat "$OUT/sizes.csv" >> "$RAW"

python3 - "$RAW" > "$OUT/results.md" <<'EOF'
import csv, statistics, sys
data = {}
for name, metric, value in csv.reader(open(sys.argv[1])):
    if value:
        data.setdefault(metric, {}).setdefault(name, []).append(float(value))
print("| Metric (median) | base | head | change | base min–max | head min–max | n |")
print("|---|---|---|---|---|---|---|")
for metric, by in data.items():
    b, h = by.get("base", []), by.get("head", [])
    mb = statistics.median(b) if b else None
    mh = statistics.median(h) if h else None
    change = f"{(mh - mb) / mb * 100:+.1f}%" if mb and mh is not None else "n/a"
    fmt = lambda v: "n/a" if v is None else (f"{v:.0f}" if v >= 100 else f"{v:.2f}")
    rng = lambda v: f"{fmt(min(v))}–{fmt(max(v))}" if v else "n/a"
    print(f"| {metric} | {fmt(mb)} | {fmt(mh)} | {change} | {rng(b)} | {rng(h)} | {len(b)}/{len(h)} |")
EOF

cat "$OUT/results.md"
[[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && cat "$OUT/results.md" >> "$GITHUB_STEP_SUMMARY"
msg="$(cat "$OUT/results.md")"
echo "::notice title=Benchmark::${msg//$'\n'/'%0A'}"
