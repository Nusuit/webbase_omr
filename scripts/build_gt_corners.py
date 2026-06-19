import os, glob, json, cv2
import xml.etree.ElementTree as ET
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DSROOT=os.path.join(ROOT,"Dataset_OMR_classified")
EXPORT_GLOB=os.path.join(ROOT,"yolo_label_with_4_corners_*")
CORNER="marker_corners"
def img_index():
    idx={}
    for root in (DSROOT, os.path.join(ROOT,"full_label_yolo","images")):
        for p in glob.glob(os.path.join(root,"**","*.jpg"),recursive=True):
            if "Trash" in p or "_legacy" in p: continue
            idx.setdefault(os.path.basename(p),p)
    return idx
def yaml_names(p):
    names={};inn=False
    for line in open(p,encoding="utf-8"):
        s=line.rstrip("\n")
        if s.strip().startswith("names:"): inn=True; continue
        if inn:
            t=s.strip()
            if not t or not s.startswith((" ","\t")): break
            if ":" in t:
                k,v=t.split(":",1)
                try: names[int(k.strip())]=v.strip()
                except: pass
    return names
idx=img_index()
out={}
for f in sorted(glob.glob(EXPORT_GLOB)):
    if not os.path.isdir(f): continue
    if os.path.exists(os.path.join(f,"annotations.xml")):
        for im in ET.parse(os.path.join(f,"annotations.xml")).getroot().iter("image"):
            stem=os.path.splitext(im.get("name"))[0]
            pts=[]
            for b in im.findall("box"):
                if b.get("label")==CORNER:
                    cx=(float(b.get("xtl"))+float(b.get("xbr")))/2
                    cy=(float(b.get("ytl"))+float(b.get("ybr")))/2
                    pts+=[round(cx,2),round(cy,2)]
            if len(pts)==8: out[stem+".jpg"]=pts
    elif os.path.isdir(os.path.join(f,"labels")):
        names=yaml_names(os.path.join(f,"data.yaml"))
        cids={str(i) for i,n in names.items() if n==CORNER}
        for txt in glob.glob(os.path.join(f,"labels","**","*.txt"),recursive=True):
            stem=os.path.splitext(os.path.basename(txt))[0]
            ip=idx.get(stem+".jpg")
            if not ip: continue
            im=cv2.imread(ip); H,W=im.shape[:2]
            pts=[]
            for ln in open(txt):
                c=ln.split()
                if c and c[0] in cids:
                    pts+=[round(float(c[1])*W,2),round(float(c[2])*H,2)]
            if len(pts)==8: out[stem+".jpg"]=pts
json.dump(out,open(os.path.join(ROOT,"web","corners_gt.json"),"w"))
print("GT corners for",len(out),"sheets -> web/corners_gt.json")
