#!/usr/bin/env python3
"""
WD ConvNeXt-v3 ONNX → INT8 dynamic 量化脚本.

输入: app/src/main/assets/wd_convnext_tagger_v3.onnx (FP16, ~376MB)
输出: app/src/main/assets/wd_convnext_tagger_v3-int8.onnx (INT8, ~95-110MB)

dynamic 量化无需校准数据 — 自动按 activation 分布选 scale.
量化 op 范围: MatMul/Gemm/Conv. WD tagger 主要计算是 Conv, 加速明显.
FP16 → INT8 推理加速 1.5-2x (vs FP16 → FP32 baseline); 体积 ~3.5x 压缩.

用法:
  .venv-wd/Scripts/python scripts/quantize_wd_int8.py
"""
import sys
from pathlib import Path
from onnxruntime.quantization import quantize_dynamic, QuantType


def main():
    import shutil, tempfile
    project_root = Path(__file__).parent.parent
    src = project_root / "app/src/main/assets/wd_convnext_tagger_v3.onnx"
    final_dst = project_root / "app/src/main/assets/wd_convnext_tagger_v3-int8.onnx"

    if not src.exists():
        print(f"ERROR: {src} not found. Place FP16/FP32 model there first.", file=sys.stderr)
        sys.exit(1)

    # onnxruntime.quantization 在 Windows 上对中文路径 shape inference 失败
    # (GBK 解码 bug). 复制到 ASCII 短路径处理, 再移回.
    work_dir = Path(tempfile.gettempdir()) / "wd_quant"
    work_dir.mkdir(exist_ok=True)
    work_src = work_dir / src.name
    work_dst = work_dir / final_dst.name
    if not work_src.exists():
        shutil.copy(src, work_src)
    src = work_src
    dst = work_dst

    if dst.exists():
        print(f"WARN: {dst} already exists, overwriting.")

    src_mb = src.stat().st_size / 1024 / 1024
    print(f"Quantizing {src.name} ({src_mb:.1f} MB) → {dst.name}")
    print(f"  weight_type=QInt8 per_channel=True (高精度量化 Conv 权重)")

    quantize_dynamic(
        model_input=str(src),
        model_output=str(dst),
        weight_type=QuantType.QInt8,
        per_channel=True,
        reduce_range=False,
    )

    if not dst.exists():
        print(f"ERROR: quantization did not produce {dst}", file=sys.stderr)
        sys.exit(1)
    dst_mb = dst.stat().st_size / 1024 / 1024
    print(f"Done. INT8 size: {dst_mb:.1f} MB (压缩比 {src_mb / dst_mb:.2f}x)")
    print(f"Copying back to: {final_dst}")
    shutil.copy(dst, final_dst)
    print(f"Final: {final_dst}")


if __name__ == "__main__":
    main()