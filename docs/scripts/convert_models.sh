#!/bin/bash
# One-shot script to convert source models → TFLite int8 → vendor to assets/.
# Not invoked by build; developers run manually with Python toolchain.
#
# Requirements:
#   pip install timm onnx onnx-tf tensorflow tf2onnx onnxruntime ai-edge-litert safetensors huggingface_hub
set -e

OUT_DIR="$(dirname "$0")/../../app/src/main/assets"
mkdir -p "$OUT_DIR"

echo "==[1/3] Download MobileNetV2 quant tflite =="
curl -L -o "$OUT_DIR/mobilenet_v2_1.0_224_quant.tflite" \
  https://storage.googleapis.com/download.tensorflow.org/models/tflite_v2_2/mobilenet_v2_1.0_224_quant.tflite

echo "==[2/3] Convert DT24-Tiny Danbooru → TFLite int8 =="
python - <<'PY'
import torch, timm, safetensors.torch
from huggingface_hub import hf_hub_download

ckpt = hf_hub_download(repo_id='igidn/DT24-Tiny', filename='model.safetensors')
backbone = timm.create_model('convnextv2_tiny.fcmae_ft_in1k', pretrained=False,
                              num_classes=0, global_pool='')
state = safetensors.torch.load_file(ckpt)
backbone.load_state_dict(
    {k.replace('backbone.', ''): v for k, v in state.items() if k.startswith('backbone.')},
    strict=False
)
backbone.eval()

dummy = torch.randn(1, 3, 448, 448)
torch.onnx.export(backbone, dummy, '/tmp/dt24_tiny_backbone.onnx', opset_version=17,
                  input_names=['pixel_values'], output_names=['features'])
print('Backbone ONNX exported → /tmp/dt24_tiny_backbone.onnx')
print('NOTE: head merge + TFLite conversion is the next manual step.')
PY

echo "==[3/3] Convert Apple MobileCLIP-S2 → TFLite int8 =="
echo "TODO: Apple ML-MobileCLIP license + conversion script (manual step)."

echo "Done. Verify $OUT_DIR/*.tflite exist and are non-empty."
