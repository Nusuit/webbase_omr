"""B1 eval: how well does the trained corner detector localize the 4 markers
on the val set? Reports:
  - % images where exactly 4 corners are recovered (>=4 dets, keep 4 best by conf)
  - per-corner pixel error vs GT (after geometric TL/TR/BR/BL sort), median/p90/max
  - error as % of image diagonal (scale-free)

Run:  python scripts/eval_corner_yolo.py [conf]   (default conf=0.25)
"""
import os, glob, sys, math, statistics as st
from ultralytics import YOLO

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXP = os.path.join(ROOT, "runs", "yolo_corner_exp")
WEIGHTS = os.path.join(EXP, "train", "corners_n85", "weights", "best.pt")

def sort_corners(pts):
    cy = sum(p[1] for p in pts) / len(pts)
    top = sorted([p for p in pts if p[1] < cy], key=lambda p: p[0])
    bot = sorted([p for p in pts if p[1] >= cy], key=lambda p: p[0])
    if len(top) != 2 or len(bot) != 2:
        s = sorted(pts, key=lambda p: (p[1], p[0]))
        top, bot = sorted(s[:2], key=lambda p: p[0]), sorted(s[2:], key=lambda p: p[0])
    return [top[0], top[1], bot[1], bot[0]]

def gt_corners(stem, split, W, H):
    lp = os.path.join(EXP, "labels", split, stem + ".txt")
    pts = []
    for line in open(lp):
        c = line.split()
        if len(c) >= 5:
            pts.append((float(c[1]) * W, float(c[2]) * H))
    return pts

def main():
    conf = float(sys.argv[1]) if len(sys.argv) > 1 else 0.25
    model = YOLO(WEIGHTS)
    val_imgs = sorted(glob.glob(os.path.join(EXP, "images", "val", "*.jpg")))
    exact4 = 0
    all_err = []          # per-corner pixel error
    all_err_pct = []      # per-corner error / diagonal
    worst = []
    for ip in val_imgs:
        stem = os.path.splitext(os.path.basename(ip))[0]
        r = model.predict(ip, conf=conf, imgsz=960, verbose=False)[0]
        W, H = r.orig_shape[1], r.orig_shape[0]
        diag = math.hypot(W, H)
        boxes = r.boxes
        dets = []
        for i in range(len(boxes)):
            x1, y1, x2, y2 = boxes.xyxy[i].tolist()
            dets.append(((x1 + x2) / 2, (y1 + y2) / 2, float(boxes.conf[i])))
        dets.sort(key=lambda d: -d[2])
        n = len(dets)
        if n >= 4:
            exact4 += 1 if n == 4 else 0
            pred = sort_corners([(d[0], d[1]) for d in dets[:4]])
            gt = sort_corners(gt_corners(stem, "val", W, H))
            errs = [math.hypot(p[0] - g[0], p[1] - g[1]) for p, g in zip(pred, gt)]
            all_err += errs
            all_err_pct += [e / diag * 100 for e in errs]
            worst.append((max(errs), stem, n))
        else:
            worst.append((9e9, stem, n))  # under-detection
    tot = len(val_imgs)
    full = sum(1 for w in worst if w[0] < 9e9)
    print(f"val images: {tot}   conf={conf}")
    print(f"recovered >=4 corners: {full}/{tot} ({100*full/tot:.0f}%)   exactly 4: {exact4}/{tot}")
    if all_err:
        print(f"per-corner px error : median={st.median(all_err):.1f}  p90={sorted(all_err)[int(.9*len(all_err))]:.1f}  max={max(all_err):.1f}")
        print(f"per-corner % of diag: median={st.median(all_err_pct):.2f}%  max={max(all_err_pct):.2f}%")
    print("\nworst sheets:")
    for e, stem, n in sorted(worst, reverse=True)[:6]:
        tag = f"{e:.0f}px" if e < 9e9 else f"UNDER-DET ({n} dets)"
        print(f"   {stem[:42]:<44} {tag}")

if __name__ == "__main__":
    main()
