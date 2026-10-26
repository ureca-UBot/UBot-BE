-- 기존 여섯 테이블을 재사용합니다. run_id가 없는 항목은 분석 전 수집 원본입니다.
-- V48는 변경하지 않고 기존 분석 기록과 후보를 보존합니다.
ALTER TABLE guard_review_items ALTER COLUMN run_id DROP NOT NULL;
ALTER TABLE guard_review_items
    ADD COLUMN capture_id UUID,
    ADD COLUMN origin_item_id BIGINT REFERENCES guard_review_items(id) ON DELETE SET NULL,
    ADD COLUMN raw_response_snapshot TEXT,
    ADD COLUMN final_answer_snapshot TEXT,
    ADD COLUMN guard_outcome VARCHAR(16)
        CHECK (guard_outcome IN ('PENDING','PASS','FILTERED','REJECTED','ERROR')),
    ADD COLUMN guard_reasons JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(guard_reasons)='array'),
    ADD COLUMN guard_checked_at TIMESTAMP,
    ADD CONSTRAINT ck_guard_review_items_capture
        CHECK (run_id IS NOT NULL OR (capture_id IS NOT NULL AND guard_outcome IS NOT NULL)),
    ADD CONSTRAINT ck_guard_review_items_rejected_answer
        CHECK (guard_outcome IS DISTINCT FROM 'REJECTED' OR final_answer_snapshot IS NULL);

CREATE UNIQUE INDEX uq_guard_review_items_capture ON guard_review_items(capture_id) WHERE run_id IS NULL;
CREATE INDEX idx_guard_review_items_captured ON guard_review_items(created_at,id) WHERE run_id IS NULL;
