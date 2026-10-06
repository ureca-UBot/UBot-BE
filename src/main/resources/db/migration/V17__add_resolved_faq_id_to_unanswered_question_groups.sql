ALTER TABLE unanswered_question_groups ADD COLUMN resolved_faq_id BIGINT REFERENCES faq (id);
