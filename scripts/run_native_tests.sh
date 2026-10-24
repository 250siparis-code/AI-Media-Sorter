#!/usr/bin/env bash
set -Eeuo pipefail
dump_test_failures() {
  # The emulator runner terminates its device before subsequent workflow steps.
  # Capture the crash buffer here while the failing process/device is still available.
  adb logcat -b crash -d -v threadtime > android-crash-logcat.txt 2>&1 || true
  tail -n 200 android-crash-logcat.txt || true
  python3 - <<'PY'
import pathlib, xml.etree.ElementTree as ET
for path in pathlib.Path("app/build/outputs/androidTest-results").rglob("TEST-*.xml"):
    root=ET.parse(path).getroot()
    print("Executed suite:",root.attrib)
    for case in root.iter("testcase"):
        for failure in list(case.findall("failure"))+list(case.findall("error")):
            print("FAILED:",case.attrib)
            print("Failure attributes:",failure.attrib)
            print(failure.text)
PY
}
trap dump_test_failures ERR
variant="$1"
page_size="$2"
if [ "$variant" = "release" ]; then
  ./gradlew connectedReleaseAndroidTest -PqaReleaseSigning=true -PtargetAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.expectedPageSize="$page_size" --stacktrace
else
  ./gradlew connectedDebugAndroidTest -PtargetAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.expectedPageSize="$page_size" --stacktrace
fi

python3 scripts/summarize_junit.py app/build/outputs/androidTest-results app/build/reports/native-summary.json
