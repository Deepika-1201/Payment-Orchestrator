# ADR-021: UPI QR on the hosted checkout as server-rendered inline SVG

**Status:** Accepted (2026-09-26)

## Context
The hosted checkout (ADR-013) offered UPI collect (enter a UPI ID) and UPI intent (open an app on the same phone). A customer on a desktop, the common case for web checkouts, has no UPI app on the device, and many prefer not to type a UPI ID. In India the usual way to pay is to scan a QR code with a phone. The PSPs already return a `display_qr` next action with a `upi://pay?…` payload.

ADR-013 constrains how that QR can be shown:
- the page has no JavaScript and loads no third-party assets;
- the CSP is `default-src 'none'` with only a hashed inline style;
- nothing from the PSP may be injected into the HTML unescaped.

## Decision
- **New option:** the checkout offers **UPI QR** (`upi_qr`, routed like any UPI QR payment) when a linked PSP supports it.
- **Server-side rendering:** for a `display_qr` next action, the server encodes the payload as a QR code (zxing `core`, error correction M, quiet zone 4) and writes it into the page as **inline SVG**, one path of unit squares.
  - Only integer coordinates reach the HTML, so nothing from the payload is ever interpreted by the browser.
  - Inline SVG is part of the document: it needs no `img-src` and no script, so the CSP stays exactly as it was.
- **Payload check:** only `upi://` payloads are rendered. Anything else falls back to text instructions.
- **Status updates:** the waiting page refreshes itself (`<meta http-equiv="refresh">`, 5 s), as the other waiting states do.
- **Test:** the integration test rebuilds the QR matrix from the SVG and decodes it. The page must show exactly the PSP's payload.

## Alternatives
| Option | Trade-off |
|---|---|
| Show the `upi://` string and let the customer copy it | No dependency; unusable on a desktop |
| Client-side QR library | Needs JavaScript and a relaxed CSP (`script-src`), against ADR-013 |
| PNG from a QR image service | Leaks the payment payload to a third party and needs `img-src` for that host |
| PNG rendered by the server (`data:` URI) | Works; needs `img-src data:` in the CSP and yields a larger, non-scalable image |
| **Inline SVG from zxing `core`** | Strict CSP unchanged, crisp at any size, small markup; one small, widely used dependency (Apache-2.0, no transitive dependencies) |

## Consequences
- **New dependency:** `com.google.zxing:core` (encoding only) is a runtime dependency. It is kept current like the others.
- **Expiry:** QR payloads expire with the PSP's next action (5 minutes on the mock). After that the page moves on with the payment's status.
- **Accessibility:** the code has an `aria-label`, and the UPI ID and intent options stay available for customers who cannot scan.
