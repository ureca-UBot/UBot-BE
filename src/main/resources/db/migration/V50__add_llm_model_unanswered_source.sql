-- 칸 형식 답변에서 모델이 ABSTAIN·CONFLICT로 판단한 하위 질문을 미응답으로 저장합니다.
ALTER TABLE unanswered_questions
    DROP CONSTRAINT unanswered_questions_reason_check,
    ADD CONSTRAINT unanswered_questions_reason_check
        CHECK (reason IN ('NO_FAQ', 'INSUFFICIENT_FAQ', 'LLM_ABSTAIN', 'LLM_CONFLICT')),
    DROP CONSTRAINT unanswered_questions_source_check,
    ADD CONSTRAINT unanswered_questions_source_check
        CHECK (source IN ('RETRIEVAL', 'OUTPUT_GUARD', 'LLM_MODEL'));
