-- Reviewed expressions only; match the same provider/model/dimensions/profile version
-- as faq_embeddings. Do not populate this table with arbitrary generated answers.
CREATE TABLE faq_cache_question (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    faq_id BIGINT NOT NULL REFERENCES faq(id) ON DELETE CASCADE,
    profile_id BIGINT NOT NULL REFERENCES embedding_profiles(profile_id),
    question TEXT NOT NULL CHECK (length(trim(question)) > 0),
    vector vector(1024) NOT NULL,
    faq_version INTEGER NOT NULL,
    faq_question TEXT NOT NULL,
    faq_answer TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (faq_id, question, profile_id)
);
CREATE INDEX faq_cache_question_profile_faq_idx ON faq_cache_question(profile_id, faq_id);
