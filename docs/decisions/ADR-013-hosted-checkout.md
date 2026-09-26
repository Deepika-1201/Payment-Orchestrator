# ADR-013: Server-rendered hosted checkout with capability URLs

**Status:** Accepted (2026-09-26)

## Context
Q3 asks for a minimal hosted checkout next to the server-to-server API. It is the first customer-facing surface: it is unauthenticated, reachable from any browser, and sits on the payment path, so its security requirements are those of a payment page (PCI DSS 4.0 req. 6.4.3 and 11.6.1 are aimed at exactly this kind of page). ADR-008 still holds: card numbers must never reach the platform.

## Decision
- **Sessions:** a merchant calls `POST /v1/checkout-sessions` for one payment in `requires_payment_method` and redirects the customer to the returned URL. The URL carries a 256-bit random token, which is the only credential. Only its SHA-256 is stored, and it is scoped to that one payment. It expires one hour after the payment's own expiry, and a suspended merchant's links stop working.
- **No JavaScript and no third-party assets:**
  - Pages are rendered on the server, and every dynamic value is HTML-escaped.
  - `Content-Security-Policy` is `default-src 'none'`. The one inline stylesheet is allowed by its hash, forms may only post back to this site (`form-action 'self'`), and the page cannot be framed (`frame-ancestors 'none'`).
  - Responses also send `Referrer-Policy: no-referrer`, so the token never leaks in a `Referer`, and `Cache-Control: no-store`.
- **Post/redirect/get:** each form submission starts an attempt through the normal payment domain and is answered with `303` back to the page. The page renders from the payment's current state, so a double click or refresh cannot start a second attempt. Pages still waiting on the customer or PSP reload themselves with `<meta refresh>`.
- **Methods:** the page offers UPI collect (UPI ID), UPI intent (opens the app), card and netbanking, limited to what routing can serve for that payment.
  - Cards and netbanking go to the PSP's page through a plain link. A same-origin form cannot redirect straight to the PSP while `form-action 'self'` is in force, so this is one extra click.
  - The PSP returns the customer to the checkout page, which then offers the merchant's `return_url`.
  - UPI QR is not offered yet, because it needs image rendering.
- **Error messages:** errors are shown as fixed messages selected by a code. Nothing from the query string is echoed into the page.

## Alternatives
| Option | Trade-off |
|---|---|
| SPA plus JS SDK calling a public API | Richer UX; needs publishable keys, CORS and client-side script integrity, which is a much larger attack surface for a demo-grade page |
| Stateless signed tokens (HMAC with an expiry) | No table; needs key management and rotation, and individual links cannot be revoked |
| Embedding PSP card fields (iframes) | Single-page card entry; pulls the page into PCI script-integrity scope for card capture and couples it to one PSP |
| **Server-rendered page, hashed capability token** | Small, auditable surface; a bearer URL can be forwarded, but it can only pay that one order |

## Consequences
- Anyone holding a link can view the order summary and pay it, or send UPI collect requests. The payment attempt limit and risk rules bound the abuse. Merchants must send the link only to the paying customer.
- The token also appears in the idempotent replay of the create response, which is stored for the key's TTL. This is accepted because its reach is the same one payment.
- The platform now needs a public origin (`pg.checkout.public-base-url`). In production the WAF must apply per-IP rate rules to `/checkout/*`, and the ALB must forward client IPs (`server.forward-headers-strategy: native`) so risk rules see the customer's IP.
