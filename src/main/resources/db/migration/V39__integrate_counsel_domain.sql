-- One BE migration history now owns new installations. Preserve an existing V4 counseling schema in place.
-- An earlier standalone V1-V3 database must be upgraded to V4 before this migration is applied.
CREATE SCHEMA IF NOT EXISTS counseling;
DO $counsel$
BEGIN
    IF to_regclass('counseling.sessions') IS NOT NULL THEN
        IF to_regclass('counseling.session_history') IS NULL
            OR to_regclass('counseling.safety_sentences') IS NULL
            OR NOT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema='counseling' AND table_name='sessions' AND column_name='handoff_id') THEN
            RAISE EXCEPTION 'Existing counseling schema must be upgraded to V4 before BE integration';
        END IF;
        RETURN;
    END IF;
-- public.vector is installed by the existing BE V1 migration.
-- The existing BE public Flyway history owns this migration.
CREATE TABLE counseling.sessions (
    id UUID PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES public.users(user_id),
    counselor_id BIGINT REFERENCES public.users(user_id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('WAITING','ACTIVE','PAUSED','ENDED')),
    assignment_version BIGINT NOT NULL DEFAULT 0,
    last_delivered_seq BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    waiting_since TIMESTAMPTZ NOT NULL DEFAULT now(),
    paused_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    CHECK (status NOT IN ('ACTIVE','PAUSED') OR counselor_id IS NOT NULL)
);
CREATE UNIQUE INDEX one_open_customer ON counseling.sessions(customer_id) WHERE status <> 'ENDED';
CREATE UNIQUE INDEX one_busy_counselor ON counseling.sessions(counselor_id) WHERE status IN ('ACTIVE','PAUSED');
CREATE INDEX session_queue ON counseling.sessions(waiting_since, id) WHERE status = 'WAITING';

