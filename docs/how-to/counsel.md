# 기존 BE의 상담 도메인

상담은 `com.ubot.counsel`에 있으며 기존 Spring Boot 프로세스에서 동작한다. 고객 FE는 `/ai`에 머물며 상담사 화면은 `/counselor`다. FE의 `/api/counsel/**` 요청은 기존 `/api` 프록시를 통해 BE `/counsel/**`로 전달된다. 별도 8081 서버는 필요하지 않다.

## 처리 흐름

1. 기존 `JwtAuthenticationFilter`가 토큰과 현재 DB 사용자를 확인한다. 컨트롤러는 `@AuthenticationPrincipal CustomUserDetails`를 사용한다.
2. `SessionService.createHandoff`가 기존 `ConversationService`를 호출해 회원 본인 대화의 전체 문답을 조회한다. 자기 서버에 인증·이력 HTTP 요청을 다시 보내지 않는다.
3. 저장 문답과 화면에만 있던 FAQ 문답을 합친다. 저장 내역은 `SAVED`, 고객 화면 보충은 `SCREEN`으로 구분하며 후자는 화면에도 출처를 표시한다.
4. 상담 생성·이력 저장·배정을 같은 트랜잭션에서 수행한다. 담당 상담사는 저장된 이력을 읽고 이후 메시지를 이어서 받는다.
5. 새 상담 메시지는 검사 결과가 `ALLOWED`일 때만 전달 순번을 부여한다. 임베딩 동안 DB 트랜잭션을 잡지 않는다. 기존 ai-worker의 전용 큐가 vLLM BGE-M3에 임베딩을 요청한다.

이력은 **연결을 요청한 대화의 요청 시점 기록**이다. 계정의 다른 모든 대화를 공유하지 않는다. 이전 상담을 이어갈 때는 `previousSessionId`를 전달해 그 이력과 허용된 상담 메시지를 계승한다. 재배정 후 이전 상담사는 읽을 수 없다. 마지막 담당 상담사와 고객은 종료 이력을 읽을 수 있다.

## 파일과 경계

| 위치 | 역할 |
|---|---|
| `counsel/session` | 상담 상태·배정·대화 이력 |
| `counsel/message` | 접수·멱등성·전달·속도 제한 |
| `counsel/history` | 회원 본인 대화의 AI·상담 통합 이력 조회 |
| `counsel/safety` | 문장 검사·벡터 검색·워커 요청 |
| `counsel/admin` | 상담사 역할 관리·감사 조회·금지 문장 관리 |
| `counsel/common/config` | 설정·API 한도·API 역할에서만 실행하는 유지보수 |
| `embedding/worker/CounselEmbeddingWorker` | 기존 워커의 상담 임베딩 소비자 |
| `db/migration/V39__integrate_counsel_domain.sql` | 상담 스키마 생성·기존 V4 인계·대화 연결·이력 출처 복원·삭제 연계를 한 번에 적용 |

공통 `ApiResponse`, `ErrorCode`, `GlobalException`, JWT·CORS·DataSource·Redis를 재사용한다. 상담에만 적용되는 예외 처리기는 SQL 오류에 메시지 원문이 로그로 노출되지 않도록 제한한다. 기존 채팅 큐 `chat:jobs` 계약과 기존 AI 대화 테이블은 변경하지 않는다.

## 기존 코드 변경과 영향

| 기존 파일 | 변경 내용 | 기존 동작에 미치는 영향 |
|---|---|---|
| `auth/config/SecurityConfig.java` | 관리자 경로에 `/counsel/admin/**` 추가 | 상담 관리자 API도 ADMIN만 접근한다. 기존 `/admin/**`와 게스트 채팅 허용 규칙은 유지한다. |
| `user/enums/UserRole.java` | COUNSELOR 추가 | 로그인·토큰·내 정보 응답에서 새 역할을 사용한다. 회원가입 기본 역할은 USER이며 상담사에게 관리자 권한을 주지 않는다. |
| `conversation/repository/ConversationRepository.java` | 상담 존재 여부·제목·최근 메시지를 조회 | AI 질문이 없는 상담도 기존 목록에 나온다. 목록과 개수 조회에 상담 테이블 접근이 추가되어 V39 스키마가 필요하며 조회 비용도 증가한다. |
| `conversation/dto/response/ConversationResponseDto.java` | `hasCounsel`, `preview` 추가 | 기존 필드를 유지한 추가 응답이다. `lastQuestionAt`은 계속 `updated_at`을 사용하지만 상담 메시지·종료 활동도 반영한다. |
| `db/migration/R__purge_expired_data.sql` | 상담 기록 정리와 진행 중 상담 보존 조건 추가 | 진행 중 상담은 대화 삭제와 AI 질문 익명화를 보류한다. 종료 대화 삭제는 상담 데이터까지 연계되며 탈퇴 상담사의 잔여 상담은 종료한다. |
| `application.yml`, `.env.example` | 상담·검사·워커 설정 추가 | 상담 API 기본 활성화, 임베딩 워커 기본 비활성화다. 운영 시 워커 활성화와 모델·금지 문장 색인 준비가 필요하다. |
| `build.gradle` | commons-csv를 테스트 의존성에서 실행 의존성으로 이동 | 초기 금지 문장 CSV를 읽기 위해 실행 JAR에도 포함한다. 버전은 유지한다. |

