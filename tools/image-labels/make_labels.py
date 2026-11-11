# 옷 사진 벡터로 옷 이름·무늬·격식을 고르는 글 벡터 표 만들기: src/main/resources/image-labels/fsiglip-labels.json
#
#   python make_labels.py <text_model.onnx> <tokenizer.json> out.json
#
# Marqo FashionSigLIP 글 모델(onnx/text_model.onnx, Apache-2.0)로 "a photo of <영어 이름>" 문장 벡터를 미리 구해 둔다.
# 서버는 글 모델 없이 이 표와 clothes_inspect가 준 사진 벡터의 유사도로 고른다(zero-shot).
# 토큰화는 open_clip SigLIP과 같게: 구두점 지우고 소문자, 64토큰까지 </s>(1)로 채움.
import sys, json, string, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
onnx, tokjson, out = sys.argv[1:4]
tok = Tokenizer.from_file(tokjson)
sess = ort.InferenceSession(onnx)

def canon(t):
    t = t.replace("_", " ").translate(str.maketrans("", "", string.punctuation)).lower()
    return " ".join(t.split())

def embed(texts):
    ids = []
    for t in texts:
        e = tok.encode(canon(t)).ids[:64]
        ids.append(e + [1] * (64 - len(e)))
    v = sess.run(None, {"input_ids": np.array(ids, dtype=np.int64)})[0]
    return v / np.linalg.norm(v, axis=1, keepdims=True)

# (영어, 한국어 이름, 이 이름을 쓸 수 있는 clothes_inspect category, 격식)
NAMES = [
    ("jeans", "청바지", "bottom", "casual"),
    ("dress trousers, slacks", "슬랙스", "bottom", "smart"),
    ("casual pants", "바지", "bottom", "casual"),
    ("shorts", "반바지", "bottom", "casual"),
    ("skirt", "치마", "bottom dress", "unknown"),
    ("leggings, yoga pants", "레깅스", "bottom", "sporty"),
    ("suit jacket, blazer", "정장 자켓", "outer top", "formal"),
    ("coat", "코트", "outer", "smart"),
    ("casual jacket", "자켓", "outer top", "casual"),
    ("vest", "조끼", "outer top", "unknown"),
    ("dress shirt, button-down shirt", "셔츠", "top", "smart"),
    ("blouse", "블라우스", "top", "smart"),
    ("polo shirt", "폴로 셔츠", "top", "smart"),
    ("t-shirt", "티셔츠", "top", "casual"),
    ("tank top, sleeveless top", "민소매", "top", "casual"),
    ("graphic t-shirt with a print", "프린트 티셔츠", "top", "casual"),
    ("hoodie", "후드티", "top outer", "casual"),
    ("sweatshirt", "맨투맨", "top", "casual"),
    ("sweater, knit", "니트", "top", "smart"),
    ("cardigan", "카디건", "top outer", "smart"),
    ("dress", "원피스", "dress top", "unknown"),
    ("jumpsuit, romper", "점프수트", "dress top bottom", "casual"),
]
# 첫 줄(무지)보다 margin 이상 높을 때만 무늬로 본다
PATTERNS = [
    ("a plain solid color garment", None),
    ("a striped garment", "줄무늬"),
    ("a floral print garment", "꽃무늬"),
    ("a plaid checked garment", "체크무늬"),
    ("a polka dot garment", "물방울무늬"),
]
nv = embed(["a photo of " + n[0] for n in NAMES])
pv = embed([p[0] for p in PATTERNS])
r5 = lambda v: [round(float(x), 6) for x in v]
doc = {
    "model": "marqo-fashionSigLIP",
    "source": "Marqo/marqo-fashionSigLIP@c56244cc94f92419e8369fa71efdaf403b124ce8 onnx/text_model.onnx",
    "patternMargin": 0.02,
    "names": [dict(en=n[0], ko=n[1], categories=n[2].split(), formality=n[3], vector=r5(v)) for n, v in zip(NAMES, nv)],
    "patterns": [dict(en=p[0], ko=p[1], vector=r5(v)) for p, v in zip(PATTERNS, pv)],
}
json.dump(doc, open(out, "w"), ensure_ascii=False)
print("ok", len(NAMES), len(PATTERNS))
