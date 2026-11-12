#!/usr/bin/env bash
# Model-download journey on an emulator or device. Needs adb and node on PATH (or ADB set).
# With more than one device attached, set ANDROID_SERIAL to the one to use.
#
# A fake 8 MB "model" is served from this computer and fetched by Android's DownloadManager through
# `adb reverse` (a loopback address: on Android 17 DownloadManager cannot reach local-network addresses).
# It is not a real model: this journey proves download, kill-survival, checksum, delete and the
# typed failure when the file cannot be loaded. Real inference needs a real .litertlm file.
set -u
ADB="${ADB:-adb}"
PKG=io.github.pdrakeweb.llmtiers.sample
HERE="$(cd "$(dirname "$0")" && pwd)"
INFO="$(mktemp)"
fail=0
SERVER_PID=""

serve() { # serve <kilobytes per second>
  [ -n "$SERVER_PID" ] && { kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null; }
  node "$HERE/fake-model-server.js" 18403 8 "$1" > "$INFO" 2>&1 & SERVER_PID=$!
  for _ in $(seq 1 50); do grep -q '^sha256=' "$INFO" && break; "$ADB" shell sleep 0.1; done
  SHA="$(sed -nE 's/^sha256=([0-9a-f]+) size=.*/\1/p' "$INFO")"; SIZE="$(sed -nE 's/^sha256=.* size=([0-9]+)/\1/p' "$INFO")"
}
"$ADB" reverse tcp:18403 tcp:18403 >/dev/null
trap 'kill $SERVER_PID 2>/dev/null; "$ADB" shell cmd connectivity airplane-mode disable >/dev/null 2>&1; rm -f "$INFO"' EXIT

logs() { "$ADB" logcat -d -s llmtiers-sample:I | grep -v '^-----' | sed -E 's/^.*llmtiers-sample: //'; }
launch() { "$ADB" shell am start -W -n "$PKG/.MainActivity" --es modelUrl http://127.0.0.1:18403/model.litertlm --es modelSize "$SIZE" "$@" >/dev/null; }
wait_for() { # wait_for <regex> <seconds>
  for _ in $(seq 1 $(( $2 * 2 ))); do logs | grep -qE "$1" && return 0; "$ADB" shell sleep 0.5; done; return 1
}
check() { # check <title> <command...>
  local title="$1"; shift
  if "$@"; then echo "   PASS  $title"; else echo "   FAIL  $title"; fail=1; fi
}

echo "== 0. Start clean"
serve 100000
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
launch --es modelSha "$SHA" --es modelAction delete
check "model is Missing" wait_for '^MODEL Missing' 15

echo "== 1. Download survives the app being killed"
serve 1024   # 8 MB at 1 MB/s: about 8 seconds
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
launch --es modelSha "$SHA" --es modelAction download
check "download started and is under way" wait_for '^MODEL Downloading bytes=[1-9]' 20
logs | grep -q '^MODEL Ready' && { echo "   FAIL  finished before the kill; nothing proven"; fail=1; }
"$ADB" shell am force-stop $PKG
check "app process is gone" bash -c "[ -z \"\$('$ADB' shell pidof $PKG)\" ]"
"$ADB" shell sleep 2; "$ADB" logcat -c
launch --es modelSha "$SHA"          # a cold start with no action: only observes
check "after relaunch the model reaches Ready, verified, full size" wait_for "^MODEL Ready bytes=$SIZE" 40
logs | sed 's/^/      /' | tail -4

echo "== 2. The model is deletable"
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
launch --es modelSha "$SHA" --es modelAction delete
check "model is Missing after delete" wait_for '^MODEL Missing' 15

echo "== 3. A checksum failure is reported and the file is not kept"
serve 100000
BAD="$(printf '0%.0s' $(seq 1 64))"
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
launch --es modelSha "$BAD" --es modelAction download
check "Failed reason=ChecksumMismatch, not recoverable" wait_for '^MODEL Failed reason=ChecksumMismatch recoverable=false' 40
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
launch --es modelSha "$BAD"
check "still reported after a restart" wait_for '^MODEL Failed reason=ChecksumMismatch' 15

echo "== 4. Offline with a downloaded file that is not a real model: a typed failure, no crash, no hang"
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
launch --es modelSha "$SHA" --es modelAction download
check "fake model downloaded and verified" wait_for "^MODEL Ready bytes=$SIZE" 40
"$ADB" shell cmd connectivity airplane-mode enable; "$ADB" shell sleep 3
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
launch --es modelSha "$SHA" --es order local --ez autoSend true --ez warmUp false
check "request ends with a RESULT" wait_for '^RESULT' 40
logs | grep -E 'provider=local|^RESULT' | sed 's/^/      /'
check "local provider reported ModelNotReady" bash -c "'$ADB' logcat -d -s llmtiers-sample:I | grep -q 'provider=local tier=ON_DEVICE outcome=ModelNotReady'"
"$ADB" shell cmd connectivity airplane-mode disable
launch --es modelSha "$SHA" --es modelAction delete >/dev/null 2>&1

[ $fail -eq 0 ] && echo "MODEL JOURNEY PASSED" || echo "MODEL JOURNEY FAILED"
exit $fail
