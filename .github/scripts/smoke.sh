#!/bin/bash
# Launches the app on an emulator, clicks through the main screens for both device types
# and fails if the app crashes. Logs go to the job output.
set -u
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.massliharf.tvremote
FAILED=0

crashcheck() {
  local where="$1"
  local log
  log=$(adb logcat -d -b crash 2>/dev/null)
  if [ -n "$log" ]; then
    echo "::error::CRASH during: $where"
    echo "$log" | head -120
    FAILED=1
    adb logcat -c -b crash
  else
    echo "OK: $where"
  fi
}

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml
}

# Taps the first view whose content-desc (or text) equals $1.
tap() {
  local ui b
  ui=$(dump_ui)
  b=$(echo "$ui" | grep -o "<node[^>]*\(content-desc\|text\)=\"$1\"[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  if [ -z "$b" ]; then
    echo "WARN: no view '$1' on screen"
    return
  fi
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 2
}

back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }

shot() { adb exec-out screencap -p > "screens/$1.png"; }

mkdir -p screens
adb install -r -g "$APK" || exit 1
adb logcat -c; adb logcat -c -b crash

echo "=== Scenario 1: first launch, no devices ==="
adb shell am start -W -n $PKG/.MainActivity
sleep 10
crashcheck "first launch"
shot first_launch
back
crashcheck "dismiss connect sheet"
for d in "Güç" "Kaynak" "Ayarlar" "Geri" "Ana sayfa" "Sessiz" "Uygulama" "Klavye" "Touchpad" "Gelişmiş"; do
  tap "$d"; crashcheck "tap $d (no device)"; back
done
tap "Gelişmiş"; shot advanced_none; back
dump_ui | grep -o 'content-desc="[^"]*"\|text="[^"]*"' | sort -u | head -60

echo "=== Scenario 2: LG + Android TV saved, Android TV active ==="
adb shell am force-stop $PKG
cat > tv.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="devices">[{&quot;type&quot;:&quot;LG&quot;,&quot;host&quot;:&quot;10.0.2.99&quot;,&quot;name&quot;:&quot;LG TV&quot;},{&quot;type&quot;:&quot;ANDROID_TV&quot;,&quot;host&quot;:&quot;10.0.2.98&quot;,&quot;name&quot;:&quot;Mi Box&quot;}]</string>
    <string name="active">ANDROID_TV:10.0.2.98</string>
</map>
XML
adb push tv.xml /data/local/tmp/tv.xml
adb shell "run-as $PKG mkdir -p shared_prefs"
adb shell "cat /data/local/tmp/tv.xml | run-as $PKG sh -c 'cat > shared_prefs/tv.xml'"
adb shell am start -W -n $PKG/.MainActivity
sleep 12
crashcheck "launch with Android TV active"
shot atv_main
for d in "Geri" "Ana sayfa" "Uygulama" "Klavye" "Oynat / duraklat"; do
  tap "$d"; crashcheck "tap $d (atv)"; back
done
tap "Touchpad"; sleep 3; crashcheck "touchpad (atv)"; shot atv_touchpad
back; back
tap "Gelişmiş"; crashcheck "advanced (atv)"; shot atv_advanced; back
tap "LG TV"; sleep 3; crashcheck "switch to LG"; shot lg_main
tap "Touchpad"; crashcheck "touchpad (lg)"; back
tap "Mi Box"; sleep 3; crashcheck "switch back to Android TV"
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity
sleep 8
crashcheck "relaunch"

echo "=== logcat errors from the app ==="
adb logcat -d | grep -E "$PKG|AndroidRuntime" | grep -E " E |FATAL" | head -60
exit $FAILED
