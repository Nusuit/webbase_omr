"""Generate Android-side manifest_n179_native.json + adb-push pairing list.

Reads the Web manifest (web/manifest.json), URL-decodes each path, maps to:
  - local Windows path under Dataset_OMR_classified/
  - phone path under /sdcard/Android/data/com.gradesnap.omr/files/bench_dataset/
"""
import json
import os
import urllib.parse

WEB_MANIFEST  = r"c:\Kien\Mobile\orm\wasm-omr-mobile\web\manifest.json"
LOCAL_ROOT    = r"c:\Kien\Mobile\orm\wasm-omr-mobile\Dataset_OMR_classified"
PHONE_ROOT    = "/sdcard/Android/data/com.gradesnap.omr/files/bench_dataset_root"
OUT_MANIFEST  = r"c:\Kien\Mobile\orm\wasm-omr-mobile\runs\manifest_n179_native.json"
OUT_PAIRS     = r"c:\Kien\Mobile\orm\wasm-omr-mobile\runs\_push_pairs.txt"

m = json.load(open(WEB_MANIFEST))
local_paths, phone_paths = [], []
for url in m:
    rel = urllib.parse.unquote(url).removeprefix("/dataset/")
    local_paths.append(os.path.join(LOCAL_ROOT, rel.replace("/", os.sep)))
    phone_paths.append(PHONE_ROOT + "/" + rel)

missing = [p for p in local_paths if not os.path.exists(p)]
print(f"total={len(local_paths)} missing={len(missing)}")
for p in missing[:5]:
    print("  missing:", p)
if missing:
    raise SystemExit(1)

with open(OUT_MANIFEST, "w", encoding="utf-8") as f:
    json.dump(phone_paths, f, ensure_ascii=False, indent=1)
print("wrote", OUT_MANIFEST)

with open(OUT_PAIRS, "w", encoding="utf-8") as f:
    for lp, pp in zip(local_paths, phone_paths):
        f.write(lp + "\t" + pp + "\n")
print("wrote", OUT_PAIRS)
print("first phone path:", phone_paths[0])
