-- V31은 develop에 적용됐으므로 별도 마이그레이션으로 확장함
-- request_context: 접수 때 좌표·지역. 종료와 같은 트랜잭션에서 NULL로 지움
-- result_payload: 성공 답변의 질문 로그 ID·매장 지도
-- heartbeat_at: 실행 중 Worker가 갱신. 오래 끊기면 다른 Worker가 넘겨받음
-- updated_at: 상태 변경 시각. heartbeat 갱신은 포함 안 함
ALTER TABLE chat_jobs
    ADD COLUMN request_context JSONB     CHECK (request_context IS NULL OR jsonb_typeof(request_context) = 'object'),
    ADD COLUMN result_payload  JSONB     CHECK (result_payload IS NULL OR jsonb_typeof(result_payload) = 'object'),
    ADD COLUMN heartbeat_at    TIMESTAMP,
    ADD COLUMN updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_chat_jobs_heartbeat_at_running
    ON chat_jobs ((COALESCE(heartbeat_at, started_at))) WHERE execution_status = 'RUNNING';
CREATE INDEX idx_chat_jobs_job_id_queued
    ON chat_jobs (job_id) WHERE execution_status = 'QUEUED';
