#!/usr/bin/env bash
set -euo pipefail
report=release-test-report
apk="$RUNNER_TEMP/nasqo-emulator-test.apk"
package=com.indexesstore.nasqo
adb logcat -c
adb install --no-streaming "$apk" 2>&1 | tee "$report/install.txt"
tr -d '\r' < "$report/install.txt" | grep -qx Success
adb shell am start -W -n "$package/org.growerp.admin.MainActivity" | tee "$report/launch.txt"
# Observe the app for 30 seconds; successful installation alone is insufficient.
for attempt in $(seq 1 6); do
  sleep 5
  adb shell pidof "$package" > "$report/pid.txt"
done
adb shell dumpsys activity activities > "$report/activity.txt"
adb logcat -d -v threadtime > "$report/logcat.txt"
adb exec-out screencap -p > "$report/screen.png"
if grep -q "Process: $package," "$report/logcat.txt"; then
  echo "Application crash detected"
  exit 1
fi
grep -E "(mResumedActivity|topResumedActivity).*com.indexesstore.nasqo" "$report/activity.txt"
adb uninstall "$package" | tee "$report/uninstall.txt"
adb install --no-streaming "$apk" 2>&1 | tee "$report/reinstall.txt"
tr -d '\r' < "$report/reinstall.txt" | grep -qx Success
echo "ANDROID13_RELEASE_INSTALL_PROCESS_SURVIVAL_AND_REINSTALL_PASS"
