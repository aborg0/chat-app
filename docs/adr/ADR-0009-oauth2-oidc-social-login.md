# ADR-0009: OAuth 2.0 + OIDC for Social Login

## Status

Accepted

## Context

The previous social login implementation (ADR-0007) trusted client-provided claims about social provider identities without cryptographic verification. The backend accepted arbitrary `providerUserId` values without confirming that:

- The user actually authenticated with the claimed provider
- The provider user ID was legitimate and assigned to that user
- The claim came from a trusted source

This allowed client-side spoofing: a malicious or compromised client could claim to be any user on any provider.

## Decision

**Implement OAuth 2.0 Authorization Code Flow with PKCE and OpenID Connect (OIDC)** for social login:

1. **No direct provider user ID claims from client**: Instead, the client initiates an OAuth authorization request and receives an authorization code.

2. **Backend token exchange**: The backend exchanges the authorization code (+ PKCE verifier) for an ID token and optional access token from the provider.

3. **Cryptographic verification**: The backend verifies the ID token's signature using the provider's public key (fetched from the provider's JWKS endpoint).

4. **Trusted claims extraction**: User identity claims (`sub` = provider user ID, `email`, `name`) are extracted only from the verified ID token.

5. **Provider configuration**: Each provider (GitHub, Google, Microsoft, Apple, Discord) is configured with:
   - OAuth `client_id` and `client_secret`
   - Authorization endpoint URI
   - Token endpoint URI
   - JWKS (JSON Web Key Set) endpoint URI for signature verification

6. **Session binding**: The `sub` claim from the verified token is treated as the canonical provider user ID and stored in `auth_identities.provider_user_id`.

## Flow

### Authorization Request (Client → Provider)

```
1. Backend generates PKCE challenge: code_challenge = BASE64URL(SHA256(code_verifier))
2. Backend generates state: random 32-byte value for CSRF protection
3. Backend stores (code_challenge, state) in temporary session store (Redis or in-memory cache)
4. Client receives: { redirectUri, state }
5. Client redirects browser to: provider_auth_uri?client_id=...&redirect_uri=...&state=...&code_challenge=...&nonce=...
```

### Authorization Response (Provider → Client)

```
6. User authenticates with provider and grants permission
7. Provider redirects to backend callback: /oauth/callback?code=...&state=...
```

### Token Exchange (Backend → Provider)

```
8. Backend validates state against stored challenge
9. Backend exchanges code + code_verifier for tokens via provider's token endpoint
10. Backend receives: { id_token (JWT), access_token, expires_in, ... }
```

### Token Verification (Backend)

```
11. Backend fetches provider's JWKS (with caching)
12. Backend verifies ID token signature using JWKS public key
13. Backend validates token claims: exp, iat, nonce, aud (client_id), iss
14. Backend extracts: sub (provider user ID), email, name, picture, etc.
```

### User Mapping (Backend)

```
15. Backend looks up `auth_identities` by (provider, sub)
16. If exists: retrieve user_id → create session
17. If not: create new user + auth_identity link → create session
18. Return: { userId, sessionToken } to client
```

## Implementation Details

### Backend Changes

- **New module**: `OAuthModule` with:
  - `OAuthService` trait exposing `initiateLogin(provider: String): Task[(redirectUri, state)]` and `exchangeCode(provider: String, code: String, state: String, codeVerifier: String): Task[AuthResult]`
  - `JwtVerifier` helper for OIDC token validation and claim extraction
  - `OAuthCache` for ephemeral challenge storage (32 seconds TTL)

- **AppConfig** additions:
  ```hocon
  oauth {
    github {
      clientId = ${?GITHUB_OAUTH_CLIENT_ID}
      clientSecret = ${?GITHUB_OAUTH_CLIENT_SECRET}
      authorizationUri = "https://github.com/login/oauth/authorize"
      tokenUri = "https://github.com/login/oauth/access_token"
      jwksUri = "https://api.github.com/app/code_scanning/default/alerts"  # Not available; use static key
      userInfoUri = "https://api.github.com/user"
    }
    # Additional providers follow same pattern
  }
  ```

- **New API endpoint**: `GET /oauth/authorize?provider=github` → returns `{ redirectUri: String, state: String }`

- **Callback endpoint**: `POST /oauth/callback` with `{ provider, code, state, codeVerifier }` → returns `{ userId, sessionToken }`

- **Database**: No schema changes required; `auth_identities.(provider, provider_user_id)` already stores the verified identity.

### Frontend Changes

- **Authorization initiation**:
  ```javascript
  1. Call GET /oauth/authorize?provider=github
  2. Store returned state + generate code_verifier locally
  3. Redirect to provider_auth_uri (from response)
  ```

- **Callback handling** (new route `/oauth-callback`):
  ```javascript
  1. Extract code + state from URL
  2. Call POST /oauth/callback { provider, code, state, codeVerifier }
  3. Receive { userId, sessionToken }
  4. Store session token → redirect to app
  ```

- **Removed**: Manual entry of provider user ID (no longer needed).

### Dependencies

- `com.github.jwt-scala:jwt-zio`: JWT signature verification (replaces ad-hoc parsing)
- `io.jsonwebtoken:jjwt` (alternative, pure Java): JWT/JWS support without external key management
- Existing `zio-http` for OAuth provider HTTP calls

### Provider-Specific Notes

**GitHub**: No native OIDC provider; uses OAuth 2.0 + custom `/user` endpoint. Public key must be obtained via separate mechanism or hardcoded. Consider using GitHub's documented public key or a library wrapper.

**Google, Microsoft, Apple, Discord**: Full OIDC support with JWKS endpoints.

## Consequences

- ✅ **No client-side spoofing**: Backend verifies every identity cryptographically.
- ✅ **CSRF protection**: State parameter binding prevents authorization code interception.
- ✅ **Replay protection**: Nonce in ID token binds token to the specific authorization request.
- ✅ **Token expiry**: ID tokens expire in minutes; stolen tokens have limited value.
- ✅ **Scalable**: JWKS caching reduces provider load.
- ⚠️ **Increased complexity**: OAuth flow requires careful state management, HTTP calls to provider, and JWT validation.
- ⚠️ **Rate limiting**: Token endpoint calls can be rate-limited by providers; implement backoff.
- ⚠️ **Configuration drift**: OAuth credentials (client_id, client_secret) must be managed securely in production (use secrets manager, not `.env`).

## Alternatives Considered

- **SAML**: More heavyweight; targets enterprise use cases. OAuth + OIDC is simpler for public social providers.
- **OpenID Connect Implicit Flow**: Deprecated due to security issues (no backend token validation possible).
- **Delegating to third-party library** (e.g., Spring Security OAuth2, Pac4j): Adds dependency on opinionated framework; ZIO-friendly libraries are limited.

## Notes

- **Initial implementation targets GitHub** with a hardcoded public key or via a library wrapper. Other providers can be added incrementally.
- **PKCE is mandatory** for SPAs, not just optional. All clients use PKCE.
- **Nonce binding** prevents token reuse across separate authorization requests.
- The temporary state + code_challenge cache must survive brief network delays (32-second TTL is typical).
- **Provider rate limits**: Each token exchange can be expensive. Consider request deduplication if a client retries the callback endpoint.
