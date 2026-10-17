-- 하위 질문 순번을 프롬프트와 같은 1, 2, 3으로 통일합니다.
-- V34가 적용된 기존 데이터(0, 1, 2)도 원문·검색어·FAQ 연결을 유지한 채 옮깁니다.
-- 0→1을 갱신할 때 기존 1번 행과 충돌하지 않도록 UNIQUE를 잠시 제거합니다.
-- Flyway의 단일 트랜잭션과 테이블 잠금 안에서 변환·제약 복원을 마칩니다.
LOCK TABLE question_log, unanswered_questions IN ACCESS EXCLUSIVE MODE;

DROP INDEX uq_question_log_attempt_sub_question;
ALTER TABLE question_log
    DROP CONSTRAINT question_log_sub_question_index_check;

ALTER TABLE unanswered_questions
    DROP CONSTRAINT uq_unanswered_questions_attempt_sub_question,
    DROP CONSTRAINT unanswered_questions_sub_question_index_check;

UPDATE question_log
SET sub_question_index = sub_question_index + 1;

UPDATE unanswered_questions
SET sub_question_index = sub_question_index + 1;

ALTER TABLE question_log
    ALTER COLUMN sub_question_index SET DEFAULT 1,
    ADD CONSTRAINT question_log_sub_question_index_check
        CHECK (sub_question_index BETWEEN 1 AND 3);

CREATE UNIQUE INDEX uq_question_log_attempt_sub_question
    ON question_log (attempt_id, sub_question_index)
    WHERE attempt_id IS NOT NULL;

ALTER TABLE unanswered_questions
    ALTER COLUMN sub_question_index SET DEFAULT 1,
    ADD CONSTRAINT unanswered_questions_sub_question_index_check
        CHECK (sub_question_index BETWEEN 1 AND 3),
    ADD CONSTRAINT uq_unanswered_questions_attempt_sub_question
        UNIQUE (attempt_id, sub_question_index);
