# llama.cpp

CPU inference sources vendored from https://github.com/ggml-org/llama.cpp
at commit `785322d17f2d51e5185cf996a1f4e46d87640841`, also present in the supplied
`android/third_party/llama.cpp` reference. Only the unmodified library build inputs
are included: CMakeLists.txt, LICENSE, cmake, include, src, ggml, vendor, licenses.
The sample application is not a build dependency.

The MIT license is in `llama.cpp/LICENSE`. Optional GPU, OpenMP, KleidiAI,
networking, tools, examples and common utilities are disabled. Android builds one
JNI shared library with the static CPU runtime for arm64-v8a and x86_64, without
host CPU instruction detection or network access during CMake configuration.
