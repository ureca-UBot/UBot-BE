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
| CORS | `CORS_ALLOWED_ORIGINS`에 적은 origin만 허용합니다(`SecurityConfig`). 기본값은 비어 있어 다른 origin에서의 브라우저 요청은 차단됩니다. GitHub Pages에서 호출하려면 `https://ureca-ubot.github.io`를 넣습니다. |
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
            JWT-001 / JWT-002 / JWT-003 / JWT-005 / JWT-008 → 토큰 삭제, 다시 로그인
```

- Refresh token은 사용자당 하나이고, 로그인·재발급 때마다 **새 값으로 교체**됩니다(유효 기간 `JWT_REFRESH_TOKEN_EXPIRATION_DAYS`, 기본 14일). 다른 기기나 탭에서 로그인하면 이전 refresh token은 `JWT-001`이 됩니다.
- 같은 refresh token으로 재발급을 동시에 두 번 보내면 먼저 처리된 쪽이 토큰을 바꾸므로 나중 요청은 `JWT-001`로 실패합니다. 재발급은 한 번에 하나만 보내야 합니다. UBot-FE `apiClient`의 `refreshTokens()`가 진행 중인 재발급을 공유해 이를 막고 있습니다.
- 교체된 지 10초가 지난 이전 refresh token이 다시 오면 탈취로 보고 그 사용자의 refresh token을 지운 뒤 `JWT-008`을 반환합니다. 10초 안에 온 경우는 동시 요청으로 보고 `JWT-001`만 반환합니다.
- 같은 이메일로 로그인에 5번, 같은 IP에서 20번 실패하면 15분 동안 `USER-014`(429)로 로그인을 막습니다. 로그인에 성공하면 이메일 실패 횟수는 초기화됩니다.
- `JWT-004`(서명·형식 오류)는 재발급으로 해결되지 않습니다.
- 로그아웃하거나 refresh token 재사용이 감지되면 그 사용자에게 이미 발급된 access token도 만료 전에 거부되고 `JWT-009`가 옵니다. 토큰을 지우고 다시 로그인하게 합니다. 차단 정보는 Redis에 저장하며, Redis에 연결할 수 없으면 차단 없이 통과시킵니다.
- 토큰이 유효해도 탈퇴한 사용자면 `USER-001`(404)이 옵니다.

## 채팅

질문·재시도·재검색은 **접수만 하고 바로 `202 Accepted`로 응답**합니다. 답변은 별도 Worker가 작업 스트림에서 받아 만들고, 클라이언트는 받은 `jobId`로 상태를 조회합니다. 답변이 나올 때까지 연결을 붙잡고 기다리지 않습니다.

```text
POST /chat/questions → 202 { jobId, status: "QUEUED" }
GET  /chat/jobs/{jobId} → QUEUED → RUNNING → SUCCEEDED(answer) 또는 FAILED(errorCode)
```

### 질문 접수

```http
POST /chat/questions
Authorization: Bearer <accessToken>
Content-Type: application/json

{ "question": "유심을 재발급받으려면 어떻게 하나요?" }
```

접수(`202`):

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "jobId": 12345,
    "status": "QUEUED",
    "conversationId": "a927bf8e-4ab4-4d3b-814b-dde3c0e62aac",
    "idempotencyKey": "<64자리 16진수>",
    "attemptCount": 1
  }
}
```

- `question`은 공백이 아닌 4000자 이하 문자열입니다. 위반하면 `G-001`입니다.
- 금지어가 포함되면 `400 FW-003`이며 `data`는 `null`입니다. 시도 기록을 만들지 않으므로 재시도할 수 없습니다.
- 비회원 질문 수 초과(`429 CHAT-017`)처럼 접수 단계에서 거절되면 작업을 만들지 않습니다.
- 같은 질문을 다시 보내면 새 질문으로 접수됩니다. 접수 응답을 받지 못해 다시 보낼 때는 중복 접수될 수 있습니다.

### 상태 조회

```http
GET /chat/jobs/{jobId}
Authorization: Bearer <accessToken>
```

처리 중(`200`):

```json
{
  "success": true,
  "code": "SUCCESS",
  "data": {
    "jobId": 12345,
    "status": "RUNNING",
    "answer": null,
    "store": null,
    "errorCode": null,
    "errorMessage": null,
    "retryable": false,
    "idempotencyKey": "<64자리 16진수>",
    "attemptCount": 1
  }
}
```

끝난 작업은 `status`가 `SUCCEEDED`(답변 성공) 또는 `FAILED`(실패)입니다.

| status | 의미 | 채워지는 값 |
|---|---|---|
| `QUEUED` | 접수되어 대기 중 | 없음. `retryable`은 `false` |
| `RUNNING` | Worker가 답변을 만드는 중 | 없음. `retryable`은 `false` |
| `SUCCEEDED` | 답변 완료 | `answer`, 매장 질문이면 `store` |
| `FAILED` | 답변 실패 | `errorCode`, `errorMessage`, `retryable` |

