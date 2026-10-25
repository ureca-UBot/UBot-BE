-- V57 is reserved by PR #300. These settings do not change live guard decisions.
ALTER TABLE guard_review_settings ADD COLUMN analysis_source VARCHAR(32) NOT NULL DEFAULT 'GENERATED_ANSWER'
    CHECK (analysis_source IN ('GENERATED_ANSWER','INPUT_LLM','OUTPUT_LLM','INPUT_CODE','OUTPUT_CODE'));

-- One summary per attempt replaces up to three synthetic NOT_CALLED records.
ALTER TABLE guard_review_checks ADD COLUMN skipped_origins JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(skipped_origins)='array'
        AND skipped_origins <@ '["INPUT_LLM","OUTPUT_CODE","OUTPUT_LLM"]'::jsonb);
CREATE INDEX idx_guard_review_checks_created ON guard_review_checks(created_at,id);
CREATE INDEX idx_guard_review_checks_origin_created ON guard_review_checks(origin,created_at,id);
