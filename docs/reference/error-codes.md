# 오류 코드 Reference

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30

모든 오류 응답은 `ApiResponse` 형식(`success: false`, `code`, `message`, `data`)입니다. 클라이언트는 HTTP 상태보다 `code`로 오류를 구분하세요. 표의 메시지는 기본값이며, 일부 오류(예: 회원가입 검증)는 더 구체적인 메시지로 바뀌어 나갑니다.

아래 표는 각 도메인의 `*ErrorCode` enum에서 옮겼습니다. 코드를 추가·변경하면 이 문서도 함께 고칩니다. 새 코드를 만드는 방법은 [exception.md](../../src/main/java/com/ubot/common/manual/exception.md)를 참고하세요.

## `data`에 값이 들어오는 오류

| 경우 | `data` |
|---|---|
| `G-001` (요청 DTO `@Valid` 검증 실패) | 필드별 오류 메시지 객체. 필드마다 첫 메시지 하나 |
| 답변 생성 중 실패한 채팅 요청: `CHAT-008`~`CHAT-010`, `CHAT-012`~`CHAT-016`, `EM-*`, `LLM-*` | 시도 정보 `ChatResponseDto` (`answer`, `status: "FAIL"`, `idempotencyKey`, `attemptCount`, `retryable`). [채팅 흐름](../architecture.md#챗봇-질문-처리-흐름) 참고 |
| 그 외 | `null`. 채팅 API라도 시도 기록을 만들기 전에 거절되는 `FW-003`, `CHAT-011`과 재시도 거절 사유 `CHAT-003`~`CHAT-007`은 `null` |

## 알아 둘 현재 동작

- **스프링 기본 예외가 500으로 나갑니다.** `GlobalExceptionHandler`는 아래 경우를 따로 처리하지 않아 `G-005`(500)로 응답합니다. 클라이언트 입력 오류여도 500이 올 수 있습니다.
  - 필수 쿼리 파라미터 누락 (예: `GET /stores/nearby`에 `latitude` 없음)
  - 존재하지 않는 경로를 **토큰을 붙여** 호출 (토큰이 없으면 인증 단계에서 `AUTH-001`)
  - 지원하지 않는 HTTP 메서드, 지원하지 않는 Content-Type
  - 위치 검색(`/locations/search`)의 카카오 API 호출 실패 (예: `KAKAO_REST_API_KEY`가 비어 있을 때). 길찾기는 카카오 호출이 실패하면 `DIRECTIONS-002`·`DIRECTIONS-003`으로 응답합니다
- 쿼리·경로 파라미터 타입 오류와 `@Validated` 제약 위반은 `G-002`, 본문 JSON을 읽을 수 없으면 `G-003`입니다.
- 정의만 있고 현재 코드에서 던지지 않는 코드: `G-004`, `CHAT-002`. 채팅 질문 길이 오류는 `CHAT-001`보다 DTO 검증(`G-001`)이 먼저 걸립니다.

## 공통

| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `G-001` | 400 | `INVALID_INPUT` | 요청 값이 올바르지 않습니다. |
| `G-002` | 400 | `INVALID_PARAMETER` | 요청 파라미터가 올바르지 않습니다. |
| `G-003` | 400 | `INVALID_REQUEST_BODY` | 요청 본문을 읽을 수 없습니다. |
| `G-004` | 404 | `RESOURCE_NOT_FOUND` | 요청한 정보를 찾을 수 없습니다. |
| `G-005` | 500 | `INTERNAL_SERVER_ERROR` | 서버 오류가 발생했습니다. |


## 인증·토큰

| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `AUTH-001` | 401 | `UNAUTHORIZED` | 토큰이 없습니다. 인증되지 않은 사용자입니다. |
| `AUTH-002` | 403 | `FORBIDDEN` | 접근 권한이 없습니다. |


- `AUTH-001`: 인증이 필요한 경로에 토큰 없이 호출. `AUTH-002`: `/admin/**`에 `ADMIN`이 아닌 사용자가 호출.

| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `JWT-001` | 401 | `REFRESH_TOKEN_NOT_FOUND` | 해당 RefreshToken이 존재하지 않습니다. |
| `JWT-002` | 401 | `REFRESH_TOKEN_EXPIRED` | 해당 RefreshToken은 만료된 토큰입니다. |
| `JWT-003` | 400 | `INVALID_REFRESH_TOKEN_REQUEST` | Request의 RefreshToken이 올바르지 않습니다. |
| `JWT-004` | 401 | `INVALID_ACCESS_TOKEN` | 유효하지 않은 Access Token입니다. |
| `JWT-005` | 401 | `DELETED_USER_TOKEN` | 삭제된 유저의 토큰입니다. |
| `JWT-006` | 409 | `DUPLICATED_CREATE_REFRESH_TOKEN` | 이미 RefreshToken이 생성되었습니다. |
| `JWT-007` | 401 | `EXPIRED_ACCESS_TOKEN` | 해당 AccessToken은 만료된 토큰입니다. |


- `JWT-007`이 오면 `/auth/refresh`로 재발급합니다. 재발급 요청이 `JWT-001`·`JWT-002`·`JWT-003`·`JWT-005`로 거절되면 다시 로그인해야 합니다. 흐름은 [프론트 연동 가이드](../how-to/frontend-integration.md#인증) 참고.
- `JWT-004`: 서명·형식이 잘못된 access token. 재발급으로 해결되지 않습니다.

## 사용자

| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `USER-001` | 404 | `USER_NOT_FOUND` | 해당 User가 존재하지 않습니다. |
| `USER-002` | 401 | `LOGIN_FAILED` | 이메일 혹은 비밀번호가 일치하지 않습니다. |
| `USER-003` | 409 | `EMAIL_ALREADY_EXISTS` | 이메일이 이미 존재합니다. |
| `USER-004` | 400 | `PASSWORD_CONFIRM_MISMATCH` | 비밀번호 및 재확인이 일치하지 않습니다. |
| `USER-005` | 400 | `INVALID_SIGNUP_REQUEST` | SignupRequestDto가 올바르지 않습니다. |
| `USER-006` | 400 | `INVALID_LOGIN_REQUEST` | LoginRequestDto가 올바르지 않습니다. |
| `USER-007` | 400 | `INVALID_EMAIL_FORMAT` | 이메일 양식이 올바르지 않습니다. |
| `USER-008` | 400 | `INVALID_PASSWORD_FORMAT` | 비밀번호 양식이 올바르지 않습니다. |
| `USER-009` | 400 | `INVALID_BIRTHDATE_FORMAT` | 생일 입력이 올바르지 않습니다. |
| `USER-010` | 400 | `INVALID_NAME_FORMAT` | 이름 입력이 올바르지 않습니다. |
| `USER-011` | 400 | `INVALID_GENDER_FORMAT` | 성별 입력이 올바르지 않습니다. |
| `USER-012` | 400 | `INVALID_RESIDENCE_FORMAT` | 사는 지역 입력이 올바르지 않습니다. |
| `USER-013` | 400 | `INVALID_USER_UPDATE_REQUEST` | 수정할 항목이 없습니다. |


- `USER-001`: 토큰은 유효하지만 사용자가 탈퇴(`deleted_at`)했거나 없을 때 인증 필터에서도 발생합니다.
- 회원가입 검증 오류(`USER-007`~`USER-012`)는 원인별 상세 메시지로 바뀌어 나갑니다.

## FAQ (관리자)

| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `FAQ-001` | 404 | `FAQ_NOT_FOUND` | 해당 FAQ ID를 가진 FAQ가 존재하지 않습니다. |
| `FAQ-002` | 400 | `FAQ_VECTOR_CREATE_FAILURE` | FAQ VECTOR 생성에 실패했습니다. |
| `FAQ-003` | 404 | `FAQ_CATEGORY_NOT_FOUND` | FAQ Category가 존재하지 않습니다. |
| `FAQ-004` | 409 | `FAQ_CATEGORY_EXIST` | 이미 존재하는 카테고리 명입니다. |
| `FAQ-005` | 400 | `FAQ_CATEGORY_SAME_NAME` | 이전 카테고리명과 후 카테고리명이 같습니다. |
| `FAQ-006` | 400 | `FAQ_CATEGORY_IN_USE` | 해당 카테고리를 사용중인 FAQ가 있습니다. |


- `FAQ-006`: 카테고리를 쓰는 활성 FAQ가 있으면 카테고리를 삭제할 수 없습니다.

## 매장·길찾기

| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `STORE-001` | 404 | `STORE_NOT_FOUND` | 매장을 찾을 수 없습니다. |
| `STORE-002` | 409 | `DUPLICATE_STORE` | 이미 등록된 매장입니다. |
| `STORE-003` | 409 | `DELETED_STORE_ALREADY_EXISTS` | 삭제된 동일 매장이 존재합니다. 기존 매장을 복구해주세요. |
| `STORE-004` | 404 | `SERVICE_TYPE_NOT_FOUND` | 서비스 유형을 찾을 수 없습니다. |
| `STORE-005` | 400 | `INVALID_STORE_COORDINATES` | 위도와 경도는 함께 입력해야 합니다. |
| `STORE-006` | 400 | `INVALID_MAP_BOUNDS` | 지도 영역 좌표가 올바르지 않습니다. |


| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `DIRECTIONS-001` | 404 | `DIRECTIONS_ROUTE_NOT_FOUND` | 경로를 찾을 수 없습니다. |
| `DIRECTIONS-002` | 503 | `DIRECTIONS_SERVICE_UNAVAILABLE` | 길찾기 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요. |
| `DIRECTIONS-003` | 504 | `DIRECTIONS_TIMEOUT` | 길찾기 서비스 응답이 지연되고 있습니다. |
| `DIRECTIONS-004` | 400 | `DIRECTIONS_INVALID_CANDIDATE` | 대중교통 경로 후보만 도보 상세를 조회할 수 있습니다. |


## 채팅

| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `CHAT-001` | 400 | `INVALID_CHAT_REQUEST` | 요청 값이 올바르지 않습니다. |
| `CHAT-002` | 401 | `LOGIN_REQUIRED` | 로그인이 필요합니다. |
| `CHAT-003` | 404 | `ATTEMPT_NOT_FOUND` | 본인의 답변 시도 기록을 찾을 수 없습니다. |
| `CHAT-004` | 409 | `PROCESSING` | 이미 답변을 생성하고 있습니다. |
| `CHAT-005` | 409 | `ALREADY_SUCCEEDED` | 이미 답변이 생성된 질문입니다. |
| `CHAT-006` | 409 | `LIMIT_REACHED` | 최초 시도를 포함하여 최대 3회까지만 시도할 수 있습니다. |
| `CHAT-007` | 409 | `RETRY_NOT_ALLOWED` | 재시도할 수 없는 답변 상태입니다. |
| `CHAT-008` | 503 | `STORAGE_UNAVAILABLE` | 채팅 기록을 저장할 수 없습니다. |
| `CHAT-009` | 503 | `TASK_START_FAILED` | 답변 생성 작업을 시작하지 못했습니다. |
| `CHAT-010` | 503 | `VECTOR_SEARCH_FAILED` | 벡터 검색 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요. |
| `CHAT-011` | 400 | `INVALID_CHAT_RETRY_REQUEST` | 요청 값이 올바르지 않습니다. |
| `CHAT-012` | 404 | `NO_FAQ` | 검색 결과가 없습니다. |
| `CHAT-013` | 404 | `INSUFFICIENT_FAQ` | 정확한 답변을 찾지 못했습니다. |
| `CHAT-014` | 503 | `PROMPT_NOT_READY` | 답변 프롬프트가 준비되지 않았습니다. |
| `CHAT-015` | 500 | `INTERNAL_ERROR` | 답변 생성 중 오류가 발생했습니다. |
| `CHAT-016` | 504 | `RESPONSE_TIMEOUT` | 답변 생성 시간이 초과되었습니다. |
| `CHAT-017` | 429 | `GUEST_QUESTION_LIMIT_REACHED` | 비회원이 질문할 수 있는 횟수를 모두 사용했습니다. 로그인 후 이용해주세요. |


- `CHAT-004`~`CHAT-007`은 재시도 요청(`/chat/questions/retries`)의 거절 사유입니다.
- `CHAT-006`의 메시지는 `CHAT_MAX_ATTEMPTS` 설정과 관계없이 "최대 3회"로 고정되어 있습니다.
- `CHAT-016`은 `CHAT_RESPONSE_TIMEOUT_MILLIS`(기본 180초) 초과입니다. 재시도할 수 있습니다.
- `CHAT-017`은 비로그인 게스트가 한 세션에서 `guest_chat_settings.max_question_count`(기본 5)를 모두 쓴 뒤에 반환합니다. 정상 답변을 받은 질문과 FAQ를 찾지 못한 질문(`CHAT-012`·`CHAT-013`)만 횟수에 포함하고, 시스템 오류로 실패한 질문과 같은 질문의 재시도는 포함하지 않습니다. 횟수를 다 쓴 뒤에도 이미 한 질문의 재시도는 `CHAT_MAX_ATTEMPTS` 안에서 가능합니다. 최대 횟수는 관리자가 `PATCH /admin/guest-chat-settings`로 바꿀 수 있습니다. 이때 `data`는 `null`입니다.
- `CHAT-012`·`CHAT-013`으로 끝난 질문은 미응답 질문으로 저장됩니다([architecture.md](../architecture.md#미응답-질문-저장-105)).
- 배포 환경에서는 Nginx가 `/chat/`의 게스트 요청을 IP별로 제한하며(회원 요청은 제한 없음), 넘으면 백엔드를 거치지 않고 `429 RATE-001`("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.")을 같은 응답 형식으로 반환합니다([deploy.md](../deploy.md)).
- 증상별 확인 방법은 [troubleshooting](../troubleshooting.md#채팅-요청이-실패함)을 참고하세요.

## 임베딩

| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `EM-001` | 503 | `EMBEDDING_SERVICE_UNAVAILABLE` | 임베딩 서버 응답이 없습니다. 잠시 후 다시 시도해주세요. |
| `EM-002` | 500 | `EMBEDDING_RESPONSE_INVALID` | 임베딩 서버 응답 형식이 올바르지 않습니다. |
| `EM-003` | 504 | `EMBEDDING_TIMEOUT` | 임베딩 서버 응답이 지연되고 있습니다. |


- `EM-003`은 응답 지연뿐 아니라 Ollama 연결 실패(연결 거부 등 I/O 오류)에도 발생합니다. `EM-001`은 Ollama가 오류 상태 코드를 반환한 경우입니다.

## LLM

| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `LLM-001` | 400 | `LLM_REQUEST_INVALID` | LLM 입력 메시지가 올바르지 않습니다. |
| `LLM-002` | 500 | `LLM_MODEL_NOT_CONFIGURED` | 답변 생성에 사용할 LLM 모델이 설정되지 않았습니다. |
| `LLM-003` | 503 | `LLM_SERVICE_UNAVAILABLE` | LLM 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요. |
| `LLM-004` | 504 | `LLM_TIMEOUT` | LLM 응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요. |
| `LLM-005` | 500 | `LLM_RESPONSE_INVALID` | LLM 서버에서 올바른 답변을 받지 못했습니다. |


- `LLM-003`: 연결 거부, 서버 오류, 채팅 모델이 Ollama에 설치되지 않은 경우 등.
- `LLM-004`: `LLM_CONNECT_TIMEOUT`·`LLM_READ_TIMEOUT` 초과.
- `LLM-005`: 응답 파싱 실패, 빈 답변, 길이 제한으로 잘린 답변, 지원하지 않는 도구 호출.

## 금지어

| code | HTTP | 이름 | 기본 메시지 |
|---|---|---|---|
| `FW-001` | 404 | `FORBIDDEN_WORD_NOT_FOUND` | 해당 금지어가 존재하지 않습니다. |
| `FW-002` | 409 | `FORBIDDEN_WORD_EXIST` | 이미 존재하는 금지어입니다. |
| `FW-003` | 400 | `FORBIDDEN_WORD_DETECTED` | 사용할 수 없는 표현이 포함되어 있습니다. |
| `FW-004` | 400 | `FORBIDDEN_WORD_REQUIRED` | 금지어를 입력해야 합니다. |
| `FW-005` | 400 | `FORBIDDEN_WORD_STATUS_REQUIRED` | 변경할 상태를 입력해야 합니다. |

- `FW-003`: 채팅 질문에 활성 금지어가 포함된 경우. 시도 기록을 만들지 않으므로 재시도 대상이 아닙니다. 어떤 단어가 걸렸는지는 응답에 포함하지 않습니다.
