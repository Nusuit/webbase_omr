"""
Train YOLOv8n on the full_label_yolo dataset (4 classes).
Classes:
  0: marker_corners
  1: id_keycode
  2: questions_1_20
  3: questions_21_60

Run from repo root:
  source .venv/Scripts/activate
  python scripts/train_yolov8.py
"""

from pathlib import Path
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parent.parent
DATASET_YAML = ROOT / "full_label_yolo" / "dataset.yaml"
IMGSZ = 640           # 640px is ~8x faster on CPU than 1280px; regions are large so quality is fine
EPOCHS = 300
BATCH = 8             # can use larger batch at 640px
PATIENCE = 80         # early stop if no improvement


def train():
    model = YOLO("yolov8n.pt")   # pretrained COCO weights
    results = model.train(
        data=str(DATASET_YAML),
        epochs=EPOCHS,
        imgsz=IMGSZ,
        batch=BATCH,
        patience=PATIENCE,
        device="cpu",            # change to 0 if CUDA GPU available
        workers=2,
        # ── Augmentation (important for small datasets) ──────────────────
        mosaic=1.0,
        mixup=0.15,
        copy_paste=0.1,
        fliplr=0.5,
        flipud=0.0,
        degrees=5.0,
        translate=0.1,
        scale=0.3,
        shear=2.0,
        perspective=0.0003,
        hsv_h=0.015,
        hsv_s=0.7,
        hsv_v=0.4,
        # ── Optimizer ────────────────────────────────────────────────────
        optimizer="AdamW",
        lr0=0.001,
        lrf=0.01,
        warmup_epochs=5,
        weight_decay=0.0005,
        # ── Output ───────────────────────────────────────────────────────
        project=str(ROOT / "runs" / "train"),
        name="omr_v2",
        exist_ok=True,
        save_period=50,
    )
    print("\n=== Training done ===")
    best_pt = Path(results.save_dir) / "weights" / "best.pt"
    print(f"Best weights: {best_pt}")
    return best_pt


def export_onnx(best_pt: Path):
    model = YOLO(str(best_pt))
    model.export(
        format="onnx",
        imgsz=IMGSZ,
        opset=12,
        simplify=True,
        dynamic=False,
    )
    onnx_path = best_pt.with_suffix(".onnx")
    print(f"Exported ONNX: {onnx_path}")
    return onnx_path


def replace_model(onnx_path: Path):
    dest = ROOT / "web" / "models" / "paper_detect.onnx"
    import shutil
    shutil.copy2(onnx_path, dest)
    print(f"Replaced model: {dest}")


if __name__ == "__main__":
    best_pt = train()
    onnx_path = export_onnx(best_pt)
    replace_model(onnx_path)
    print("\nDone! New model deployed to web/models/paper_detect.onnx")
