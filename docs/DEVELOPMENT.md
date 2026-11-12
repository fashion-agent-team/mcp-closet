# mcp-closet 개발 메모

2026-11-05 기준. 도구 입출력은 앱 레포 `docs/contracts/mcp-tools.md`, 표는 `docs/infra/db/closet/V1__init.sql`(여기 `src/main/resources/db/migration/V1__init.sql`에 그대로 복사)을 따릅니다.

## 구조

| 파일 | 하는 일 |
| --- | --- |
| `ClosetTools` | MCP 도구 6개(`closet_search`, `closet_save`, `closet_wear`, `closet_outfits`, `closet_list`, `closet_remove`). 실패는 `{"error": {"code", "message"}}` |
| `ClosetService` | 도구의 실제 일. 같은 옷 합치기, 입은 날, 검색 후보 고르기 |
| `GarmentRepository` | `closet.garments`, `closet.wears` (JdbcClient, SQL은 data-model.md 3절) |
| `CandidateFilter` | `occasion`·`apparentTemperature`로 맞지 않는 옷 빼기, 최근 입은 옷 감점 |
| `GarmentNames` | 같은 옷인지 볼 때 이름 비교(니트=스웨터, 자켓=재킷 …) |
| `SameGarment` | (사진 벡터가 없을 때) 같은 옷 후보라도 톤(light↔dark)·무늬가 다르면 다른 옷 |
| `ImageMatcher` | 옷 사진 벡터 두 개가 같은 옷인지(코사인, 보정 행렬이 있으면 바꾼 뒤), 평균 갱신 |
| `ImageLabels` | 옷 사진 벡터로 이름·무늬·격식 고르기(zero-shot, 미리 계산한 글 벡터 표) |
| `style/StyleKnowledge` | 코디 지식 문서를 뜰 때 `closet.style_docs`에 맞춰 넣고, 검색 때 조각을 찾음 |
| `embedding/E5OnnxEmbeddingModel` | multilingual-e5-small을 ONNX Runtime으로 돌림(평균 풀링 + 길이 1) |
| `embedding/HashEmbeddingModel` | 모델 없이 띄울 때(테스트·개발). 뜻을 모름 |

RAG 나누기: 이 서버는 **검색**만 합니다. 찾은 옷 후보와 코디 지식 조각을 돌려주면 agent가 GPT에 넘겨 그 안에서만 골라 말하게 합니다(생성). 그래서 이 서버에는 OpenAI 키가 없습니다.

## 임베딩

- 모델: `intfloat/multilingual-e5-small`(384차원). 기본은 Hugging Face `Xenova/multilingual-e5-small`의 양자화 ONNX(약 118MB). Dockerfile이 이미지에 넣어서 뜰 때 내려받지 않습니다.
- e5는 저장할 글에 `passage: `, 찾는 글에 `query: `를 붙입니다(모델 카드). 옷은 `passage: 상의 니트. 색: 차콜 그레이 (어두운 톤). 격식: 단정한 옷, 출근·모임. 소재: 니트 같음.`처럼 한국어 문장으로 만듭니다.
- Spring AI `spring-ai-transformers`는 쓰지 않았습니다. 평균 풀링을 DJL PyTorch 엔진으로 해서, 처음 뜰 때 libtorch(수백 MB)를 내려받고 메모리도 더 씁니다. 대신 토크나이저(DJL HuggingFace tokenizers)와 ONNX Runtime만 쓰고 풀링은 직접 계산합니다.
- 코디 지식은 Spring AI `PgVectorStore`로 넣고(표 모양은 약속대로), 찾을 때는 이미 만든 검색어 벡터로 같은 표를 직접 찾습니다(`similaritySearch`는 검색어를 한 번 더 임베딩해서).
- 모델을 바꾸면 코디 지식은 뜰 때 다시 넣지만(`metadata.embeddingModel`), 옷 벡터(`garments.embedding`)는 그대로입니다. 모델을 바꾸면 옷 벡터도 다시 만들어야 합니다.

## 같은 옷 합치기

### 사진 벡터로 (2026-11-11부터 기본)

