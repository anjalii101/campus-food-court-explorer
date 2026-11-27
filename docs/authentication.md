# Authentication

How the Campus Food Court backend authenticates users. Everything here is implemented in
`com.campusfoodcourt.campus_foodcourt.{auth,security,config}` and covered by the tests named in each section.

## Overview

| Credential | Format | Lifetime | Transport | Stored server-side |
|---|---|---|---|---|
| Access token | JWT, HS256 | 15 minutes, fixed | JSON response body; sent back as `Authorization: Bearer <token>` | No (stateless) |
| Refresh token | Opaque, 256-bit random | 7 days, rolling (renewed on every refresh) | `HttpOnly` cookie only, never in a JSON body | SHA-256 hash only |

Endpoints (all under `/api/auth`):

| Method & path | Auth | CSRF token | Rate limited |
|---|---|---|---|
| `GET /csrf` | none | issues it | no |
| `POST /register` | none | required | yes |
| `POST /login` | none | required | yes |
| `POST /refresh` | refresh cookie | required | yes |
| `POST /logout` | refresh cookie | required | no |
| `GET /me` | bearer access token | not needed | no |

## Access tokens (JWT)

### Generation

`JwtUtils.generateToken(email, tokenVersion)` builds a compact JWS with JJWT 0.12.x:

| Part | Name | Value |
|---|---|---|
| Header | `alg` | `HS256`, always |
| Claim | `iss` | `campus-foodcourt` (`app.jwt.issuer`) |
| Claim | `sub` | the user's email, lower-cased (the stable login identifier) |
| Claim | `token_type` | `access` |
| Claim | `token_version` | the user's `users.token_version` at issue time (see "Account status") |
| Claim | `iat` | issue time |
| Claim | `exp` | `iat` + 15 minutes (`app.jwt.expiration-ms`, default `900000`) |

Roles are not put into the token. On every request the user is loaded from the database by `sub`, so a
deleted user or a changed role takes effect immediately, not after the token expires.

### Validation

`JwtUtils.verify(token)` is the only way the application reads a token (`extractVerifiedEmail` is a
shortcut for it). It uses one pre-built JJWT parser that:

1. accepts **only HS256**. The parser's algorithm registry is reduced to HS256, so a token signed with
   the same key using HS384 or HS512 is refused. JWTs with `alg: none` and encrypted JWTs (JWE) are
   also refused.
2. verifies the HMAC signature with the configured key.
3. rejects the token if the current time is after `exp`. No clock-skew allowance is configured, because
   tokens are issued and checked by the same process.
4. requires `iss` = `campus-foodcourt` and `token_type` = `access`. A missing claim is rejected the same
   way as a wrong value.
5. additionally requires `exp`, a non-blank `sub` and a non-negative integer `token_version` to be present.

`JwtAuthenticationFilter` then loads the user and also requires the account to be `ACTIVE` and the token's
`token_version` to equal the user's current one. If any check fails, `JwtAuthenticationFilter` leaves the request unauthenticated. Protected endpoints then
answer `401` with `WWW-Authenticate: Bearer`. The reason is logged at DEBUG level only.

Tests: `JwtUtilsTest` (unit: valid token, wrong key, tampered payload, expiry, missing `exp`, wrong or
missing issuer, wrong or missing type, missing subject, malformed input, HS384/HS512 with the same key,
`alg: none`, a relabelled header) and `JwtAccessTokenTest` (the same cases end to end through HTTP).

### Configuration

All JWT settings come from `JwtProperties` (`app.jwt.*`), the single source of truth. `JwtUtils` has no
defaults of its own, and the application refuses to start if the secret is invalid.

| Property | Env var | Default | Rule |
|---|---|---|---|
| `app.jwt.secret` | `JWT_SECRET` | none, required | Base64; at least 32 bytes after decoding |
| `app.jwt.expiration-ms` | `JWT_ACCESS_TOKEN_TTL_MS` | `900000` (15 min) | positive |
| `app.jwt.issuer` | (`APP_JWT_ISSUER`) | `campus-foodcourt` | not blank |

