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
| `closet_search` | RAG의 검색 단계. 상황(일정·날씨 문장)에 맞는 내 옷 후보 + 코디 지식 문서. 생성(추천 문장)은 agent의 Gemini가 함 |
| `closet_save` | 옷 추천을 마칠 때 본 옷을 기록. 같은 옷이면 합침(임베딩 유사도 + 종류·색) |
| `closet_wear` | 입은 날 기록 |

입출력 JSON은 [MCP 도구 입출력](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/mcp-tools.md)를 그대로 따릅니다.

## 이 레포가 지킬 것

- 옷 설명은 `clothes_inspect`가 준 Garment JSON을 그대로 JSONB로 저장합니다. **사진은 저장하지 않습니다.**
- 코디 지식 문서(`style_docs`: 드레스코드, 색 조합, 날씨별 옷차림)는 우리가 직접 씁니다.
- 옷장이 비면 `empty: true`를 돌려주고 agent가 "옷 확인을 몇 번 하면 옷장이 채워져요"라고 안내합니다.
- DB 스키마 `closet`만 씁니다(`garments`, `wears`, `style_docs`).

## 할 일

- [ ] 뼈대 + `/mcp` + Dockerfile, pgvector 확장
- [ ] `closet_save`(중복 합치기 포함), `closet_wear`
- [ ] 임베딩 모델(ONNX) 붙이기, `closet_search`
- [ ] 코디 지식 문서 10~20개 쓰기

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

API 키와 DB 비밀번호는 레포에 넣지 않습니다. 로컬은 `.env`(커밋 금지), 배포는 서버 환경 변수로 둡니다. 목록은 앱 레포 `docs/infra/.env.example`에 있습니다.
