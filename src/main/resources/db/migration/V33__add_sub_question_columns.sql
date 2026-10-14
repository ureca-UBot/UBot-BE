-- 질문 분리·멀티턴을 위해 질문 로그와 미답변 질문을 하위 질문 단위로 저장합니다.
-- 하위 질문은 판정 LLM이 나누거나 맥락을 넣어 다시 쓴 질문(턴당 최대 3개)입니다.
-- 판정을 거치지 않은 질문은 원문을 하위 질문 1개(sub_question_index = 0)로 저장하므로 기존 행과 구조가 같습니다.

-- question_log 한 행은 하위 질문 하나입니다. user_question은 화면에 보여 줄 원문으로 같은 턴의 모든 행에 같은 값이 들어가고,
-- sub_question은 검색·랭킹에 쓴 질문입니다. 기존 행은 sub_question이 NULL이며 읽을 때 user_question으로 대신합니다.
-- attempt_id는 같은 턴의 행을 묶습니다. 시도 로그(3개월)가 질문 로그(3년)보다 먼저 지워지므로 연결만 끊습니다.
ALTER TABLE question_log
    ADD COLUMN attempt_id         BIGINT REFERENCES answer_attempts_history (attempt_id) ON DELETE SET NULL,
    ADD COLUMN sub_question       TEXT,
    ADD COLUMN sub_question_index SMALLINT NOT NULL DEFAULT 0 CHECK (sub_question_index BETWEEN 0 AND 2);

-- 시도 로그를 지울 때 SET NULL 대상을 찾는 데 씁니다. 없으면 삭제 배치마다 question_log 전체를 읽습니다.
CREATE INDEX idx_question_log_attempt_id ON question_log (attempt_id);

-- 같은 시도의 같은 하위 질문이 두 번 저장되지 않게 합니다. 시도와 연결되지 않은 행(기존 행, 시도 로그가 지워진 행)은 제외합니다.
CREATE UNIQUE INDEX uq_question_log_attempt_sub_question
    ON question_log (attempt_id, sub_question_index)
    WHERE attempt_id IS NOT NULL;

-- 미답변 질문 한 행은 실패한 하위 질문 하나입니다. 하위 질문 텍스트를 테이블마다 같은 이름으로 부르도록 컬럼 이름을 바꿉니다.
-- 한 시도에서 하위 질문이 여러 개 실패할 수 있으므로 시도당 한 건이던 UNIQUE를 하위 질문 순번까지 넓힙니다.
ALTER TABLE unanswered_questions RENAME COLUMN question TO sub_question;

ALTER TABLE unanswered_questions
    ADD COLUMN sub_question_index SMALLINT NOT NULL DEFAULT 0 CHECK (sub_question_index BETWEEN 0 AND 2),
    DROP CONSTRAINT unanswered_questions_attempt_id_key,
    ADD CONSTRAINT uq_unanswered_questions_attempt_sub_question UNIQUE (attempt_id, sub_question_index);