The signing key is built with `Keys.hmacShaKeyFor(decoded secret)`. The algorithm is always stated
explicitly (`Jwts.SIG.HS256`) and is never inferred from the key length. This matters because a 48-byte
key would otherwise default to HS384.

### Why HS256

- **One party signs and verifies.** The backend is a single modular monolith that both issues and checks
  access tokens. No third party needs to verify them, so the main advantage of asymmetric signing
  (RS256/ES256: anyone can verify with a public key) does not apply here.
- **Simple and fast.** HMAC-SHA-256 with a key of at least 256 bits is a strong, standard choice (RFC 7518),
  cheap enough to run on every request, and there is only one secret to manage.
- **Easy to change later.** If tokens must one day be verified by another service, switch to an asymmetric
  algorithm and publish the public key. Because the parser accepts exactly one algorithm, that becomes an
  explicit, reviewed change, never a silent downgrade.

The key must stay secret: anyone who holds it can mint tokens. Generate it randomly (see `.env.example`).
Rotating it logs every user out of their access token, but refresh tokens keep working.

## Refresh tokens and rotation

- At login or registration a new token **family** (a UUID) is created, and a refresh token is issued. It
  is 32 bytes from `SecureRandom`, Base64url-encoded. Only its SHA-256 hash is stored, in `refresh_tokens`.
- The raw token travels **only** in the `refresh_token` cookie: `HttpOnly; Secure; SameSite=Strict;
  Path=/api/auth`, with `Max-Age` equal to the token's remaining lifetime. It is never placed in a JSON
  body, and the DTO fields are annotated `@JsonIgnore`.
- **`POST /refresh`** locks the token row (`SELECT … FOR UPDATE`), then:
  - if the token is valid, it is marked `ROTATED` and a successor is issued in the same family, valid for
    another full 7 days from now (a rolling inactivity expiry). There is **no absolute session lifetime**.
    A new access token is issued in the same transaction; if signing fails, the rotation is rolled back.
  - if the token was already `ROTATED` (a replay, for example a stolen copy), **reuse is detected**: every
    active token in the family is revoked with `REUSE_DETECTED` and a warning is logged.
  - if the token is expired, unknown or revoked for any other reason (`LOGOUT`, `ACCOUNT_DEACTIVATED`), the
    answer is `401` and the cookie is cleared, without a theft alert.
- **`POST /logout`** revokes every active token in the presented token's family (`LOGOUT`) and clears the
  cookie. Access tokens that were already issued remain valid until they expire (at most 15 minutes).

Tests: `RefreshTokenLifecycleTest`, `SessionLifetimeTest`, `RevocationReasonTest`,
`RefreshTokenConcurrencyTest`, `RefreshCookieTransportTest`, `RealServerCookieHeaderTest`,
`AuthFailureSafetyTest`.

### CSRF

The four cookie-based endpoints (register, login, refresh, logout) require a double-submit CSRF token. The
SPA calls `GET /api/auth/csrf`, receives the token in the body and in the readable, host-only
`__Host-XSRF-TOKEN` cookie, and echoes it in the `X-XSRF-TOKEN` header. Bearer-authenticated endpoints do
not need it, because browsers never attach `Authorization` headers automatically. Tests: `CsrfProtectionTest`.

## Account status

Every user has an `account_status` (V7 migration): `ACTIVE`, `SUSPENDED` or `DEACTIVATED`. It is `NOT NULL`,
defaults to `ACTIVE` (existing users were migrated as `ACTIVE`) and is restricted by the check constraint
`ck_users_account_status`. Registration always creates an `ACTIVE` user with `ROLE_USER`; clients cannot
choose either value. Only `ACTIVE` accounts can authenticate:

- **Login:** an inactive account gets the same `401 Invalid credentials` as a wrong password, after the same
  BCrypt check, and receives no tokens.
- **Access tokens:** the user is loaded from the database on every request, so an access token stops working
  as soon as the account becomes inactive, without waiting for it to expire. The response is the same `401` as
  for any invalid token.
