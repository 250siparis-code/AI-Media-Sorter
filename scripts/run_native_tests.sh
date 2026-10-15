#!/usr/bin/env bash
set -euo pipefail
variant="$1"
page_size="$2"
if [ "$variant" = "release" ]; then
  ./gradlew connectedReleaseAndroidTest -PqaReleaseSigning=true -Pandroid.testInstrumentationRunnerArguments.expectedPageSize="$page_size" --stacktrace
else
  ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.expectedPageSize="$page_size" --stacktrace
fi
