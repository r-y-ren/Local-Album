#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# 模型下载脚本 — 从 GitHub Releases 下载 AI 模型到 assets/models/
#
# 用法:
#   chmod +x scripts/download_models.sh
#   ./scripts/download_models.sh
#
# 模型文件将下载到: app/src/main/assets/models/
#
# 模型来源: https://github.com/r-y-ren/Local-Album/releases/tag/v0.1.0
# ──────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets/models"

# GitHub Release 下载基础 URL
RELEASE_URL="https://github.com/r-y-ren/Local-Album/releases/download/v0.1.0"

mkdir -p "$ASSETS_DIR"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

download() {
    local url="$1"
    local filename="$2"
    local dest="$3"

    if [ -f "$dest" ] && [ -s "$dest" ]; then
        echo -e "${GREEN}[跳过]${NC} $filename 已存在 ($(du -h "$dest" | cut -f1))"
        return 0
    fi

    echo -e "${YELLOW}[下载]${NC} $filename <- $url"
    if curl -fSL --connect-timeout 30 --max-time 600 -o "$dest" "$url"; then
        echo -e "${GREEN}[完成]${NC} $filename ($(du -h "$dest" | cut -f1))"
    else
        echo -e "${RED}[失败]${NC} $filename — 请检查网络或手动下载"
        rm -f "$dest"
        return 1
    fi
}

echo "══════════════════════════════════════════════"
echo "  LocalAlbum AI 模型下载工具"
echo "  目标目录: $ASSETS_DIR"
echo "  来源: GitHub Releases v0.1.0"
echo "══════════════════════════════════════════════"
echo ""

# ── 1. EVA02-CLIP 语义搜索模型 (~415MB) ──
mkdir -p "$ASSETS_DIR/eva02_clip"
download "$RELEASE_URL/eva02_text_int8.onnx" "eva02_text_int8.onnx" "$ASSETS_DIR/eva02_clip/eva02_text_int8.onnx"
download "$RELEASE_URL/eva02_visual_336_int8.onnx" "eva02_visual_336_int8.onnx" "$ASSETS_DIR/eva02_clip/eva02_visual_336_int8.onnx"

# ── 2. 换脸模型 inswapper_128 (~529MB) ──
download "$RELEASE_URL/inswapper_128.onnx" "inswapper_128.onnx" "$ASSETS_DIR/inswapper_128.onnx"

# ── 3. 默认人脸模型包 buffalo_l (~276MB) ──
# 默认 InsightFace Provider 从包内按需解压 det_10g.onnx 与 w600k_r50.onnx。
download "$RELEASE_URL/buffalo_l.zip" "buffalo_l.zip" "$ASSETS_DIR/buffalo_l.zip"

# 不再随项目分发仅供备用 Provider 使用的检测模型；同时清理旧下载残留。
rm -f "$ASSETS_DIR/retinaface-resnet50.onnx" "$ASSETS_DIR/scrfd_person_2.5g.onnx"

# ── 4. emap 矩阵 (换脸用, ~1MB) ──
download "$RELEASE_URL/emap_512.bin" "emap_512.bin" "$ASSETS_DIR/emap_512.bin"

# ── 5. PP-OCR 模型 (~25MB) ──
mkdir -p "$ASSETS_DIR/PP-OCRv5_mobile_rec_infer"
download "$RELEASE_URL/PP-OCRv5_mobile_rec_inference.onnx" "PP-OCRv5_mobile_rec_inference.onnx" "$ASSETS_DIR/PP-OCRv5_mobile_rec_infer/inference.onnx"

mkdir -p "$ASSETS_DIR/PP-OCRv6_small_det_infer"
download "$RELEASE_URL/PP-OCRv6_small_det_inference.onnx" "PP-OCRv6_small_det_inference.onnx" "$ASSETS_DIR/PP-OCRv6_small_det_infer/inference.onnx"

echo ""
echo "══════════════════════════════════════════════"
echo "  下载完成！请检查 $ASSETS_DIR"
echo ""
echo "  如需从 inswapper_128.onnx 提取 emap 矩阵:"
echo "    pip install onnx numpy"
echo "    python scripts/extract_emap.py"
echo ""
echo "  然后运行 ./gradlew installDebug 构建 APK"
echo "══════════════════════════════════════════════"
