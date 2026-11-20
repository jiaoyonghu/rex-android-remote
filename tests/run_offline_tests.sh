#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src="$out/sources.txt"
find tests/jvm_stubs -name '*.java' > "$src"
for class in RexCommand CommandPhrases CommandAcoustics CommandAudioSegmenter CommandActivityGate BilingualCommandRouter EnrollmentPrompts SpeakerMath SpeakerProfile SpeechActivityGate CommandTemplateStore VoskGrammar SpeechFusion SherpaCommandPolicy; do
  echo "app/src/main/java/com/rex/remote/$class.java" >> "$src"
done
for file in CommandActivityGateTest CommandTest CommandAcousticsTest CommandTemplateStoreTest EnrollmentTest SpeakerMathTest SpeechGateTest SpeechFusionTest SpeakerProfileTest FarFieldAcousticsTest SherpaCommandPolicyTest; do
  echo "tests/$file.java" >> "$src"
done
javac --release 17 -encoding UTF-8 -d "$out" @"$src"
for test in CommandActivityGateTest CommandTest EnrollmentTest SpeakerMathTest SpeechGateTest; do
  java -cp "$out" "$test"
done
for test in CommandAcousticsTest CommandTemplateStoreTest SpeechFusionTest SpeakerProfileTest FarFieldAcousticsTest SherpaCommandPolicyTest; do
  java -cp "$out" "com.rex.remote.$test"
done
