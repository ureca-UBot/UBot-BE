# 상담 도메인 통합 검증 — 2026-10-20

## 금지어 변경 후 실제 두 계정 브라우저 검증

20:12 KST에 **화면에 표시되는 Edge 창 두 개**를 열고 기존 테스트 고객(USER)·상담사(COUNSELOR)를 실제 로그인 폼으로 각각 로그인했다. 별도 브라우저 세션을 사용했으며 인증 토큰 주입이나 API 응답 대체는 하지 않았다.

- 기존 채팅 목록에서 저장된 대화를 선택해 상담을 호출했다. 두 계정이 같은 상담 ID에 연결되고 상담사가 이전 AI 문답·상담 기록을 모두 읽었다. 이전 AI 문답은 앞선 검증에서 저장한 fixture이며 이번에도 AI 답변 생성을 실행한 것은 아니다.
- 새 고객 문의와 상담사 답변이 실제 워커·vLLM BGE-M3 검사 후 ALLOWED로 양쪽 화면에 표시됐다.
- 기존 관리자 금지어 API로 임시 단어를 등록했다. 고객·상담사 양쪽에서 BLOCKED 안내가 표시되고 상대방에게 메시지가 전달되지 않았다. 판정 기록의 벡터 ID·유사도는 null이었다.
- 임시 단어를 비활성화한 뒤 같은 문장을 전송하면 임베딩 검사 후 ALLOWED로 전달됐다.
- 초기 색인 문장과 같은 문장을 전송해 실제 벡터 유사도 1.0, BLOCKED 및 상대방 미전달을 확인했다. 검증 시간의 vLLM 로그에는 임베딩 요청 4건이 모두 HTTP 200으로 기록됐다.
- 상담 종료 후 고객을 로그아웃하고 새 브라우저 컨텍스트에서 재로그인했다. 기존 목록에서 이전 이력과 새 허용 메시지가 복원됐으며 차단된 메시지는 나타나지 않았다.
- 브라우저 오류는 0건이다. 검증 상담을 종료하고 임시 금지어를 비활성화했으며 테스트용 관리자의 임시 권한을 회수했다. 고객·상담사 창은 사용자가 확인할 수 있도록 열어뒀다.

결과는 PASS이며 Git 제외 경로 `UBot-FE/test-results/counsel-filter-web/result.json`과 같은 폴더의 PNG 9장에 보관했다. 대표 화면은 `02-customer-conversation.png`, `03-counselor-conversation.png`, `04-customer-keyword-blocked.png`다.

## 기존 금지어 필터 재사용 및 중복 판정 제거

20:06 KST 기준 `SafetyGuardService`는 기존 `ForbiddenWordFilterService.validateForbiddenWord`로 원문을 먼저 검사한다. 탐지된 메시지는 워커 호출 없이 상담 `BLOCKED`로 저장·감사하고, 통과한 메시지만 임베딩한다. 고객·상담사 전송과 관리자 평가에 같은 경로를 적용했다. 기존 AI 채팅의 금지어 검사·`FW-003` 응답, 공통 필터·캐시 구현, FE, DB 마이그레이션은 변경하지 않았다.

`DirectInsultRules`의 직접 차단 정규식과 특정 호칭·인용·요금·환불 표현에 따른 유사도 판정 예외를 제거했다. 전처리·해시만 `SafetyTextNormalizer`로 옮겨 기존 벡터의 전처리 버전은 유지한다. 새 판정 감사 버전은 `forbidden-word-semantic-v2`다. 고정 예외 제거 후 유사도가 임계값 이상인 인용·불만 표현도 차단될 수 있으며, 아래 자동 테스트는 모델 정확도 평가가 아닌 처리 순서·판정 기준 검증이다.

```powershell
.\gradlew.bat test --tests 'com.ubot.counsel.*' --tests 'com.ubot.forbiddenword.*' --tests 'com.ubot.conversation.*' --tests 'com.ubot.retention.*' --tests 'com.ubot.auth.*' --tests 'com.ubot.user.*' --tests 'com.ubot.admin.*' --tests 'com.ubot.chat.*' --tests 'com.ubot.embedding.*' --tests 'com.ubot.faq.retrieval.*' --tests 'com.ubot.inquiry.*' --tests 'com.ubot.outbox.*' --tests 'com.ubot.unanswered.*' bootJar --console=plain
.\gradlew.bat test --tests 'com.ubot.counsel.WorkerEmbeddingTransportTest' --console=plain
```

