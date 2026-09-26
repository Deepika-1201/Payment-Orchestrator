# ADR-022: Application-level security hardening

**Status:** Accepted (2026-09-26)

## Context
Architecture §8 lists several controls as edge or infrastructure concerns: HSTS at the load balancer, source-IP allowlists for PSP webhooks at the WAF, and "no secrets or PII in logs" (NFR-9). Relying on those alone has gaps:
- **Local and other deployments:** they have no WAF.
- **Misconfiguration:** a misconfigured edge fails open.
- **Logging:** log hygiene depends on every developer, every time.

The JSON APIs also had no response hardening. Responses that show a secret once (API keys, webhook secrets) had no `Cache-Control`, and nothing capped the size of a JSON request body before Jackson buffered it.

A production start with development settings was possible too. That includes enabling the mock PSPs, allowing webhook delivery to private addresses, or reusing the data key committed for local use.

## Decision
- **Response headers:** every `/v1` and `/admin` response carries:
  - `Cache-Control: no-store`
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`
  - `Referrer-Policy: no-referrer`

  This covers errors and `401`s. Over HTTPS the API and the hosted checkout also send `Strict-Transport-Security: max-age=31536000; includeSubDomains`. The scheme comes from `X-Forwarded-Proto`, trusted only from internal proxies (`server.forward-headers-strategy: native`).
- **Body limit:** bodies above `pg.api.max-request-body` (256 KB) get `413 payload_too_large`, before authentication or any read. A declared `Content-Length` is checked up front. Chunked bodies are counted while they stream and are cut off at the limit.
- **PSP webhook source allowlists** in the application: `pg.webhooks.inbound.allowed-sources.<PROVIDER>` takes a list of IPv4 or IPv6 CIDRs, typically the PSP's published webhook IPs. Sources outside the list get `403` and the metric `pg.webhooks.inbound{result=source_rejected}`. Providers without a list accept any source. Signatures are verified in every case. The WAF IP-set rule remains the first line (Terraform).
- **Log redaction** for structured production logs: a JSON members customizer runs every string value through `LogRedactor`. This covers the message, MDC values and stack traces. It masks:
  - API keys, webhook secrets and bearer tokens;
  - credentials in URLs;
  - card numbers that pass the Luhn check (last 4 kept);
  - emails and VPAs (first character kept).
- **Production configuration guard:** the `prod` profile refuses to start when any of these hold:
  - mock PSPs are on;
  - outbound webhooks allow private targets or plain HTTP;
  - rate limits are off;
  - no admin operators are configured;
  - the checkout URL is not HTTPS;
  - the data key is empty or one committed in this repository.

  All problems are reported at once.
- Audit log and transition log immutability already exists: V1 triggers reject `UPDATE` and `DELETE`, as they do for the ledger.

## Alternatives
| Option | Trade-off |
|---|---|
| Rely on the ALB and WAF only | Fewer moving parts in the application; no protection in any other deployment, and a silent failure when the edge is misconfigured |
| Spring Security header writers | Standard; pulls in the whole filter chain for five headers |
| Mask PII at every log call site | Precise; relies on discipline, which is what failed before |
| Log-pattern masking for plain-text logs too | Covers development logs; production uses structured logs only |
| Warn instead of failing on unsafe production settings | Never blocks a deploy; unsafe settings then ship unnoticed |

## Consequences
- **Log output:** a masked value can hide a legitimate field that looks like an email or a card number, for example an order reference made of 16 digits that passes Luhn. This is acceptable for logs, which are never a system of record.
- **Proxy trust:** the allowlist and HSTS rely on the proxy boundary. Only internal addresses may set `X-Forwarded-For` and `X-Forwarded-Proto`, which is Tomcat's default for internal networks. A new ingress path must keep that property.
- **PSP IPs:** when a PSP changes its published webhook IPs, the configuration must follow, otherwise its webhooks are rejected. Rejections are counted and logged, and status checks and reconciliation still converge.
