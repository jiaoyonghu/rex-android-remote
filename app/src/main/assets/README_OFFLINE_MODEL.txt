REx v0.4: TWO official offline Vosk models are bundled in the built APK.
Mandarin: vosk-model-small-cn-0.22 (approx. 42 MB)
English:  vosk-model-small-en-us-0.15 (approx. 40 MB)
Licenses: Apache-2.0 (see official model catalog).
Source: https://alphacephei.com/vosk/models
Both model ZIP archives are downloaded by GitHub Actions during build,
NOT by the Android phone, and installed in app-private storage on first run.
No Android system SpeechRecognizer or internet permission is used.
Dual monolingual decoders support common Mandarin/English command mixing,
but arbitrary code switching is NOT guaranteed to transcribe perfectly.