첫 회귀는 **868건 중 863건 통과, 기존 환경 조건 제외 5건, 실패·오류 0건**이다. 이후 추가한 Redis·워커 연결 검증을 포함해 워커 전송 테스트 7건을 모두 통과했다. 중복 실행된 6건을 제외한 최종 검증 범위는 **869건 중 864건 통과, 제외 5건, 실패·오류 0건**이다. 실행 JAR 빌드도 통과했다.

- 실제 기존 관리자 금지어 API로 등록·수정·비활성화·재활성화한 결과가 상담에 반영된다. 차단 시 임베딩 호출은 0회이며 고객·상담사 전송 및 관리자 평가에 적용된다.
- 금지어 차단 메시지는 전달 순번 없이 저장·감사되며 상대방에게 노출되지 않는다. 동일 메시지 ID 재시도로 중복 메시지·감사 기록을 만들지 않는다.
- 금지어 필터가 검사한 원문을 통과한 뒤 임베딩한다. 고정 차단 표현도 유사도가 낮으면 허용하며, 고정 예외에 해당했던 표현도 유사도가 높으면 차단한다.
- 실제 Testcontainers Redis와 `CounselEmbeddingWorker`를 연결했다. 금지어 차단 시 상담 큐 작업과 모델 호출이 없고, 통과한 메시지는 정규화 후 기존 임베딩 클라이언트에 도달한다. 기존 `chat:jobs`는 유지한다. 모델 응답은 테스트 대역을 사용했다.
- 워커 구성이 누락되면 HTTP로 우회하지 않고 구성 오류를 내며, 워커 오류는 기존 `CHECK_FAILED` 처리를 유지한다.

20:05 KST 로컬 BE를 새 JAR로 재기동해 health UP, 기존 V39 검증·추가 마이그레이션 없음, 기존 금지어 캐시 적재와 새 감사 버전 기록을 확인했다. 이 변경에서 실제 vLLM 문장 정확도나 브라우저 검증을 다시 실행하지는 않았다.

추가로 상담의 기존 기능 재사용 여부를 확인했다.

| 확인 항목 | 결과 |
|---|---|
| 하드코딩된 의미 검사 예외 | 특정 표현이 임베딩 결과를 우회하던 조건까지 제거 |
| 워커/HTTP 선택 | 워커 빈이 없으면 HTTP로 넘어가던 경로를 제거하고 기본값도 worker로 일치 |
| 벡터 차원 | 상담의 숫자 1024 중복을 기존 `EmbeddingClient.DIMENSIONS` 참조로 변경 |
| 인증·대화 접근·임베딩 모델 호출·FE 인증 갱신 | 기존 JWT/CustomUserDetails·ConversationService/Repository·EmbeddingClient·requestWithBaseUrl 재사용 확인 |
| 초기 금지 문장 CSV | DB 초기 등록용이며 실시간 판정 우회 규칙은 없음. 관리자 문장 API로 관리하는 데이터로 유지 |
| 기존 금지어 캐시 | 변경 이벤트가 같은 JVM에만 전달됨. 여러 API 인스턴스 간 정책 변경 전파는 남은 개선 사항이며 이번에는 공통 캐시 코드를 변경하지 않음 |

상세 실행 결과와 첫 회귀 보고서는 Git 제외 경로 `build/counsel-filter-verification/`에 보관했다. 아래는 변경 전 검증 이력이다.

## 최신 develop 통합 후 검증

19:48 KST 기준 feature 브랜치의 기반을 최신 `develop` 커밋 `348a447`로 맞췄다. 기존 미커밋 변경은 stash로 백업한 뒤 복원했다. 양쪽에서 수정한 `.env.example`, `application.yml`, 설정 문서, 보관 프로시저는 자동 병합됐으며 미해결 충돌은 없다.