- 상태 조회는 답변의 성공·실패와 관계없이 `200`입니다. 실패 원인은 `data.errorCode`로 구분합니다(예: `CHAT-012` 검색 결과 없음, `CHAT-013` 근거 부족, `LLM-004` LLM 시간 초과, `CHAT-016` 대기 또는 실행 시간 초과).
- 본인의 작업만 조회됩니다. 회원은 JWT, 비회원은 질문할 때의 세션 쿠키로 확인합니다. 남의 작업과 없는 작업은 똑같이 `404 CHAT-020`입니다.
- `jobId`는 순차 번호입니다. 다른 사람의 번호를 넣어도 본인 작업이 아니면 `404 CHAT-020`입니다.
- 비회원 세션은 30분 동안 요청이 없으면 만료되고, 만료된 뒤에는 이전 작업을 조회할 수 없습니다.
- 게스트 질문 후 로그인하면 같은 세션 쿠키와 JWT로 상태를 조회하세요. 해당 게스트 대화를 회원에게 귀속한 뒤 조회하며, 이후에는 JWT로 조회합니다.
- 새 작업의 답변과 지도·위치 요청 정보는 성공 상태와 함께 DB에 저장됩니다. Redis 캐시 만료·유실에도 유지됩니다.
- 조회 간격은 1~2초를 권합니다. Nginx는 질문 요청 제한과 별도로 상태 조회를 IP당 20회/초, burst 40으로 제한합니다. 429·5xx·네트워크 오류는 지수 백오프와 jitter로 재조회하며, 성공·실패 확정 시 폴링을 종료합니다.
- jobId와 conversationId를 화면 상태에 보관해 재조회에 사용합니다. 회원 작업은 API 재시작 뒤에도 조회할 수 있습니다. 게스트는 기존 메모리 세션을 사용하므로 API 재시작·세션 만료 시 조회 권한을 잃을 수 있습니다. 새 질문 POST를 자동 반복하지 마세요.
- 접수 뒤 `CHAT_WORKER_MAX_QUEUE_WAIT`(기본 10분) 안에 시작하지 못한 질문은 `FAILED`·`CHAT-016`으로 끝나며 재시도할 수 있습니다.

### 처리 단계와 제한 시간

접수·상태 조회 응답에는 `phase`, `phaseUpdatedAt`, `deadlineAt`, `serverTime`이 추가됩니다.
기존 `status`와 오류·재시도 필드는 그대로 사용합니다. 시간은 UTC ISO-8601 문자열(`Z`)입니다.

| phase | 안내 문구 예시 |
|---|---|
| `WAITING` | 질문을 접수했습니다. 순서를 기다리고 있습니다. |
| `SEARCHING` | 질문과 관련된 정보를 찾고 있습니다. |
| `COLLECTING_CONTEXT` | 답변에 필요한 정보를 확인하고 있습니다. |
| `WAITING_FOR_MODEL` | 답변 생성을 준비하고 있습니다. |
| `GENERATING` | 답변을 생성하고 있습니다. |

`GENERATING`에는 모델의 도구 호출과 최종 답변 처리도 포함됩니다. 단계는 금방 지나가거나 생략될 수 있으므로
프론트는 모든 단계를 순서대로 받아야 한다고 가정하지 않습니다. 단계 저장이 일시적으로 실패하면 마지막으로
저장한 단계가 보일 수 있습니다. `phaseUpdatedAt`은 진행률이나 heartbeat가 아닙니다.

```json
{
  "jobId": 123,
  "status": "RUNNING",
  "phase": "GENERATING",
  "phaseUpdatedAt": "2026-10-15T08:00:05Z",
  "deadlineAt": "2026-10-15T08:03:00Z",
  "serverTime": "2026-10-15T08:00:10Z"
}
```

- `QUEUED`의 마감은 접수 시각 + `CHAT_WORKER_MAX_QUEUE_WAIT`(기본 10분)입니다.
  안내 예: “요청이 많으면 답변 생성 시작까지 최대 10분 기다릴 수 있습니다.”
- `RUNNING`의 마감은 이번 실행 선점 시각 + `CHAT_WORKER_EXECUTION_TIMEOUT`(기본 3분)입니다.
  검색·정보 수집·모델 대기·생성이 이 시간을 함께 쓰며, 단계가 바뀌어도 연장되지 않습니다.
- 시간 문구를 10분·3분으로 고정하지 말고 `deadlineAt - serverTime`으로 남은 제한 시간을 계산합니다.
  응답 수신 뒤 경과 시간은 브라우저의 단조 시계로 계산하면 사용자 PC 시계가 달라도 표시할 수 있습니다.
- 워커 장애로 새 실행이 작업을 넘겨받으면 기존 복구 정책에 따라 실행 제한을 다시 부여하고 마감도 갱신합니다.
  따라서 접수 후 총 13분 안에 반드시 완료된다는 의미가 아닙니다.
