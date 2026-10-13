-- Reviewed expressions only; match the same provider/model/dimensions/profile version
-- as faq_embeddings. Do not populate this table with arbitrary generated answers.
CREATE TABLE faq_cache_question (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    faq_id BIGINT NOT NULL REFERENCES faq(id) ON DELETE CASCADE,
    profile_id BIGINT NOT NULL REFERENCES embedding_profiles(profile_id),
    question TEXT NOT NULL CHECK (length(trim(question)) > 0),
    -- Keep this expression in sync with Java FaqQuestionNormalizer.VERSION=1.
    normalized_question TEXT GENERATED ALWAYS AS (
        btrim(regexp_replace(regexp_replace(regexp_replace(
            normalize(question, NFC), U&'[ \0009\00A0\3000]+', ' ', 'g'),
            '[?？]+', '?', 'g'), '[!！]+', '!', 'g'))
    ) STORED NOT NULL,
    -- Only the Java registration API marks a normalized embedding as version 1.
    normalization_version INTEGER NOT NULL DEFAULT 0,
    vector vector(1024) NOT NULL,
    faq_version INTEGER NOT NULL,
    faq_question TEXT NOT NULL,
    faq_answer TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT faq_cache_question_normalized_unique UNIQUE (faq_id, normalized_question, profile_id)
);
CREATE INDEX faq_cache_question_profile_faq_idx ON faq_cache_question(profile_id, faq_id);
