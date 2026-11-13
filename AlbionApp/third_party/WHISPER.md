# whisper.cpp

Speech recognition sources vendored from https://github.com/ggml-org/whisper.cpp
at commit `482279b8a18855ba741d7b88c30fcb3874691f59` (v1.9.5, 2026-10-30).
Only the unmodified library inputs are included: `include/whisper.h`,
`src/whisper.cpp`, `src/whisper-arch.h`, `src/aneforge/*` (a no-op stub outside
Apple Silicon) and `LICENSE` (MIT).

whisper.cpp is compiled against the ggml copy in `third_party/ggml` (ggml 0.28.0,
see `GGML.md`). The model is
not bundled: the app downloads `ggml-base.en-q5_1.bin` or `ggml-small.en-q5_1.bin`
from https://huggingface.co/ggerganov/whisper.cpp and verifies its SHA-256.
