-- Conversation threads and prepared actions for the in-process Monnie SDK.

CREATE TABLE ai_conversation_threads (
    id           VARCHAR(64) PRIMARY KEY,
    user_email   VARCHAR(255) NOT NULL,
    surface      VARCHAR(64)  NOT NULL DEFAULT 'CHAT',
    title        VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_threads_user ON ai_conversation_threads (user_email);

CREATE TABLE ai_conversation_messages (
    id           BIGSERIAL PRIMARY KEY,
    thread_id    VARCHAR(64)  NOT NULL REFERENCES ai_conversation_threads (id) ON DELETE CASCADE,
    turn_id      VARCHAR(64),
    role         VARCHAR(16)  NOT NULL,
    body         TEXT         NOT NULL,
    hidden       BOOLEAN      NOT NULL DEFAULT FALSE,
    stream_status VARCHAR(16) NOT NULL DEFAULT 'COMPLETE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_messages_thread ON ai_conversation_messages (thread_id, id);

CREATE TABLE ai_prepared_actions (
    id                VARCHAR(64) PRIMARY KEY,
    thread_id         VARCHAR(64)  NOT NULL,
    user_email        VARCHAR(255) NOT NULL,
    kind              VARCHAR(64)  NOT NULL,
    params            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    params_hash       VARCHAR(64)  NOT NULL,
    render            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    editable_fields   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    requires_pin      BOOLEAN      NOT NULL DEFAULT TRUE,
    status            VARCHAR(32)  NOT NULL,
    version           INTEGER      NOT NULL DEFAULT 0,
    idempotency_key   VARCHAR(64)  NOT NULL UNIQUE,
    prepared_by_agent VARCHAR(64),
    evidence          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    failure_code      VARCHAR(64),
    execution_ref     VARCHAR(128)
);

CREATE INDEX idx_ai_actions_user ON ai_prepared_actions (user_email, id);
CREATE INDEX idx_ai_actions_thread_kind ON ai_prepared_actions (thread_id, kind, status);
