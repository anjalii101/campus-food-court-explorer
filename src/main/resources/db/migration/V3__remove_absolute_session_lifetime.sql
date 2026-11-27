-- Session policy revised: there is no absolute session lifetime any more.
-- Refresh tokens only have a rolling inactivity expiry (expires_at, 7 days by default),
-- renewed on every successful rotation. Remove the per-family absolute deadline added in V2.
-- IF EXISTS keeps this correct whether or not V2 was ever applied to a given database.
ALTER TABLE refresh_tokens DROP CONSTRAINT IF EXISTS ck_refresh_tokens_expiry_within_session;
ALTER TABLE refresh_tokens DROP COLUMN IF EXISTS session_expires_at;
