# ADR-0007: Authentication and Session Security Controls

## Status

Accepted

## Context

The initial implementation had several authentication and session weaknesses that exposed the application to common OWASP risks:

- Session tokens were generated using `UUID.randomUUID()`, which produces only 122 bits of randomness and is formatted as a recognisable UUID string, making token format visible to clients.
- Login errors distinguished between an unknown username and a wrong password, enabling account enumeration.
- Social login accepted any arbitrary `provider` string, allowing unbounded identity creation with attacker-controlled values.
- No input validation was performed on `username`, `password`, `deviceId`, or `providerUserId` at the service boundary.
- Sessions had no expiry, so a stolen token remained valid indefinitely.
- Backend error responses returned raw exception messages in some cases, leaking internal state (database column names, class names, stack-trace fragments).
- HTTP responses carried no browser security headers, leaving clients exposed to clickjacking and content-type sniffing.
- Request bodies had no size limit, enabling trivial denial-of-service via large payloads.

## Decision

**Session tokens** are generated with `SecureRandom` and base64url-encoded (32 bytes, 256 bits of entropy). Tokens are validated against a safe character pattern before any database query.

**Session expiry** is enforced. Each session row carries a non-nullable `expires_at` timestamp set to 30 days from creation. All session lookups filter on `expires_at > NOW()`.

**Credential error messages** are normalised. All failure paths through password login (unknown username, social account without a password hash, wrong password) return the single message `"Invalid credentials"`. Re-authentication failure uses `"Re-authentication failed"`, not an internal reason.

**Social login providers** are restricted to an explicit allowlist (`github`, `google`, `microsoft`, `apple`, `discord`). Arbitrary provider strings are rejected.

**Input validation** is enforced at the service boundary for all authentication inputs:
- `username`: matches `^[a-zA-Z0-9._-]{3,64}$`
- `password`: 8–200 characters, at least one letter and one digit
- `deviceId`: 1–200 characters, non-blank
- `providerUserId`: 1–255 characters, non-blank

**Error sanitisation** in the API layer: raw exception messages are only forwarded to the client when they match a known-safe subset of client-facing messages. All other failures return `HTTP 500: Request failed`.

**Browser security headers** are added to every backend HTTP response:
- `x-content-type-options: nosniff`
- `x-frame-options: DENY`
- `cache-control: no-store`

The nginx reverse proxy adds additional CSP and referrer-policy headers for static asset responses.

**Request body size** is capped at 100 000 characters. Requests exceeding this limit are rejected before JSON decoding.

**Registration conflict** messages do not expose whether a username was taken due to a duplicate constraint or another error; both paths return `"Username already exists"`.

## Consequences

- Stolen or guessed session tokens are bounded: a token older than 30 days is invalid even if its `active` flag was never cleared.
- Account enumeration is no longer possible through login error messages.
- Social login cannot be used to inject arbitrary provider identifiers into the database.
- Internal implementation details (column names, exception types) are not observable through API error bodies.
- The token pattern `^[A-Za-z0-9_-]{20,200}$` must be satisfied by any session token value. Code that constructs tokens outside `SessionsModule` must conform to this pattern.
- The 30-day session TTL and 7-day share-link TTL are currently hardcoded constants. If per-application or per-user TTL configurability is needed in future, those constants should be promoted to config values.

## Alternatives Considered

- **JWT for sessions**: rejected; opaque session tokens stored in the database are simpler, allow immediate revocation, and avoid JWT algorithm confusion vulnerabilities.
- **Rate limiting on login**: not implemented in this change. Should be added at the reverse proxy or API gateway layer if brute-force protection is required beyond the current input validation.
- **Keeping UUID v4 tokens**: rejected because 122-bit entropy is the practical lower bound, and the UUID format is a recognisable artefact that reveals implementation details. The upgrade to 256-bit `SecureRandom` tokens has no downside.

## Notes

- Migration `V8__session_expiration.sql` adds `expires_at` to existing session rows by backfilling `NOW() + INTERVAL '30 days'` before setting the column to `NOT NULL`.
- Security headers in `ApiRoutes` use lowercase header names to conform to HTTP/2 convention.
