-- 보관 기간이 지난 데이터를 지우기 위한 FK 동작, 인덱스, 탈퇴 회원 분리 보관 테이블입니다.
-- 삭제 자체는 R__purge_expired_data.sql의 purge_expired_data()가 수행합니다.

-- 같은 묶음(부모와 함께 생기고 사라지는 데이터)은 부모를 지우면 함께 지웁니다.
ALTER TABLE faq_log
    DROP CONSTRAINT faq_log_question_log_id_fkey,
    ADD CONSTRAINT faq_log_question_log_id_fkey
        FOREIGN KEY (question_log_id) REFERENCES question_log (id) ON DELETE CASCADE;

ALTER TABLE question_log
    DROP CONSTRAINT question_log_conversation_id_fkey,
    ADD CONSTRAINT question_log_conversation_id_fkey
        FOREIGN KEY (conversation_id) REFERENCES conversations (conversation_id) ON DELETE CASCADE,
    DROP CONSTRAINT question_log_user_id_fkey,
    ADD CONSTRAINT question_log_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE;

-- FAQ 모니터링은 question_log·faq_log를 3년 집계하므로 질문 로그 행은 3년 남깁니다.
-- 3개월이 지나면 질문 원문과 회원·대화 연결만 비우고(익명화) anonymized_at을 기록합니다.
-- 익명화된 행은 user_id와 conversation_id가 모두 비므로 소유자 CHECK에 예외를 둡니다.
ALTER TABLE question_log
    ADD COLUMN anonymized_at TIMESTAMP,
    DROP CONSTRAINT question_log_owner_check,
    ADD CONSTRAINT question_log_owner_check
        CHECK (user_id IS NOT NULL OR conversation_id IS NOT NULL OR anonymized_at IS NOT NULL) NOT VALID;

ALTER TABLE answer_attempts_history
    DROP CONSTRAINT answer_attempts_history_conversation_id_fkey,
    ADD CONSTRAINT answer_attempts_history_conversation_id_fkey
        FOREIGN KEY (conversation_id) REFERENCES conversations (conversation_id) ON DELETE CASCADE,
    DROP CONSTRAINT answer_attempts_history_source_attempt_id_fkey,
    ADD CONSTRAINT answer_attempts_history_source_attempt_id_fkey
        FOREIGN KEY (source_attempt_id) REFERENCES answer_attempts_history (attempt_id) ON DELETE CASCADE,
    DROP CONSTRAINT answer_attempts_history_user_id_fkey,
    ADD CONSTRAINT answer_attempts_history_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE;

-- 미답변 질문은 답변 시도 로그(3개월)가 아니라 그룹을 따라 지웁니다.
-- 검토 대기·보류 중인 그룹의 질문이 지워지면 question_count와 중심 벡터가 남은 질문으로 다시 계산돼 관리자 우선순위가 흔들리므로,
-- 시도 로그가 지워지면 연결만 끊고 질문은 그룹이 처리된 뒤 함께 지웁니다. 질문 텍스트는 이미 question 컬럼에 있습니다.
ALTER TABLE unanswered_questions
    ALTER COLUMN attempt_id DROP NOT NULL,
    DROP CONSTRAINT unanswered_questions_attempt_id_fkey,
    ADD CONSTRAINT unanswered_questions_attempt_id_fkey
        FOREIGN KEY (attempt_id) REFERENCES answer_attempts_history (attempt_id) ON DELETE SET NULL,
    DROP CONSTRAINT unanswered_questions_group_id_fkey,
    ADD CONSTRAINT unanswered_questions_group_id_fkey
        FOREIGN KEY (group_id) REFERENCES unanswered_question_groups (id) ON DELETE CASCADE;

ALTER TABLE notifications
    DROP CONSTRAINT notifications_reservation_id_fkey,
    ADD CONSTRAINT notifications_reservation_id_fkey
        FOREIGN KEY (reservation_id) REFERENCES store_reservations (reservation_id) ON DELETE CASCADE,
    DROP CONSTRAINT notifications_user_id_fkey,
    ADD CONSTRAINT notifications_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE;

ALTER TABLE old_faq
    DROP CONSTRAINT old_faq_faq_id_fkey,
    ADD CONSTRAINT old_faq_faq_id_fkey
        FOREIGN KEY (faq_id) REFERENCES faq (id) ON DELETE CASCADE;

ALTER TABLE store_services
    DROP CONSTRAINT store_services_store_id_fkey,
    ADD CONSTRAINT store_services_store_id_fkey
        FOREIGN KEY (store_id) REFERENCES stores (store_id) ON DELETE CASCADE;

-- 회원을 지우면 남아 있는 로그도 함께 지웁니다.
-- 구독이 있는 회원은 subscriptions.user_id(NO ACTION)가 삭제를 막으므로 이 CASCADE는 구독이 없는 회원에게만 동작합니다.
-- conversations는 MEMBER 대화에 user_id가 필요하다는 CHECK가 있어 SET NULL을 쓸 수 없습니다.
ALTER TABLE conversations
    DROP CONSTRAINT conversations_user_id_fkey,
    ADD CONSTRAINT conversations_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE;

ALTER TABLE store_reservations
    DROP CONSTRAINT store_reservations_user_id_fkey,
    ADD CONSTRAINT store_reservations_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE;

