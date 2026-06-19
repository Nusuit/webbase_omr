"""B0: Build a single-class (marker_corners) feasibility dataset from the two
CVAT/YOLO exports, for testing whether YOLO can reliably localize the 4 sheet
corner markers.

- Source A: yolo_label_with_4_corners_1(1)/labels/train/*.txt  (already YOLO txt; class 4 = marker_corners)
- Source B: yolo_label_with_4_corners_2(1)/annotations.xml      (CVAT XML)
- Keeps ONLY marker_corners boxes, remaps to class 0.
- Resolves each labeled image by basename against Dataset_OMR_classified (skips Trash/_legacy).
- Writes runs/yolo_corner_exp/{images,labels}/{train,val} + data.yaml.
- Stratified split per source so val contains both phone (IMG_) and Zalo (z) images.

Run:  python scripts/build_corner_exp_dataset.py
"""
import os, glob, shutil, random, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_A = os.path.join(ROOT, "yolo_label_with_4_corners_1(1)")
SRC_B = os.path.join(ROOT, "yolo_label_with_4_corners_2(1)")
DSROOT = os.path.join(ROOT, "Dataset_OMR_classified")
OUT = os.path.join(ROOT, "runs", "yolo_corner_exp")
CORNER_NAME = "marker_corners"
CORNER_CLASS_A = "4"   # class id of marker_corners in export A's data.yaml
random.seed(42)

def build_image_index():
    idx = {}
    # Primary: classified dataset (skip Trash/legacy). Fallback: labeled master set.
    roots = [DSROOT, os.path.join(ROOT, "full_label_yolo", "images")]
    for root in roots:
        for p in glob.glob(os.path.join(root, "**", "*.jpg"), recursive=True):
            if "Trash" in p or "_legacy" in p:
                continue
            idx.setdefault(os.path.basename(p), p)
    return idx

def parse_export_a():
    """basename(stem) -> list of (cx,cy,w,h) normalized corner boxes."""
    out = {}
    for txt in glob.glob(os.path.join(SRC_A, "labels", "train", "*.txt")):
        stem = os.path.splitext(os.path.basename(txt))[0]
        boxes = []
        for line in open(txt):
            parts = line.split()
            if parts and parts[0] == CORNER_CLASS_A:
                boxes.append(tuple(float(x) for x in parts[1:5]))
        out[stem] = boxes
    return out

def parse_export_b():
    tree = ET.parse(os.path.join(SRC_B, "annotations.xml"))
    out = {}
    for img in tree.getroot().iter("image"):
        stem = os.path.splitext(img.get("name"))[0]
        W, H = float(img.get("width")), float(img.get("height"))
        boxes = []
        for b in img.findall("box"):
            if b.get("label") != CORNER_NAME:
                continue
            xtl, ytl = float(b.get("xtl")), float(b.get("ytl"))
            xbr, ybr = float(b.get("xbr")), float(b.get("ybr"))
            cx, cy = (xtl + xbr) / 2 / W, (ytl + ybr) / 2 / H
            bw, bh = (xbr - xtl) / W, (ybr - ytl) / H
            boxes.append((cx, cy, bw, bh))
        out[stem] = boxes
    return out

def main():
    idx = build_image_index()
    a, b = parse_export_a(), parse_export_b()
    print(f"export A: {len(a)} labeled images | export B: {len(b)} labeled images")

    # reset output
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    for sub in ("images/train", "images/val", "labels/train", "labels/val"):
        os.makedirs(os.path.join(OUT, sub), exist_ok=True)

    sources = [("A", a), ("B", b)]
    stats = {"placed": 0, "missing_img": [], "not4": []}
    splits = {"train": [], "val": []}
    for tag, data in sources:
        stems = sorted(data.keys())
        random.shuffle(stems)
        nval = max(1, round(len(stems) * 0.2))
        for i, stem in enumerate(stems):
            split = "val" if i < nval else "train"
            boxes = data[stem]
            if len(boxes) != 4:
                stats["not4"].append((tag, stem, len(boxes)))
                continue  # skip incomplete sheets from the feasibility set
            img_path = idx.get(stem + ".jpg")
            if not img_path:
                stats["missing_img"].append((tag, stem))
                continue
            dst_img = os.path.join(OUT, "images", split, stem + ".jpg")
            shutil.copy2(img_path, dst_img)
            with open(os.path.join(OUT, "labels", split, stem + ".txt"), "w") as f:
                for (cx, cy, w, h) in boxes:
                    f.write(f"0 {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}\n")
            splits[split].append(stem)
            stats["placed"] += 1

    with open(os.path.join(OUT, "data.yaml"), "w") as f:
        f.write(f"path: {OUT.replace(os.sep, '/')}\n"
                "train: images/train\nval: images/val\nnames:\n  0: marker_corners\n")

    print(f"placed={stats['placed']}  train={len(splits['train'])}  val={len(splits['val'])}")
    if stats["not4"]:
        print(f"\n!! {len(stats['not4'])} sheets with != 4 corner boxes:")
        for tag, stem, n in stats["not4"]:
            print(f"   [{tag}] {stem[:40]}  -> {n} boxes")
    if stats["missing_img"]:
        print(f"\n!! {len(stats['missing_img'])} labeled images not found in dataset:")
        for tag, stem in stats["missing_img"]:
            print(f"   [{tag}] {stem[:50]}")
    print(f"\nOutput -> {OUT}")

if __name__ == "__main__":
    main()
