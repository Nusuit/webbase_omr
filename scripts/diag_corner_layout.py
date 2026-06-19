import json, glob, os, cv2, statistics as st
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
gt = json.load(open(os.path.join(ROOT, "web", "corners_gt.json")))

idx = {}
for p in glob.glob(os.path.join(ROOT, "Dataset_OMR_classified", "**", "*.jpg"), recursive=True):
    if "Trash" in p or "_legacy" in p:
        continue
    idx.setdefault(os.path.basename(p), p)

def ds_of(base):
    p = idx.get(base, "").replace("\\", "/")
    for d in ("dataset_1", "dataset_2", "dataset_3", "dataset_4", "dataset_5"):
        if "/" + d + "/" in p:
            return d
    return "?"

def sortc(pts):
    P = [(pts[i], pts[i + 1]) for i in range(0, 8, 2)]
    P.sort(key=lambda q: q[1])
    top = sorted(P[:2]); bot = sorted(P[2:])
    return [top[0], top[1], bot[1], bot[0]]  # TL TR BR BL

agg = defaultdict(list)
dims = defaultdict(list)
for base, pts in gt.items():
    p = idx.get(base)
    if not p:
        continue
    d = ds_of(base)
    im = cv2.imread(p)
    H, W = im.shape[:2]
    dims[d].append((W, H))
    c = sortc(pts)
    agg[d].append([(x / W, y / H) for x, y in c])

print("dataset  |   TL          TR          BR          BL       | n  | spanX spanY | imgWxH")
for d in sorted(agg):
    rows = agg[d]
    means = [(st.mean(r[i][0] for r in rows), st.mean(r[i][1] for r in rows)) for i in range(4)]
    spanX = means[1][0] - means[0][0]
    spanY = means[3][1] - means[0][1]
    w, h = dims[d][0]
    print("%-8s |" % d, " ".join("(%.2f,%.2f)" % (m[0], m[1]) for m in means),
          "| %2d |" % len(rows), "%.2f  %.2f |" % (spanX, spanY), "%dx%d" % (w, h))
