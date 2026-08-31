#!/usr/bin/env bash
#
# The body of the emulator job in .github/workflows/android.yml.
#
# **It is a file rather than an inline `script:` because it has to be.**
# `reactivecircus/android-emulator-runner` runs the `script` input *one line at
# a time*, each through its own `/usr/bin/sh -c`. So a subshell spanning two
# lines is a syntax error, an `if` never reaches its `fi`, and a variable set on
# one line is gone by the next. The first run of this workflow died on
#
#     /usr/bin/sh: 1: Syntax error: end of file unexpected (expecting ")")
#
# after the emulator had booted, which reads like a broken test and is not one.
# Handing the action a single line that runs this file removes the whole class.
#
# Everything about *why* the checks below are what they are lives in the
# workflow beside the job. This file is the how.

set -uo pipefail

: "${PLUGIN:?the workflow must export PLUGIN}"
: "${RUNNER_TEMP:?expected to run inside GitHub Actions}"
: "${GITHUB_STEP_SUMMARY:?expected to run inside GitHub Actions}"

adb logcat -G 16M
adb logcat -c

status=0
(
  cd example/android || exit 1
  ./gradlew "$PLUGIN:connectedDebugAndroidTest" --console=plain \
    -Pandroid.testInstrumentationRunnerArguments.notClass=com.lib.flutter_quick_video_encoder.ClipCompositeCostTest,com.lib.flutter_quick_video_encoder.ClipDeviceCapabilityTest
) || status=1

adb logcat -d > "$RUNNER_TEMP/logcat.txt"
layouts=$(grep -o 'CLIP layout .*' "$RUNNER_TEMP/logcat.txt" | sort -u || true)

{
  echo "### Instrumented run — emulator, API 34, google_apis x86_64"
  echo
  echo 'Plane layouts this run actually exercised:'
  echo
  echo '```'
  echo "${layouts:-(none logged)}"
  echo '```'
  echo
  echo 'An emulator hands back the friendliest layout in the'
  echo '`COLOR_FormatYUV420Flexible` family. The padded and semiplanar branches'
  echo 'a real phone takes on every frame did not run here.'
  echo '**Green is not "the device suite passes."** Tier 3 is still manual —'
  echo 'see `app/android/FIRST-DEVICE-RUN.md`.'
} >> "$GITHUB_STEP_SUMMARY"

echo "$layouts"

# Only asserted when the run itself succeeded. If the tests failed, the reason
# is above, and a second failure here for a missing log line would send the next
# reader to the wrong place.
if [ "$status" -ne 0 ]; then
  echo "layout not checked — the instrumented run failed above"
  exit "$status"
fi

# No layout line at all means no clip was decoded, which means the tests did not
# do the thing they are named for however green they look.
if [ -z "$layouts" ]; then
  echo "::error::the run logged no 'CLIP layout' line — nothing decoded a clip"
  exit 1
fi

# The claim: fully planar (chroma pixel stride 1) and unpadded (luma row stride
# equal to the used width). That is what was measured on an emulator and it is
# *narrower* than what a phone does. Failing when it changes is the point — a
# runner image that started padding rows has widened this job's coverage, and
# the notes here and in FIRST-DEVICE-RUN.md are then wrong and want rewriting.
echo "$layouts" | awk '
  {
    used = $0; sub(/.*used=/, "", used); sub(/x.*/, "", used)
    yrow = $0; sub(/.*y\(row=/, "", yrow); sub(/,.*/, "", yrow)
    if ($0 !~ /u\(row=[0-9]+,px=1\) v\(row=[0-9]+,px=1\)/) {
      print "chroma is not planar in: " $0; bad = 1
    }
    if (yrow != used) {
      print "luma rows are padded (" yrow " for width " used ") in: " $0; bad = 1
    }
  }
  END {
    if (bad) {
      print "::error::the emulator no longer hands back the planar unpadded layout."
      print "::error::That is wider coverage than this job claims — update the note"
      print "::error::in the workflow and in app/android/FIRST-DEVICE-RUN.md, then re-pin it."
      exit 1
    }
  }'
