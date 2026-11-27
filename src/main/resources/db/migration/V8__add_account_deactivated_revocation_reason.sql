-- Refresh tokens can also be revoked because their user's account was deactivated (ACCOUNT_DEACTIVATED,
-- written by AccountStatusService). The existing reasons keep their meaning:
--   ROTATED (normal refresh), LOGOUT, REUSE_DETECTED (replay of a rotated token).
-- 'ACCOUNT_DEACTIVATED' is 19 characters and fits revoked_reason VARCHAR(20).
ALTER TABLE refresh_tokens DROP CONSTRAINT ck_refresh_tokens_revoked_reason;

ALTER TABLE refresh_tokens ADD CONSTRAINT ck_refresh_tokens_revoked_reason
    CHECK (revoked_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'ACCOUNT_DEACTIVATED'));
