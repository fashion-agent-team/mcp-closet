# mcp-closet

**closet MCP 서버 · 내 옷장 (RAG)** · 패션 에이전트(시각장애인용 음성 AI 옷 추천)의 한 부분입니다.

담당: (미정)

## 한눈에

- 포트: 8095, 엔드포인트: `http://mcp-closet:8095/mcp` (Streamable HTTP)
- 기술: Spring Boot, Spring AI MCP Server, Spring AI VectorStore + pgvector, multilingual-e5-small 임베딩(ONNX, 384차원), PostgreSQL
- 상태 확인: `GET /actuator/health`

## MCP 도구

| 도구 | 하는 일 |
| --- | --- |
| `closet_search` | RAG의 검색 단계. 상황(일정·날씨 문장)에 맞는 내 옷 후보 + 코디 지식 문서. 생성(추천 문장)은 agent의 GPT가 함 |
| `closet_save` | 옷 추천을 마칠 때 본 옷을 기록. 같은 옷이면 합침(임베딩 유사도 + 종류·색·이름, 톤·무늬가 다르거나 한 번에 온 옷끼리는 안 합침, `forceNewIds`) |
| `closet_wear` | 입은 날 기록 |
| `closet_outfits` | 예전에 입은 조합(같은 날 입은 옷을 한 벌로). "지난번 면접 때 뭐 입었지?" |
| `closet_list` | 옷장 목록(종류 순, 입은 횟수). "내 옷장에 뭐 있어?" |
| `closet_remove` | 옷 지우기(버린 옷, 잘못 저장된 옷). 입은 날 기록도 함께 지움 |

입출력 JSON은 [MCP 도구 입출력](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/mcp-tools.md)를 그대로 따릅니다.

## 이 레포가 지킬 것

- 옷 설명은 `clothes_inspect`가 준 Garment JSON을 그대로 JSONB로 저장합니다. **사진은 저장하지 않습니다.**
- 코디 지식 문서(`style_docs`: 드레스코드, 색 조합, 날씨별 옷차림)는 우리가 직접 씁니다.
- 옷장이 비면 `empty: true`를 돌려주고 agent가 "옷 확인을 몇 번 하면 옷장이 채워져요"라고 안내합니다.
- DB 스키마 `closet`만 씁니다(`garments`, `wears`, `style_docs`).

## 할 일

- [x] 뼈대 + `/mcp` + Dockerfile, pgvector 확장
- [x] `closet_save`(중복 합치기 포함), `closet_wear`
- [x] 임베딩 모델(ONNX) 붙이기, `closet_search`
- [x] 코디 지식 문서 10~20개 쓰기 (17개, `src/main/resources/style_docs/`)
- [x] PR마다 자동 테스트 (GitHub Actions `CI`)
- [x] Docker 이미지 자동 빌드·확인·올리기 (`ghcr.io/fashion-agent-team/mcp-closet`)
- [ ] agent에 붙여 옷 추천 1단계에서 불러 보기
- [ ] 실제 옷 사진으로 같은 옷 합치기 기준값(0.90) 다시 확인

## 이미지

- `develop`에 합쳐지면 `ghcr.io/fashion-agent-team/mcp-closet:dev`, `main`에 합쳐지면 `:latest`로 자동으로 올라갑니다(GitHub Actions `Docker`). 올리기 전에 pgvector와 함께 띄워 `/actuator/health`가 UP인지, 이미지 안의 모델을 쓰는지 확인합니다. PR에서는 빌드와 확인만 합니다.
- 받기: `docker pull ghcr.io/fashion-agent-team/mcp-closet:dev` (임베딩 모델 포함, 약 1.07GB, 메모리 1GB 이상 권장)
- 필요한 환경 변수: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`

## 실행

```bash
# PostgreSQL + pgvector가 떠 있어야 합니다(앱 레포 docs/infra/docker-compose.yml의 postgres).
SPRING_DATASOURCE_PASSWORD=… ./gradlew bootRun          # 처음 뜰 때 e5 모델(약 118MB)을 models/에 내려받음
CLOSET_EMBEDDING_PROVIDER=hash ./gradlew bootRun        # 모델 없이 띄우기(개발용, 검색 품질 없음)
./gradlew test                                          # Docker 필요(Testcontainers pgvector). 없으면 DB 테스트는 건너뜀
CLOSET_E5_DIR=/path/to/e5 ./gradlew test                # model.onnx·tokenizer.json이 있으면 실제 e5 테스트도 돎
docker build -t ghcr.io/fashion-agent-team/mcp-closet:dev .   # 모델을 이미지에 넣음(Hugging Face에서 받고 sha256 검사)
docker build --build-arg MODEL_BASE_URL=http://내부주소/e5 -t … .  # Hugging Face가 막힌 곳: 같은 파일을 둔 주소(onnx/model_quantized.onnx, tokenizer.json)
```

| 환경 변수 | 기본값 | 뜻 |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | `jdbc:postgresql://localhost:5432/fashion` / `fashion` / (없음) | 공용 DB |
| `CLOSET_EMBEDDING_PROVIDER` | `onnx` | `onnx`(multilingual-e5-small) 또는 `hash`(개발용) |
| `CLOSET_EMBEDDING_MODEL_URI` / `CLOSET_EMBEDDING_TOKENIZER_URI` | Hugging Face `Xenova/multilingual-e5-small` 양자화 ONNX | `https:`, `file:`, `classpath:` |
| `CLOSET_MODEL_DIR` | `models` | 내려받은 모델 둘 곳 |
| `CLOSET_SAME_GARMENT_THRESHOLD` | `0.90` | 같은 옷으로 합치는 유사도 기준 |

API 키는 필요 없습니다(임베딩은 서버 CPU에서 돌고, GPT는 agent가 부릅니다). 만든 방식과 잰 숫자는 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)에 있습니다.

## 공통 약속

모든 레포가 지키는 약속은 앱 레포에 있습니다. **코드보다 약속 문서를 먼저 고칩니다.**

- [서비스 간 약속](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/README.md): 공통 규칙, 포트, 인증(`X-User-Id`), 오류 모양, DB 스키마, 옷 추천 흐름
- [MCP 도구 입출력](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/mcp-tools.md)
- [앱 ↔ 게이트웨이 API (OpenAPI)](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/openapi.yaml)
- [아키텍처와 기술 스택](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/ARCHITECTURE.md)

## 버전

| 항목 | 버전 |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.0.9 |
| Spring Cloud | 2025.1.4 |
| Spring AI (BOM) | 2.0.2 |

## 브랜치

GitFlow를 따릅니다. `develop`에서 `feature/…` 브랜치를 따서 PR로 합칩니다. 규칙은 앱 레포의 `docs/GITFLOW.md`를 봅니다.

## 비밀 값

이 서버는 API 키를 쓰지 않습니다. DB 비밀번호는 레포에 넣지 않습니다. 로컬은 `.env`(커밋 금지), 배포는 서버 환경 변수로 둡니다. 목록은 앱 레포 `docs/infra/.env.example`에 있습니다.
