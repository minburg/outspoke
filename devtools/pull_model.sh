#!/usr/bin/env bash
# =============================================================================
# pull_model.sh — pull Parakeet-V3 model files OUT of the app's internal
# storage back to your local machine.
#
# Run this BEFORE a manual uninstall so you can re-push quickly afterwards.
#
# Usage:
#   ./devtools/pull_model.sh [/path/to/save/dir]
#
#   Saves to ./model_files/ by default.
# =============================================================================

set -euo pipefail

PACKAGE="dev.brgr.outspoke"
INTERNAL_MODEL_DIR="/data/user/0/${PACKAGE}/files/models/parakeet-v3"
STAGING_DIR="/sdcard/tmp_outspoke_pull"
OUTPUT_DIR="${1:-./model_files}"

MODEL_FILES=(
  "encoder-model.int8.onnx"
  "decoder_joint-model.int8.onnx"
  "nemo128.onnx"
  "config.json"
  "vocab.txt"
)

if ! command -v adb &>/dev/null; then
  echo "❌  adb not found in PATH."
  exit 1
fi

mkdir -p "${OUTPUT_DIR}"

echo "📂  Copying model files out of app storage via run-as …"
adb shell "run-as ${PACKAGE} mkdir -p ${STAGING_DIR}"

for f in "${MODEL_FILES[@]}"; do
  adb shell "run-as ${PACKAGE} cp ${INTERNAL_MODEL_DIR}/${f} ${STAGING_DIR}/${f}"
done

echo "⬇️   Pulling to ${OUTPUT_DIR}/ …"
for f in "${MODEL_FILES[@]}"; do
  adb pull "${STAGING_DIR}/${f}" "${OUTPUT_DIR}/${f}"
  echo "    ✓  ${f}"
done

adb shell "rm -rf ${STAGING_DIR}"

echo ""
echo "✅  Model files saved to ${OUTPUT_DIR}/"
echo "    To restore after a reinstall: ./devtools/push_model.sh ${OUTPUT_DIR}"

