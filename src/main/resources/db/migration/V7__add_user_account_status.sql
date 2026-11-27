-- Account status of a user (maps com.campusfoodcourt.campus_foodcourt.entity.AccountStatus).
--   ACTIVE      : may log in, use access tokens and refresh
--   SUSPENDED   : temporarily blocked; no new tokens, existing access tokens rejected
--   DEACTIVATED : closed; no new tokens, existing access tokens rejected
-- The constant default fills every existing row with ACTIVE (existing users keep working) and keeps
-- inserts that omit the column valid. PostgreSQL 11+ applies a constant default without rewriting the table.
ALTER TABLE users ADD COLUMN account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE users ADD CONSTRAINT ck_users_account_status
    CHECK (account_status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'));
