# ADR-023: Admin single sign-on with OIDC access tokens

**Status:** Accepted (2026-09-26)

## Context
ADR-019 introduced admin roles with named operators identified by hashed static tokens, and named OIDC SSO as the target. Static tokens have several drawbacks:
- They never expire.
- They are issued and revoked by redeploying configuration.
- They are not tied to the company identity provider, where joiners, leavers and MFA are managed.

Operations and finance staff should sign in with their corporate identity, and their gateway roles should follow their identity-provider groups.

## Decision
- **Token validation:** when `pg.security.oidc.issuer` is set, the admin filter accepts bearer **JWT access tokens** from that identity provider. It requires:
  - a signature by a key in the provider's JWKS (`jwks-uri`), with RS256 or ES256 only;
  - `iss` equal to the configured issuer;
  - `aud` containing `audience`;
  - `sub` and a valid `exp`, with 60 s clock skew;
  - JOSE type `JWT` or `at+jwt`.
- **Keys:** JWKS are cached, and an unknown key id triggers a refresh, so the provider can rotate keys without a gateway restart.
- **Rejected tokens:** unsigned (`alg: none`), HMAC-signed (including an HMAC keyed with the public RSA key), expired, tampered or foreign tokens get `401`. The reason is logged, never the token.
- **Identity:**
  - **Roles:** from the `roles-claim` (default `roles`, a list or a space- or comma-separated string), mapped case-insensitively to `admin`, `ops`, `finance`, `read_only`. Unknown values are ignored.
  - **Audit actor:** the `name-claim` (default `preferred_username`, falling back to `sub`).
  - **Enforcement:** the resulting `AdminPrincipal` is the same one static credentials produce, so ADR-019 permissions apply unchanged. A valid token without known roles authenticates but may do nothing (`403`).
- **Static tokens coexist:** hashed operator tokens (`admin-users`) and break-glass tokens keep working. A token that looks like a JWT (three segments) is checked only as a JWT.
- **Configuration:** the issuer, JWKS URI and audience are explicit, with no discovery call at startup, so a slow identity provider cannot block a deploy.

## Alternatives
| Option | Trade-off |
|---|---|
| Spring Security OAuth2 resource server | Full-featured; brings the whole security filter chain, while the gateway needs one token check that produces the ADR-019 principal |
| Browser SSO (authorization-code flow) in the gateway | Needed for a UI; the admin surface is an API used by tools and a future console, which obtain tokens themselves |
| Token introspection (RFC 7662) per request | Immediate revocation; a network call to the identity provider on every admin request |
| **JWT verification with a cached JWKS (Nimbus JOSE)** | Fast, offline, standard; revocation waits for token expiry, so the identity provider should issue short-lived access tokens |

## Consequences
- **Identity provider setup:** an app registration for the gateway audience, and a groups or roles mapper that emits the `roles` claim. Access-token lifetime of 15 minutes or less is recommended.
- **New dependency:** `com.nimbusds:nimbus-jose-jwt`.
- **Break-glass tokens:** they stay possible. Production should rely on SSO plus a sealed break-glass `admin-users` entry.
- **Out of scope:** maker-checker for money-affecting actions is a separate control (ADR-024).
