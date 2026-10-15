-- Old soft-deleted variants have no usable version history. Remove them before
-- dropping their lifecycle columns; embeddings are deleted by the FK cascade.
DELETE FROM faq_question_variants WHERE deleted_at IS NOT NULL;

ALTER TABLE faq_question_variants
    DROP COLUMN deleted_at,
    DROP COLUMN updated_at;
