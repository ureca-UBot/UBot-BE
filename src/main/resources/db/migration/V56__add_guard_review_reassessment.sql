-- V55의 검사 이력과 연결된 관리자 보류 재검토 결과를 보존합니다.
-- 원래 guard_review_checks의 판정은 변경하지 않고 분석 항목에 재검토 결과만 따로 보존합니다.
ALTER TABLE guard_review_items ADD COLUMN reassessment JSONB
    CHECK (reassessment IS NULL OR jsonb_typeof(reassessment)='object');
CREATE INDEX idx_guard_review_items_reassessment
    ON guard_review_items(check_id,id DESC) WHERE guard_outcome='REVIEW' AND run_id IS NOT NULL;

-- 예약 분석의 출처 선택 설정입니다. 실시간 가드의 판정 기준은 변경하지 않습니다.
ALTER TABLE guard_review_settings ADD COLUMN analysis_source VARCHAR(32) NOT NULL DEFAULT 'GENERATED_ANSWER'
    CHECK (analysis_source IN ('GENERATED_ANSWER','INPUT_LLM','OUTPUT_LLM','INPUT_CODE','OUTPUT_CODE'));

-- 호출되지 않은 검사들은 시도마다 한 개의 요약으로 보관합니다.
ALTER TABLE guard_review_checks ADD COLUMN skipped_origins JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(skipped_origins)='array'
        AND skipped_origins <@ '["INPUT_LLM","OUTPUT_CODE","OUTPUT_LLM"]'::jsonb);
CREATE INDEX idx_guard_review_checks_created ON guard_review_checks(created_at,id);
CREATE INDEX idx_guard_review_checks_origin_created ON guard_review_checks(origin,created_at,id);