clothes_inspect(mcp-clothes-state PR #16)가 옷마다 `embedding`(Marqo FashionSigLIP, 768개, 길이 1)과 응답의 `embeddingModel`을 줍니다. agent가 둘을 closet_save에 그대로 넘기면:

1. 같은 사람 + 같은 `category` + 같은 `image_model`인 옷의 `image_embedding`을 가져와 가장 가까운 옷을 찾습니다(`ImageMatcher`).
2. 그 유사도가 기준 이상이면 합칩니다. 기준은 보정 행렬에 따라 다릅니다: 기본(CC0 보정) 0.60(일상 사진으로 다시 맞춤, 아래), 보정 없음 0.89, DeepFashion 보정 0.43. 색 이름·색 계열·이름·톤은 보지 않습니다.
3. 합치면 `image_embedding`을 지금까지 본 사진의 평균으로 바꿉니다(`image_count`, 10장부터는 새 사진 비중 1/10). 새 옷이면 그 벡터 그대로.
4. 사진 벡터가 있는 입력은 사진 벡터가 없는 옛 옷과 비교하지 않습니다(옛 규칙이 틀리는 경우가 많아서). 한 번에 온 옷끼리 안 합치기와 `forceNewIds`는 그대로입니다.

왜 바꿨나(DeepFashion In-shop 표본, 비상업 연구용이라 레포에 넣지 않음. 결과 문서는 팀 공유):

| 옷 30벌 옷장 흉내, 새 옷 잘못 합침 5%에 맞춘 기준 | 같은 옷 맞게 합침 | 엉뚱한 옷에 합침 |
| --- | --- | --- |
| 글 설명 규칙(예전) | 36% | 39% (새 옷 잘못 합침 52%) |
| FashionSigLIP, 처음 본 사진 하나 | 58% | 3.0% |
| FashionSigLIP, 본 사진 평균 (지금 방식) | 73% | 2.2% |
| + CC0 보정 행렬 (기본) | 78% | 1.4% |
| + DeepFashion 보정 행렬 (학교 시연용, 레포 밖) | 85% | 0.4% |

두 서버(clothes_inspect → closet_save)를 실제로 띄우고 MCP로 이어 돌린 결과(옷장 30벌 × 5명, 옷장에는 사진 한 장씩, 다시 찍은 같은 옷 75번, 새 옷 75번):

| | 같은 옷 맞게 합침 | 엉뚱한 옷에 합침 | 새 옷을 잘못 합침 | 저장 시간(중앙값) |
| --- | --- | --- | --- | --- |
| 보정 없음 | 46/75 (61%) | 1 | 2 | 16ms |
| CC0 보정 (기본) | 53/75 (71%) | 0 | 3 | 20ms |
| DeepFashion 보정 | 60/75 (80%) | 0 | 3 | 20ms |

- 이미지 모델 12개를 비교해서 FashionSigLIP이 가장 좋았습니다(SigLIP, SigLIP2, FashionCLIP, Jina CLIP 순, DINOv2·CLIP B 계열은 26~45%).
- 위 숫자는 clothes_inspect 서버가 실제로 돌려준 벡터(옷 600벌, 학습에 안 쓴 옷)로 다시 잰 값입니다. 같은 옷 두 장을 옷장에 넣고 세 번째 사진으로 찾습니다.
- 기준값: 위 5%에 맞춘 값이 보정 없음 0.886, CC0 보정 0.675, DeepFashion 보정 0.424라서 0.89 / 0.68 / 0.43으로 둡니다(새 옷 잘못 합침 4.1% / 4.3% / 약 5%).
- 톤(밝음↔어두움) 충돌 규칙을 더하면 맞게 합침이 2~4%p 줄기만 해서 사진 쪽에는 쓰지 않습니다.
- 스튜디오 사진(224px)이라 실제 폰 사진에서는 낮을 수 있습니다. 팀원 옷 20벌쯤으로 다시 재야 합니다.

**아직 남는 문제: 중복 저장.** 옷 30벌을 앞모습으로 세 번씩 섞인 순서로 저장하면(CC0 보정, 기준 0.68) 옷장에 평균 42개가 생깁니다(같은 옷이 따로 저장된 것 17개, 다른 옷이 한 개로 섞인 것 4개). 저장할 때마다 합쳐진 옷을 다른 옷과 다시 비교해 합치는 방법도 재 봤는데, 중복은 17 → 14로 줄지만 섞임이 4 → 5로 늘어서 넣지 않았습니다(섞이면 옷 설명이 틀려지므로 중복보다 나쁨). 실제 폰 사진으로 기준값과 보정 행렬을 다시 맞추는 것이 다음 단계입니다.

**일상 사진으로 기준값 다시 맞추기 (2026-11-12).** 위 숫자는 스튜디오 사진이라, 일반인이 찍은 사진(DeepFashion2 validation의 소비자 사진, 같은 옷 1,500벌 · 4,843장, 비상업 데이터라 레포에 넣지 않음)으로 다시 쟀습니다. 일상 사진 한 장으로 옷장에 넣고, 같은 옷의 다른 일상 사진으로 다시 저장합니다(옷장 30벌 × 4,000번, CC0 보정).

| 기준 | 같은 옷 맞게 합침 | 엉뚱한 옷에 합침 | 새 옷 잘못 합침 |
| --- | --- | --- | --- |
| 0.55 | 72% | 1.9% | 5.0% |
| 0.58 | 67% | 1.4% | 3.1% |
| **0.60 (기본)** | **63%** | **1.0%** | **2.3%** |
| 0.62 | 59% | 0.7% | 1.6% |
| 0.65 | 51% | 0.5% | 1.1% |
| 0.68 (예전) | 45% | 0.3% | 0.6% |

일상 사진은 조명·각도·가림이 달라 같은 옷끼리의 유사도가 스튜디오보다 낮습니다. 0.68에서는 같은 옷의 절반 이상을 새 옷으로 따로 저장했습니다. 섞임(엉뚱한 옷에 합침)은 옷 설명을 틀리게 하므로 중복보다 나빠서, 5% 지점(0.55) 대신 잘못 합침을 2%대로 묶는 0.60으로 둡니다. 같은 사람이 연달아 찍은 거의 같은 장면(유사도 0.95 이상)을 빼도 0.60에서 61% / 0.9% / 2.5%로 비슷합니다. 보정 없이 같은 5% 지점(0.846)에서는 65%라서 CC0 보정은 일상 사진에서도 도움이 됩니다(72%).

**보정 행렬(WCCN)**: 같은 옷 사진끼리의 차이(조명·각도·흐림) 방향을 줄이는 768×768 행렬입니다. 계산은 `(v − mu)·W`를 길이 1로. 파일은 float32 리틀 엔디언 `mu`(768개) 다음 `W`(768×768, 행 우선)이고, 크기가 틀리면 서버가 뜨지 않습니다(기준이 조용히 어긋나지 않게).

- **기본: `src/main/resources/image-adapter/fsiglip-cc0-aug.bin`** (2.4MB, sha256 `bf6a9fa1…60dc`). [clothing-dataset-small](https://github.com/alexeygrigorev/clothing-dataset-small)(CC0, 아무 용도로나 사용 가능)의 옷 사진 1,400장을 한 장마다 폰으로 찍은 것처럼 6번 흔들어(박스·회전·밝기·색온도·흐림·저해상도·JPEG) "같은 옷"으로 묶고 만들었습니다(λ=3). 만드는 법: `tools/image-adapter/make_adapter.py`.
  - 같은 방법을 DeepFashion 사진으로 하면 84%(진짜 여러 장으로 만든 것 85%와 거의 같음)인데, CC0 사진은 바닥에 놓고 찍은 사진이라 입은 사진과 달라 78%에 그칩니다. 팀원이 입고 찍은 사진(CC0로 모아서)으로 다시 만들면 더 오를 수 있습니다.
- **DeepFashion 보정**: 같은 옷 사진 여러 장(옷 1,400벌)으로 만들어 가장 좋지만 **비상업 연구용 데이터에서 나온 것**이라 레포와 Docker 이미지에 넣지 않습니다. 학교 시연 때만 `CLOSET_IMAGE_ADAPTER=/경로/파일`, `CLOSET_IMAGE_ADAPTER_THRESHOLD=0.43`.
- 보정을 끄려면 `CLOSET_IMAGE_ADAPTER=`(빈 값). 기준은 `CLOSET_IMAGE_THRESHOLD`(0.89).
- 보정 행렬을 바꾸면 이미 저장된 벡터는 그대로 두고 비교할 때만 바꾸므로 다시 저장할 필요는 없습니다. 기준값은 함께 바꿔야 합니다.

### 사진 벡터가 없을 때 (예전 규칙)

조건: 같은 사람 + 같은 `category` + 같은 `colorFamily` + 유사도 0.90 이상 + 이름이 같은 묶음(`GarmentNames`). 합치면 `description`, `embedding`, 이름·색 이름·톤을 마지막 것으로 바꾸고 `last_seen_at`을 올립니다.

이름 조건을 넣은 이유(실제 e5로 잼, `E5OnnxEmbeddingModelTest`):

| 기준 옷: 니트, 차콜 그레이 | 공식 Xenova 양자화 | 직접 변환 fp32 / 양자화 | 같은 옷? |
| --- | --- | --- | --- |
| 스웨터, 진회색, 소재 니트 | 0.937 | 0.937 / 0.937 | 같음 |
| 니트, 차콜, 소재 모름 | 0.975 | 0.979 / 0.978 | 같음 |
| 니트 스웨터, 짙은 회색, 울 같음 | 0.914 | 0.920 / 0.921 | 같음 |
| 가디건, 차콜 그레이, 니트 같음 | 0.968 | 0.974 / 0.969 | 다름 |
| 후드티, 차콜 그레이 | 0.933 | 0.936 / 0.932 | 다름 |
| 맨투맨, 회색 | 0.891 | 0.902 / 0.900 | 다름 |
| 검색: "면접, 체감 9도" ↔ 면접 문서 / 등산 문서 | 0.839 / 0.806 | 0.841 / 0.808 (fp32) | |

e5 small은 "같은 색 비슷한 상의"를 아주 가깝게 봐서 유사도만으로는 같은 니트와 같은 색 가디건을 가르지 못합니다. 그래서 유사도는 낮게(0.90) 두고 이름 묶음으로 거릅니다.

같은 색 니트가 두 벌인 경우(`SameGarment`, 2026-11-06):

- 톤이 light↔dark로 다르거나, 둘 다 무늬가 적혀 있는데 다르면(무지↔스트라이프, 스트라이프↔체크) 다른 옷. mid와 모르는 무늬(null)는 다르다고 보지 않습니다(조명·사진마다 빠질 수 있어서). 줄무늬=스트라이프처럼 같은 무늬 이름은 맞춥니다.
- 합칠 때 이번 사진에 무늬가 없으면 전에 본 무늬를 남깁니다. 안 그러면 다음에 스트라이프 니트가 와도 가르지 못합니다.
- 한 번의 closet_save에 온 옷끼리는 합치지 않고, 두 벌을 같은 옛 옷 하나에 몰지 않습니다(한 사진에 같이 있으면 다른 옷).
- `forceNewIds`: 사용자가 "그건 다른 옷"이라고 하면 agent가 그 입력 id를 넣어 다시 부릅니다.

공식 e5로 잰 결과(기준: 차콜 그레이 무지 니트):

| 들어온 옷 | 전 | 지금 | 기준과 유사도 |
| --- | --- | --- | --- |
| 같은 니트를 "스웨터, 진회색"으로 다시 봄(무늬 안 적힘) | 합침 | 합침 | - |
| 차콜 스트라이프 니트 | 합침 | 새 옷 | 0.923 |
| 연회색 니트(light) | 합침 | 새 옷 | 0.956 |
| 한 사진에 똑같은 차콜 무지 니트 두 벌 | 둘 다 합침 | 하나는 합침, 하나는 새 옷 | 1.000 |
| 사용자가 다른 옷이라고 함(forceNewIds) | 합침 | 새 옷 | 0.988 |

남는 한계: 무늬·톤까지 똑같은 니트 두 벌을 다른 날 따로 찍으면 여전히 한 벌로 합칩니다. 글 설명만으로는 가를 수 없어서 사용자에게 묻는 것(forceNewIds)으로 풉니다. 사진 벡터(CLIP 등)를 쓰면 더 가를 수 있지만 보류 중입니다.

"공식 Xenova 양자화"는 Hugging Face에서 받은 `onnx/model_quantized.onnx`(118,308,185바이트, sha256 `f80102d3…c193`)와 `tokenizer.json`(sha256 `0b44a9d7…4c39`)으로 Python onnxruntime에서 같은 계산을 한 값입니다(입력 input_ids·attention_mask·token_type_ids, 출력 last_hidden_state). "직접 변환"은 Hugging Face가 막힌 개발 컨테이너에서 공개 Docker 이미지의 e5-small 가중치를 ONNX로 바꿔 Java 쪽(`E5OnnxEmbeddingModel`)에서 잰 값입니다. 둘의 차이는 0.01 안쪽이고, 같은 옷은 공식 파일에서도 모두 0.90 이상(가장 낮은 것 0.914)입니다.

## 사진으로 이름·무늬·격식 고르기 (2026-11-11)

clothes_inspect의 이름은 "티셔츠·바지·니트"처럼 굵고, 격식은 42%가 unknown, 무늬는 늘 비어 있습니다. 그래서 "청바지", "줄무늬 옷"을 찾아도 e5 글 검색이 잘 못 찾았습니다.

`closet_save`가 사진 벡터를 받으면(`embeddingModel`이 `marqo-fashionSigLIP`일 때) FashionSigLIP 글 쪽으로 미리 뽑아 둔 이름 22개·무늬 5개의 벡터(`image-labels/fsiglip-labels.json`, `tools/image-labels/make_labels.py`로 만듦)와 비교해 가장 가까운 것을 고릅니다. 서버에서 글 모델을 돌리지 않습니다.

- 이름: 그 종류(top·bottom…)에 맞는 이름 중 가장 가까운 것으로 바꿉니다(청바지·슬랙스·블라우스·후드티·카디건·원피스…).
- 무늬: 민무늬보다 0.02 넘게 가까운 무늬가 있을 때만 채우고, clothes_inspect가 준 무늬가 있으면 그대로 둡니다.
- 격식: clothes_inspect가 unknown일 때만 이름의 격식으로 채웁니다.
- 고른 값은 `description.imageGuess`에도 따로 남기고, clothes_inspect가 준 원래 값은 그대로 둡니다.

실제 두 서버로 잰 값(DeepFashion 시험 옷 200벌로 60벌 옷장 4개, 위 5개 중 맞은 비율):

| 질문 | 전 | 후 |
| --- | --- | --- |
| 옷 이름·무늬 15개(청바지, 후드티나 맨투맨, 줄무늬 옷 …) | 20% | 44% |
| 상황 4개(운동, 면접, 추운 날, 더운 날) | 36% | 28% |

상황 질문은 4개×4명이라 흔들림이 큽니다. 줄어든 것은 주로 면접(30→10%)인데, 여자 블라우스 상당수가 "민소매"로 바뀌며 격식 casual로 남아서입니다. clothes_inspect 이름을 글에 함께 넣어 봤지만 45%·27%로 거의 같아 넣지 않았습니다.

## 영어 검색어로 사진 벡터 찾기 (closet_search `imageQuery`, 2026-11-11)

agent가 `imageQuery`에 찾는 옷을 짧은 영어로 주면(예: `warm winter clothes`) FashionSigLIP 글 모델로 벡터를 만들어 옷 사진 벡터(같은 모델)와 코사인을 잽니다. 점수 = e5 점수 + 1.5 × 사진↔글 유사도. 사진 벡터가 없는 옷은 있는 옷들의 평균을 더해서 손해 보지 않게 합니다. 글 모델이 꺼져 있거나 실패하면 `imageQuery`를 무시하고 예전처럼 찾습니다.

- 글 모델: Hugging Face `Marqo/marqo-fashionSigLIP`의 `onnx/text_model.onnx`(441MB)를 int8로 줄인 것(~111MB, `tools/image-labels/quantize_text.py`). jar에는 넣지 않고 Docker 이미지에 넣습니다(빌드할 때 받아 sha256 검사 후 줄임). 이미지에서는 `CLOSET_IMAGE_TEXT_MODEL_URI`·`CLOSET_IMAGE_TEXT_TOKENIZER_URI`가 이미지 안 파일을 가리켜 켜져 있고, 빈 값으로 주면 꺼집니다. `./gradlew bootRun`은 기본 꺼짐.
- 전처리: open_clip SigLIP 방식(문장부호 빼기·소문자 → T5 sentencepiece, 64토큰, 빈칸 1). 앞에 `a photo of `를 붙입니다. Java 토큰 id·벡터가 Python과 같은지 `ImageTextModelTest`(FSIGLIP_TEXT_DIR)로 확인.
- 가중치 1.5: Python 벤치마크에서 0.5·1·1.5·2·3 중 전체 정답률이 가장 높았습니다(1.5~2가 비슷).
- 속도: closet_search 18ms → 64ms(글 모델 한 번), 서버 메모리 약 1.1GB.

실제 두 서버로 잰 값(위와 같은 옷장·질문, 위 5개 중 맞은 비율):

| 질문 | 예전 | 사진으로 이름 고르기 | + imageQuery |
| --- | --- | --- | --- |
| 옷 이름·무늬 15개 | 20% | 44% | 57% |
| 상황 4개 | 36% | 28% | 37% |

종류별로 돌아가며 고르기(diversify) 때문에 "원피스"를 찾아도 상의·하의가 섞여 나와서 이 표의 위 5개 정답률은 그만큼 낮게 나옵니다.

## closet_search

1. `query: <situation>`을 임베딩합니다.
2. 코디 지식: `occasion`이 있으면 그 자리 문서와 `any` 문서에서 가까운 조각 3개, `apparentTemperature`가 있으면 그 온도 구간의 날씨 조각 1개, 상황 문장에 비 이야기(비·우산·장마…)가 있으면 비 조각 1개를 앞에 둡니다. 비 문서는 처음에 `any`였는데 어느 검색에나 끼어서 따로 뺐습니다.
3. 옷: 내 옷 전부를 정확한 거리로 정렬(한 사람 100벌 안팎, 근사 인덱스 없음, research R3) → `excludeGarmentIds` 빼기 → `CandidateFilter`로 빼기 → 자리에 맞는 격식이면 점수에 최대 0.06 더해 다시 정렬 → 종류별로 돌아가며 `limit`개(기본 8). 상의만 8벌 나오지 않게 하려는 것입니다. 격식 가산점은 e5 점수가 옷끼리 0.77~0.84로 몰려서, 결혼식 검색에 반팔 티셔츠·스니커즈가 구두·슬랙스보다 먼저 나왔던 것을 고치려고 넣었습니다. 실행 결과는 프로젝트 파일 `mcp-closet/demo-result.md`.
4. 옷장이 비면 `candidates: []`, `empty: true`.

`CandidateFilter`는 앱의 `schedule_rules.dart`, `weather_rules.dart`가 "맞지 않아요"라고 하는 것만 뺍니다.

| 조건 | 빼는 옷 |
| --- | --- |
| mourning | 격식 casual·sporty, 밝은 색(빨강·분홍·주황·노랑·초록·보라) |
| wedding | 격식 sporty, 흰 원피스·흰 겉옷, 청바지 |
| formal | 격식 casual·sporty |
| business | 격식 sporty |
| active | 격식 formal |
| 체감 23도 이상 | 겉옷, 니트·울·패딩·코트 |
| 체감 15도 미만 | 반팔·반바지·민소매·린넨 |

격식 `unknown`처럼 애매한 옷은 남기고 GPT가 고르게 합니다.

최근 입은 옷: 어제 0.06, 그제 0.04, 3~7일 전 0.02를 점수에서 뺍니다. 빼지 않고 뒤로만 보내는 것은 옷이 적은 사람도 추천을 받게 하려는 것이고, 오늘 입은 옷은 같은 날 다시 고르는 중일 수 있어 그대로 둡니다. 출력 `score`는 가산점·감점을 더한 값입니다.

## closet_outfits

새 표 없이 `wears`만 씁니다. 같은 날 `closet_wear`로 기록한 옷 = 그날의 조합. 최근 날부터 `limit`개(기본 5, 최대 30), 기간 기본 90일. `garmentId`를 주면 그 옷을 입은 날만. 한계: 하루에 두 번 갈아입으면 한 벌로 묶이고, 그날의 자리(면접 등)는 저장하지 않으므로 agent가 `schedule_list`로 날짜를 찾아 넘깁니다.

## closet_list, closet_remove

시각장애인 사용자는 화면으로 옷장을 볼 수 없어서 "내 옷장에 뭐 있어?"를 말로 들어야 하고, 버린 옷이나 잘못 합쳐진 옷을 정리할 방법이 필요해서 넣었습니다. 지우기는 소프트 삭제가 아니라 실제로 지웁니다(표를 바꾸지 않으려고). 그 옷의 입은 날도 FK `ON DELETE CASCADE`로 함께 지워져 예전 조합에서 빠집니다. 되돌릴 수 없으므로 agent가 옷을 읽어 주고 확인받은 뒤 부르게 도구 설명에 적었습니다.

## closet_wear

`garmentIds`가 모두 이 사람 옷이어야 기록합니다. 하나라도 없거나 남의 옷이면 `not_found`이고 아무것도 기록하지 않습니다(agent는 closet_save가 준 id만 넘기므로 섞이면 버그). 같은 옷 같은 날은 한 번만(`ON CONFLICT DO NOTHING`). `date`가 없으면 서울 기준 오늘, 내일보다 뒤면 `bad_request`.

## 잰 숫자 (2026-11-05, 개발 컨테이너, 옷 10벌)

| 항목 | fp32 모델(470MB) | 양자화 모델(118MB, 기본) |
| --- | --- | --- |
| 뜨는 시간 | 7.7초 | 6.0초 |
| 모델 준비 | 2.3초 | 1.3초 |
| 메모리(RSS, 검색 후) | 약 1.5GB | 약 870MB |
| closet_save 10벌 | 227ms | - |
| closet_search | 44~74ms | 43~126ms |
| bootJar 크기 | 199MB (ONNX Runtime·토크나이저 네이티브 라이브러리 포함) | |

## Docker 이미지 (2026-11-06 빌드해 봄)

- 모델 두 파일은 `ADD --checksum=sha256:…`로 넣습니다. 받은 파일이 Hugging Face 공식 파일과 다르면 빌드가 멈춥니다. Hugging Face가 막힌 곳에서는 `MODEL_BASE_URL`로 다른 주소를 줍니다(개발 컨테이너에서는 받아 둔 공식 파일을 로컬 HTTP로 띄워 빌드함, sha256 같음).
- 처음 빌드해 보니 `ADD --chmod=644`가 만든 `/app/models` 폴더까지 644(들어갈 수 없음)가 되어, `USER 1000`인 서버가 모델을 못 보고 Hugging Face에서 다시 받으려다 죽었습니다. 폴더를 `mkdir -m 755`로 먼저 만들어 고쳤습니다.
- 잰 값(개발 컨테이너, 메모리 제한 1.5GB): 이미지 1.07GB(압축 391MB, JRE·bootJar 199MB·모델 135MB), 뜨는 시간 약 10초(모델 준비 4.1초), 옷 9벌 저장·검색 뒤 메모리 약 770MB. 뜰 때 모델을 내려받지 않음(로그 확인), health UP, closet_save·closet_search 정상.

## 테스트

- `McpServerTest`: 서버 + pgvector(Testcontainers)를 띄우고 MCP 클라이언트로 `/mcp`에 붙음. 임베딩은 hash. Docker 없으면 건너뜀.
- `E5OnnxEmbeddingModelTest`: `CLOSET_E5_DIR`(model.onnx, tokenizer.json)이 있을 때만.
- 나머지는 순수 단위 테스트. 모두 26개(실제 e5 3개 포함).

## 애매할 때 GPT로 한 번 더 보기 (2026-11-12)

모델이 확실할 때는 모델만으로 정하고, 애매할 때만 agent가 GPT에게 사진 두 장을 보여 줍니다(박창근 결정: "정확도가 떨어지거나 인식이 안 되면 GPT로 인식률을 높인다", 옷 부분 작은 사진은 우리 DB에만 저장).

- **확실함:** 사진 유사도 ≥ 기준(CC0 보정 0.60) → 바로 합침. < 기준 − 0.10(0.50) → 새 옷.
- **애매함:** 그 사이면 새 옷으로 저장하고 `saved[].maybeSameAs`에 가장 가까운 옷(garmentId·이름·색·무늬·소재·작은 사진·유사도)을 줍니다.
- **인식 실패:** 사진 벡터가 없고 글 규칙으로도 합쳐지지 않았는데 작은 사진이 왔으면, 글로 가장 가까운 같은 종류·색 계열 옷을 `maybeSameAs`로 줍니다.
- agent는 이번 옷 사진과 `maybeSameAs.thumbnail`을 GPT에게 보여 같은 옷이면 `closet_merge(garmentId=새 옷, intoGarmentId=maybeSameAs.garmentId)`를 부릅니다. 옷장 서버는 GPT를 부르지 않습니다(API 키 없음).
- `closet_merge`: 입은 날을 옮기고(같은 날은 하나), 사진 벡터는 사진 수로 가중 평균, 설명·작은 사진은 더 최근 것, 처음 본 날은 더 이른 것을 남기고 garmentId를 지웁니다. 종류가 다르면 거절합니다.
- 작은 사진: `garments.thumbnail`(bytea, V3), JPEG만, 48KB 넘으면 저장하지 않음(오류 아님). description(jsonb)에는 넣지 않습니다.