develop에서 이미 사용한 V37(Outbox)·V38(고객센터 문의)과 상담 V37의 Flyway 버전이 겹쳤다. 상담 SQL 내용은 유지하고 파일을 `V39__integrate_counsel_domain.sql`로 변경했다. 상담 마이그레이션은 여전히 한 파일이며, 인계 테스트도 기존 BE V38 → V39 기준으로 수정했다. develop의 문의·Outbox·미응답 질문 구현과 V37·V38 마이그레이션은 그대로 유지한다.

```powershell
.\gradlew.bat cleanProcessResources cleanProcessTestResources test --tests 'com.ubot.counsel.*' --tests 'com.ubot.conversation.*' --tests 'com.ubot.retention.*' --tests 'com.ubot.auth.*' --tests 'com.ubot.user.*' --tests 'com.ubot.admin.*' --tests 'com.ubot.chat.*' --tests 'com.ubot.embedding.*' --tests 'com.ubot.faq.retrieval.*' --tests 'com.ubot.inquiry.*' --tests 'com.ubot.outbox.*' --tests 'com.ubot.unanswered.*' bootJar --console=plain
```

19:44 KST 결과는 **780건 중 775건 통과, 5건 환경 조건 제외, 실패·오류 0건**이다. 상담 마이그레이션 2건·상담 통합 45건과 develop에 추가된 문의 14건·Outbox 7건을 포함한다. 제외된 것은 기존 의도 분류 분석 1건과 임베딩 라이브 환경이 필요한 4건이다. 실행 JAR에 V37·V38·V39가 각각 한 번씩 포함되는지 확인했다.

이 작업의 로컬 DB에는 이전 상담 V37이 이미 적용돼 있어 전체 DB dump와 Flyway 이력을 먼저 백업했다. 원래 스크립트명·체크섬을 검증하고 해당 상담 이력만 V39로 정렬한 뒤, 한 번의 로컬 기동에서 develop V37·V38을 적용했다. 이후 기본 설정으로 다시 기동해 **현재 버전 39, Flyway 검증 성공, 추가 마이그레이션 없음, health UP**을 확인했다. 임시 out-of-order 옵션은 최종 실행에서 제거했다.

기존 상담 테이블 및 conversations·question_log의 원래 행 내용과 개수를 비교해 상담 5건·메시지 10건을 포함한 데이터 보존을 확인했다. 두 번의 재기동으로 정상 추가된 검사 설정 감사 이력 2건은 구분했으며, 이전 감사 행들도 모두 보존됐다. 백업과 검증 결과는 Git 제외 경로 `build/develop-integration-20261020/`에 있다. 이 이력 정리는 이전 개발용 상담 V37이 적용된 해당 로컬 DB에만 수행했다.

19:48 KST에는 실제 고객·상담사 계정으로 재로그인해 HTTP API를 다시 검증했다. AI에서 이어진 상담과 상담만 있는 대화 모두 기존 목록의 제목·미리보기·상담 표시, 전체 메시지·연결·종료 기록이 이전 브라우저 검증 결과와 일치했다. 담당 상담사의 인계 기록 조회, 상담사의 고객 통합 이력 접근 차단(403), 비회원 접근 차단(401)도 통과했다. 두 계정은 검증 후 로그아웃했으며 새 상담 데이터는 생성하지 않았다. 결과는 Git 제외 경로 `UBot-FE/test-results/counsel-history-live/after-develop.json`에 보관했다. 이번 통합 후 재검증은 API 기준이며, 앞서 수행한 실제 브라우저 검증은 아래 이력에 남긴다.

아래 V37·V38 표기는 각 검증 시점의 이력이다. 현재 상담 배포 버전은 **V39 하나**다.

## V37 단일 마이그레이션 통합 후 검증

V37·V38을 `V37__integrate_counsel_domain.sql` 하나로 합쳤다. 기존 보관 프로시저는 저장소 방식대로 `R__purge_expired_data.sql`에 유지한다. 이전 V38 리소스가 빌드 결과에 남지 않도록 리소스 출력물을 정리한 뒤 아래 회귀 범위를 다시 실행했다.

