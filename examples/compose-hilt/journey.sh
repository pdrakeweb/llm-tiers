#!/usr/bin/env bash
# Drives the example app on an emulator or device and checks that a request completes.
# Needs adb and node on PATH (or ADB set). With more than one device, set ANDROID_SERIAL.
#
# A fake OpenAI-compatible server on this computer is reached through `adb reverse` at
# 127.0.0.1 (a loopback address, so no local-network permission is involved). Controls are found
# by their on-screen text, not by coordinates.
set -u
ADB="${ADB:-adb}"
PKG=io.github.pdrakeweb.llmtiers.example
HERE="$(cd "$(dirname "$0")" && pwd)"
fail=0

node "$HERE/../../sample/journey/fake-openai.js" 18402 remote >/dev/null 2>&1 & SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
"$ADB" reverse tcp:18402 tcp:18402 >/dev/null

dump() { "$ADB" exec-out uiautomator dump /dev/tty 2>/dev/null; }
# center_of <text>: prints "x y" for the first node whose text starts with <text>
center_of() {
  dump | sed 's/></>\n</g' | grep -F "text=\"$1" | head -1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' |
    awk '{ if (NF == 4) print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}
tap() { local at; at="$(center_of "$1")"; [ -n "$at" ] || { echo "   cannot find '$1' on screen"; return 1; }; "$ADB" shell input tap $at; "$ADB" shell sleep 0.7; }
# The on-screen keyboard covers the lower controls. Back hides it, but only press Back when the
# keyboard is really showing: otherwise Back closes the app.
hide_keyboard() {
  if "$ADB" shell dumpsys input_method | grep -q "mInputShown=true"; then "$ADB" shell input keyevent 4; "$ADB" shell sleep 0.7; fi
}
type_into() { tap "$1" && "$ADB" shell input text "$2" && "$ADB" shell sleep 0.5 && hide_keyboard; }
on_screen() { dump | grep -qF "$1"; }
check() { local title="$1"; shift; if "$@"; then echo "   PASS  $title"; else echo "   FAIL  $title"; fail=1; fi; }

"$ADB" shell pm clear $PKG >/dev/null
"$ADB" logcat -c
"$ADB" shell am start -W -n $PKG/.MainActivity >/dev/null
"$ADB" shell sleep 2

echo "== 1. Enter a remote provider and save it"
type_into "OpenAI-compatible URL" "http://127.0.0.1:18402/v1"
type_into "Model name" "fake-model"
check "saved" tap "Save"

echo "== 2. Ask a question; the remote provider answers and the app says who answered"
type_into "Question" "hello"
check "sent" tap "Send"
"$ADB" shell sleep 4
check "the answer is on screen" on_screen "remote says hello"
check "the app says who answered" on_screen "Answered by remote"
"$ADB" logcat -d -s llm-tiers:I | grep -v '^-----' | sed -E 's/^.*llm-tiers: /      /'

echo "== 3. A personal note is not sent until the user approves it"
type_into "Personal note" "secret"
check "sent" tap "Send"
"$ADB" shell sleep 2
check "the app asks first, naming the provider" on_screen "Send to remote?"
check "refused" tap "Don't send"
"$ADB" shell sleep 2
# The on-device provider may still take it (no approval is needed there); here it has no model.
check "the remote provider was skipped, and the app shows why" on_screen "remote: skipped (ApprovalDenied)"

echo "== 4. Settings and the encrypted key survive a restart"
"$ADB" shell am force-stop $PKG
"$ADB" shell am start -W -n $PKG/.MainActivity >/dev/null
"$ADB" shell sleep 2
check "the saved URL is back" on_screen "http://127.0.0.1:18402/v1"

[ $fail -eq 0 ] && echo "EXAMPLE JOURNEY PASSED" || echo "EXAMPLE JOURNEY FAILED"
exit $fail
