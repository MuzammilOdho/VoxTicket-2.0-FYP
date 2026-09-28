#!/usr/bin/env bash
# Reproducible download of the Phase 2/3 routing model artifacts.
# Model: intfloat/multilingual-e5-small (384 dims), ONNX (quantized) + tokenizer.
# Source: https://huggingface.co/intfloat/multilingual-e5-small/tree/main/onnx
#
# Expected SHA-256 (do NOT substitute another embedding model):
#   model.onnx:     ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665
#   tokenizer.json: 0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39
#
# Usage: ./scripts/download-routing-model.sh [target-dir]
# Default target: <repo>/models/e5   (git-ignored; never commit *.onnx)
# Then: export ROUTING_MODEL_PATH=<target-dir>
set -euo pipefail

TARGET="${1:-$(cd "$(dirname "$0")/.." && pwd)/models/e5}"
BASE_URL="https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/onnx"
EXPECTED_ONNX="ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665"
EXPECTED_TOK="0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"

mkdir -p "$TARGET"
echo "Downloading routing model artifacts to $TARGET ..."
curl -fL --retry 3 -o "$TARGET/model.onnx"     "$BASE_URL/model.onnx"
curl -fL --retry 3 -o "$TARGET/tokenizer.json" "$BASE_URL/tokenizer.json"

echo "Verifying SHA-256 ..."
ACTUAL_ONNX="$(sha256sum "$TARGET/model.onnx" | cut -d' ' -f1)"
ACTUAL_TOK="$(sha256sum "$TARGET/tokenizer.json" | cut -d' ' -f1)"
[ "$ACTUAL_ONNX" = "$EXPECTED_ONNX" ] || { echo "FATAL: model.onnx SHA mismatch: $ACTUAL_ONNX"; exit 1; }
[ "$ACTUAL_TOK" = "$EXPECTED_TOK" ]   || { echo "FATAL: tokenizer.json SHA mismatch: $ACTUAL_TOK"; exit 1; }

echo "OK: artifacts verified."
echo "Set: export ROUTING_MODEL_PATH=$TARGET"
