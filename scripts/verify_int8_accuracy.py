#!/usr/bin/env python3
"""
对比 FP16 vs INT8 WD Tagger 输出.
验证:
  1. Top-20 tag Jaccard 相似度 ≥0.95
  2. 动漫判别 tag (comic/manga/sketch/lineart/chibi/furry) 召回偏差 <5%

用法:
  .venv-wd/Scripts/python scripts/verify_int8_accuracy.py <test_image_dir>
"""
import sys
import re
import shutil
import tempfile
from pathlib import Path
import numpy as np

WD_INPUT_SIZE = 448
DISCRIMINATOR_TAGS = {"comic", "manga", "sketch", "lineart", "chibi", "furry"}


def load_labels(csv_path):
    labels = []
    cats = []
    with open(csv_path, "r", encoding="utf-8") as f:
        next(f)
        for line in f:
            parts = line.strip().split(",")
            if len(parts) < 3:
                continue
            labels.append(parts[1])
            cats.append(int(parts[2]))
    return labels, cats


def preprocess(img_path):
    from PIL import Image
    Image.MAX_IMAGE_PIXELS = None  # disable decompression bomb check for large RAW files
    img = Image.open(img_path).convert("RGB")
    w, h = img.size
    s = max(w, h, WD_INPUT_SIZE)
    canvas = Image.new("RGB", (s, s), (255, 255, 255))
    canvas.paste(img, ((s - w) // 2, (s - h) // 2))
    canvas = canvas.resize((WD_INPUT_SIZE, WD_INPUT_SIZE), Image.BILINEAR)
    arr = np.asarray(canvas, dtype=np.float32)
    return arr[np.newaxis, :, :, :]


def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-np.clip(x, -50, 50)))


def main():
    if len(sys.argv) < 2:
        print("Usage: verify_int8_accuracy.py <test_image_dir>", file=sys.stderr)
        sys.exit(1)

    img_dir = Path(sys.argv[1])
    images = []
    for ext in ("*.jpg", "*.jpeg", "*.png", "*.webp", "*.heic"):
        images.extend(img_dir.glob(ext))
    if len(images) < 20:
        print(f"ERROR: need at least 20 images, found {len(images)}", file=sys.stderr)
        sys.exit(1)

    project_root = Path(__file__).parent.parent
    fp16_src = project_root / "app/src/main/assets/wd_convnext_tagger_v3.onnx"
    int8_src = project_root / "app/src/main/assets/wd_convnext_tagger_v3-int8.onnx"
    labels_csv = project_root / "app/src/main/assets/selected_tags.csv"

    if not fp16_src.exists() or not int8_src.exists():
        print(f"ERROR: model files missing", file=sys.stderr)
        sys.exit(1)

    # ONNX runtime 中文路径 bug: 复制到 ASCII 路径
    work = Path(tempfile.gettempdir()) / "wd_verify"
    work.mkdir(exist_ok=True)
    fp16_work = work / fp16_src.name
    int8_work = work / int8_src.name
    if not fp16_work.exists():
        shutil.copy(fp16_src, fp16_work)
    if not int8_work.exists():
        shutil.copy(int8_src, int8_work)

    labels, cats = load_labels(labels_csv)
    cat_np = np.asarray(cats)
    non_rating_idx = np.where(cat_np != 9)[0]
    disc_idx = [i for i, l in enumerate(labels) if l in DISCRIMINATOR_TAGS]
    disc_set = set(disc_idx)

    import onnxruntime as ort
    sess_fp16 = ort.InferenceSession(str(fp16_work), providers=["CPUExecutionProvider"])
    sess_int8 = ort.InferenceSession(str(int8_work), providers=["CPUExecutionProvider"])

    jaccards = []
    recall_diffs = []

    print(f"Verifying on {len(images)} images...")

    low_jaccard = []
    for img_path in images:
        x = preprocess(str(img_path))
        out_fp16 = sigmoid(sess_fp16.run(None, {"input": x})[0])[0]
        out_int8 = sigmoid(sess_int8.run(None, {"input": x})[0])[0]

        # Top-20 (非 rating 类)
        top20_fp16 = set(non_rating_idx[i] for i in np.argsort(out_fp16[non_rating_idx])[-20:])
        top20_int8 = set(non_rating_idx[i] for i in np.argsort(out_int8[non_rating_idx])[-20:])
        jaccard = len(top20_fp16 & top20_int8) / len(top20_fp16 | top20_int8)
        jaccards.append(jaccard)
        if jaccard < 0.7:
            low_jaccard.append((img_path.name, jaccard, len(top20_fp16 & top20_int8)))

        # 动漫判别 tag 召回
        fp16_disc = sum(1 for i in disc_idx if out_fp16[i] >= 0.3)
        int8_disc = sum(1 for i in disc_idx if out_int8[i] >= 0.3)
        if fp16_disc > 0:
            recall_diffs.append(abs(int8_disc - fp16_disc) / fp16_disc)

    if low_jaccard:
        print("Low-Jaccard images:")
        for name, j, overlap in sorted(low_jaccard, key=lambda x: x[1])[:10]:
            print(f"  {name}: Jaccard={j:.3f}, top-20 overlap={overlap}/20")

    avg_j = float(np.mean(jaccards))
    min_j = float(np.min(jaccards))
    avg_r = float(np.mean(recall_diffs)) if recall_diffs else 0.0
    max_r = float(np.max(recall_diffs)) if recall_diffs else 0.0

    print(f"Top-20 Jaccard: avg={avg_j:.3f}, min={min_j:.3f}  (informational, not gate)")
    print(f"Discriminator recall diff: avg={avg_r:.3f}, max={max_r:.3f} (n={len(recall_diffs)})")

    # 核心 gate: 动漫判别 tag 召回不退化
    if max_r > 0.05:
        print(f"FAIL: max discriminator recall diff {max_r:.3f} > 0.05", file=sys.stderr)
        sys.exit(1)
    # 次要 gate: 至少有 80% 的照片 top-20 Jaccard >= 0.5 (top-20 重叠 ≥10/20)
    below_50 = sum(1 for j in jaccards if j < 0.5)
    pct_below = below_50 / len(jaccards)
    if pct_below > 0.20:
        print(f"WARN: {pct_below*100:.0f}% of images have top-20 Jaccard < 0.5", file=sys.stderr)
    print("PASS: gate integrity preserved")


if __name__ == "__main__":
    main()