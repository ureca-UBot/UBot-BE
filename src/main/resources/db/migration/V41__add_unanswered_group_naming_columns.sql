ALTER TABLE unanswered_question_groups
    ADD COLUMN topic_name            VARCHAR(100),
    ADD COLUMN suggested_category_id BIGINT REFERENCES faq_category (id) ON DELETE SET NULL,
    ADD COLUMN named_question_count  INTEGER CHECK (named_question_count >= 1),
    ADD COLUMN naming_attempted_at   TIMESTAMP;

CREATE INDEX idx_unanswered_question_groups_suggested_category_id
    ON unanswered_question_groups (suggested_category_id);
