#!/usr/bin/env bash
# Sample-app journey on an emulator or device. Needs adb and node on PATH (or ADB set).
# With more than one device attached, set ANDROID_SERIAL to the one to use.
#
# Two fake OpenAI-compatible servers run on this computer:
#   "home"  on port 18401, reached from the emulator at 10.0.2.2 (a local-network address)
#   "cloud" on port 18402, reached through `adb reverse` at 127.0.0.1 (stands in for the cloud:
#           no real key is needed, and it stays reachable when the home address does not)
# Each step launches the app with its settings as launch extras, waits for the RESULT log line and
# prints the app's content-free log (provider ids, tiers, timings, failure types).
set -u
ADB="${ADB:-adb}"
PKG=io.github.pdrakeweb.llmtiers.sample
HERE="$(cd "$(dirname "$0")" && pwd)"
HOME_URL=http://10.0.2.2:18401/v1
CLOUD_URL=http://127.0.0.1:18402/v1
fail=0

start_home() { node "$HERE/fake-openai.js" 18401 home >/dev/null 2>&1 & HOME_PID=$!; }
stop_home() { kill "$HOME_PID" 2>/dev/null; wait "$HOME_PID" 2>/dev/null; }
node "$HERE/fake-openai.js" 18402 cloud >/dev/null 2>&1 & CLOUD_PID=$!
start_home
trap 'kill $HOME_PID $CLOUD_PID 2>/dev/null; "$ADB" shell cmd connectivity airplane-mode disable >/dev/null 2>&1' EXIT
"$ADB" reverse tcp:18402 tcp:18402 >/dev/null

# step <title> <expected RESULT fragment> <expected log fragment> [max ms] -- <am start extras...>
step() {
  local title="$1" expect="$2" also="$3" max="$4"; shift 5
  "$ADB" logcat -c
  "$ADB" shell am start -S -W -n "$PKG/.MainActivity" --ez autoSend true --ez autoApprove true "$@" >/dev/null
  local out=""
  for _ in $(seq 1 60); do
    out="$("$ADB" logcat -d -s llmtiers-sample:I | grep -v '^-----' | sed -E 's/^.*llmtiers-sample: //')"
    echo "$out" | grep -q '^RESULT' && break
    "$ADB" shell sleep 0.5
  done
  echo "== $title"
  echo "$out" | sed 's/^/   /'
  local took; took="$(echo "$out" | grep '^RESULT' | sed -E 's/.*tookMs=([0-9]+).*/\1/')"
  if echo "$out" | grep -q "^RESULT $expect" && echo "$out" | grep -q -- "$also" && { [ -z "$max" ] || [ "${took:-99999}" -le "$max" ]; }; then
    echo "   PASS"
  else
    echo "   FAIL (expected 'RESULT $expect', '$also'${max:+, within $max ms})"; fail=1
  fi
}

# The downloaded-model tier has its own journeys; leaving it out keeps this one independent of
# whether a model happens to be on the device.
common=(--es lanUrl "$HOME_URL" --es lanModel fake-model --es cloudUrl "$CLOUD_URL" --es cloudModel fake-model --es order home,cloud,nano)

"$ADB" shell pm revoke $PKG android.permission.ACCESS_LOCAL_NETWORK >/dev/null 2>&1
step "1. Local-network permission not granted: home is skipped with the reason, cloud answers" \
  "Answer by=cloud" "provider=home tier=LAN outcome=LocalNetworkPermissionDenied" 1500 -- "${common[@]}"

"$ADB" shell pm grant $PKG android.permission.ACCESS_LOCAL_NETWORK
step "2. Permission granted, home server up: LAN answers" \
  "Answer by=home" "provider=home tier=LAN outcome=Succeeded" "" -- "${common[@]}"

stop_home
step "3. Home server stopped (connection refused): cloud answers" \
  "Answer by=cloud" "provider=home tier=LAN outcome=Unreachable" 1500 -- "${common[@]}"

step "4. Home address silent (no reply at all, as when away from home): cloud answers within 1.5 s" \
  "Answer by=cloud" "provider=home tier=LAN outcome=Unreachable" 1500 -- "${common[@]}" --es lanUrl http://10.255.255.1:18401/v1

start_home
step "5. Home server back: LAN answers again" \
  "Answer by=home" "provider=home tier=LAN outcome=Succeeded" "" -- "${common[@]}"

step "6. Pinned to home while it cannot answer: not replaced by cloud" \
  "NoneAvailable" "provider=cloud tier=CLOUD outcome=NotPinned" "" -- "${common[@]}" --es lanUrl http://10.255.255.1:18401/v1 --es pin home

step "7. Plain HTTP to a public host is refused before anything is sent; home answers" \
  "Answer by=home" "provider=cloud tier=CLOUD outcome=Unreachable" "" -- "${common[@]}" --es cloudUrl http://example.com/v1 --es order cloud,home

step "8. Personal note with approval: sent to home" \
  "Answer by=home" "provider=home tier=LAN outcome=Succeeded" "" -- "${common[@]}" --es personal "journey note"

"$ADB" shell cmd connectivity airplane-mode enable
"$ADB" shell sleep 3
step "9. Offline: network tiers are skipped, the on-device tier is the only one consulted" \
  "" "provider=cloud tier=CLOUD outcome=Offline" 1500 -- "${common[@]}"
"$ADB" shell cmd connectivity airplane-mode disable

[ $fail -eq 0 ] && echo "JOURNEY PASSED" || echo "JOURNEY FAILED"
exit $fail
