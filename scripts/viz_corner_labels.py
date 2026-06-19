"""Draw the 4 labeled corner-marker centers, sorted geometrically into
TL(0) -> TR(1) -> BR(2) -> BL(3), onto sample images so the ordering logic
can be eyeballed. Saves overlays to runs/yolo_corner_exp/viz/.

sort_corners() is the exact logic intended for the C++ side: split by the
centroid into top/bottom halves, then order each half by x.

Run:  python scripts/viz_corner_labels.py [N]   (default 8 samples)
"""
import os, glob, sys, cv2

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXP = os.path.join(ROOT, "runs", "yolo_corner_exp")
VIZ = os.path.join(EXP, "viz")

def sort_corners(pts):
    """pts: list of (x,y) pixel. Returns [TL, TR, BR, BL]."""
    cy = sum(p[1] for p in pts) / len(pts)
    top = sorted([p for p in pts if p[1] < cy], key=lambda p: p[0])
    bot = sorted([p for p in pts if p[1] >= cy], key=lambda p: p[0])
    # guard against degenerate split
    if len(top) != 2 or len(bot) != 2:
        s = sorted(pts, key=lambda p: (p[1], p[0]))
        top, bot = sorted(s[:2], key=lambda p: p[0]), sorted(s[2:], key=lambda p: p[0])
    return [top[0], top[1], bot[1], bot[0]]  # TL, TR, BR, BL

COL = [(0, 0, 255), (0, 255, 0), (255, 0, 0), (0, 255, 255)]  # TL,TR,BR,BL (BGR)

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    os.makedirs(VIZ, exist_ok=True)
    # mix train+val, prefer some z (Zalo) and some IMG
    lbls = glob.glob(os.path.join(EXP, "labels", "*", "*.txt"))
    z = [p for p in lbls if os.path.basename(p).startswith("z")]
    img = [p for p in lbls if not os.path.basename(p).startswith("z")]
    pick = (z[: n // 2] + img[: n - n // 2])
    bad = 0
    for lp in pick:
        stem = os.path.splitext(os.path.basename(lp))[0]
        split = os.path.basename(os.path.dirname(lp))
        ip = os.path.join(EXP, "images", split, stem + ".jpg")
        im = cv2.imread(ip)
        if im is None:
            continue
        H, W = im.shape[:2]
        pts = []
        for line in open(lp):
            c = line.split()
            if len(c) >= 5:
                pts.append((float(c[1]) * W, float(c[2]) * H))
        if len(pts) != 4:
            bad += 1
            continue
        ordered = sort_corners(pts)
        for i, (x, y) in enumerate(ordered):
            cv2.circle(im, (int(x), int(y)), 22, COL[i], -1)
            cv2.putText(im, str(i), (int(x) - 12, int(y) + 12),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.2, (255, 255, 255), 3)
        # draw quad in order to reveal any crossing (=wrong order)
        for i in range(4):
            a, b = ordered[i], ordered[(i + 1) % 4]
            cv2.line(im, (int(a[0]), int(a[1])), (int(b[0]), int(b[1])), (255, 0, 255), 3)
        out = os.path.join(VIZ, f"{stem}.jpg")
        cv2.imwrite(out, im)
        print(f"  {split}/{stem[:38]}  -> 0=TL 1=TR 2=BR 3=BL")
    print(f"\n{len(pick)-bad} overlays -> {VIZ}  (bad={bad})")
    print("Check: the magenta quad must NOT cross itself; 0/1 on top, 2/3 on bottom.")

if __name__ == "__main__":
    main()
