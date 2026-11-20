#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# Fail once with the COMPLETE list instead of one missing file per Actions run.
src_classes=(RexCommand CommandPhrases CommandAcoustics CommandAudioSegmenter
 CommandActivityGate BilingualCommandRouter EnrollmentPrompts SpeakerMath
 SpeakerProfile SpeechActivityGate CommandTemplateStore VoskGrammar SpeechFusion
 SherpaCommandPolicy VoiceIdentityPolicy PersonalWakePolicy)
test_classes=(CommandActivityGateTest CommandTest CommandAcousticsTest
 CommandTemplateStoreTest EnrollmentTest SpeakerMathTest SpeechGateTest
 SpeechFusionTest SpeakerProfileTest FarFieldAcousticsTest
 SherpaCommandPolicyTest VoiceIdentityPolicyTest PersonalWakePolicyTest)
stubs=(android/content/Context.java android/content/SharedPreferences.java
 android/util/Base64.java org/json/JSONArray.java org/json/JSONException.java)
missing=()
for class in "${src_classes[@]}"; do
  file="app/src/main/java/com/rex/remote/$class.java"
  [[ -f "$file" ]] || missing+=("$file")
done
for class in "${test_classes[@]}"; do
  file="tests/$class.java"
  [[ -f "$file" ]] || missing+=("$file")
done
for file in "${stubs[@]}"; do
  [[ -f "tests/jvm_stubs/$file" ]] || missing+=("tests/jvm_stubs/$file")
done
if (( ${#missing[@]} )); then
  printf 'REX v1.1: missing %s required test/source files:\n' "${#missing[@]}" >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 2
fi

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src="$out/sources.txt"
find tests/jvm_stubs -name '*.java' -print > "$src"
for class in "${src_classes[@]}"; do
  echo "app/src/main/java/com/rex/remote/$class.java" >> "$src"
done
for class in "${test_classes[@]}"; do
  echo "tests/$class.java" >> "$src"
done
javac --release 17 -encoding UTF-8 -d "$out" @"$src"
for test in CommandActivityGateTest CommandTest EnrollmentTest SpeakerMathTest SpeechGateTest; do
  java -cp "$out" "$test"
done
for test in CommandAcousticsTest CommandTemplateStoreTest SpeechFusionTest \
 SpeakerProfileTest FarFieldAcousticsTest SherpaCommandPolicyTest \
 VoiceIdentityPolicyTest PersonalWakePolicyTest; do
  java -cp "$out" "com.rex.remote.$test"
done
printf 'REX v1.1: all 13 offline Java regression tests PASSED\n'