- **Refresh:** the status is checked after the token row is locked and before anything is written, so an
  inactive account gets no successor. The response is the same `401` as for an invalid token, and the cookie
  is cleared.
- **Logout** works regardless of status, so an inactive user can still end their sessions.

Statuses are changed only through `AccountStatusService.changeStatus` (internal; no HTTP endpoint yet):

| Change to | Sessions (refresh-token families) | After returning to `ACTIVE` |
|---|---|---|
| `SUSPENDED` | Kept. Refresh is refused, but the tokens are not revoked. | Unexpired sessions resume. |
| `DEACTIVATED` | Every active token of the user, in every family (all devices), is revoked with `ACCOUNT_DEACTIVATED` (V8 migration) by one bulk `UPDATE`, and `token_version` is incremented (V9), in the same transaction as the status change. This happens only on an actual transition to `DEACTIVATED`; repeating a deactivation changes nothing (no version increment, no revocation, existing revocation reasons kept). | Nothing is restored, neither refresh nor access tokens; the user must log in again. |
| `ACTIVE` | Unchanged. | Not applicable. |

**Concurrency.** The status change is an `UPDATE users …`, which row-locks the user (`FOR NO KEY UPDATE`
level). For deactivation it is conditional (`… and account_status <> 'DEACTIVATED'`) and is followed, only if it
matched, by the version increment and the bulk token revocation; a concurrent second deactivation waits for the
row lock, re-checks the condition against the committed row and does nothing. Login, refresh and logout share-lock the same row
(`SELECT … FOR SHARE`) before touching any token, and the two lock modes conflict, so they cannot interleave with a
status change: either they finish first (and deactivation then revokes the token they created) or they run
afterwards and see the new status. Session operations do not block each other. Every path locks the user row
before token rows, so these locks cannot deadlock. Status checks and writes use database queries, never
persistence-context state. The interleavings are tested deterministically in `AccountStatusConcurrencyTest`.

**Access tokens after deactivation.** Access tokens are stateless, so revoking refresh tokens alone would not
stop one issued before a deactivation from working again after a quick reactivation. Each user therefore has a
`token_version` (V9 migration, `BIGINT NOT NULL DEFAULT 0`). Every access token carries the version current at
issue time (read under the same user-row share lock as the status check), and the filter accepts a token only
while it equals `users.token_version`. Deactivation increments the version in its transaction, so every earlier
access token is invalid for good, including one issued by a login or refresh that finished just before the
deactivation. Suspension does not change the version, so unexpired tokens resume after reactivation. A counter
is used instead of a "valid after" timestamp because equality of integers has no clock-precision or
equal-timestamp edge cases (JWT `iat` has only one-second precision). Tokens without `token_version` (issued
before V9) are refused; clients then refresh. Tests: `AccessTokenInvalidationTest`,
`AccessTokenVersionBoundaryTest`.

Tests: `AccountStatusEnforcementTest`, `AccountStatusTransitionTest`, `AccountStatusConcurrencyTest`,
`AccountStatusMigrationTest`.

## Authentication rate limiting

### What is limited

Each protected endpoint has its own counter per client address:

| Endpoint | Default limit | Env vars |
|---|---|---|
| `POST /api/auth/login` | 20 requests / 1 minute | `AUTH_RATE_LIMIT_LOGIN_MAX_REQUESTS`, `AUTH_RATE_LIMIT_LOGIN_WINDOW` |
| `POST /api/auth/register` | 20 requests / 10 minutes | `AUTH_RATE_LIMIT_REGISTER_MAX_REQUESTS`, `AUTH_RATE_LIMIT_REGISTER_WINDOW` |
| `POST /api/auth/refresh` | 60 requests / 1 minute | `AUTH_RATE_LIMIT_REFRESH_MAX_REQUESTS`, `AUTH_RATE_LIMIT_REFRESH_WINDOW` |

Windows are ISO-8601 durations (`PT1M`, `PT10M`).

The defaults are meant to be lenient for real users and still useful against scripted abuse:

- **Login:** 20 per minute is far more than a person typing passwords, and it caps one address at about
  1,200 password guesses an hour. Each guess also costs a BCrypt hash at strength 12.
