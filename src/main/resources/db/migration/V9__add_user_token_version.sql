-- Access-token version of a user. Every access token carries the version that was current when it was issued
-- (claim "token_version"), and JwtAuthenticationFilter accepts a token only while it equals users.token_version.
-- Deactivation increments it (AccountStatusService), so access tokens issued before a deactivation stay invalid even
-- if the account is reactivated before they expire. Suspension does not change it.
-- A counter instead of a timestamp: equality of integers has no clock-precision or equal-timestamp edge cases.
-- Existing users start at 0.
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE users ADD CONSTRAINT ck_users_token_version_non_negative CHECK (token_version >= 0);
