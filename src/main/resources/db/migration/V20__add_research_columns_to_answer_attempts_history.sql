ALTER TABLE answer_attempts_history
    ADD COLUMN source_attempt_id BIGINT NULL REFERENCES answer_attempts_history (attempt_id),
    ADD COLUMN intent VARCHAR(20) NULL CHECK (intent IN ('GENERAL', 'STORE_DATA', 'USER_DATA')),
    ADD CONSTRAINT answer_attempts_history_research_intent_check
        CHECK ((source_attempt_id IS NULL) = (intent IS NULL));

CREATE UNIQUE INDEX uq_answer_attempts_history_source_intent
    ON answer_attempts_history (source_attempt_id, intent)
    WHERE source_attempt_id IS NOT NULL;