기존 `AuthService`, `JwtAuthenticationFilter`, `ConversationService`, `ChatJobWorker`, `EmbeddingClient` 구현은 수정하지 않았다. 새 상담 코드가 기존 인증·회원 대화 서비스를 호출하고, 허용된 메시지 전송과 최초 종료 때 `conversations.updated_at`을 갱신한다. 따라서 목록 순서와 대화 보관 만료 시점에는 의도한 영향이 있다.

상담 임베딩은 기존 `EmbeddingClient`를 재사용하되 큐와 소비 스레드를 분리한다. 공유 DB 커넥션·Redis·모델 서버의 자원 사용은 증가하므로 기존 채팅의 처리 지연이 동일하다고 보장하지 않는다. `COUNSEL_ENABLED=false`는 상담 API·유지보수 실행을 제한하는 설정이며 대화 목록·보관 작업의 상담 스키마 의존성을 제거하지 않는다.

기존 파일의 나머지 수정은 상담사 인증 회귀 테스트(`AuthIntegrationTest`)와 README·설정 문서다. 현재 상담 추가 버전은 **V39 하나**이며 기존 보관 프로시저는 저장소의 repeatable migration 방식으로 유지한다. develop의 V37(Outbox), V38(고객센터 문의) 다음에 적용한다.

## API

모든 경로는 BE 기준이며 FE에서는 앞에 `/api`를 붙인다. 관리자 경로는 ADMIN, 상담 요청은 USER, 조회·전송은 해당 참여자만 허용한다.

| 메서드·경로 | 내용 |
|---|---|
| POST `/counsel/requests` | `{handoffId,conversationId,displayedTurns:[{question,answer}],previousSessionId?}` |
| GET `/counsel/sessions/current` | 현재 배정·대기 상담 |
| GET `/counsel/sessions/conversation/{conversationId}` | 본인의 해당 대화 마지막 상담 |
| GET `/conversations/{conversationId}/timeline?afterSequence=0&limit=100` | 회원 본인의 AI·상담 통합 이력; 최대 100개씩 sequence로 다음 페이지 조회 |
| GET `/counsel/sessions/{id}` | 참여자 상담 정보 |
| GET `/counsel/sessions/{id}/context?afterSequence=0&limit=100` | 이전 대화; 마지막 sequence로 다음 페이지 조회 |
| POST `/counsel/sessions/{id}/end` | 종료 또는 대기 취소 |
| POST `/counsel/counselors/availability` | `{status:"AVAILABLE"|"OFFLINE"}` |
| POST `/counsel/counselors/heartbeat` | 상담사 생존 확인 |
| POST `/counsel/sessions/{id}/messages` | `{clientMessageId,content}` |
| GET `/counsel/sessions/{id}/messages?afterSequence=0&limit=100` | 허용된 메시지; 마지막 deliverySequence로 다음 페이지 조회 |
| GET `/counsel/admin/safety/events`, `/summary` | 원문 없는 판정 감사·집계 |
| POST/DELETE `/counsel/admin/counselors/{userId}` | 유휴 USER ↔ COUNSELOR |
| POST `/counsel/admin/safety/evaluate` | `{content}` 가드 평가 |
| GET/POST `/counsel/admin/safety/sentences` | 금지 문장 목록·등록 `{content,severity}` |
| GET/PATCH `/counsel/admin/safety/sentences/{id}` | 조회·수정 `{revision,content,severity,enabled}` |
| POST `/counsel/admin/safety/sentences/{id}/index` | `{revision}` 임베딩 색인 |
| DELETE `/counsel/admin/safety/sentences/{id}?revision=N` | 논리 삭제·벡터 검색 제외 |

동일 handoffId 재시도는 같은 상담을 반환한다. 동일 clientMessageId는 같은 접수 결과를 반환하며, 확정 CHECK_FAILED 재검사는 새 UUID를 사용한다. 관리자 수정 시 기존 벡터를 즉시 비활성화한다. 색인 중 변경·삭제·늦은 응답은 이전 revision의 벡터를 게시하지 않는다. 과거 판정 참조는 보존한다.

## 기존 대화 목록과 종료 이력

