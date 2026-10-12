cmake_minimum_required(VERSION 3.22.1)
project(voxsum)

# ---------------------------------------------------------------------------
# Native layer for VoxSumDroid.
#
#   ASR + diarization -> nemo-x-asr-diarizer (CrispASR x-asr + audio.cpp
#                        Nemotron-3 diarization, ggml), built from the
#                        native/audiocpp, native/crispasr, native/crispasr-ggml
#                        submodules.
#   meeting reader    -> the forked LiteRT-LM CPU engine (mfa/) on the stock
#                        libLiteRt.so of the LiteRT AAR, SentencePiece tokenizer.
#
# ---------------------------------------------------------------------------

set(NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native")

# ---------------------------------------------------------------------------
# ASR + diarization — nemo-x-asr-diarizer (nemo/, vendored from
# github.com/vieenrose/nemo-x-asr-diarizer.cpp): streaming X-ASR (CrispASR) +
# streaming Nemotron-3 diarization (audio.cpp), one audio timeline.
#
# TWO ggml copies live in this process (CrispASR's, audio.cpp's)
# and they must never bind to each other:
#   * audio.cpp builds as libaudiocpp.so with its ggml hidden behind a version
#     script (checked: no exported ggml_/gguf_ symbols);
#   * CrispASR + its ggml link STATICALLY into libvoxsum-nemo.so, which exports
#     only its JNI entry points (nemo/nemo_jni.map).
# Both are ExternalProjects because their ggml targets would collide in one CMake
# tree. ARMv8.2-A+dotprod floor (no i8mm: the Dimensity reference phones lack it);
# a device without dotprod is refused at startup (CpuSupport.kt).
# ---------------------------------------------------------------------------
include(ExternalProject)
# The CPU floor for every ggml copy in the app: ARMv8.2-A with the int8 dot-product extension.
# Only on arm64: ggml turns GGML_CPU_ARM_ARCH into -march, which breaks the x86_64 emulator build.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(VOXSUM_ARM_ARCH armv8.2-a+dotprod)
else()
    set(VOXSUM_ARM_ARCH "")
endif()
set(NEMO_ARCH ${VOXSUM_ARM_ARCH})
set(NEMO_COMMON_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
    -DBUILD_SHARED_LIBS=OFF
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
    -DGGML_CPU_ARM_ARCH=${NEMO_ARCH})

# NEMO_NO_GGML (default ON): X-ASR and Sortformer host parts are plain C++ (nemo/xasr_host.cc, diar_host.cc) around the
# LiteRT graphs, so no ggml, CrispASR or audio.cpp is built. OFF restores the ggml engine (backend ggml / litert_cpu).
option(NEMO_NO_GGML "nemo engine without ggml (LiteRT graphs + C++ host parts)" ON)
if(NOT NEMO_NO_GGML)
set(AUDIOCPP_BIN ${CMAKE_CURRENT_BINARY_DIR}/audiocpp-build)
ExternalProject_Add(audiocpp_ext
    SOURCE_DIR ${NATIVE_DIR}/audiocpp
    BINARY_DIR ${AUDIOCPP_BIN}
    # The pre-encode hook (audiocpp_nemotron3_diar_set_external_pre_encoder) the LiteRT diarizer plugs into.
    PATCH_COMMAND sh -c "git -C ${NATIVE_DIR}/audiocpp apply --reverse --check ${CMAKE_CURRENT_SOURCE_DIR}/nemo/audiocpp-pre-encode-hook.patch >/dev/null 2>&1 || git -C ${NATIVE_DIR}/audiocpp apply ${CMAKE_CURRENT_SOURCE_DIR}/nemo/audiocpp-pre-encode-hook.patch"
    CMAKE_ARGS ${NEMO_COMMON_ARGS}
        -DAUDIOCPP_BUILD_C_API=ON
        -DAUDIOCPP_MODEL_SET=custom -DAUDIOCPP_MODELS=nemotron_3_diar
        -DENGINE_ENABLE_NATIVE_CPU=OFF -DENGINE_ENABLE_OPENMP=OFF
        -DCMAKE_SHARED_LINKER_FLAGS=-llog
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target audiocpp
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS ${AUDIOCPP_BIN}/bin/libaudiocpp.so)
add_library(audiocpp SHARED IMPORTED)
set_target_properties(audiocpp PROPERTIES IMPORTED_LOCATION ${AUDIOCPP_BIN}/bin/libaudiocpp.so)
add_dependencies(audiocpp audiocpp_ext)

set(CRISP_BIN ${CMAKE_CURRENT_BINARY_DIR}/crispasr-build)
set(CRISP_LIBS
    ${CRISP_BIN}/src/libxasr.a
    ${CRISP_BIN}/src/libcrispasr-core.a
    ${CRISP_BIN}/ggml/src/libggml.a
    ${CRISP_BIN}/ggml/src/libggml-cpu.a
    ${CRISP_BIN}/ggml/src/libggml-base.a)
ExternalProject_Add(crispasr_ext
    SOURCE_DIR ${NATIVE_DIR}/crispasr
    BINARY_DIR ${CRISP_BIN}
    # The chunk hook (xasr_set_external_chunk_encoder) that the LiteRT X-ASR path plugs into.
    # Applied once; re-running the build sees it already applied and skips it.
    PATCH_COMMAND sh -c "git -C ${NATIVE_DIR}/crispasr apply --reverse --check ${CMAKE_CURRENT_SOURCE_DIR}/nemo/crispasr-chunk-hook.patch >/dev/null 2>&1 || git -C ${NATIVE_DIR}/crispasr apply ${CMAKE_CURRENT_SOURCE_DIR}/nemo/crispasr-chunk-hook.patch"
    CMAKE_ARGS ${NEMO_COMMON_ARGS}
        -DCMAKE_PROJECT_crispasr_INCLUDE=${CMAKE_CURRENT_SOURCE_DIR}/nemo/crispasr_ggml.cmake
        -DNEMO_GGML_SRC=${NATIVE_DIR}/crispasr-ggml
        # CrispASR includes ggml internals via its own ../ggml/src; point that at the pinned fork.
        -DCMAKE_CXX_FLAGS=-I${NATIVE_DIR}/crispasr-ggml/src
        # The fork declares a diagnostic counter only under GGML_USE_LLAMAFILE (and every published
        # measurement ran with it on), so it must stay on.
        -DGGML_LLAMAFILE=ON
        -DCRISPASR_NO_C2PA_NATIVE=ON -DCRISPASR_MEDIA_NDK=OFF
        -DCRISPASR_BUILD_EXAMPLES=OFF -DCRISPASR_BUILD_TESTS=OFF -DCRISPASR_BUILD_SERVER=OFF
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR>
        --target xasr crispasr-core ggml ggml-base ggml-cpu
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS ${CRISP_LIBS})

endif()

# LiteRT C API for the nemo LiteRT backends (CPU only). The stock libLiteRt.so of the LiteRT AAR,
# extracted by the Gradle task extractLiteRt into LITERT_SO_DIR; headers vendored in mfa/third_party.
if(NOT DEFINED LITERT_SO_DIR OR NOT EXISTS "${LITERT_SO_DIR}/${ANDROID_ABI}/libLiteRt.so")
    message(FATAL_ERROR "libLiteRt.so not found under LITERT_SO_DIR (run extractLiteRt)")
endif()
add_library(litert SHARED IMPORTED)
set_target_properties(litert PROPERTIES IMPORTED_LOCATION "${LITERT_SO_DIR}/${ANDROID_ABI}/libLiteRt.so")

add_library(voxsum-nemo SHARED
    nemo/nemo_jni.cpp
    nemo/engine.cpp
    nemo/fusion.cpp
    nemo/litert_graph.cc
    nemo/diar_litert.cc
    nemo/diar_pre_encode.cc
    nemo/xasr_litert.cc)
target_compile_features(voxsum-nemo PRIVATE cxx_std_17)
target_compile_definitions(voxsum-nemo PRIVATE NEMO_HAVE_TOKEN_TIMES NEMO_WITH_LITERT NEMO_LITERT_ENV_API)
target_compile_options(voxsum-nemo PRIVATE -O2 -w)
target_include_directories(voxsum-nemo PRIVATE nemo)
if(NEMO_NO_GGML)
    target_sources(voxsum-nemo PRIVATE nemo/xasr_host.cc nemo/kaldi_fbank.cpp nemo/diar_host.cc)
    target_compile_definitions(voxsum-nemo PRIVATE NEMO_NO_GGML)
    target_include_directories(voxsum-nemo PRIVATE nemo/hostxasr)
    set(NEMO_NATIVE_LIBS "")
else()
    target_sources(voxsum-nemo PRIVATE nemo/diar_crispasr.cpp)
    add_dependencies(voxsum-nemo crispasr_ext audiocpp_ext)
    target_include_directories(voxsum-nemo PRIVATE
        ${NATIVE_DIR}/crispasr/src
        ${NATIVE_DIR}/audiocpp/include
        ${NATIVE_DIR}/crispasr-ggml/include
        ${NATIVE_DIR}/crispasr-ggml/src
        ${NATIVE_DIR}/crispasr-ggml/src/ggml-cpu)
    set(NEMO_NATIVE_LIBS ${CRISP_LIBS} audiocpp)
endif()
target_link_options(voxsum-nemo PRIVATE
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/nemo/nemo_jni.map
    $<$<NOT:$<BOOL:${NEMO_NO_GGML}>>:-fopenmp -static-openmp>)   # CrispASR's x-asr kernels use OpenMP; link the NDK's libomp statically
target_include_directories(voxsum-nemo PRIVATE mfa/third_party)
target_link_libraries(voxsum-nemo ${NEMO_NATIVE_LIBS} litert android log dl m)


# ---------------------------------------------------------------------------
# Mobile meeting reader — the forked LiteRT-LM CPU engine (mfa/, vendored from
# github.com/vieenrose/LiteRT-LM branch mobile-fused-attention, see mfa/PROVENANCE.md) running
# Google's Gemma-4 mobile graphs (E2B, E4B) with the int8 attention fused into one custom op.
# It links the stock libLiteRt.so of the com.google.ai.edge.litert:litert AAR, extracted by the
# Gradle task extractLiteRt into LITERT_SO_DIR (the C headers are vendored in mfa/third_party).
# The tokenizer is SentencePiece (native/sentencepiece submodule, static).
# ---------------------------------------------------------------------------
if(DEFINED LITERT_SO_DIR AND EXISTS "${LITERT_SO_DIR}/${ANDROID_ABI}/libLiteRt.so")
    set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
    set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
    set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
    # Its CLI tools are built too (the Android Gradle plugin builds every target); its logging
    # calls __android_log_write, so every target from here on links liblog.
    link_libraries(log)
    add_subdirectory("${NATIVE_DIR}/sentencepiece" sentencepiece-build EXCLUDE_FROM_ALL)

    add_library(voxsum-mfa SHARED mfa/mfa_jni.cpp mfa/mfa_engine.cc mfa/i8_attn.cc)
    target_compile_features(voxsum-mfa PRIVATE cxx_std_17)
    target_compile_options(voxsum-mfa PRIVATE -O3 -fexceptions -fopenmp)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        target_compile_options(voxsum-mfa PRIVATE -march=${VOXSUM_ARM_ARCH})
    endif()
    target_include_directories(voxsum-mfa PRIVATE mfa mfa/third_party "${NATIVE_DIR}/sentencepiece/src")
    target_link_options(voxsum-mfa PRIVATE -fopenmp -static-openmp)
    target_link_libraries(voxsum-mfa litert sentencepiece-static android log)
else()
    message(WARNING "libLiteRt.so not found under LITERT_SO_DIR — the mobile reader is not built")
endif()
