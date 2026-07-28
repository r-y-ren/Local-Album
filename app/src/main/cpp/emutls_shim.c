/*
 * emutls_shim.c — 统一 emutls 实现 shim（修复多 native 库符号冲突闪退）。
 *
 * ## 背景
 *
 * ONNX Runtime / OpenCV / TFLite / PyTorch 等 native 库各自链接了 emutls
 * （emulated thread-local storage）实现。libonnxruntime.so 引用但**不定义**
 * `__emutls_get_address`，运行时需从已加载的库中解析；libopencv_java5.so
 * 自带一个 WEAK 版本。当多个库在同一线程交替使用 emutls 时（如识别扫描中
 * InsightFace[ONNX] → FaceAligner[OpenCV] → InsightFace[ONNX] 交替，或换脸
 * 流水线 ONNX↔OpenCV 交替），各库 emutls 控制块（`__emutls_v.*`）布局不兼容，
 * 导致 `__emutls_get_address` 访问越界 → SIGSEGV → 闪退。
 *
 * ## 修复
 *
 * 本 shim 以 `--whole-archive` 链接 NDK clang builtins 归档
 * （libclang_rt.builtins），导出标准 `__emutls_get_address` 实现，供所有库
 * 共用。必须在其他 native 库加载前通过 `System.loadLibrary("emutls_shim")`
 * 加载，使其符号优先进入动态符号表，被后续加载的库（libonnxruntime.so 等）
 * 解析引用，避免解析到 OpenCV 自带的不兼容 WEAK 实现。
 *
 * 见 CMakeLists.txt 的链接配置与 LocalAlbumApplication.onCreate 的加载顺序。
 */

/**
 * 标记函数：导出全局符号，供 Java 侧验证 .so 已成功加载；
 * 同时作为 .so 的导出符号占位，确保链接器保留 whole-archive 的 builtins 对象。
 */
int emutls_shim_loaded(void) {
    return 1;
}
