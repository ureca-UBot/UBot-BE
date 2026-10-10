# 프론트엔드 연동 가이드

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30
>
> 프론트 기준: UBot-FE `develop` [`900af60`](https://github.com/ureca-UBot/UBot-FE/commit/900af60fce5bbdc0c935309953774741f44563c5) (2026-09-29 14:44 KST 커밋)

UBot-FE가 이 백엔드를 호출할 때 알아야 할 규칙입니다. 요청·응답 필드 전체는 Swagger UI(`/swagger-ui.html`), 오류 코드는 [오류 코드 Reference](../reference/error-codes.md)를 참고하세요.

## 공통 규칙

- 백엔드 경로에는 `/api` 같은 접두사가 없습니다. `/auth/login`, `/chat/questions`, `/stores`처럼 호출합니다.
- 모든 응답은 `ApiResponse` 형식입니다. 성공 여부는 `success`, 오류 종류는 `code`로 구분합니다([api-response.md](../../src/main/java/com/ubot/common/manual/api-response.md)).

```json
{ "success": true, "code": "SUCCESS", "message": "요청이 성공적으로 처리되었습니다.", "data": { } }
```

- 목록 API의 `data`는 `PageResponseDto`입니다: `content`, `page`(0부터), `size`, `totalElements`, `totalPages`, `first`, `last`.
- 예외적으로 본문이 없는 응답이 있습니다. `DELETE /admin/stores/{storeId}`는 `204 No Content`, `POST /admin/stores`는 `201 Created`와 `Location` 헤더를 반환합니다.
- 인증이 필요한 API는 `Authorization: Bearer <accessToken>` 헤더가 필요합니다. 인증 없이 호출할 수 있는 경로는 [architecture.md#인증](../architecture.md#인증)에 있습니다.

## 호출 경로와 프록시

### 로컬 개발 (현재 동작)

UBot-FE는 `/api/...`로 호출하고, Vite 개발 서버의 프록시가 `/api`를 떼어 백엔드로 넘깁니다.

```text
브라우저 → http://localhost:5173/api/auth/login
        → (Vite proxy, /api 제거) → http://localhost:8080/auth/login
```

- 프록시 대상은 UBot-FE의 `VITE_API_PROXY_TARGET`(기본 `http://localhost:8080`)입니다.
- 같은 origin으로 호출하므로 로컬 개발에서는 CORS가 필요 없습니다.

### 배포 환경 (미해결)

UBot-FE는 GitHub Pages(`https://ureca-ubot.github.io/UBot-FE/`)에 배포되고, 백엔드는 EC2의 Nginx(HTTP 80) 뒤에 있습니다. 현재 구성으로는 배포된 프론트에서 백엔드를 호출할 수 없습니다. UBot-FE의 채팅 연동 문서에도 "배포 환경의 API 주소와 CORS 설정이 별도로 필요"하다고 적혀 있습니다.

| 막히는 이유 | 현재 상태 |
|---|---|
| CORS | 백엔드에 CORS 설정이 없습니다(`SecurityConfig`, `@CrossOrigin`, `WebMvcConfigurer` 모두 없음). 다른 origin에서의 브라우저 요청은 차단됩니다. |
| HTTPS | GitHub Pages는 HTTPS, 백엔드 Nginx는 HTTP만 엽니다. 브라우저가 HTTPS 페이지의 HTTP 요청(mixed content)을 차단합니다. HTTPS 적용은 후속 작업입니다([deploy.md](../deploy.md#배포-범위)). |

`/api` 접두사는 Nginx가 처리합니다. `/api/`로 시작하는 요청은 `/api`를 떼어 백엔드로 넘기고, 접두사 없는 경로도 그대로 넘깁니다. 그래서 UBot-FE의 두 API 클라이언트가 `/api`를 붙이는 방식이 달라도(아래 표) 둘 다 Nginx를 거치면 동작합니다.

| UBot-FE 클라이언트 | 사용하는 곳 | 요청 URL 조합 | 인증 헤더 |
|---|---|---|---|
| `src/shared/api/client.ts` (`apiClient`) | 인증, 내 정보, 채팅, 관리자 FAQ·매장·금지어 | `VITE_API_BASE_URL`(기본 빈 값) + `/api/...` 경로 | 붙임 |
| `src/user/store-locator/api/client.ts` (`storeRequest`) | 매장 찾기, 길찾기 | `VITE_API_BASE_URL`(기본 `/api`) + `/stores/...` 경로 | 붙이지 않음 |

예를 들어 `VITE_API_BASE_URL`을 Nginx 주소로 지정하면 `apiClient`는 `…/api/auth/login`을, `storeRequest`는 `…/stores`를 호출하고 둘 다 백엔드에 도달합니다. Nginx를 거치지 않고 백엔드(8080)를 직접 호출하면 `/api`가 붙은 경로는 실패합니다.

남은 문제는 CORS와 HTTPS입니다. 프론트를 백엔드와 같은 origin(같은 Nginx)에서 제공하면 둘 다 필요 없고, GitHub Pages에서 호출하려면 둘 다 적용해야 합니다. 게스트 채팅은 `JSESSIONID` 쿠키로 식별하므로, 다른 origin에서 호출할 때는 쿠키 전달 설정(`credentials`, `SameSite=None; Secure`)도 필요합니다.

## 인증

### 회원가입·로그인

| API | 요청 본문 | 응답 `data` |
|---|---|---|
| `POST /auth/signup` | `email`(100자 이하), `password`·`passwordConfirm`(UTF-8 8~20바이트), `name`(20자 이하), `birthDate`(`YYYY-MM-DD`, 오늘 이전), `gender`(`MALE`/`FEMALE`), `residenceArea`(100자 이하) | `null` |
| `POST /auth/login` | `email`, `password` | `accessToken`, `refreshToken` |
| `POST /auth/refresh` | `refreshToken` | 새 `accessToken`, 새 `refreshToken` |
| `POST /auth/logout` | 없음 (access token 필요) | `null` |

- 회원가입 검증 실패는 항목별 `USER-007`~`USER-012`로, 이메일 중복은 `USER-003`으로 옵니다. 로그인 실패는 이메일·비밀번호 구분 없이 `USER-002`입니다.
- 이메일은 앞뒤 공백을 지우고 소문자로 바꿔 저장·비교합니다.

### Access token

- JWT입니다. payload의 `sub`는 사용자 ID(문자열), `role`은 `USER` 또는 `ADMIN`, `exp`는 만료 시각입니다. UBot-FE는 이 `role`로 관리자 화면 접근을 막습니다.
- 유효 시간은 서버 설정 `JWT_ACCESS_TOKEN_EXPIRATION_MILLIS`입니다(`.env.example` 10분, 설정이 없으면 1시간).
- 서버는 권한을 토큰의 `role`이 아니라 **요청마다 DB의 사용자 역할**로 판단합니다. DB에서 역할을 바꿔도 프론트의 토큰에 있는 `role`은 그대로이므로, 프론트 화면에 반영하려면 다시 로그인하거나 토큰을 재발급받아야 합니다.
- 로그아웃은 서버의 refresh token만 지웁니다. 이미 발급된 access token은 만료될 때까지 유효합니다.

### 재발급 흐름

```text
API 호출 → 401 JWT-007 (access token 만료)
        → POST /auth/refresh { refreshToken }
            성공 → 새 토큰 두 개 저장 → 원래 요청 재시도
            JWT-001 / JWT-002 / JWT-003 / JWT-005 → 토큰 삭제, 다시 로그인
```

- Refresh token은 사용자당 하나이고, 로그인·재발급 때마다 **새 값으로 교체**됩니다(유효 기간 `JWT_REFRESH_TOKEN_EXPIRATION_DAYS`, 기본 14일). 다른 기기나 탭에서 로그인하면 이전 refresh token은 `JWT-001`이 됩니다.
- 같은 refresh token으로 재발급을 동시에 두 번 보내면 먼저 처리된 쪽이 토큰을 바꾸므로 나중 요청은 `JWT-001`로 실패합니다. 재발급은 한 번에 하나만 보내야 합니다. UBot-FE `apiClient`의 `refreshTokens()`가 진행 중인 재발급을 공유해 이를 막고 있습니다.
- `JWT-004`(서명·형식 오류)는 재발급으로 해결되지 않습니다.
- 토큰이 유효해도 탈퇴한 사용자면 `USER-001`(404)이 옵니다.

## 채팅

### 질문

```http
POST /chat/questions
Authorization: Bearer <accessToken>
Content-Type: application/json

{ "question": "유심을 재발급받으려면 어떻게 하나요?" }
```

성공(`200`):

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "answer": "…모델이 생성한 문자열…",
    "status": "SUCCESS",
    "idempotencyKey": "<64자리 16진수>",
    "attemptCount": 1,
    "retryable": false
  }
}
```

답변 생성 실패(예: `404 CHAT-013`):

```json
{
  "success": false,
  "code": "CHAT-013",
  "message": "정확한 답변을 찾지 못했습니다.",
  "data": {
    "answer": "정확한 답변을 찾지 못했습니다.",
    "status": "FAIL",
    "idempotencyKey": "<64자리 16진수>",
    "attemptCount": 1,
    "retryable": true
  }
}
```

- `question`은 공백이 아닌 4000자 이하 문자열입니다. 위반하면 `G-001`입니다.
- 금지어가 포함되면 `400 FW-003`이며 `data`는 `null`입니다. 시도 기록을 만들지 않으므로 재시도할 수 없습니다.
- 답변이 나올 때까지 응답이 오지 않습니다. 서버는 최대 `CHAT_RESPONSE_TIMEOUT_MILLIS`(기본 180초)를 기다리고, 넘으면 `504 CHAT-016`을 반환합니다. 배포 환경의 Nginx도 180초에 연결을 끊습니다. **클라이언트 쪽 요청 제한 시간은 180초보다 길게** 두어야 서버의 `CHAT-016` 응답을 받을 수 있습니다. 현재 UBot-FE는 클라이언트 제한 시간을 두지 않습니다.
- 스트리밍(SSE) 없이 완성된 답변을 한 번에 반환합니다(회의 결정, #82).

### 재시도

```http
POST /chat/questions/retries
Authorization: Bearer <accessToken>
Idempotency-Key: <실패 응답의 idempotencyKey>
```

- 본문은 없습니다. 질문은 서버가 기존 기록에서 가져옵니다.
- `data.retryable`이 `true`일 때만 재시도 버튼을 보여 주면 됩니다. `retryable`은 안내값이고, 실제 요청이 오면 서버가 본인 기록인지와 재시도 조건을 다시 검사합니다(#82). UBot-FE는 `status === "FAIL"`, `retryable`, `idempotencyKey`가 모두 있을 때만 재시도합니다.
- 최초 요청을 포함해 최대 `CHAT_MAX_ATTEMPTS`(기본 3)회까지 시도할 수 있습니다. 거절 사유는 `CHAT-003`(본인 기록 없음), `CHAT-004`(처리 중), `CHAT-005`(이미 성공), `CHAT-006`(횟수 초과), `CHAT-007`, `CHAT-011`(키 형식 오류)이며, 이때 `data`는 `null`입니다.

### 의도 재검색

성공한 답변이 질문의 의도와 맞지 않을 때, 사용자가 고른 의도(`GENERAL`, `STORE_DATA`, `USER_DATA`)로 같은 질문을 다시 검색합니다. 회원만 쓸 수 있습니다.

```http
POST /chat/questions/research
Authorization: Bearer <accessToken>
Idempotency-Key: <성공 응답의 idempotencyKey>
Content-Type: application/json

{ "intent": "STORE_DATA", "latitude": 37.4979, "longitude": 127.0276 }
```

- `intent`는 필수입니다. 알 수 없는 값이면 `400`입니다.
- `latitude`·`longitude`는 선택이며 함께 보내거나 함께 생략합니다. 매장 의도에서 현재 위치를 쓰고 싶을 때만 보내면 됩니다. 한쪽만 보내거나 범위(위도 ±90, 경도 ±180)를 벗어나면 `G-001`입니다.
- 질문은 서버가 원본 기록에서 가져옵니다. 응답 형식은 질문·재시도와 같은 `ChatResponseDto`이며, `idempotencyKey`는 **재검색 시도의 새 키**입니다. 원본의 키를 덮어쓰지 마세요.
- 재검색한 답변은 재시도할 수 없습니다(`retryable`은 항상 `false`). 실패하면 다른 의도를 고르게 하세요.
- 같은 질문의 같은 의도는 한 번만 재검색할 수 있습니다. 거절 사유는 `CHAT-018`(재검색할 수 없는 답변), `CHAT-019`(이미 재검색한 의도), `CHAT-003`(본인 기록 없음)이며 이때 `data`는 `null`입니다. 비로그인이면 `401`입니다.
- 재검색이 `CHAT-012`·`CHAT-013`으로 끝나면 해당 의도의 FAQ에서 근거를 찾지 못했다는 뜻입니다.

### 알아 둘 점

- **답변 문자열이 JSON일 수 있습니다.** 모델이 출력한 JSON 문자열이 그대로 `answer`에 들어옵니다([LLM 모듈 안내](llm-module.md#출력-형식과-서버-처리-json-분리는-후속-작업)). 지금은 UBot-FE의 `ChatMessages.tsx`(`getAnswerText`)가 이를 JSON으로 파싱해 안쪽 `answer`만 표시하고, 파싱에 실패하면 원문을 표시합니다. 그런데 모델이 JSON을 마크다운 코드 블록(```` ```json … ``` ````)으로 감싸 반환하는 경우가 있고(`exaone3.5:7.8b`로 확인), 이때는 파싱에 실패해 코드 블록이 화면에 그대로 보입니다. 백엔드가 JSON 분리(#81)를 구현하면 응답 형식이 바뀔 수 있으므로 양쪽이 함께 맞춰야 합니다.
- **대화 이력 API가 없습니다.** 새로고침하면 화면의 대화는 사라집니다. 이전 대화 기억은 2차 MVP 범위입니다(#81).
- **요청을 중간에 끊어도 서버 작업은 계속됩니다.** 브라우저가 요청을 끊어도 서버는 답변을 끝까지 만들어 저장하거나 제한 시간에 실패로 기록합니다. 끊긴 요청의 결과를 다시 받을 방법은 없습니다. 성공으로 끝났다면 재시도는 `CHAT-005`로 거절됩니다.
- 같은 사용자가 여러 질문을 동시에 보내는 것을 서버가 막지는 않습니다. UBot-FE는 한 번에 한 요청만 보내도록 막고 있습니다.

## 매장·위치·길찾기

- `/stores/**`, `/locations/**`는 인증 없이 호출합니다.
- 길찾기 `GET /stores/{storeId}/directions?mode=WALK|CAR|TRANSIT&latitude=&longitude=`는 경로 목록을 반환합니다. 도보·자동차는 1건, 대중교통은 소요 시간순 후보 목록입니다.
- 대중교통 후보 하나를 고르면 그 객체를 그대로 본문에 담아 `POST /stores/{storeId}/directions/transit-detail?latitude=&longitude=`로 보내 도보 구간이 채워진 경로를 받습니다. 대중교통이 아닌 후보를 보내면 `DIRECTIONS-004`입니다.
- 길찾기 결과는 서버에 저장하지 않고 요청마다 카카오 API를 호출합니다.
- 필수 쿼리 파라미터가 빠지면 현재 400이 아니라 `500 G-005`가 옵니다([오류 코드 Reference](../reference/error-codes.md#알아-둘-현재-동작)).

## 관리자 화면

- `/admin/**`은 `ADMIN` 역할이 필요합니다. 아니면 `403 AUTH-002`입니다.
- 회원가입으로는 `ADMIN` 계정을 만들 수 없습니다. 로컬에서는 [DB에서 역할을 바꾼 뒤](db-access.md#로컬에서-관리자-계정-만들기) 다시 로그인합니다.
- FAQ 카테고리를 쓰는 활성 FAQ가 있으면 카테고리 삭제가 `FAQ-006`으로 거절됩니다.
- FAQ를 만들거나 질문을 수정하면 서버가 요청 안에서 임베딩을 계산하므로 응답이 수 초 걸릴 수 있습니다.
