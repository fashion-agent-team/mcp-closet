# 옷 사진 보정 행렬(WCCN) 만들기: src/main/resources/image-adapter/fsiglip-cc0-aug.bin
#
#   git clone --depth 1 https://github.com/alexeygrigorev/clothing-dataset-small   # CC0, 옷 사진 3,781장
#   python make_adapter.py boxes.csv clothing-dataset-small/ vision_model.onnx out.bin 1400 6
#
# boxes.csv: file,item,x,y,w,h (hat 폴더를 뺀 모든 사진, 박스는 0.03,0.03,0.94,0.94 = 사진 전체)
# 옷 한 장을 폰 촬영처럼 흔들어(박스·회전·밝기·색온도·흐림·저해상도·JPEG) 6장 만들고, Marqo FashionSigLIP vision ONNX로
# 벡터(원본+좌우 뒤집기 평균, clothes_inspect와 같은 자르기)를 구한 뒤, 같은 원본끼리의 흩어짐(within-class covariance)을
# 줄이는 W를 만든다. 결과 파일: float32 리틀 엔디언 mu(768) 다음 W(768×768, 행 우선). λ=3.
# 같은 옷 사진을 여러 장 모은 데이터(DeepFashion 등)는 비상업이라 쓰지 않았다. 측정은 docs/DEVELOPMENT.md.
import sys, csv, io, random, numpy as np, onnxruntime as ort
from PIL import Image, ImageOps, ImageEnhance, ImageFilter
boxes, base, onnx, out, nitems, naug = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5]), int(sys.argv[6])
LAM = 3.0
rows=list(csv.DictReader(open(boxes)))
rnd=random.Random(3)
# 원본 하나씩: item당 첫 행
seen=set(); picks=[]
for r in rows:
    k=r.get("item") or r["file"]
    if k in seen: continue
    seen.add(k); picks.append(r)
rnd.shuffle(picks); picks=picks[:nitems]
mean=np.array([0.5]*3); std=np.array([0.5]*3)
def square(im):
    s=max(im.size); bg=Image.new("RGB",(s,s),(124,116,104)); bg.paste(im,((s-im.size[0])//2,(s-im.size[1])//2))
    return bg.resize((224,224),Image.BICUBIC)
def crop(im,x,y,w,h,p=0.03,jit=0.0):
    W,H=im.size
    dx=lambda: rnd.uniform(-jit,jit)
    x0=(x-p+dx()*w)*W; y0=(y-p+dx()*h)*H; x1=(x+w+p+dx()*w)*W; y1=(y+h+p+dx()*h)*H
    return im.crop((max(0,x0),max(0,y0),min(W,x1),min(H,y1)))
def augment(im):
    if rnd.random()<0.7: im=im.rotate(rnd.uniform(-12,12),resample=Image.BICUBIC,expand=False,fillcolor=(124,116,104))
    im=ImageEnhance.Brightness(im).enhance(rnd.uniform(0.6,1.35))
    im=ImageEnhance.Contrast(im).enhance(rnd.uniform(0.7,1.3))
    im=ImageEnhance.Color(im).enhance(rnd.uniform(0.7,1.3))
    a=np.asarray(im,dtype=np.float32); t=rnd.uniform(-0.12,0.12)   # 색온도(따뜻함↔차가움)
    a[...,0]*=1+t; a[...,2]*=1-t; im=Image.fromarray(np.clip(a,0,255).astype(np.uint8))
    if rnd.random()<0.5: im=im.filter(ImageFilter.GaussianBlur(rnd.uniform(0.3,1.5)))
    if rnd.random()<0.5:
        s=rnd.uniform(0.35,0.8); w,h=im.size; im=im.resize((max(8,int(w*s)),max(8,int(h*s))),Image.BILINEAR)
    b=io.BytesIO(); im.save(b,"JPEG",quality=rnd.randint(35,85)); return Image.open(io.BytesIO(b.getvalue())).convert("RGB")
def arr(im): return (((np.asarray(im,dtype=np.float32)/255)-mean)/std).transpose(2,0,1).astype(np.float32)
sess=ort.InferenceSession(onnx); ins={i.name:i for i in sess.get_inputs()}; outs=[o.name for o in sess.get_outputs()]
pix=[n for n in ins if "pixel" in n or "image" in n][0]
def run(x):
    feed={pix:x}
    for n in ins:
        if n==pix: continue
        if "input_ids" in n: feed[n]=np.zeros((x.shape[0],77),dtype=np.int64)
        elif "attention" in n: feed[n]=np.ones((x.shape[0],77),dtype=np.int64)
    res=dict(zip(outs,sess.run(None,feed)))
    for k in ["image_embeds","pooler_output"]:
        if k in res: return res[k].astype(np.float32)
    v=res[outs[0]]; return (v[:,0] if v.ndim==3 else v).astype(np.float32)
def nrm(v): return v/np.linalg.norm(v,axis=-1,keepdims=True)
E=[];G=[]
for gi,r in enumerate(picks):
    im=Image.open(base+r["file"]).convert("RGB"); x,y,w,h=(float(r[k]) for k in "xywh")
    ims=[square(crop(augment(im),x,y,w,h,jit=0.08)) for _ in range(naug)]
    a=run(np.stack([arr(m) for m in ims])); b=run(np.stack([arr(ImageOps.mirror(m)) for m in ims]))
    E.append(nrm(nrm(a)+nrm(b))); G+= [gi]*naug
    if gi%100==0:
        print(gi, flush=True)
X=np.concatenate(E).astype(np.float64); G=np.array(G)
mu=X.mean(0)
Sw=np.zeros((X.shape[1],X.shape[1]))
for g in np.unique(G):
    D=X[G==g]-X[G==g].mean(0); Sw+=D.T@D
Sw/=len(X); d=Sw.shape[0]; Sw+=LAM*np.trace(Sw)/d*np.eye(d)
w,U=np.linalg.eigh(Sw); W=U@np.diag(w**-0.5)@U.T
np.concatenate([mu.astype("<f4"),W.astype("<f4").ravel()]).tofile(out)
print("done", out, len(G))
