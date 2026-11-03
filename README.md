# mcp-closet

**closet MCP 서버** · 패션 에이전트(시각장애인용 코디 도우미)의 한 부분입니다.

사용자가 찍은 옷을 DB에 기록해 사용자 전용 옷장을 만들고, 이후 이 옷장에서 옷을 추천합니다.

## 도구 (안)

- `add_clothes(description)`: 옷 설명(종류·색·무늬·격식)을 옷장에 기록 (사진은 저장 안 함)
- `list_closet()`: 내 옷장 목록
- `recommend_from_closet(conditions)`: 날씨·일정 조건에 맞는 옷 고르기

## 지금 상태

앱이 본 옷의 설명만 폰에 쌓고 있습니다. 여기로 옮길 코드:
- `fashion-agent`의 `lib/services/wardrobe_store.dart`, `lib/models/garment.dart`
- 색 조합 규칙 `lib/styling/color_rules.dart` (추천에 다시 쓸 수 있음)

## 할 일

- [ ] MCP 서버 만들기
- [ ] 옷장을 DB에 저장
- [ ] 같은 옷 합치기

## 전체 구조

```
application(fashion-agent) ─REST→ fashion-agent-gateway ─→ fashion-agent-agent ─MCP→ mcp-weather / mcp-schedule / mcp-clothes-state / mcp-face-check / mcp-closet
                                                                                              ↓
                                                                                              DB (계정·회원정보·일정·옷장)
```

## 브랜치

GitFlow를 따릅니다. `develop`에서 `feature/…` 브랜치를 따서 PR로 합칩니다. 규칙은 앱 레포의 `docs/GITFLOW.md`를 봅니다.

## 비밀 값

API 키와 DB 접속 정보는 레포에 넣지 않습니다. 배포 환경의 비밀 값(Secret)으로만 둡니다.
