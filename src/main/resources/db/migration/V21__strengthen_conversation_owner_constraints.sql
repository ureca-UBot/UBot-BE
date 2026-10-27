ALTER TABLE conversations DROP CONSTRAINT conversations_check;
ALTER TABLE conversations ADD CONSTRAINT conversations_type_user_check
    CHECK ((type = 'GUEST' AND user_id IS NULL) OR (type = 'MEMBER' AND user_id IS NOT NULL));

ALTER TABLE question_log ADD CONSTRAINT question_log_owner_check
    CHECK (user_id IS NOT NULL OR conversation_id IS NOT NULL) NOT VALID;