AI 질문 없이 상담을 요청해도 같은 트랜잭션에서 회원 대화를 만든다. 인계 저장에 실패하면 대화 생성도 롤백하며, 응답을 잃어 동일 handoffId로 재시도해도 대화·상담을 중복 생성하지 않는다. 이전 형식의 대화 ID 없는 재상담 요청도 previousSessionId의 대화를 이어 쓴다.

`GET /conversations/me`는 기존 필드에 `hasCounsel`, `preview`를 추가한다. 상담만 있는 대화의 기본 제목은 `상담사 상담`이며 첫 허용된 고객 메시지가 있으면 그 내용을 사용한다. 허용된 상담 메시지와 최초 종료가 대화의 `updated_at`을 갱신한다. 차단·검사 실패 원문은 목록 미리보기에 포함하지 않는다. 기존 `/conversations/{id}/messages`의 AI 문답 응답은 유지한다.

통합 이력의 `id`는 항목 식별자, `sequence`는 조회 시 정렬 순번, `role`은 USER·AI·COUNSELOR·SYSTEM이다. AI 문답 원본, 허용된 상담 메시지, 연결 요청·종료 표시를 순서대로 반환한다. 화면 문답은 최초 `origin_session_id`·`origin_sequence`로 한 번만 읽는다. 원본 대화가 없는 과거 자료는 인계 사본의 SAVED·COUNSEL 기록도 보존해 표시한다. 이 API는 종료 이력의 페이지 조회용이며 실시간 전달 커서로 사용하지 않는다. 실시간 상담은 기존 `/messages`의 `deliverySequence`를 사용한다.

## 보관과 삭제

기존 `DataRetentionScheduler`와 `purge_expired_data()`를 확장한다. 새로운 삭제 스케줄러는 추가하지 않는다.

- 정상 상담 메시지·인계 사본·해당 판정 이벤트는 기존 대화와 함께 **마지막 활동 후 3개월** 보관한다. 종료되지 않은 상담이 있으면 대화 삭제와 AI 질문 익명화를 보류한다.
- BLOCKED·CHECK_FAILED 메시지와 해당 판정 이벤트는 **메시지 생성 후 3개월**이 지나면 함께 삭제한다. 일반 이력 조회에는 보관 중에도 반환하지 않는다.
- 고객이 기존 탈퇴 정책에 따라 실제 삭제되면 상담·메시지·인계 사본도 함께 삭제된다. 상담사 삭제는 남은 상담을 종료하고 담당자·발신자 식별자를 비우며 고객의 정상 상담 내용은 대화 보관 기간까지 남긴다.
- 통합 V39는 기존 연결 없는 상담에도 회원 대화를 만들어 목록에서 찾을 수 있게 한다. 같은 문장이 여러 번 등장한 화면 기록은 등장 순서를 구분하며, 반복 인계된 사본은 최초 출처로 연결한다.

위 기간은 이 프로젝트의 기존 3개월 로그·대화 정책을 적용한 제품 동작이다.

## 실행과 데이터

- 기존 BE/API와 ai-worker를 실행한다. API는 `COUNSEL_ENABLED=true`, 해당 워커 한 인스턴스는 `COUNSEL_EMBEDDING_WORKER_ENABLED=true`로 둔다. 로컬 `APP_ROLE=all`에서는 한 BE 프로세스가 둘 다 처리한다.
- 기본 `COUNSEL_EMBEDDING_TRANSPORT=worker`. `http`는 독립 모델 연결·테스트용 옵션이다. 워커 오류 시 HTTP로 자동 우회하지 않는다.
- 최초 50개 예시 등록은 `COUNSEL_SEED_ON_START=true`로 API/all 프로세스를 한 번 실행한다. 준비된 워커와 vLLM이 필요하다. 정상 등록 후 false로 돌린다. 같은 프로필·모델·데이터 해시이면 재등록하지 않는다.
- 임베딩 모델명은 워커와 `COUNSEL_GUARD_MODEL`이 일치해야 하며 차원은 1024다.
- V39는 새 설치에서는 상담 테이블을 생성한다. 기존 별도 서비스의 V4 테이블이 있으면 데이터·벡터·감사 이력을 보존한다. V1~V3 데이터는 먼저 V4로 올려야 한다.
- 기존 테이블 소유자가 별도 DB 역할인 환경에서는 DBA가 기존 BE 계정에 counseling 스키마 및 테이블·시퀀스 접근 권한을 부여해야 한다. 새 설치는 기존 BE 마이그레이션 계정이 생성한다.

공유 DB·Redis·GPU 부하가 있으므로 기존 AI 성능이 완전히 동일하다고 보장하지 않는다. 현재 상담 임베딩 소비자는 별도 스레드 1개, 큐 상한 16개이며 기존 채팅 실행 풀과 큐는 유지한다. 실제 AI 답변 생성 모델 실행 여부는 상담 임베딩 서버 실행과 별개다.
