#!/usr/bin/env bash
# Android smoke test: install the CI-built PackBack APK on the running emulator,
# launch it, and confirm it starts without immediately crashing.
#
# Run by the `android-smoke` job in .github/workflows/ci.yml via
# reactivecircus/android-emulator-runner. Kept in its own file so the shell logic
# runs under bash (not the runner's /bin/sh) with strict error handling.
set -euo pipefail

PACKAGE="com.studentmemory.copilot"
ACTIVITY="${PACKAGE}/${PACKAGE}.MainActivity"
APK="apk/app-debug.apk"

adb wait-for-device

echo "Installing the CI-built APK..."
adb install -r "${APK}"

# Clear logcat so only post-launch output is inspected.
adb logcat -c

echo "Launching PackBack..."
adb shell am start -W -n "${ACTIVITY}"

# Give the app a moment to initialise (or crash) after launch.
sleep 10

PID="$(adb shell pidof "${PACKAGE}" | tr -d '\r')"
if [ -z "${PID}" ]; then
  echo "::error::PackBack is not running after launch — it failed to start or crashed immediately."
  adb logcat -d -t 400 | grep -iE "AndroidRuntime|FATAL|ActivityManager.*${PACKAGE}" || true
  exit 1
fi

# A crash may still be recorded even if a restart happened; fail only on a fatal signature
# attributable to PackBack. Scope the fatal-exception check to the app's own PID so an
# unrelated emulator process logging FATAL EXCEPTION cannot fail the job.
if adb logcat -d --pid="${PID}" | grep -E "FATAL EXCEPTION"; then
  echo "::error::PackBack (pid ${PID}) logged a fatal exception immediately after launch."
  adb logcat -d --pid="${PID}" -t 400 || true
  exit 1
fi

# ANR/force-finish lines are emitted by system_server, so match them by package name.
if adb logcat -d | grep -E "Force finishing activity ${PACKAGE}|ANR in ${PACKAGE}"; then
  echo "::error::PackBack was force-finished or triggered an ANR immediately after launch."
  adb logcat -d -t 400 | grep -iE "ANR in ${PACKAGE}|Force finishing activity ${PACKAGE}|${PACKAGE}" || true
  exit 1
fi

echo "PackBack launched and is still running (pid ${PID}). Smoke test passed."