CREATE TABLE counseling.counselor_presence (
    user_id BIGINT PRIMARY KEY REFERENCES public.users(user_id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('AVAILABLE','OFFLINE')),
    last_heartbeat_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE counseling.messages (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES counseling.sessions(id),
    sender_id BIGINT NOT NULL REFERENCES public.users(user_id),
    sender_role VARCHAR(16) NOT NULL,
    assignment_version BIGINT NOT NULL,
    client_message_id UUID NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','ALLOWED','BLOCKED','CHECK_FAILED')),
    delivery_sequence BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    UNIQUE (session_id, sender_id, client_message_id),
    UNIQUE (session_id, delivery_sequence),
    CHECK ((status = 'ALLOWED') = (delivery_sequence IS NOT NULL)),
    CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
CREATE INDEX pending_messages ON counseling.messages(created_at) WHERE status = 'PENDING';
CREATE INDEX delivered_messages ON counseling.messages(session_id, delivery_sequence) WHERE status = 'ALLOWED';

CREATE TABLE counseling.guard_profiles (
    profile_version VARCHAR(64) PRIMARY KEY,
    model_name VARCHAR(100) NOT NULL,
    preprocessing_version VARCHAR(32) NOT NULL,
    dataset_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE counseling.guard_config_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_name VARCHAR(100) NOT NULL,
    profile_version VARCHAR(64) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE counseling.safety_filter_vectors (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category VARCHAR(16) NOT NULL CHECK (category = 'INSULT'),
    example_text VARCHAR(1000) NOT NULL,
    embedding public.vector(1024) NOT NULL,
    severity SMALLINT NOT NULL CHECK (severity BETWEEN 1 AND 3),
    model_name VARCHAR(100) NOT NULL,
    profile_version VARCHAR(64) NOT NULL REFERENCES counseling.guard_profiles(profile_version),
    active BOOLEAN NOT NULL DEFAULT true
);
CREATE INDEX vector_profile ON counseling.safety_filter_vectors(profile_version, model_name) WHERE active;
CREATE TABLE counseling.safety_filter_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id BIGINT NOT NULL UNIQUE REFERENCES counseling.messages(id),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('ALLOWED','BLOCKED','CHECK_FAILED')),
    matched_vector_id BIGINT REFERENCES counseling.safety_filter_vectors(id),
    score DOUBLE PRECISION,
    threshold DOUBLE PRECISION NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    profile_version VARCHAR(64) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    category VARCHAR(16),
    error_code VARCHAR(64),
    admin_review VARCHAR(32) CHECK (admin_review IN ('FALSE_POSITIVE','TRUE_POSITIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX safety_event_time ON counseling.safety_filter_events(created_at DESC, id DESC);
CREATE TABLE counseling.role_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_id BIGINT NOT NULL REFERENCES public.users(user_id),
    user_id BIGINT NOT NULL REFERENCES public.users(user_id),
    previous_role VARCHAR(16) NOT NULL,
    new_role VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE counseling.rate_buckets (
    user_id BIGINT PRIMARY KEY REFERENCES public.users(user_id),
    window_start TIMESTAMPTZ NOT NULL DEFAULT now(),
    requests INTEGER NOT NULL DEFAULT 1
);

-- Preserve V1 data and constraints while aligning with UBot naming/identity rules.
ALTER INDEX counseling.one_open_customer RENAME TO uq_sessions_open_customer;
ALTER INDEX counseling.one_busy_counselor RENAME TO uq_sessions_busy_counselor;
ALTER INDEX counseling.session_queue RENAME TO idx_sessions_waiting_since;
ALTER INDEX counseling.pending_messages RENAME TO idx_messages_pending_created_at;
ALTER INDEX counseling.delivered_messages RENAME TO idx_messages_session_delivery_sequence;
ALTER INDEX counseling.vector_profile RENAME TO idx_safety_filter_vectors_profile_model;
ALTER INDEX counseling.safety_event_time RENAME TO idx_safety_filter_events_created_at;

ALTER TABLE counseling.messages
    RENAME CONSTRAINT messages_session_id_sender_id_client_message_id_key TO uq_messages_sender_client_message;
ALTER TABLE counseling.messages
    RENAME CONSTRAINT messages_session_id_delivery_sequence_key TO uq_messages_delivery_sequence;
ALTER TABLE counseling.safety_filter_events
    RENAME CONSTRAINT safety_filter_events_message_id_key TO uq_safety_filter_events_message;

ALTER TABLE counseling.messages ALTER COLUMN id SET GENERATED BY DEFAULT;
ALTER TABLE counseling.guard_config_history ALTER COLUMN id SET GENERATED BY DEFAULT;
ALTER TABLE counseling.safety_filter_vectors ALTER COLUMN id SET GENERATED BY DEFAULT;
ALTER TABLE counseling.safety_filter_events ALTER COLUMN id SET GENERATED BY DEFAULT;
ALTER TABLE counseling.role_audit ALTER COLUMN id SET GENERATED BY DEFAULT;

CREATE TABLE counseling.safety_sentences (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    profile_version VARCHAR(64) NOT NULL REFERENCES counseling.guard_profiles(profile_version),
    model_name VARCHAR(100) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    normalized_sha256 VARCHAR(64) NOT NULL,
    severity SMALLINT NOT NULL CHECK (severity BETWEEN 1 AND 3),
    enabled BOOLEAN NOT NULL DEFAULT true,
    revision BIGINT NOT NULL DEFAULT 1,
    indexed_revision BIGINT,
    indexed_vector_id BIGINT UNIQUE REFERENCES counseling.safety_filter_vectors(id),
    index_token UUID,
    index_started_at TIMESTAMPTZ,
    index_error VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CHECK ((indexed_revision IS NULL) = (indexed_vector_id IS NULL)),
    CHECK ((index_token IS NULL) = (index_started_at IS NULL))
);
CREATE UNIQUE INDEX uq_safety_sentences_profile_text
    ON counseling.safety_sentences(profile_version, model_name, normalized_sha256) WHERE deleted_at IS NULL;
CREATE INDEX idx_safety_sentences_profile_id
    ON counseling.safety_sentences(profile_version, model_name, id DESC) WHERE deleted_at IS NULL;

CREATE TABLE counseling.safety_sentence_audit (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    sentence_id BIGINT NOT NULL REFERENCES counseling.safety_sentences(id),
    admin_id BIGINT REFERENCES public.users(user_id),
    action VARCHAR(24) NOT NULL CHECK (action IN ('SEED','CREATE','UPDATE','DELETE','INDEX_REQUESTED','INDEXED','INDEX_FAILED')),
    revision BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    severity SMALLINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    vector_id BIGINT REFERENCES counseling.safety_filter_vectors(id),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_safety_sentence_audit_sentence_id ON counseling.safety_sentence_audit(sentence_id, id);

-- Import the existing reviewed seed examples without changing their vectors or historical event references.
-- The shipped CSV uses ASCII whitespace; NFKC + whitespace collapse matches nfkc-space-v1 for these rows.
INSERT INTO counseling.safety_sentences
    (profile_version,model_name,content,normalized_sha256,severity,enabled,indexed_revision,indexed_vector_id)
SELECT profile_version,model_name,example_text,
    encode(sha256(convert_to(regexp_replace(btrim(normalize(example_text, NFKC)), '[\s]+', ' ', 'g'), 'UTF8')), 'hex'),
    severity,active,1,id
FROM counseling.safety_filter_vectors;

INSERT INTO counseling.safety_sentence_audit
    (sentence_id,action,revision,content,severity,enabled,vector_id)
SELECT id,'SEED',revision,content,severity,enabled,indexed_vector_id FROM counseling.safety_sentences;

ALTER TABLE counseling.sessions ADD COLUMN conversation_id UUID;
ALTER TABLE counseling.sessions ADD COLUMN handoff_id UUID;
CREATE UNIQUE INDEX uq_sessions_handoff_id ON counseling.sessions(handoff_id) WHERE handoff_id IS NOT NULL;
CREATE INDEX idx_sessions_customer_conversation ON counseling.sessions(customer_id,conversation_id,created_at DESC);

CREATE TABLE counseling.session_history (
    session_id UUID NOT NULL REFERENCES counseling.sessions(id),
    sequence BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER','AI','COUNSELOR')),
    content TEXT NOT NULL,
    source VARCHAR(16) NOT NULL CHECK (source IN ('SAVED','SCREEN','COUNSEL')),
    PRIMARY KEY (session_id,sequence)
);

END;
$counsel$;

-- 상담으로 시작한 대화도 기존 대화 목록과 보관 정책을 따른다.
DO $migration$
DECLARE
    room RECORD;
    conversation UUID;
BEGIN
    FOR room IN
        SELECT s.* FROM counseling.sessions s
        WHERE NOT EXISTS (
            SELECT 1 FROM public.conversations c
            WHERE c.public_id = s.conversation_id AND c.user_id = s.customer_id
        )
        ORDER BY s.created_at, s.id
    LOOP
        conversation := gen_random_uuid();
        INSERT INTO public.conversations(public_id, user_id, type, created_at, updated_at)
        VALUES (conversation, room.customer_id, 'MEMBER', room.created_at,
                GREATEST(room.created_at, room.ended_at,
                    (SELECT max(decided_at) FROM counseling.messages WHERE session_id = room.id)));
        UPDATE counseling.sessions SET conversation_id = conversation WHERE id = room.id;
    END LOOP;
END;
$migration$;

ALTER TABLE counseling.sessions
    ALTER COLUMN conversation_id SET NOT NULL,
    ADD CONSTRAINT fk_sessions_conversation FOREIGN KEY (conversation_id)
        REFERENCES public.conversations(public_id) ON DELETE CASCADE,
    DROP CONSTRAINT sessions_customer_id_fkey,
    ADD CONSTRAINT fk_sessions_customer FOREIGN KEY (customer_id)
        REFERENCES public.users(user_id) ON DELETE CASCADE,
    DROP CONSTRAINT sessions_counselor_id_fkey,
    ADD CONSTRAINT fk_sessions_counselor FOREIGN KEY (counselor_id)
        REFERENCES public.users(user_id) ON DELETE SET NULL;

ALTER TABLE counseling.messages
    DROP CONSTRAINT messages_session_id_fkey,
    ADD CONSTRAINT fk_messages_session FOREIGN KEY (session_id)
        REFERENCES counseling.sessions(id) ON DELETE CASCADE,
    ALTER COLUMN sender_id DROP NOT NULL,
    DROP CONSTRAINT messages_sender_id_fkey,
    ADD CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id)
        REFERENCES public.users(user_id) ON DELETE SET NULL;
ALTER TABLE counseling.session_history
    DROP CONSTRAINT session_history_session_id_fkey,
    ADD CONSTRAINT fk_session_history_session FOREIGN KEY (session_id)
        REFERENCES counseling.sessions(id) ON DELETE CASCADE,
    ADD COLUMN origin_session_id UUID,
    ADD COLUMN origin_sequence BIGINT;
ALTER TABLE counseling.safety_filter_events
    DROP CONSTRAINT safety_filter_events_message_id_fkey,
    ADD CONSTRAINT fk_safety_events_message FOREIGN KEY (message_id)
        REFERENCES counseling.messages(id) ON DELETE CASCADE;

-- 인계 사본에 원본 위치를 남겨 재상담 시 같은 화면 문답을 중복 표시하지 않는다.
-- 기존 사본은 같은 대화에서 동일 문장이 등장한 순서까지 비교해 최초 출처를 찾는다.
WITH occurrences AS (
    SELECT h.session_id, h.sequence, s.conversation_id, s.created_at, h.role, h.content, h.source,
           row_number() OVER (PARTITION BY h.session_id, h.role, h.content, h.source ORDER BY h.sequence) AS occurrence
    FROM counseling.session_history h JOIN counseling.sessions s ON s.id = h.session_id
), origins AS (
    SELECT session_id, sequence,
           first_value(session_id) OVER w AS origin_session_id,
           first_value(sequence) OVER w AS origin_sequence
    FROM occurrences
    WINDOW w AS (PARTITION BY conversation_id, role, content, source, occurrence ORDER BY created_at, session_id)
)
UPDATE counseling.session_history h
SET origin_session_id = o.origin_session_id, origin_sequence = o.origin_sequence
FROM origins o WHERE h.session_id = o.session_id AND h.sequence = o.sequence;

UPDATE public.conversations c SET updated_at = GREATEST(c.updated_at, activity.last_at)
FROM (
    SELECT s.conversation_id, max(GREATEST(s.created_at, s.ended_at, m.decided_at)) AS last_at
    FROM counseling.sessions s
    LEFT JOIN counseling.messages m ON m.session_id = s.id AND m.status = 'ALLOWED'
    GROUP BY s.conversation_id
) activity WHERE c.public_id = activity.conversation_id;

-- 기존 탈퇴 회원 삭제를 새 상담 FK가 막지 않게 한다.
ALTER TABLE counseling.counselor_presence DROP CONSTRAINT counselor_presence_user_id_fkey,
    ADD CONSTRAINT fk_counselor_presence_user FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;
ALTER TABLE counseling.rate_buckets DROP CONSTRAINT rate_buckets_user_id_fkey,
    ADD CONSTRAINT fk_rate_buckets_user FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;
ALTER TABLE counseling.role_audit
    ALTER COLUMN admin_id DROP NOT NULL, ALTER COLUMN user_id DROP NOT NULL,
    DROP CONSTRAINT role_audit_admin_id_fkey, DROP CONSTRAINT role_audit_user_id_fkey,
    ADD CONSTRAINT fk_role_audit_admin FOREIGN KEY (admin_id) REFERENCES public.users(user_id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_role_audit_user FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE SET NULL;
ALTER TABLE counseling.safety_sentence_audit DROP CONSTRAINT safety_sentence_audit_admin_id_fkey,
    ADD CONSTRAINT fk_safety_sentence_audit_admin FOREIGN KEY (admin_id) REFERENCES public.users(user_id) ON DELETE SET NULL;

CREATE INDEX idx_sessions_conversation ON counseling.sessions(conversation_id, created_at, id);
CREATE INDEX idx_messages_rejected_created_at ON counseling.messages(created_at)
    WHERE status IN ('BLOCKED', 'CHECK_FAILED');
