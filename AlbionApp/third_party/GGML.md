# ggml

Tensor library used by whisper.cpp for offline speech recognition. Vendored unchanged
from the `ggml/` directory of https://github.com/ggml-org/llama.cpp at commit
`785322d17f2d51e5185cf996a1f4e46d87640841` (ggml 0.28.0): CMakeLists.txt, cmake,
include, src. The rest of llama.cpp is not part of the project.

MIT license: `ggml/LICENSE` (copied from llama.cpp, same authors). Optional GPU,
OpenMP, KleidiAI, tests and examples are disabled. Android builds one JNI shared
library (`albion_stt`) with the static CPU runtime for arm64-v8a and x86_64, without
host CPU instruction detection or network access during CMake configuration.
