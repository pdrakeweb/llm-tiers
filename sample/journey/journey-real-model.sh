#!/usr/bin/env bash
# Real on-device inference on an emulator or device. Needs adb on PATH (or ADB set) and internet.
# With more than one device attached, set ANDROID_SERIAL to the one to use.
#
# DOWNLOADS 347 MB onto the device: the "Qwen3 0.6B" entry of the library's starter catalog, from
# huggingface.co/litert-community/Qwen3-0.6B-int4 (Apache 2.0). The model manager verifies its
# SHA-256. The device then goes into airplane mode and the downloaded model answers a prompt.
# Pass --keep to leave the model on the device afterwards.
set -u
ADB="${ADB:-adb}"
PKG=io.github.pdrakeweb.llmtiers.sample
MODEL=qwen3-0.6b-q4
SIZE=347251840
fail=0
trap '"$ADB" shell cmd connectivity airplane-mode disable >/dev/null 2>&1' EXIT

logs() { "$ADB" logcat -d -s llmtiers-sample:I | grep -v '^-----' | sed -E 's/^.*llmtiers-sample: //'; }
wait_for() { for _ in $(seq 1 $(( $2 / 2 ))); do logs | grep -qE "$1" && return 0; "$ADB" shell sleep 2; done; return 1; }
check() { local title="$1"; shift; if "$@"; then echo "   PASS  $title"; else echo "   FAIL  $title"; fail=1; fi; }

echo "== 1. Download the model through the model manager and verify it"
"$ADB" shell cmd connectivity airplane-mode disable
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
"$ADB" shell am start -W -n $PKG/.MainActivity --es modelId $MODEL --es modelAction download >/dev/null
check "Ready, full size, SHA-256 verified" wait_for "^MODEL Ready bytes=$SIZE" 1200
check "LiteRT-LM loaded it" wait_for '^MODEL Loaded ok=true' 120
logs | grep '^MODEL Loaded' | sed 's/^/      /'

echo "== 2. Airplane mode: the downloaded model answers, nothing else is tried"
"$ADB" shell cmd connectivity airplane-mode enable; "$ADB" shell sleep 3
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
"$ADB" shell "am start -W -n $PKG/.MainActivity --es modelId $MODEL --ez sendWhenLoaded true --es lanUrl http://10.0.2.2:18401/v1 --es lanModel m --es prompt 'Why\ is\ the\ sky\ blue?'" >/dev/null
check "request completed" wait_for '^RESULT' 180
logs | grep -E 'provider=|^RESULT' | sed 's/^/      /'
check "answered by the local model" bash -c "'$ADB' logcat -d -s llmtiers-sample:I | grep -q 'RESULT Answer by=local'"
check "home and cloud were skipped as Offline" bash -c "'$ADB' logcat -d -s llmtiers-sample:I | grep -c 'outcome=Offline' | grep -q '^2$'"
check "tokens streamed (more than one token event)" bash -c "'$ADB' logcat -d -s llmtiers-sample:I | grep -qE 'RESULT Answer .*tokenEvents=([2-9]|[1-9][0-9]+) '"
"$ADB" shell cmd connectivity airplane-mode disable

if [ "${1:-}" != "--keep" ]; then
  "$ADB" shell am force-stop $PKG
  "$ADB" shell am start -W -n $PKG/.MainActivity --es modelId $MODEL --es modelAction delete >/dev/null
fi
[ $fail -eq 0 ] && echo "REAL MODEL JOURNEY PASSED" || echo "REAL MODEL JOURNEY FAILED"
exit $fail