```powershell
.\gradlew.bat cleanProcessResources cleanProcessTestResources test --tests 'com.ubot.counsel.*' --tests 'com.ubot.conversation.*' --tests 'com.ubot.retention.*' --tests 'com.ubot.auth.*' --tests 'com.ubot.user.*' --tests 'com.ubot.admin.*' --tests 'com.ubot.chat.*' --tests 'com.ubot.embedding.*' --tests 'com.ubot.faq.retrieval.*' bootJar --console=plain
```

19:30 KST 결과는 **697건 중 692건 통과, 5건 환경 조건 제외, 실패·오류 0건**이다. 실행 JAR에는 상담 버전 V37만 포함됐다. 마이그레이션 테스트 2건과 상담 통합 테스트 45건이 포함된다.

- 상담 테이블이 없는 기존 V36 DB에 V37 한 번으로 최종 FK·필수 대화 연결·이력 출처 컬럼을 생성한다. Flyway validate와 두 번째 migrate에서 추가 실행이 없는지 확인했다.
- 기존 V4 상담 스키마는 독립된 테스트 fixture로 준비한다. V36 → V37 인계 시 상담 메시지·벡터·금지 문장·감사 기록을 보존하고, 연결 없는 대화를 복구하며 반복 문장과 인계 사본을 구분하는지 확인했다.
- 기존 통합 테스트에서 이미 적용된 V37 SQL을 직접 재실행하던 검증은 위 실제 이전 스키마 인계 검증으로 대체했다.

이 작업의 로컬 `ubot-feat-200-postgres-1/ubot`에는 합치기 전 V37·V38이 이미 적용돼 있었다. 전체 DB dump와 원래 Flyway 이력을 백업하고, 이전 체크섬 및 최종 스키마를 확인한 뒤 V37 체크섬과 V38 이력 행만 통합본에 맞췄다. 상담 스키마의 모든 테이블 및 기존 conversations·question_log의 행 수·내용 해시가 전후 동일했다. 상담 5건·메시지 10건을 보존했다.

19:32 KST 새 JAR 재기동에서 Flyway 검증 성공, 현재 버전 37, 추가 마이그레이션 없음, Spring Boot 기동 및 `/actuator/health` UP을 확인했다. 로컬 백업·이력 정리 결과는 Git 제외 경로 `build/migration-consolidation-20261020/`에 있다. 이 정리는 개발 중 이 두 버전을 적용했던 해당 로컬 DB에 한정했다.

기존 코드 변경과 영향은 [상담 도메인 문서](how-to/counsel.md)의 해당 표에 정리했다. 아래 내용은 통합 전 구현·브라우저 검증 이력이다.

## 상담 이력 저장·목록·통합 조회 추가 검증

19:06 KST 최종 실행은 **697건 중 692건 통과, 5건 환경 조건 제외, 실패·오류 0건**이다. 아래 명령으로 기존 인증·회원·관리자·AI 채팅·임베딩·FAQ 검색, 대화 목록, 보관 정책을 함께 확인하고 실행 JAR을 빌드했다.

```powershell
.\gradlew.bat test --tests 'com.ubot.counsel.*' --tests 'com.ubot.conversation.*' --tests 'com.ubot.retention.*' --tests 'com.ubot.auth.*' --tests 'com.ubot.user.*' --tests 'com.ubot.admin.*' --tests 'com.ubot.chat.*' --tests 'com.ubot.embedding.*' --tests 'com.ubot.faq.retrieval.*' bootJar --console=plain
```

추가한 검증은 상담만 있는 대화 생성·목록 노출·미리보기, 멱등 재시도와 생성 롤백, 반복 상담·동일 문장 반복을 구분한 사본 중복 제거, 264개 이력 페이지 조회, 타인·상담사·비회원의 통합 이력 접근 차단, 정상 상담과 차단 기록의 보관·삭제, 진행 중 상담 보존, 탈퇴 상담사와 고객 삭제다. 별도 마이그레이션 테스트는 V37 데이터에서 V38로 올려 연결 없는 상담과 원본 대화가 사라진 인계 기록을 보존하는지 확인했다.

전체 회귀 첫 실행에서 시작 직후 상담 유지보수 스케줄과 테스트 초기화 TRUNCATE가 경쟁했다. 상담 통합 테스트의 자동 스케줄러만 mock으로 교체하고 정리 로직은 기존처럼 서비스 직접 호출로 검증한다. 수정 후 위 전체 회귀를 다시 통과했다.

