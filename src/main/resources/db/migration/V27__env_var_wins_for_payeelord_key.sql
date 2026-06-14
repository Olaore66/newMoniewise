-- Gateway now checks env var (PAYEELORD_API_KEY) before system_config.
-- Remove the stale/incorrect key that was stored in DB so it can never shadow the env var.
DELETE FROM system_config WHERE key = 'PAYEELORD_API_KEY';