- **Register:** 20 per 10 minutes allows a group signing up together from one network, but stops bulk
  account creation from one address.
- **Refresh:** an SPA refreshes about once per 15 minutes per tab. 60 per minute leaves a large margin for
  many tabs or many users behind one address. Refresh tokens are 256-bit random values, so this limit
  protects the database, not the token secrecy.

**Every attempt counts:** successful, failed, and those rejected for a missing CSRF token. The limiter runs
in the security filter chain before CSRF validation. A throttled request never reaches BCrypt, refresh
rotation or the database.

### Response

Over the limit, the endpoint answers:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 42
Cache-Control: no-store
Content-Type: application/problem+json

{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Too many requests. Try again later."}
```

`Retry-After` is the number of whole seconds (rounded up, at least 1) until the client's window ends.

### Algorithm

The limiter is a **fixed window per client** (`FixedWindowRateLimiter`). A client's window starts with its
first request. Up to `max-requests` requests are allowed, then everything is rejected until the window
ends, when the count starts again from zero. Rejected requests do not extend the window.

- **Concurrency.** Checking and incrementing a client's count is one atomic `ConcurrentHashMap.compute`, so
  parallel requests cannot exceed the limit. This is tested with 64 threads.
- **Memory bound.** Each endpoint tracks at most `max-tracked-clients` (default 20,000, about 200 bytes each,
  so a few MB in total). The cap is enforced exactly. Expired windows are removed once per window period
  and whenever the table is full. If the table is still full of active windows, a **new** client receives
  `429` (fail closed) and a throttled warning is logged. Clients already tracked are unaffected.
- **Client identity.** The key is the TCP peer address, `HttpServletRequest#getRemoteAddr()`. IPv6
  addresses are grouped by their `/64` prefix (`AUTH_RATE_LIMIT_IPV6_PREFIX_LENGTH`), because one
  subscriber normally controls a whole `/64` and could otherwise rotate addresses freely. No DNS lookups
  are ever made.

Tests: `FixedWindowRateLimiterTest` (limit, `Retry-After`, reset, cleanup, cap, concurrency),
`ClientAddressesTest`, `AuthRateLimitIntegrationTest` (through the real filter chain, including a real
Tomcat request with spoofed `X-Forwarded-For`), and `AuthRateLimitPropertiesTest` (pins the defaults above).

### Proxy headers are not trusted

`X-Forwarded-For`, `X-Real-IP` and `Forwarded` are **ignored**, because any client can send them.
`server.forward-headers-strategy=none` is set explicitly, so Spring Boot also does not start honouring them
automatically on a detected cloud platform.

If the application is later deployed behind a reverse proxy or load balancer, every request would appear to
come from the proxy and all users would share one budget. In that case, configure the proxy as trusted at
the server level instead of reading headers in application code. Use
`server.forward-headers-strategy=native` together with
`server.tomcat.remoteip.internal-proxies=<regex matching only your proxy addresses>`, and make sure the
proxy overwrites (not appends to) `X-Forwarded-For`.

### Limitations of in-memory rate limiting

- **Counters reset on restart.** A restart or redeploy gives every client a fresh budget.
- **Not shared between instances.** Each JVM counts on its own. With N instances behind a load balancer, a
  client effectively gets up to N times the limit. This is acceptable for the current single-instance
  deployment. A horizontally scaled deployment needs a shared store, such as Redis or the database.
- **Shared addresses share a budget.** Users behind one NAT, proxy or campus gateway address (or on one
  IPv6 `/64`) are counted together. If many students reach the app through a single public address, raise
  the limits or configure trusted proxies as described above.
- **Fixed-window edges.** A client can send up to 2× the limit in a short burst that straddles the end of
  one window and the start of the next. This is a deliberate trade-off for simplicity.
- **Per address, not per account.** A distributed attack against one account from many addresses is not
  stopped by this limiter. Per-account throttling or lockout is not implemented yet.
- **Fail closed when the table is full.** Under a very large distributed attack, new clients may be refused
  until windows expire. Existing clients keep working.
