-- Chat inbox fields for in-process Monnie threads.

ALTER TABLE ai_conversation_threads ADD COLUMN IF NOT EXISTS instructions TEXT;
ALTER TABLE ai_conversation_threads ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE ai_conversation_threads ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_ai_threads_inbox
    ON ai_conversation_threads (user_email, deleted_at, updated_at DESC);
