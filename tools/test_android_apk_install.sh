#!/usr/bin/env bash
set -euo pipefail
report=apk-install-report
apk="$report/nasqo.apk"
package=org.growerp.admin.nasqo.preview.v1228
{
  adb shell getprop ro.build.version.release
  adb shell getprop ro.build.version.sdk
  adb shell getprop ro.product.cpu.abilist
} | tee "$report/device.txt"
adb logcat -c
set +e
adb install --no-streaming "$apk" 2>&1 | tee "$report/install.log"
install_result=${PIPESTATUS[0]}
set -e
adb logcat -d -v threadtime > "$report/install-device.log"
test "$install_result" -eq 0
tr -d '\r' < "$report/install.log" | grep -qx Success
adb shell dumpsys package "$package" > "$report/package.txt"
adb shell am start -W -n "$package/org.growerp.admin.MainActivity" | tee "$report/launch.log"
adb shell pidof "$package" | tee "$report/pid.txt"
# Reproduce a normal delete and reinstall only on this disposable emulator.
adb uninstall "$package" | tee "$report/uninstall.log"
adb install --no-streaming "$apk" 2>&1 | tee "$report/reinstall.log"
tr -d '\r' < "$report/reinstall.log" | grep -qx Success
echo ANDROID_13_FRESH_INSTALL_LAUNCH_AND_REINSTALL_PASS
