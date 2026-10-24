# LLM Timeout Rate High (`UBotLlmTimeoutRateHigh`)

최근 `ALERT_LLM_TIMEOUT_WINDOW` 동안 기존 답변 생성·질문 분류 호출(`client="chat"`)이 `ALERT_LLM_TIMEOUT_MIN_CALLS`건 이상이고, 그중 시간 초과 비율이 `ALERT_LLM_TIMEOUT_RATIO`를 넘는 상태가 `ALERT_LLM_TIMEOUT_FOR` 동안 이어졌습니다. 가드 호출(`client="guard"`)은 분자·분모·최소 호출 수에서 제외합니다.

## 영향

- 답변이 LLM-004로 실패하거나, 전체 실행 제한(180초)을 넘겨 CHAT-016으로 끝납니다.

## 확인

1. 시간 초과가 답변(`kind="answer"`)인지 판정(`kind="judgment"`)인지: `llm_requests_seconds_count{client="chat",outcome="timeout"}`. 가드는 `client="guard"`로 따로 조회합니다.
2. 응답 시간: `llm_requests_seconds` p95가 `LLM_READ_TIMEOUT`에 붙어 있는지
3. 모델 서버 상태: NLB 대상 그룹 health, vLLM 로그(메모리 부족·재시작), GPU 사용률
4. 동시 호출: `llm_concurrency_in_use{client="chat"}`가 상한에 붙어 있고 `llm_concurrency_waiting{client="chat"}`이 계속 0보다 큰지. 같은 서버를 공유하는 가드의 실제 점유·대기도 포함됩니다.
5. 같은 시각 GPU 대수 변화(스폿 회수, 스케일 인)

## 조치

- 모델 서버가 멈췄거나 메모리 부족이면 vLLM 컨테이너를 다시 시작합니다. 모델을 다시 올리는 데 몇 분 걸립니다.
- 요청이 몰린 것이면 GPU를 늘립니다(스폿 ASG). 늘릴 수 없으면 `CHAT_WORKER_CONCURRENCY`·`LLM_MAX_CONCURRENT`를 낮춰 한 GPU에 몰리는 호출을 줄입니다.
- 특정 질문 유형만 느리면 프롬프트 길이·도구 호출 반복을 확인합니다.

## 복구 확인

1. 시간 초과 비율이 기준 아래로 내려감
2. `llm_requests_seconds` p95가 평소 수준으로 돌아옴
3. 새 테스트 질문이 `SUCCEEDED`로 끝남

## 금지

- 원인 확인 없이 `LLM_READ_TIMEOUT`만 크게 늘리는 것. 전체 실행 제한(180초)과 사용자 대기 시간이 함께 늘어납니다.