- 성공·실패 시 `phase`, `deadlineAt`은 `null`입니다. `phaseUpdatedAt`은 종료 시각입니다.
- 마이그레이션 이전 작업의 새 필드는 `null`일 수 있습니다. 선점 직후에도 실제 처리 단계가 아직 없을 수 있어
  `phase=null`이면 기존 `status`로 “대기 중” 또는 “처리 중”을 표시하고, 마감이 없으면 카운트다운을 생략합니다.
- 제한 시간은 완료 예상 시간이 아닙니다. 만료 감지·실패 저장에는 지연이 생길 수 있으므로, 카운트다운이 0이 되어도
  프론트가 실패를 확정하지 않습니다. “처리 결과를 확인하고 있습니다”로 안내하고 서버 최종 상태를 확인합니다.
- 같은 `jobId`로 다시 조회하면 DB에 남은 단계와 마감을 받습니다. 작업 목록 조회나 요청 중복 방지 기능은 추가되지 않았습니다.

### 재시도

```http
POST /chat/questions/retries
Authorization: Bearer <accessToken>
Idempotency-Key: <실패한 작업의 idempotencyKey>
```

- 본문은 없습니다. 질문은 서버가 기존 기록에서 가져옵니다. 응답은 질문 접수와 같은 `202`이며 **새 `jobId`**로 다시 조회합니다(`conversationId`는 없습니다).
- 상태 조회의 `retryable`이 `true`일 때만 재시도 버튼을 보여 주면 됩니다. `retryable`은 안내값이고, 실제 요청이 오면 서버가 본인 기록인지와 재시도 조건을 다시 검사합니다(#82).
- 최초 요청을 포함해 최대 `CHAT_MAX_ATTEMPTS`(기본 3)회까지 시도할 수 있습니다. 거절 사유는 `CHAT-003`(본인 기록 없음), `CHAT-004`(처리 중), `CHAT-005`(이미 성공), `CHAT-006`(횟수 초과), `CHAT-007`, `CHAT-011`(키 형식 오류)이며, 이때 `data`는 `null`입니다.

### 의도 재검색

성공한 답변이 질문의 의도와 맞지 않을 때, 사용자가 고른 의도(`GENERAL`, `STORE_DATA`, `USER_DATA`)로 같은 질문을 다시 검색합니다. 회원만 쓸 수 있습니다.

```http
POST /chat/questions/research
Authorization: Bearer <accessToken>
Idempotency-Key: <성공한 작업의 idempotencyKey>
Content-Type: application/json

{ "intent": "STORE_DATA", "latitude": 37.4979, "longitude": 127.0276 }
```

- `intent`는 필수입니다. 알 수 없는 값이면 `400`입니다.
- `latitude`·`longitude`는 선택이며 함께 보내거나 함께 생략합니다. 매장 의도에서 현재 위치를 쓰고 싶을 때만 보내면 됩니다. 한쪽만 보내거나 범위(위도 ±90, 경도 ±180)를 벗어나면 `G-001`입니다.
- 응답은 질문 접수와 같은 `202`입니다. `idempotencyKey`는 **재검색 시도의 새 키**입니다. 원본의 키를 덮어쓰지 마세요.
- 재검색한 답변은 재시도할 수 없습니다(`retryable`은 항상 `false`). 실패하면 다른 의도를 고르게 하세요.
- 같은 질문의 같은 의도는 한 번만 재검색할 수 있습니다. 거절 사유는 `CHAT-018`(재검색할 수 없는 답변), `CHAT-019`(이미 재검색한 의도), `CHAT-003`(본인 기록 없음)이며 이때 `data`는 `null`입니다. 비로그인이면 `401`입니다.
- 재검색이 `CHAT-012`·`CHAT-013`으로 끝나면 해당 의도의 FAQ에서 근거를 찾지 못했다는 뜻입니다.

### 알아 둘 점

- **`answer`는 답변 문장입니다.** 백엔드가 모델 출력 JSON을 파싱해 안쪽 `answer`만 내려 줍니다([LLM 모듈 안내](llm-module.md#출력-형식과-서버-처리)). 2026-10-12 이전에 저장된 대화 기록은 JSON 문자열 그대로일 수 있으므로, UBot-FE의 `ChatMessages.tsx`(`getAnswerText`)처럼 JSON이면 안쪽 `answer`를, 일반 문장이면 그대로 표시하는 처리를 유지합니다.
- **화면을 떠나도 서버 작업은 계속됩니다.** 조회를 멈춰도 Worker는 답변을 끝까지 만들어 저장합니다. 같은 `jobId`로 다시 조회하면 결과를 받을 수 있습니다(비회원은 세션이 살아 있을 때만).
- 같은 사용자가 여러 질문을 동시에 보내는 것을 서버가 막지는 않습니다. UBot-FE는 한 번에 한 요청만 보내도록 막고 있습니다.
- 스트리밍(SSE) 없이 완성된 답변을 한 번에 돌려줍니다(회의 결정, #82).

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
