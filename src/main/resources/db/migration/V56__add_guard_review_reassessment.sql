-- V55의 검사 이력과 연결된 관리자 보류 재검토 결과를 보존합니다.
-- 원래 guard_review_checks의 판정은 변경하지 않고 분석 항목에 재검토 결과만 따로 보존합니다.
ALTER TABLE guard_review_items ADD COLUMN reassessment JSONB
    CHECK (reassessment IS NULL OR jsonb_typeof(reassessment)='object');
CREATE INDEX idx_guard_review_items_reassessment
    ON guard_review_items(check_id,id DESC) WHERE guard_outcome='REVIEW' AND run_id IS NOT NULL;
