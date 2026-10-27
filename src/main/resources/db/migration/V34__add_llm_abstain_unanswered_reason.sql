ALTER TABLE unanswered_questions
    DROP CONSTRAINT unanswered_questions_reason_check,
    ADD CONSTRAINT unanswered_questions_reason_check
        CHECK (reason IN ('NO_FAQ', 'INSUFFICIENT_FAQ', 'LLM_ABSTAIN'));
