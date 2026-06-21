"""Package a portable, self-contained corner-marker dataset for Colab GPU training.

Auto-discovers every export folder matching 'yolo_label_with_4_corners_*' (supports
both YOLO-txt exports with a labels/ dir and CVAT-xml 'annotations.xml' exports),
keeps ONLY the marker_corners class (remapped to class 0), resolves each image by
basename against the local datasets, copies images in, makes a train/val split,
and zips everything to the Google Drive folder so Colab can pick it up.

Output zip layout (no data.yaml — the Colab notebook writes it with the right path):
    corner_dataset/images/{train,val}/*.jpg
    corner_dataset/labels/{train,val}/*.txt

Run:  python scripts/package_corner_dataset.py
      python scripts/package_corner_dataset.py --out "G:/My Drive/omr_corner_train"
"""
import os, glob, shutil, random, argparse, zipfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DSROOT = os.path.join(ROOT, "Dataset_OMR_classified")
FULLLABEL_IMG = os.path.join(ROOT, "full_label_yolo", "images")
EXPORT_GLOBS = [os.path.join(ROOT, "yolo_label_with_4_corners_*"),
                os.path.join(ROOT, "dapan_yolo_*"),
                os.path.join(ROOT, "dapand2")]
CORNER_NAME = "marker_corners"
random.seed(42)


def build_image_index():
    # ONLY the curated dataset (dataset_1..5 student sheets + answer keys).
    # full_label_yolo / archive are deliberately excluded: they leak legacy
    # session photos (IMG_20260320_1536xx) that are neither eval sheets nor
    # answer keys, inflating the train set past the clean 179+6=185.
    idx = {}
    for p in glob.glob(os.path.join(DSROOT, "**", "*.jpg"), recursive=True):
        if "Trash" in p or "_legacy" in p:
            continue
        idx.setdefault(os.path.basename(p), p)
    return idx


def _read_yaml_names(path):
    """Tiny YAML 'names:' reader -> {id:int -> name}. Avoids a yaml dep."""
    names, in_names = {}, False
    for line in open(path, encoding="utf-8"):
        s = line.rstrip("\n")
        if s.strip().startswith("names:"):
            in_names = True
            continue
        if in_names:
            t = s.strip()
            if not t or not s.startswith((" ", "\t")):
                break
            if ":" in t:
                k, v = t.split(":", 1)
                try:
                    names[int(k.strip())] = v.strip()
                except ValueError:
                    pass
    return names


def parse_yolo_txt_folder(folder):
    """folder with data.yaml + labels/train/*.txt -> {stem: [(cx,cy,w,h),...]}"""
    yaml = os.path.join(folder, "data.yaml")
    names = _read_yaml_names(yaml) if os.path.exists(yaml) else {}
    corner_ids = {str(i) for i, n in names.items() if n == CORNER_NAME}
    out = {}
    for txt in glob.glob(os.path.join(folder, "labels", "**", "*.txt"), recursive=True):
        stem = os.path.splitext(os.path.basename(txt))[0]
        boxes = []
        for line in open(txt):
            parts = line.split()
            if parts and parts[0] in corner_ids:
                boxes.append(tuple(float(x) for x in parts[1:5]))
        if boxes:
            out[stem] = boxes
    return out


def parse_cvat_xml(folder):
    xml = os.path.join(folder, "annotations.xml")
    out = {}
    for img in ET.parse(xml).getroot().iter("image"):
        stem = os.path.splitext(img.get("name"))[0]
        W, H = float(img.get("width")), float(img.get("height"))
        boxes = []
        for b in img.findall("box"):
            if b.get("label") != CORNER_NAME:
                continue
            xtl, ytl = float(b.get("xtl")), float(b.get("ytl"))
            xbr, ybr = float(b.get("xbr")), float(b.get("ybr"))
            boxes.append(((xtl + xbr) / 2 / W, (ytl + ybr) / 2 / H,
                          (xbr - xtl) / W, (ybr - ytl) / H))
        if boxes:
            out[stem] = boxes
    return out


def discover():
    """Merge all export folders -> {stem: boxes}. Later folders win on conflict."""
    merged = {}
    folders = sorted(g for pat in EXPORT_GLOBS for g in glob.glob(pat))
    for f in folders:
        if not os.path.isdir(f):
            continue
        if os.path.exists(os.path.join(f, "annotations.xml")):
            data, kind = parse_cvat_xml(f), "xml"
        elif os.path.isdir(os.path.join(f, "labels")):
            data, kind = parse_yolo_txt_folder(f), "txt"
        else:
            print(f"  skip (no labels): {os.path.basename(f)}")
            continue
        print(f"  {os.path.basename(f)}: {len(data)} labeled ({kind})")
        merged.update(data)
    return merged


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="G:/My Drive/omr_corner_train")
    ap.add_argument("--val-frac", type=float, default=0.15)
    args = ap.parse_args()

    print("Discovering export folders:")
    data = discover()
    idx = build_image_index()
    stems = sorted(data.keys())
    random.shuffle(stems)

    staging = os.path.join(ROOT, "runs", "corner_dataset_pkg", "corner_dataset")
    if os.path.exists(os.path.dirname(staging)):
        shutil.rmtree(os.path.dirname(staging))
    for sub in ("images/train", "images/val", "labels/train", "labels/val"):
        os.makedirs(os.path.join(staging, sub), exist_ok=True)

    nval = max(1, round(len(stems) * args.val_frac))
    placed, miss, not4 = 0, [], []
    for i, stem in enumerate(stems):
        boxes = data[stem]
        if len(boxes) != 4:
            not4.append((stem, len(boxes)))
            continue
        ip = idx.get(stem + ".jpg")
        if not ip:
            miss.append(stem)
            continue
        split = "val" if i < nval else "train"
        shutil.copy2(ip, os.path.join(staging, "images", split, stem + ".jpg"))
        with open(os.path.join(staging, "labels", split, stem + ".txt"), "w") as f:
            for (cx, cy, w, h) in boxes:
                f.write(f"0 {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}\n")
        placed += 1

    os.makedirs(args.out, exist_ok=True)
    zip_path = os.path.join(args.out, "corner_dataset.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in glob.glob(os.path.join(staging, "**", "*"), recursive=True):
            if os.path.isfile(f):
                z.write(f, os.path.relpath(f, os.path.dirname(staging)))

    print(f"\nplaced={placed}  (val={nval})  not4={len(not4)}  missing_img={len(miss)}")
    if not4:
        print("  !=4 boxes:", ", ".join(f"{s[:24]}({n})" for s, n in not4[:8]))
    if miss:
        print("  image not found:", ", ".join(s[:24] for s in miss[:8]))
    print(f"\nZIP -> {zip_path}  ({os.path.getsize(zip_path)/1e6:.1f} MB)")

if __name__ == "__main__":
    main()
