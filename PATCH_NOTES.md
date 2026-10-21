# ANB AI v1.1 — Local AI First

This patch adds an opt-in on-device AI path using Google's LiteRT-LM Android runtime and the `litert-community/Qwen3-0.6B` model.

## What changed

- App version updated to `1.1.0` (versionCode 2).
- Kotlin Gradle plugins updated to `2.4.20` to match LiteRT-LM 0.18.0's Kotlin metadata.
- Added `com.google.ai.edge.litertlm:litertlm-android:0.18.0`.
- Added `LocalAiRepository.kt` for model download, local engine startup and local chat generation.
- Updated `MainActivity.kt` with explicit model download and start controls; the model is not downloaded automatically and is not bundled in the APK.

## First run

1. Install the APK produced by GitHub Actions.
2. Open ANB AI and tap **Download model • 614 MB**. Keep a stable internet connection and enough free storage available.
3. When the download finishes, tap **Start local AI**. Initial model loading may take time.
4. Ask a question. Inference is intended to run on-device using the CPU backend; no cloud AI API key is used by this local path.

Model source: https://huggingface.co/litert-community/Qwen3-0.6B
Model license shown by its model card: Apache-2.0.

## Important limitations

- The Qwen3 0.6B model file is about 614 MB. Runtime memory use is higher than the file size; some phones may be slow or may not have enough memory.
- The first download needs internet. Model responses after download are local.
- This patch has not been verified by GitHub Actions yet. Do not treat it as a successful build until the new workflow run is green and the APK artifact exists.
