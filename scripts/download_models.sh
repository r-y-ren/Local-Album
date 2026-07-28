#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# 模型下载脚本 — 下载核心 AI 模型到 assets/models/
#
# 用法:
#   chmod +x scripts/download_models.sh
#   ./scripts/download_models.sh
#
# 模型文件将下载到: app/src/main/assets/models/
#
# 注意:
#   - MobileNetV2 从 TF Hub 下载 (~4.3MB)
#   - 其他模型 URL 为占位符, 请替换为实际可用的下载地址
#   - GLM-OCR 模型较大 (~300MB), 如不需要 OCR 可注释掉
# ──────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets/models"

mkdir -p "$ASSETS_DIR"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

download() {
    local url="$1"
    local filename="$2"
    local dest="$ASSETS_DIR/$filename"

    if [ -f "$dest" ] && [ -s "$dest" ]; then
        echo -e "${GREEN}[跳过]${NC} $filename 已存在 ($(du -h "$dest" | cut -f1))"
        return 0
    fi

    echo -e "${YELLOW}[下载]${NC} $filename <- $url"
    if curl -fSL --connect-timeout 30 --max-time 600 -o "$dest" "$url"; then
        echo -e "${GREEN}[完成]${NC} $filename ($(du -h "$dest" | cut -f1))"
    else
        echo -e "${RED}[失败]${NC} $filename — 请检查 URL 或手动下载"
        rm -f "$dest"
        return 1
    fi
}

echo "══════════════════════════════════════════════"
echo "  核心 AI 模型下载工具"
echo "  目标目录: $ASSETS_DIR"
echo "══════════════════════════════════════════════"
echo ""

# ── 1. MobileNetV2 (场景分类, ~4.3MB) ──
download \
    "https://storage.googleapis.com/download.tensorflow.org/models/tflite/mobilenet_v2_1.0_224_quant.tflite" \
    "model:mobilenet_v2.tflite"

# ── 2. ArcFace (人脸嵌入, ~4MB) ──
# NOTE: ArcFace ONNX 需要转换为 TFLite，URL 为占位符
# 实际使用时请替换为可用的 TFLite 模型下载地址
echo -e "${YELLOW}[提示]${NC} ArcFace 模型需要手动转换为 TFLite 格式后放置到 $ASSETS_DIR/model:arcface.tflite"
# download \
#     "https://huggingface.co/deepinsight/insightface/resolve/main/models/arcface_r100.onnx" \
#     "model:arcface.tflite"

# ── 3. MobileCLIP 图像编码器 (~35MB) ──
# NOTE: URL 为占位符
echo -e "${YELLOW}[提示]${NC} MobileCLIP 图像编码器模型需放置到 $ASSETS_DIR/model:mobileclip_image.tflite"
# download \
#     "https://huggingface.co/apple/MobileCLIP-S0/resolve/main/image_encoder.tflite" \
#     "model:mobileclip_image.tflite"

# ── 4. MobileCLIP 文本编码器 (~10MB) ──
# NOTE: URL 为占位符
echo -e "${YELLOW}[提示]${NC} MobileCLIP 文本编码器模型需放置到 $ASSETS_DIR/model:mobileclip_text.tflite"
# download \
#     "https://huggingface.co/apple/MobileCLIP-S0/resolve/main/text_encoder.tflite" \
#     "model:mobileclip_text.tflite"

# ── 5. GLM-OCR 编码器 (~180MB) ──
# NOTE: URL 为占位符, 体积较大
echo -e "${YELLOW}[提示]${NC} GLM-OCR 编码器模型需放置到 $ASSETS_DIR/model:glm_ocr_encoder.tflite"
# download \
#     "https://huggingface.co/THUDM/GLM-OCR/resolve/main/vision_encoder.tflite" \
#     "model:glm_ocr_encoder.tflite"

# ── 6. GLM-OCR 解码器 (~120MB) ──
# NOTE: URL 为占位符, 体积较大
echo -e "${YELLOW}[提示]${NC} GLM-OCR 解码器模型需放置到 $ASSETS_DIR/model:glm_ocr_decoder.tflite"
# download \
#     "https://huggingface.co/THUDM/GLM-OCR/resolve/main/text_decoder.tflite" \
#     "model:glm_ocr_decoder.tflite"

echo ""
echo "══════════════════════════════════════════════"
echo "  完成！请检查 $ASSETS_DIR"
echo "  然后运行 ./gradlew installDebug 构建 APK"
echo "══════════════════════════════════════════════"
