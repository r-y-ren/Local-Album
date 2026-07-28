cmake_minimum_required(VERSION 3.22.1)
project(emutls_shim C)

# =============================================================================
# emutls 统一实现 shim（修复多 native 库 __emutls_get_address 符号冲突闪退）
# =============================================================================
#
# 背景：ONNX Runtime / OpenCV / TFLite / PyTorch 等 native 库各自链接了 emutls
# 实现。libonnxruntime.so 引用但不定义 __emutls_get_address，运行时从已加载
# 库解析；libopencv_java5.so 自带 WEAK 版本但控制块布局与 ONNX 不兼容。
# 多库在同一线程交替使用 emutls（识别扫描 / 换脸流水线 ONNX↔OpenCV 交替）
# 时触发 SIGSEGV 闪退。
#
# 修复：以 --whole-archive 链接 NDK clang builtins 归档（libclang_rt.builtins），
# 导出标准 __emutls_get_address 实现，供所有库共用。必须在其他 native 库加载前
# 通过 System.loadLibrary("emutls_shim") 加载（见 LocalAlbumApplication.onCreate）。
# =============================================================================

# ---- 定位 NDK clang builtins 归档（含标准 emutls 实现）----
# 路径: <NDK>/toolchains/llvm/prebuilt/<host-tag>/lib/clang/<ver>/lib/linux/libclang_rt.builtins-<arch>-android.a

if(NOT DEFINED ANDROID_NDK)
    message(FATAL_ERROR "ANDROID_NDK 未定义，请在 android.ndkVersion 中配置 NDK 版本")
endif()

# 主机标签
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
    set(_emutls_host_tag "linux-x86_64")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(_emutls_host_tag "darwin-x86_64")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(_emutls_host_tag "windows-x86_64")
else()
    set(_emutls_host_tag "linux-x86_64")
endif()

# ANDROID_ABI → builtins 架构名
set(_emutls_arch "")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(_emutls_arch "aarch64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(_emutls_arch "arm")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(_emutls_arch "x86_64")
elseif(ANDROID_ABI STREQUAL "x86")
    set(_emutls_arch "i386")
else()
    message(FATAL_ERROR "不支持的 ANDROID_ABI: ${ANDROID_ABI}")
endif()

# clang 归档目录用主版本号（如 18），而非完整版本号（如 18.0.1）
string(REGEX REPLACE "^([0-9]+)\\..*" "\\1" _emutls_clang_major "${CMAKE_C_COMPILER_VERSION}")
set(_emutls_builtins_dir
    "${ANDROID_NDK}/toolchains/llvm/prebuilt/${_emutls_host_tag}/lib/clang/${_emutls_clang_major}/lib/linux")
set(_emutls_builtins_lib
    "${_emutls_builtins_dir}/libclang_rt.builtins-${_emutls_arch}-android.a")

if(NOT EXISTS "${_emutls_builtins_lib}")
    message(WARNING "未在预期路径找到 builtins 归档: ${_emutls_builtins_lib}，尝试 find_library 兜底")
    find_library(_emutls_builtins_lib_found clang_rt.builtins-${_emutls_arch}-android
        PATHS "${_emutls_builtins_dir}" NO_DEFAULT_PATH)
    if(_emutls_builtins_lib_found)
        set(_emutls_builtins_lib "${_emutls_builtins_lib_found}")
    else()
        message(FATAL_ERROR "无法定位 libclang_rt.builtins-${_emutls_arch}-android.a")
    endif()
endif()

message(STATUS "emutls_shim: 使用 builtins 归档: ${_emutls_builtins_lib}")

# ---- 构建 shim 共享库 ----
add_library(emutls_shim SHARED emutls_shim.c)

# --whole-archive 强制包含 builtins 中所有对象（含 __emutls_get_address），
# --export-dynamic 确保符号进入动态符号表，供后续加载的库（libonnxruntime.so 等）解析。
target_link_options(emutls_shim PRIVATE
    "-Wl,--whole-archive"
    "${_emutls_builtins_lib}"
    "-Wl,--no-whole-archive"
    "-Wl,--export-dynamic"
)

target_link_libraries(emutls_shim atomic m)
