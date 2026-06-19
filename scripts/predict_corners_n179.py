"""Predict the 4 corner-marker centres for every student sheet with the trained
corner model, in ORIGINAL image pixel coordinates (the same space the web worker
passes to the C++ core). Saves web/corners_pred.json keyed by image basename:

    { "IMG_xxx.jpg": [x0,y0,x1,y1,x2,y2,x3,y3], ... }

Points are unsorted; the C++ side orders them TL/TR/BR/BL. Sheets with <4
detections are recorded under "_under" so the harness can fall back.

Run:  python scripts/predict_corners_n179.py
"""
import os, glob, json, sys
from ultralytics import YOLO

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEIGHTS = "G:/My Drive/omr_corner_train/out_20260618_1503/best.pt"
DSROOT = os.path.join(ROOT, "Dataset_OMR_classified")
OUT = os.path.join(ROOT, "web", "corners_pred.json")
IMGSZ = 960

def main():
    model = YOLO(WEIGHTS)
    # student sheets only ("Bài làm"), the N=179 accuracy set
    imgs = []
    for ds in ("dataset_1", "dataset_2", "dataset_3", "dataset_4", "dataset_5"):
        d = os.path.join(DSROOT, ds, "Bài làm")
        imgs += sorted(glob.glob(os.path.join(d, "*.jpg")))
    print(f"sheets found: {len(imgs)}")

    out, under = {}, []
    for ip in imgs:
        base = os.path.basename(ip)
        r = model.predict(ip, imgsz=IMGSZ, conf=0.01, verbose=False)[0]
        dets = sorted(
            [((b[0] + b[2]) / 2, (b[1] + b[3]) / 2, float(c))
             for b, c in zip(r.boxes.xyxy.tolist(), r.boxes.conf.tolist())],
            key=lambda d: -d[2])
        if len(dets) >= 4:
            pts = []
            for (cx, cy, _) in dets[:4]:
                pts += [round(cx, 2), round(cy, 2)]
            out[base] = pts
        else:
            under.append(base)
    if under:
        out["_under"] = under
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f)
    print(f"predicted >=4 corners: {len(out) - (1 if under else 0)}/{len(imgs)}")
    if under:
        print(f"under-detected ({len(under)}): {under[:6]}")
    print(f"wrote {OUT}")

if __name__ == "__main__":
    main()