ALTER TABLE refresh_tokens
    DROP CONSTRAINT refresh_tokens_user_id_fkey,
    ADD CONSTRAINT refresh_tokens_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE;

-- 감사 컬럼은 운영 목적으로만 남기므로 회원이 지워지면 비웁니다.
ALTER TABLE faq
    DROP CONSTRAINT faq_admin_id_fkey,
    ADD CONSTRAINT faq_admin_id_fkey
        FOREIGN KEY (admin_id) REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE old_faq
    DROP CONSTRAINT old_faq_created_by_fkey,
    ADD CONSTRAINT old_faq_created_by_fkey
        FOREIGN KEY (created_by) REFERENCES users (user_id) ON DELETE SET NULL,
    DROP CONSTRAINT old_faq_updated_by_fkey,
    ADD CONSTRAINT old_faq_updated_by_fkey
        FOREIGN KEY (updated_by) REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE plans
    DROP CONSTRAINT plans_created_by_fkey,
    ADD CONSTRAINT plans_created_by_fkey
        FOREIGN KEY (created_by) REFERENCES users (user_id) ON DELETE SET NULL,
    DROP CONSTRAINT plans_updated_by_fkey,
    ADD CONSTRAINT plans_updated_by_fkey
        FOREIGN KEY (updated_by) REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE bundle_products
    DROP CONSTRAINT bundle_products_created_by_fkey,
    ADD CONSTRAINT bundle_products_created_by_fkey
        FOREIGN KEY (created_by) REFERENCES users (user_id) ON DELETE SET NULL,
    DROP CONSTRAINT bundle_products_updated_by_fkey,
    ADD CONSTRAINT bundle_products_updated_by_fkey
        FOREIGN KEY (updated_by) REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE addon_services
    DROP CONSTRAINT addon_services_created_by_fkey,
    ADD CONSTRAINT addon_services_created_by_fkey
        FOREIGN KEY (created_by) REFERENCES users (user_id) ON DELETE SET NULL,
    DROP CONSTRAINT addon_services_updated_by_fkey,
    ADD CONSTRAINT addon_services_updated_by_fkey
        FOREIGN KEY (updated_by) REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE roaming_products
    DROP CONSTRAINT roaming_products_created_by_fkey,
    ADD CONSTRAINT roaming_products_created_by_fkey
        FOREIGN KEY (created_by) REFERENCES users (user_id) ON DELETE SET NULL,
    DROP CONSTRAINT roaming_products_updated_by_fkey,
    ADD CONSTRAINT roaming_products_updated_by_fkey
        FOREIGN KEY (updated_by) REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE notices
    DROP CONSTRAINT notices_created_by_fkey,
    ADD CONSTRAINT notices_created_by_fkey
        FOREIGN KEY (created_by) REFERENCES users (user_id) ON DELETE SET NULL,
    DROP CONSTRAINT notices_updated_by_fkey,
    ADD CONSTRAINT notices_updated_by_fkey
        FOREIGN KEY (updated_by) REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE guest_chat_settings
    DROP CONSTRAINT guest_chat_settings_updated_by_fkey,
    ADD CONSTRAINT guest_chat_settings_updated_by_fkey
        FOREIGN KEY (updated_by) REFERENCES users (user_id) ON DELETE SET NULL;

-- 삭제 조건으로 쓰는 시각 컬럼
CREATE INDEX idx_question_log_created_at ON question_log (created_at);
CREATE INDEX idx_answer_attempts_history_created_at ON answer_attempts_history (created_at);
CREATE INDEX idx_conversations_updated_at ON conversations (updated_at);
CREATE INDEX idx_notifications_created_at ON notifications (created_at);
CREATE INDEX idx_store_reservations_visit_at ON store_reservations (visit_at);
CREATE INDEX idx_users_deleted_at ON users (deleted_at) WHERE deleted_at IS NOT NULL;

-- CASCADE·SET NULL과 참조 확인(NOT EXISTS)에 쓰는 FK 컬럼
CREATE INDEX idx_question_log_user_id ON question_log (user_id);
CREATE INDEX idx_answer_attempts_history_user_id ON answer_attempts_history (user_id);
CREATE INDEX idx_conversations_user_id ON conversations (user_id);
CREATE INDEX idx_notifications_reservation_id ON notifications (reservation_id);
CREATE INDEX idx_faq_log_faq_id ON faq_log (faq_id);
CREATE INDEX idx_unanswered_questions_best_faq_id ON unanswered_questions (best_faq_id);

-- 구독·청구 기록(전자상거래법 시행령 제6조, 5년)이 남아 있는 탈퇴 회원의 식별 정보를 서비스 테이블과 분리해 보관합니다.
-- users 행은 FK 유지를 위해 남기고 개인정보 컬럼만 비웁니다.
CREATE SCHEMA retention;

CREATE TABLE retention.withdrawn_user_records (
    user_id      BIGINT       PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    email        VARCHAR(255) NOT NULL,
    legal_basis  VARCHAR(50)  NOT NULL CHECK (legal_basis IN ('E_COMMERCE_ACT_5Y')),
    retain_until DATE         NOT NULL,
    moved_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_withdrawn_user_records_retain_until ON retention.withdrawn_user_records (retain_until);
