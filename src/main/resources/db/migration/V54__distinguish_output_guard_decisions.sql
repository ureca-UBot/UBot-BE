-- 기존 수집 원문과 여섯 테이블을 유지합니다. 기존 행의 검사 단계를 추측해서 채우지 않습니다.
ALTER TABLE guard_review_items
    DROP CONSTRAINT guard_review_items_guard_outcome_check,
    ADD CONSTRAINT ck_guard_review_items_guard_outcome
        CHECK (guard_outcome IN ('PENDING','PASS','FILTERED','REJECTED','REVIEW','SKIPPED','ERROR')),
    ADD COLUMN guard_stage VARCHAR(16)
        CHECK (guard_stage IN ('CODE','LLM','DISABLED','UNKNOWN'));

ALTER TABLE guard_review_items
    DROP CONSTRAINT ck_guard_review_items_rejected_answer,
    ADD CONSTRAINT ck_guard_review_items_rejected_answer
        CHECK (guard_outcome NOT IN ('REJECTED','REVIEW') OR final_answer_snapshot IS NULL);
