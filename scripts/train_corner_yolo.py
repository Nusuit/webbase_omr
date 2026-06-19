"""B1 feasibility: train a single-class (marker_corners) YOLOv8n detector on the
small hand-labeled set. CPU-only. Goal is to see if YOLO can reliably localize
the 4 corner markers, NOT to produce a production model.

Run:  python scripts/train_corner_yolo.py
"""
import os
from ultralytics import YOLO

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "runs", "yolo_corner_exp", "data.yaml")
PROJECT = os.path.join(ROOT, "runs", "yolo_corner_exp", "train")

def main():
    model = YOLO("yolov8n.pt")  # pretrained COCO backbone
    model.train(
        data=DATA,
        epochs=100,
        imgsz=960,            # corners are tiny (~1.8% of width); need resolution
        batch=8,
        device="cpu",
        workers=2,
        patience=30,
        project=PROJECT,
        name="corners_n85",
        exist_ok=True,
        verbose=True,
        # light aug: sheets are upright, avoid flips that break corner identity
        fliplr=0.0, flipud=0.0, mosaic=0.0, degrees=0.0, scale=0.2,
        plots=True,
    )

if __name__ == "__main__":
    main()
