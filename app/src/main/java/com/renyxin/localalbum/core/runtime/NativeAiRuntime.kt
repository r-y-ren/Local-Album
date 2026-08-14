package com.renyxin.localalbum.core.runtime

import ai.onnxruntime.OrtEnvironment
import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * Thread-safe, lazy native runtime bootstrap.
 *
 * Merely referencing this type does not load a native library. Callers must acquire the relevant
 * resource lane first, then explicitly request the smallest runtime they need. The emutls shim is
 * always loaded before OpenCV or ONNX Runtime can be initialized in this process.
 */
class NativeRuntimeLoader internal constructor(
    private val loadShim: () -> Unit,
    private val initializeOpenCv: () -> Boolean,
) {
    private val lock = Any()

    @Volatile
    private var shimReady = false

    @Volatile
    private var openCvReady = false

    val isShimReady: Boolean
        get() = shimReady

    val isOpenCvReady: Boolean
        get() = openCvReady

    /** Ensures the emutls shim is globally visible before any native AI runtime is first requested. */
    fun ensureNativePrerequisite() {
        ensureShim()
    }

    fun ensureOnnxPrerequisite() {
        ensureNativePrerequisite()
    }

    /** Ensures OpenCV in the required shim -> OpenCV order without loading any model session. */
    fun ensureOpenCvRuntime() {
        synchronized(lock) {
            ensureShimLocked()
            if (openCvReady) return
            check(initializeOpenCv()) {
                "OpenCV native runtime initialization failed"
            }
            openCvReady = true
        }
    }

    fun ensureFaceSwapRuntime() {
        ensureOpenCvRuntime()
    }

    private fun ensureShim() {
        if (shimReady) return
        synchronized(lock) {
            ensureShimLocked()
        }
    }

    private fun ensureShimLocked() {
        if (shimReady) return
        loadShim()
        shimReady = true
    }
}

/** Process-wide lazy native runtime entry point used by model and interactive AI code. */
object NativeAiRuntime {
    private const val TAG = "NativeAiRuntime"

    private val loader: NativeRuntimeLoader by lazy {
        NativeRuntimeLoader(
            loadShim = {
                System.loadLibrary("emutls_shim")
                Log.i(TAG, "emutls shim loaded on demand")
            },
            initializeOpenCv = {
                OpenCVLoader.initLocal().also { initialized ->
                    if (initialized) {
                        Log.i(TAG, "OpenCV initialized on demand")
                    } else {
                        Log.e(TAG, "OpenCV on-demand initialization failed")
                    }
                }
            },
        )
    }

    val isShimReady: Boolean
        get() = loader.isShimReady

    val isOpenCvReady: Boolean
        get() = loader.isOpenCvReady

    fun ensureNativePrerequisite() {
        loader.ensureNativePrerequisite()
    }

    fun ensureOnnxPrerequisite() {
        ensureNativePrerequisite()
    }

    /** Returns the process ONNX environment only after the emutls prerequisite is installed. */
    fun getOrtEnvironment(): OrtEnvironment {
        ensureOnnxPrerequisite()
        return OrtEnvironment.getEnvironment()
    }

    fun ensureOpenCvRuntime() {
        loader.ensureOpenCvRuntime()
    }

    fun ensureFaceSwapRuntime() {
        ensureOpenCvRuntime()
    }
}
