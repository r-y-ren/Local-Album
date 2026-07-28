#!/usr/bin/env python3
"""
从 inswapper_128.onnx 提取 emap 矩阵，导出为 emap_512.bin 供 Android 运行时加载。

原理（参照 ComfyUI-ReActor/reactor_core/inswap.py:INSwapper.__init__）：
    model = onnx.load(model_file, load_external_data=False)
    emap = numpy_helper.to_array(model.graph.initializer[-1])

inswapper_128.onnx 的最后一个 initializer 即 emap，形状为 [512, 512] float32。
换脸时 source latent = normed_embedding(1x512) @ emap(512x512)，再 L2 归一化。

用法：
    pip install onnx numpy
    python scripts/extract_emap.py
输出：
    app/src/main/assets/models/emap_512.bin  (512*512*4 = 1048576 字节, float32, 行主序)
"""
import os
import sys
import numpy as np

try:
    import onnx
    from onnx import numpy_helper
except ImportError:
    print("请先安装依赖: pip install onnx numpy", file=sys.stderr)
    sys.exit(1)

# 相对仓库根目录的路径
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
MODEL_PATH = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "models", "inswapper_128.onnx")
OUT_PATH = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "models", "emap_512.bin")


def main():
    if not os.path.exists(MODEL_PATH):
        print(f"错误: 找不到模型文件 {MODEL_PATH}", file=sys.stderr)
        sys.exit(1)

    print(f"加载 ONNX 模型: {MODEL_PATH}")
    model = onnx.load(MODEL_PATH, load_external_data=False)

    initializers = model.graph.initializer
    if len(initializers) == 0:
        print("错误: 模型无 initializer", file=sys.stderr)
        sys.exit(1)

    emap = numpy_helper.to_array(initializers[-1])
    print(f"emap 形状: {emap.shape}, dtype: {emap.dtype}")

    # 强制为 float32，行主序（C order），扁平化
    emap_f32 = np.ascontiguousarray(emap, dtype=np.float32).ravel(order="C")
    expected = 512 * 512
    if emap_f32.size != expected:
        print(f"警告: emap 元素数 {emap_f32.size} != 预期 {expected}", file=sys.stderr)

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    emap_f32.tofile(OUT_PATH)
    print(f"已导出 emap → {OUT_PATH} ({emap_f32.nbytes} 字节, {emap_f32.size} float32)")

    # 自检：模拟 latent = embed @ emap
    fake_embed = np.random.randn(512).astype(np.float32)
    fake_embed /= np.linalg.norm(fake_embed)
    emap_2d = emap_f32.reshape(512, 512)
    latent = fake_embed @ emap_2d
    latent /= np.linalg.norm(latent)
    print(f"自检 latent 范数: {np.linalg.norm(latent):.6f} (应≈1.0)")


if __name__ == "__main__":
    main()
