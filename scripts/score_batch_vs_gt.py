import json, csv, sys, os, urllib.parse, statistics as st

GT_CSV = "runs/accuracy_eval/question_level_predictions.csv"

# Build GT: (dataset, basename) -> {q:int -> gt}, and expected_questions
gt = {}
exp = {}
with open(GT_CSV, encoding="utf-8-sig") as f:
    for row in csv.DictReader(f):
        if row["method"] != "Traditional CV":
            continue
        sid = row["sheet_id"]              # e.g. dataset_1/IMG_xxx.jpg
        ds, base = sid.split("/", 1)
        key = (ds, base)
        q = int(row["question"])
        gt.setdefault(key, {})[q] = row["gt"].strip()
        exp[key] = int(row["expected_questions"])

def key_from_url(url):
    # /dataset/dataset_1/B%C3%A0i%20l%C3%A0m/IMG_xxx.jpg
    parts = urllib.parse.unquote(url).split("/")
    ds = next((p for p in parts if p.startswith("dataset_")), None)
    base = parts[-1]
    return (ds, base)

def score(path):
    d = json.load(open(path, encoding="utf-8"))
    per_ds = {}   # ds -> [correct, total]
    cpp = []
    for r in d:
        if r.get("status") != 0:
            continue
        cpp.append(r["cpp_ms"])
        key = key_from_url(r["url"])
        if key not in gt:
            continue
        ds = key[0]
        n = exp[key]
        c = per_ds.setdefault(ds, [0, 0])
        for q in range(1, n + 1):
            pred = (r.get(f"auto_q{q:02d}") or "").strip()
            g = gt[key].get(q, "")
            c[1] += 1
            if pred == g:
                c[0] += 1
    tot_c = sum(v[0] for v in per_ds.values())
    tot_t = sum(v[1] for v in per_ds.values())
    return per_ds, tot_c, tot_t, cpp

print(f"{'run':<8} {'acc':<9} {'correct/total':<14} {'cpp_med':<8} {'cpp_mean':<8}")
allcpp = []
accs = []
for path in sys.argv[1:]:
    per_ds, c, t, cpp = score(path)
    allcpp += cpp
    acc = 100.0 * c / t if t else 0
    accs.append(acc)
    name = os.path.basename(path).replace("redmi_fixed_cv_threads_", "").replace("_n179.json", "")
    print(f"{name:<8} {acc:6.2f}%   {c}/{t:<10} {st.median(cpp):<8.0f} {st.mean(cpp):<8.0f}")
if accs:
    print(f"\nAccuracy across runs: min={min(accs):.2f}% max={max(accs):.2f}% (CV deterministic → should be identical)")
    print(f"cpp_ms over ALL runs pooled: median={st.median(allcpp):.0f} mean={st.mean(allcpp):.0f} min={min(allcpp)} max={max(allcpp)}")