사용자의 브라우저 재검증 요청에 따라 FE lint·TypeScript/Vite 빌드와 `test:counsel:history`, `test:counsel`, `test:counsel:pages`를 모두 다시 실행해 통과했다. 이력 회귀는 134개 항목의 페이지 조회, 원문 중복 방지, 기존 목록·새 로그인 컨텍스트·모바일 복원, 이전 목록 더 보기, 오류 재시도, 지연된 이전 응답 무시, 종료 후 폴링 중단을 포함한다. Pages는 프로덕션 base 경로·단일 BE origin·CSP·JWT-007 재발급을 검증했다.

검증 중 모바일 인기 FAQ 영역이 `최근 대화 · 상담 내역` 버튼을 덮는 문제를 발견했다. 모바일 목록을 `AiPage`의 대화 본문 밖, 같은 레이아웃 안으로 옮기고 좌우 여백을 적용했다. 기존 FAQ 위치 계산과 데스크톱 레이아웃은 유지한다. 테스트의 개발 StrictMode 취소·재실행은 고유 페이지 offset으로 확인하고, 오류 fixture는 명시적인 재시도 시점까지 유지하도록 수정했다.

19:07 KST 로컬 BE를 새 JAR로 재시작하고 V38 및 보관 프로시저 적용을 확인했다. **19:13~19:14 KST에는 기존 실제 고객·상담사 계정으로 새 이력 화면까지 재검증해 통과했다.**

- 두 계정의 실제 로그인 폼과 `/auth/me` 역할 확인. API 응답·JWT mock이나 브라우저 토큰 주입 없이 별도 Edge 컨텍스트를 사용했다.
- AI 문답에서 이어진 상담과 AI 문답 없는 상담을 각각 요청했다. 고객과 상담사가 같은 상담에 연결되고, 실제 메시지 4개가 모두 ALLOWED로 상대 브라우저에 전달됐다.
- 두 상담을 종료한 뒤 실제 로그아웃 버튼을 눌렀다. 새 브라우저 컨텍스트에서 두 계정으로 다시 로그인했으며 고객의 저장된 상담 ID가 없는 상태에서 기존 채팅 목록으로 접근했다.
- 두 대화의 제목·`상담 내역 포함`·마지막 답변 미리보기와 원문을 확인했다. AI 연결 대화는 문답 4개와 상담 메시지 2개, 상담 전용 대화는 메시지 2개가 각각 한 번씩 표시됐다. 연결·종료 표시도 보존됐다.
- 다시 새로고침해도 동일 기록이 복원됐다. 종료 기록을 연 뒤 5초 동안 상담·timeline API 추가 조회가 없었다.
- 390px 모바일에서도 새 실제 로그인 후 목록에서 두 기록을 열 수 있고 가로 넘침이 없었다. 로그아웃하면 기록이 숨겨지고 비회원의 기록 조회·상담 요청은 401이었다.
- 브라우저 오류 0건. 테스트 상담사는 OFFLINE으로 복구하고 진행 중 테스트 상담은 남기지 않았다.

AI 문답 2쌍은 해당 고객의 DB에 저장한 검증용 데이터다. AI 답변 생성 모델 실행을 검증한 것은 아니다. 실제 로그인·상담 메시지 저장·재조회는 실행 중인 기존 BE와 DB를 사용했다. 결과와 화면은 Git 제외 경로 `UBot-FE/test-results/counsel-history-live/result.json` 및 같은 폴더의 PNG에 보관했다. 자격증명은 출력하거나 버전 관리에 넣지 않았다.

## 자동 검증

기존 BE에서 다음을 실행했다.

```powershell
.\gradlew.bat test --tests 'com.ubot.counsel.*' --tests 'com.ubot.auth.*' --tests 'com.ubot.user.*' --tests 'com.ubot.admin.*' --tests 'com.ubot.chat.*' --tests 'com.ubot.embedding.*' --tests 'com.ubot.faq.retrieval.*' bootJar --console=plain
```

