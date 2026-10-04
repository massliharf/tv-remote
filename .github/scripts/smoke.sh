#!/bin/bash
# End-to-end test on an emulator against fake TVs running on the CI host (10.0.2.2).
# Fails if the app crashes or a fake TV does not receive the expected commands.
set -u
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.massliharf.tvremote
FAILED=0
mkdir -p screens

pip install -q websockets pyOpenSSL androidtvremote2 >/dev/null 2>&1
python3 -u .github/scripts/fake_tvs.py screens > screens/fake_tvs.log 2>&1 &
sleep 3

crashcheck() {
  local log
  log=$(adb logcat -d -b crash 2>/dev/null)
  if [ -n "$log" ]; then
    echo "::error::CRASH during: $1"
    echo "$log" | head -150
    FAILED=1
    adb logcat -c -b crash
  else
    echo "OK: $1"
  fi
}

focus() { adb shell dumpsys window | grep -m1 mCurrentFocus; }

# Bring the remote back to the front with no sheet open.
front() {
  for _ in 1 2 3; do
    local f; f=$(focus)
    if echo "$f" | grep -q "$PKG/"; then return; fi
    if echo "$f" | grep -q "$PKG"; then adb shell input keyevent KEYCODE_BACK; sleep 1; continue; fi
    adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3
  done
}

ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }

# Taps the view whose content-desc or text equals $1. $2 = optional vertical position 0-100 inside it.
tap() {
  local b
  b=$(ui | grep -o "<node[^>]*\(content-desc\|text\)=\"$1\"[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  if [ -z "$b" ]; then echo "::warning::no view '$1' on screen"; return 1; fi
  set -- $b "${2:-50}"
  adb shell input tap $(( ($1 + $3) / 2 )) $(( $2 + ($4 - $2) * $5 / 100 ))
  sleep 2
}

shot() { adb exec-out screencap -p > "screens/$1.png"; }

expect() { # expect "<pattern>" "<what>"
  if grep -q -- "$1" screens/fake_tvs.log; then echo "OK: TV received $2"; else echo "::error::TV did not receive $2"; FAILED=1; fi
}

write_prefs() { # $1 = devices json (xml-escaped), $2 = active id
  adb shell am force-stop $PKG
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<string name=\"devices\">%s</string>\n<string name=\"active\">%s</string>\n</map>\n" "$1" "$2" > tv.xml
  adb push tv.xml /data/local/tmp/tv.xml >/dev/null
  adb shell "run-as $PKG mkdir -p shared_prefs"
  adb shell "cat /data/local/tmp/tv.xml | run-as $PKG sh -c 'cat > shared_prefs/tv.xml'"
}

Q='&quot;'
LG="{${Q}type${Q}:${Q}LG${Q},${Q}host${Q}:${Q}10.0.2.2${Q},${Q}name${Q}:${Q}LG TV${Q}}"
ATV="{${Q}type${Q}:${Q}ANDROID_TV${Q},${Q}host${Q}:${Q}10.0.2.2${Q},${Q}name${Q}:${Q}Mi Box${Q}}"

adb install -r -g "$APK" || exit 1
adb logcat -c; adb logcat -c -b crash

echo "=== 1. First launch, nothing saved ==="
adb shell am start -W -n $PKG/.MainActivity
sleep 8; crashcheck "first launch"; shot 1_first_launch
front
for d in "Güç" "Ana sayfa" "Uygulama" "Klavye" "Touchpad" "Gelişmiş"; do
  tap "$d"; crashcheck "tap $d (no device)"; front
done

echo "=== 2. LG TV ==="
write_prefs "[$LG,$ATV]" "LG:10.0.2.2"
adb shell am start -W -n $PKG/.MainActivity
sleep 8; crashcheck "connect LG"; shot 2_lg_connected
front
tap "Ana sayfa"; crashcheck "LG home"
tap "Yön tuşları" 20; crashcheck "LG dpad up"
tap "Yön tuşları" 50; crashcheck "LG OK"
tap "SES" 15; crashcheck "LG volume up"
tap "KANAL" 85; crashcheck "LG channel down"
tap "Sessiz"; crashcheck "LG mute"
tap "Uygulama"; sleep 2; shot 2_lg_apps; tap "Netflix"; crashcheck "LG launch app"; front
tap "Kaynak"; sleep 2; tap "HDMI 2"; crashcheck "LG input"; front
tap "Touchpad"; crashcheck "LG touchpad on"; shot 2_lg_touchpad
adb shell input swipe 500 1000 700 1100 300; sleep 1; crashcheck "LG touchpad move"
tap "Yön tuşu"; front
tap "Klavye"; sleep 2; adb shell input text "merhaba"; sleep 2; crashcheck "LG keyboard"; front
tap "Gelişmiş"; sleep 1; shot 2_lg_advanced; tap "5"; crashcheck "LG digit"; front
expect "ssap://audio/volumeUp" "LG volume up"
expect "ssap://tv/channelDown" "LG channel down"
expect "ssap://audio/setMute" "LG mute"
expect "launch.*netflix" "LG app launch"
expect "switchInput.*HDMI_2" "LG input switch"
expect "name:HOME" "LG home button"
expect "name:UP" "LG up button"
expect "type:move" "LG pointer move"
expect "insertText" "LG text"

echo "=== 3. Android TV: pairing ==="
rm -f screens/atv_code.txt
tap "Mi Box"; sleep 6; crashcheck "switch to Android TV"; shot 3_atv_pin
for _ in $(seq 1 15); do [ -f screens/atv_code.txt ] && break; sleep 1; done
if [ -f screens/atv_code.txt ]; then
  adb shell input text "$(cat screens/atv_code.txt)"; sleep 1
  tap "Eşleştir"
  sleep 6; crashcheck "Android TV pairing"
else
  echo "::error::no pairing code"; FAILED=1
fi
shot 3_atv_connected
front
tap "Ana sayfa"; crashcheck "ATV home"
tap "Yön tuşları" 80; crashcheck "ATV down"
tap "SES" 85; crashcheck "ATV volume down"
tap "Oynat / duraklat"; crashcheck "ATV play/pause"
tap "Uygulama"; sleep 2; tap "YouTube"; crashcheck "ATV launch app"; front
tap "Klavye"; sleep 2; adb shell input text "film"; sleep 2; crashcheck "ATV keyboard"; front
tap "Touchpad"; sleep 4; crashcheck "ATV touchpad (bluetooth)"; shot 3_atv_touchpad
front
tap "Gelişmiş"; sleep 1; shot 3_atv_advanced; front
expect "ATV PAIR secret OK" "Android TV pairing secret"
expect "KEYCODE_HOME" "ATV home"
expect "KEYCODE_DPAD_DOWN" "ATV down"
expect "KEYCODE_VOLUME_DOWN" "ATV volume down"
expect "KEYCODE_MEDIA_PLAY_PAUSE" "ATV play/pause"
expect "youtube.tv" "ATV app link"
expect "value: \"film\"" "ATV text"

echo "=== 4. Switch back and relaunch ==="
tap "LG TV"; sleep 4; crashcheck "switch back to LG"
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity; sleep 8; crashcheck "relaunch"
shot 4_relaunch

echo "=== fake TV log ==="
cat screens/fake_tvs.log | cut -c1-200 | head -150
echo "=== app errors in logcat ==="
adb logcat -d | grep -E "AndroidRuntime|$PKG" | grep -E " E |FATAL" | head -40
exit $FAILED
