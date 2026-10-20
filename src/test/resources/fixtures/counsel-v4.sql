-- Frozen pre-integration standalone V4 schema for data-preservation migration tests.
-- Loaded explicitly by tests after BE V38; this is not a Flyway migration.
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
-- The DBA provisions the schema and public.vector extension first.
-- Flyway history belongs exclusively to counseling.
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
