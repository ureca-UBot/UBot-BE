-- 벡터는 V23의 Profile 테이블(faq_embeddings, unanswered_question_embeddings, unanswered_group_embeddings)에만 저장합니다.
-- 컬럼을 지우면 그 컬럼의 인덱스(idx_faq_vector)도 함께 지워집니다.
ALTER TABLE faq DROP COLUMN vector;
ALTER TABLE old_faq DROP COLUMN vector;
ALTER TABLE unanswered_questions DROP COLUMN question_vector;
ALTER TABLE unanswered_question_groups DROP COLUMN centroid;
