# ADR: 미응답 질문 저장을 Outbox 기반 비동기 처리로 전환

- 상태: 채택 (#225)
- 날짜: 2026-10-20
- 작성: 유민하

## 1. 배경

채팅 답변 Worker는 FAQ 근거를 찾지 못한 하위 질문을 미응답 질문으로 저장합니다. 지금까지는 답변 처리 안에서 바로 저장했습니다.

```text
검색 실패 → 미응답 저장(질문 임베딩 → 가까운 묶음 찾기 → 저장 → 묶음 중심 벡터 갱신) → 답변 작업 종료
```

미응답 저장은 사용자 답변과 관계없는 관리자용 데이터인데, 임베딩 호출과 벡터 검색 때문에 답변 작업 시간에 그대로 더해졌습니다. 저장이 실패하면 경고 로그만 남고 그 질문은 사라졌습니다.

## 2. 결정

답변 처리 중에는 공용 대기열 `outbox_events`에 `UNANSWERED_QUESTION` 작업 한 행만 넣고 바로 답변을 이어 갑니다. 임베딩과 묶기는 `OutboxWorker`가 작업 종류별 처리기(`UnansweredOutboxHandler`)에 넘겨 따로 처리합니다.

```text
검색 실패 → outbox_events에 INSERT(수 ms) → 답변 작업 종료
                      ↓ 2초마다
           OutboxWorker → event_type별 처리기 → 미응답 저장 → 대기열 행 삭제
```

대기열은 여러 비동기 작업이 같이 쓰는 공용 테이블입니다. 다음 작업으로 FAQ 등록 후 자동 재검증을 같은 대기열에 붙일 예정이라, 처음부터 공용으로 만들었습니다.

```text
outbox_events
  event_type   작업 종류 (지금은 UNANSWERED_QUESTION, CHECK로 허용 값 관리)
  dedup_key    중복 방지 키 (작업 종류 + 업무 키, 유니크)
  payload      작업에 필요한 값 (JSONB)
  retry_count  실패 횟수
```

새 작업을 붙일 때는 `OutboxEventType`에 값을 하나 더하고, 마이그레이션으로 `event_type` CHECK를 넓히고, `OutboxEventHandler` 구현을 하나 추가합니다.

| 항목 | 값 | 이유 |
|---|---|---|
| 대기열 단위 | 하위 질문 1개 = 1행, `dedup_key`=`UNANSWERED_QUESTION:{attempt_id}:{sub_question_index}` | 질문 분리(#228) 후 하위 질문마다 따로 저장하므로. 같은 하위 질문이 두 번 들어와도 한 번만 처리 |
| 처리 주기 | `OUTBOX_POLL_DELAY_MS=2000` | 관리자가 나중에 보는 데이터라 몇 초 지연은 문제없음. 실측 평균 1.1초 |
| 동시 처리 | `FOR UPDATE SKIP LOCKED` | Worker 서버가 여러 대여도 한 행은 한 서버만 처리 |
| 실패 처리 | 행을 남기고 `retry_count + 1`, 5회 실패하면 더 처리하지 않음 | 일시 장애는 다음 주기에 복구, 계속 실패하는 행이 대기열을 막지 않게 |
| 실행 위치 | Worker 역할(`APP_ROLE`=`worker`·`all`) 서버 | 답변 Worker와 같은 서버에서 처리 |

## 3. 검토한 대안

| 대안 | 채택하지 않은 이유 |
|---|---|
| 동기 저장 유지 | 답변 시간에 임베딩 시간이 더해지고, 실패하면 질문이 사라짐 |
| `@Async`로 별도 스레드에서 저장 | 서버가 재시작되면 메모리의 작업이 사라짐. 실패 재시도 기록이 남지 않음 |
| Redis Stream 등 메시지 큐 | 미응답은 PostgreSQL에 저장하므로 DB 트랜잭션과 묶을 수 있는 DB 테이블이 더 단순함. 운영할 구성 요소도 늘지 않음 |
| 작업마다 전용 Outbox 테이블 | 처음에는 `unanswered_question_outbox` 전용 테이블로 만들었음. FAQ 자동 재검증이 두 번째 비동기 작업으로 정해져서, 테이블·Worker·재시도 로직을 작업마다 새로 만들지 않도록 공용으로 바꿈 |

### 공용으로 바꾸면서 생긴 비용

| 잃은 것 | 대신 하는 일 |
|---|---|
| `attempt_id` FK, `reason` CHECK 같은 DB 검사 | payload는 JSON이라 DB가 검사하지 못함. 처리기가 값을 읽어 기존 저장 로직(`createUnansweredQuestion`)으로 넘기므로, 잘못된 값은 거기서 실패하고 재시도 횟수만 늘어남 |
| 답변 기록이 지워지면 대기열 행도 같이 지우는 CASCADE | 보관 기간 삭제로 답변 기록이 먼저 지워지면, 해당 행은 저장할 때 FK 오류로 5번 실패한 뒤 멈춤. 처리 주기가 2초라 실제로 일어날 가능성은 거의 없음 |
| 컬럼만 보고 내용 확인 | 운영 중에는 `payload->>'attemptId'` 같은 JSON 조회로 확인 |

## 4. 테스트 방법

### 4.1 무엇을 비교하나

같은 로컬 DB를 두 벌 복사해서, develop(동기 저장) 서버와 #225(Outbox) 서버에 각각 같은 질문을 보냅니다.

- 질문: FAQ에 없는 통신 관련 질문 20개(예: "우주정거장 로밍 신청 방법 알려줘")를 2회씩, 총 40회
- 질문은 하나씩 순서대로 보냅니다(동시 요청 아님). 시작 전에 측정에서 빼는 예열 질문을 1개 보냅니다.
- 측정 스크립트: `tools/perf/unanswered_outbox_perf.py`

| 지표 | 계산 방법 | 의미 |
|---|---|---|
| 서버 작업 시간 | `chat_jobs.finished_at - started_at` (FAIL·SUCCESS별 중앙값) | Worker가 답변 하나를 처리하는 데 걸린 시간. 미응답 저장 비용이 여기에 포함되는지 확인 |
| 사용자 체감 시간 | 질문 전송부터 작업 결과 조회까지 (클라이언트 측정) | 실제 사용자가 기다리는 시간 |
| 저장 지연 | `unanswered_questions.created_at - chat_jobs.finished_at` | 음수면 답변이 끝나기 전에 저장(동기), 양수면 끝난 뒤 저장(비동기) |
| 저장 누락 | 저장된 미응답 건수, 대기열에 남은 행, 재시도를 다 쓴 행 | Outbox로 바꿔도 빠지는 질문이 없는지 확인 |

### 4.2 직접 돌려 보기 (PowerShell, `UBot-BE` 폴더 기준)

준비: `.env`가 develop 기준으로 최신이고, FAQ 시드와 벡터가 적재돼 있어야 합니다. `OLLAMA_GPU=true`인지 확인합니다. Ollama가 CPU로 돌면 시간이 10배 이상 늘어서 비교가 안 됩니다.

1. 컨테이너를 띄우고, 백엔드 서버는 끈 상태에서 DB를 두 벌 복사합니다.

   ```powershell
   .\tools\ubot.ps1 up
   docker compose exec -T postgres psql -U ubot -d postgres -c "CREATE DATABASE ubot_perf_sync TEMPLATE ubot;"
   docker compose exec -T postgres psql -U ubot -d postgres -c "CREATE DATABASE ubot_perf_outbox TEMPLATE ubot;"
   ```

2. develop 코드를 다른 폴더에 꺼내 동기 저장 서버를 띄웁니다(터미널 1).

   ```powershell
   git fetch origin
   git worktree add ..\UBot-BE-dev origin/develop
   Copy-Item .env ..\UBot-BE-dev\.env
   cd ..\UBot-BE-dev
   $env:POSTGRES_DB = 'ubot_perf_sync'
   .\gradlew.bat bootRun
   ```

3. 이 브랜치 폴더에서 측정합니다(터미널 2). 관리자 비밀번호를 물어봅니다.

   ```powershell
   [Console]::OutputEncoding = [Text.Encoding]::UTF8
   $env:PYTHONIOENCODING = 'utf-8'
   python -X utf8 .\tools\perf\unanswered_outbox_perf.py sync --db ubot_perf_sync --email admin@admin.com
   ```

4. 터미널 1에서 서버를 끄고(Ctrl+C), 이 브랜치로 Outbox 서버를 띄웁니다.

   ```powershell
   cd ..\UBot-BE
   $env:POSTGRES_DB = 'ubot_perf_outbox'
   .\gradlew.bat bootRun
   ```

5. 터미널 2에서 같은 측정을 합니다.

   ```powershell
   python -X utf8 .\tools\perf\unanswered_outbox_perf.py outbox --db ubot_perf_outbox --email admin@admin.com
   ```

6. 정리합니다.

   ```powershell
   git worktree remove ..\UBot-BE-dev
   docker compose exec -T postgres psql -U ubot -d postgres -c "DROP DATABASE ubot_perf_sync WITH (FORCE);"
   docker compose exec -T postgres psql -U ubot -d postgres -c "DROP DATABASE ubot_perf_outbox WITH (FORCE);"
   ```

- 회차 수는 `--rounds`로 바꿉니다(기본 2회, 40건). 서버를 띄운 터미널의 `$env:POSTGRES_DB`는 그 터미널에만 적용됩니다.
- 출력 예:

  ```text
  == outbox (40회)
  사용자 체감 시간 중앙값: 2041ms
  서버 작업 시간 FAIL: 32건, 중앙값 640ms, 평균 666ms
  서버 작업 시간 SUCCESS: 8건, 중앙값 2069ms, 평균 1987ms
  미응답 저장: INSUFFICIENT_FAQ 34건, LLM_ABSTAIN 4건
  답변 종료 → 미응답 저장 시간: 중앙값 1092ms, 최대 1960ms
  대기열에 남은 행: 0개, 재시도 횟수 소진: 0개
  ```

## 5. 결과 (2026-10-20, 로컬 PC, Ollama GPU)

아래 수치는 공용 테이블로 바꾸기 전(전용 `unanswered_question_outbox`)에 측정했습니다. 답변 경로에서 하는 일은 두 구조 모두 "대기열에 INSERT 한 번"으로 같아서 결과가 달라지지 않을 것으로 보지만, 공용 구조로 다시 재지는 않았습니다.

| 지표 | develop (동기 저장) | Outbox | 차이 |
|---|---|---|---|
| 서버 작업 시간 FAIL (중앙값) | 729ms (32건) | 640ms (32건) | **−89ms (−12%)** |
| 서버 작업 시간 SUCCESS (중앙값) | 2,030ms (8건) | 2,069ms (8건) | 거의 같음 |
| 사용자 체감 시간 (중앙값) | 약 2.1초 | 약 2.0초 | 거의 같음 |
| 저장 지연 (중앙값) | −59ms (답변 종료 전) | +1,092ms (최대 1,960ms) | 저장이 답변 뒤로 이동 |
| 저장된 미응답 | 40건 | 38건 | 아래 설명 |
| 대기열에 남은 행 / 재시도 소진 | 해당 없음 | 0 / 0 | 누락 없음 |

## 6. 결과의 의미

- **FAIL −89ms가 미응답 저장 비용입니다.** FAIL은 FAQ를 못 찾아 LLM을 부르지 않고 끝나는 경로라, 작업 시간에서 미응답 저장(임베딩 + 묶음 검색 + 저장) 비중이 큽니다. 이 비용이 응답 경로에서 빠져 작업 시간이 12% 줄었습니다.
- **SUCCESS는 차이가 없습니다.** LLM 답변 생성(약 2초)이 대부분이라 수십 ms 차이는 오차 범위에 묻힙니다. 여기서 저장되는 미응답은 LLM이 답변 불가(ABSTAIN)로 판단한 경우뿐입니다.
- **사용자 체감 시간은 거의 같습니다.** 체감 시간에는 작업 대기(Redis Stream에서 Worker가 꺼내는 시간)와 결과 조회 간격이 들어가서 89ms 차이가 드러나지 않습니다. 이 전환의 효과는 "사용자가 더 빨리 받는다"보다 **답변 Worker가 같은 시간에 더 많은 질문을 처리하고, 미응답 저장 실패가 답변에 영향을 주지 않는다**는 쪽입니다.
- **저장 지연 1.1초는 의도한 결과입니다.** 저장 시점이 답변 종료 전(−59ms)에서 종료 후(+1.1초)로 옮겨졌습니다. 처리 주기가 2초라 최대 약 2초 늦어지며, 관리자 화면에서 보는 데이터라 문제없습니다.
- **저장 건수 40 대 38은 누락이 아닙니다.** FAIL로 저장된 `INSUFFICIENT_FAQ`는 양쪽 모두 34건으로 같습니다. 차이는 `LLM_ABSTAIN`(6건 대 4건)인데, 같은 질문에도 LLM이 매번 답변 불가 여부를 조금씩 다르게 판단해서 생긴 차이입니다. Outbox 쪽은 대기열에 남은 행과 저장 실패 로그가 모두 0이었습니다.

## 7. 한계

- 질문을 하나씩 보낸 결과입니다. 동시에 여러 질문이 들어오면 동기 저장은 임베딩 호출이 답변 Worker를 붙잡아 차이가 더 커질 것으로 예상하지만, 측정하지 않았습니다.
- 로컬 PC 기준이라 EC2 수치와 다를 수 있습니다.
- 재시도 5회는 근거 수치가 아니라 기본값입니다. 일시 장애(DB 연결 끊김 등)를 재현해 회복까지 몇 번이 필요한지 확인하지 않았습니다.
- 임베딩 서버가 죽은 상황은 이 비교로 볼 수 없습니다. 그때는 FAQ 검색 자체가 실패해서 미응답 저장 단계까지 가지 않습니다.

## 8. 남은 과제

- 베타 테스트 때 운영 지표로 대기열 행 수, 재시도 소진 행 수, 저장 지연을 봅니다.
- 동시 요청(예: 5·10명 동시) 조건에서 다시 측정합니다.
- FAQ 등록 후 자동 재검증을 같은 대기열의 두 번째 작업으로 붙입니다(다음 이슈).
- 출력 가드에서 넘기는 미응답(#233 `source=OUTPUT_GUARD`)도 같은 대기열을 쓰게 할지 정합니다.