총 673건 중 **668건 통과, 5건 환경 조건에 따라 제외**, 실패 0건이다. 제외된 것은 기존 의도 분류 분석 1건과 `EMBEDDING_LIVE_BASE_URL`이 필요한 임베딩 라이브 테스트 4건이다. 이번 상담 자동 테스트는 통합 40건·Redis 전송 6건 모두 통과했다. 임베딩 워커 6건과 기존 인증·채팅·FAQ 검색 회귀도 포함한다.

- 실제 기존 JWT 필터, 현재 DB 역할, 만료·폐기 토큰, 기존 CORS 정책 사용
- 실제 `ConversationService`의 회원 대화 소유권 확인 및 문답 연결
- 이전 이력·화면 FAQ 병합, 260개 이력 페이지 조회, 재배정 접근 회수
- 종료 후 재상담 시 화면 이력과 허용된 사람 상담 메시지 계승
- 이력 저장 실패 시 상담 생성 롤백, 기존 테이블 인계 시 데이터 보존
- 관리자 문장 CRUD·revision·색인 중 수정/삭제·늦은 결과 배제·감사 실패 롤백
- 중복 메시지, 검사 완료 순서, 타임아웃, 미완료 복구, 가드 차단, 최종 전달 순번

FE 전체 lint·TypeScript/Vite 빌드와 브라우저 회귀, 프로덕션 Pages 테스트를 통과했다. 고객 `/ai` 유지·이력 표시·계정 역할·전송 재시도·390px 화면·새로고침·종료 직전 101건 배출·종료 후 GET/POST 중단·상담사 다음 배정과 Pages의 동일 BE origin/CSP/refresh를 검증한다.

## 실제 실행

17:43 KST에 기존 BE(8080) 하나와 FE(5173), 기존 워커·Redis·vLLM BGE-M3로 검증했다. 별도 상담 서버(8081)는 종료했다. V37은 기존 상담 테이블과 예시 50개를 보존했다.

- 실제 고객/상담사 로그인 폼과 별도 브라우저 컨텍스트 사용. API·인증 토큰을 mock으로 대체하지 않았다.
- 고객의 해당 대화에 저장한 테스트 문답 2쌍을 기존 BE 이력 API로 읽었다. 이번 검증은 AI 답변 생성 모델을 실행해 만든 문답이 아니다.
- `/ai`에서 요청 후 WAITING → ACTIVE, 두 계정의 상담 ID 일치.
- 고객과 상담사 모두 이전 고객/AI 기록 4개 표시, 중복 없음, 새로고침 복원.
- 고객·상담사 메시지 모두 실제 워커/vLLM 검사 후 ALLOWED로 양방향 전달.
- 종료·종료 이력 새로고침·새 AI 대화 시작, 비회원 버튼 비활성화·상담 요청 401 확인.
- 상담사 테스트 계정은 OFFLINE으로 복구. 브라우저 오류 0건.

17:46 KST에는 통합 BE의 관리자 API를 실제 로그인과 워커/vLLM으로 검증했다. 문장 등록→색인→가드 평가 유사도 1.0/BLOCKED, 수정 시 이전 벡터 비활성화, 오래된 revision 거부, 재색인, 삭제를 확인했다. 테스트 문장은 삭제하고 임시 ADMIN 권한을 회수했으며 기존 토큰도 403이었다. 원래 예시 50개만 활성 상태로 남겼다.

실행 결과는 Git 제외 경로 `UBot-FE/test-results/counsel-live/result.json`, `UBot-BE/build/admin-safety-live-result.json`에 보관했다. 자격증명은 출력하거나 버전 관리에 넣지 않았다.

통합 전 독립 서비스 프로젝트는 워크스페이스의 `.archive/UBot-Counsel`에 보존했다. 현재 빌드·배포 대상은 기존 BE와 FE 두 프로젝트다.

## 범위

공유 GPU 동시 부하와 운영 환경 정확도·지연 분포, 실제 GitHub Pages 배포는 검증하지 않았다. 기존 메인 FE 번들 크기 경고는 남아 있다. 원격 push·PR·GitHub 이슈 등록은 수행하지 않았으며 이슈 본문은 `docs/issues/200-counsel-integration.md`에 준비했다.
