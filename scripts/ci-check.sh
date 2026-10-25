#!/usr/bin/env bash
# Builds and unit-tests every commit of a push, so each step of the history stays green.
# Usage: scripts/ci-check.sh <sha before the push> <head sha>
set -uo pipefail

before="${1:-}"
head="$(git rev-parse "${2:-HEAD}")"
tasks=(:app:assembleDebug :app:testDebugUnitTest)

commits=()
if [[ -n "$before" && ! "$before" =~ ^0+$ ]] \
    && git cat-file -e "$before^{commit}" 2>/dev/null \
    && git merge-base --is-ancestor "$before" "$head"; then
    mapfile -t commits < <(git rev-list --reverse --max-count=30 "$before..$head")
fi
[[ ${#commits[@]} -eq 0 ]] && commits=("$head")

# Turns the interesting part of a Gradle log into one annotation readable from the run page
report() {
    local title="$1" log="$2" msg
    msg="$(grep -E -A4 "^e: |FAILED|What went wrong|Exception|error:|AssertionError" "$log" | head -n 120)"
    [[ -z "$msg" ]] && msg="$(tail -n 60 "$log")"
    msg="${msg//'%'/'%25'}"
    msg="${msg//$'\r'/}"
    msg="${msg//$'\n'/'%0A'}"
    echo "::error title=$title::$msg"
}

status=0
for c in "${commits[@]}"; do
    subject="$(git log -1 --format='%h %s' "$c")"
    echo "::group::$subject"
    git checkout -q "$c"
    if ! ./gradlew "${tasks[@]}" 2>&1 | tee build.log; then
        report "Failed at ${c:0:7}" build.log
        status=1
    fi
    echo "::endgroup::"
    [[ $status -ne 0 ]] && break
done

git checkout -q "$head"
exit $status
