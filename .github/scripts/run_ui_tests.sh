#!/usr/bin/env bash
set -uo pipefail
test_status=0
./gradlew --no-daemon --console=plain :app:connectedDebugAndroidTest || test_status=$?
# Managed test cleanup uninstalls the APK and deletes its external-files directory.
# MediaStore captures survive that cleanup and are collected even after a failed test.
adb pull /sdcard/Pictures/AlphaCompanionUiCaptures ui-captures || {
  if [ "$test_status" -eq 0 ]; then test_status=1; fi
}
exit "$test_status"
