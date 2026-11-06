# mcp-closet 개발 메모

2026-11-05 기준. 도구 입출력은 앱 레포 `docs/contracts/mcp-tools.md`, 표는 `docs/infra/db/closet/V1__init.sql`(여기 `src/main/resources/db/migration/V1__init.sql`에 그대로 복사)을 따릅니다.

## 구조

| 파일 | 하는 일 |
| --- | --- |
| `ClosetTools` | MCP 도구 4개(`closet_search`, `closet_save`, `closet_wear`, `closet_outfits`). 실패는 `{"error": {"code", "message"}}` |
| `ClosetService` | 도구의 실제 일. 같은 옷 합치기, 입은 날, 검색 후보 고르기 |
| `GarmentRepository` | `closet.garments`, `closet.wears` (JdbcClient, SQL은 data-model.md 3절) |
| `CandidateFilter` | `occasion`·`apparentTemperature`로 맞지 않는 옷 빼기, 최근 입은 옷 감점 |
| `GarmentNames` | 같은 옷인지 볼 때 이름 비교(니트=스웨터, 자켓=재킷 …) |
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

e5 small은 "같은 색 비슷한 상의"를 아주 가깝게 봐서 유사도만으로는 같은 니트와 같은 색 가디건을 가르지 못합니다. 그래서 유사도는 낮게(0.90) 두고 이름 묶음으로 거릅니다. 한계: 같은 색 니트 두 벌은 한 벌로 합쳐질 수 있습니다. 실제 옷 사진으로 clothes_inspect 결과를 모아 다시 확인할 일입니다.

"공식 Xenova 양자화"는 Hugging Face에서 받은 `onnx/model_quantized.onnx`(118,308,185바이트, sha256 `f80102d3…c193`)와 `tokenizer.json`(sha256 `0b44a9d7…4c39`)으로 Python onnxruntime에서 같은 계산을 한 값입니다(입력 input_ids·attention_mask·token_type_ids, 출력 last_hidden_state). "직접 변환"은 Hugging Face가 막힌 개발 컨테이너에서 공개 Docker 이미지의 e5-small 가중치를 ONNX로 바꿔 Java 쪽(`E5OnnxEmbeddingModel`)에서 잰 값입니다. 둘의 차이는 0.01 안쪽이고, 같은 옷은 공식 파일에서도 모두 0.90 이상(가장 낮은 것 0.914)입니다.

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

## 테스트

- `McpServerTest`: 서버 + pgvector(Testcontainers)를 띄우고 MCP 클라이언트로 `/mcp`에 붙음. 임베딩은 hash. Docker 없으면 건너뜀.
- `E5OnnxEmbeddingModelTest`: `CLOSET_E5_DIR`(model.onnx, tokenizer.json)이 있을 때만.
- 나머지는 순수 단위 테스트. 모두 22개(실제 e5 3개 포함).
