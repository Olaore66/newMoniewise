-- Supports GET /ai/sdk/actions?status=PENDING, which lists a user's prepared actions
-- across every thread.
--
-- The existing idx_ai_actions_user is (user_email, id) and cannot serve this: the query
-- filters on status and orders by created_at, so without this index Postgres reads every
-- action the user has ever had and sorts them.

CREATE INDEX IF NOT EXISTS idx_ai_actions_user_status
    ON ai_prepared_actions (user_email, status, created_at DESC);
