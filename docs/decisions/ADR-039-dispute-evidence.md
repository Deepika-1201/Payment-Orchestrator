# ADR-039: Dispute responses through the API: evidence kept encrypted, delivered to the PSP before its deadline

**Status:** Accepted (2026-10-10). Extends ADR-018.

## Context
Phase 22 (requirements §8.5, FR-D2 and FR-D3) lets a merchant answer a dispute through the gateway: contest it with evidence, or accept it. ADR-018 left both on the PSP's dashboard. Facts that shaped the design, from Razorpay's Disputes and Documents API documentation (2026-10-10):
- **Documents.** `POST /v1/documents` takes one file as `multipart/form-data` with `purpose=dispute_evidence` and returns a `doc_…` id. JPEG, PNG and PDF are accepted, up to 50 MB. Razorpay allows one upload at a time per merchant and refuses a concurrent one ("Document upload already in progress"), to be retried after a few seconds.
- **Contest.** `PATCH /v1/disputes/{id}/contest` takes lists of document ids per evidence category (shipping proof, billing proof, cancellation proof, customer communication, proof of service, explanation letter, refund confirmation, access log, refund and cancellation policy, terms and conditions, and typed "others"), a `summary` of up to 1,000 characters, an optional partial `amount`, and `action`: `draft` or `submit`. Drafts are never submitted automatically. Submitting needs at least one document and moves the dispute from `open` to `under_review`.
- **Accept.** `POST /v1/disputes/{id}/accept` moves an open dispute to `lost`. It cannot be undone.
- **Limits.** Neither call is allowed after `respond_by`, or once the dispute is under review, won, lost or closed. Neither documents an idempotency key.

FR-D2 and FR-D3 cite no RBI or NPCI rule. The deadline is the PSP's `respond_by`, which already reflects the card network's or NPCI's timelines; the gateway's job is to keep to it.

## Decision
- **Evidence files first.** The merchant uploads each file with `POST /v1/disputes/{id}/evidence_files`, as JSON with base64 content, an evidence category (the list above) and a file name.
  - PDF, JPEG or PNG only, checked against the file's first bytes, not just the declared type.
  - Up to 5 MB per file and 10 files per dispute **(assumed)**, well under Razorpay's limits.
  - That one path accepts bodies up to 7 MB; every other path keeps the 256 KB limit (ADR-022).
  - The gateway never serves the files back. They only travel on to the PSP.
- **One response per dispute.** While the dispute is `open` and `respond_by` has not passed, the merchant either contests it (`POST /v1/disputes/{id}/contest`, a statement of up to 1,000 characters and the evidence files to send) or accepts it (`POST /v1/disputes/{id}/accept`). Full amount only.
- **Recorded first, then delivered.** The response is saved under the payment's lock as `pending`, then sent after commit.
  - A contest uploads each listed file not yet at the PSP and keeps the PSP's document id, so a retry never uploads a file twice. It then submits the contest; an acceptance calls the PSP's accept.
  - The call answers 200 when the PSP took the response, else 202 with the response `pending`.
  - A timeout, an unavailable PSP or Razorpay's upload lock leaves the response `pending`. A worker retries it with backoff until `respond_by`.
  - A refusal by the PSP, or a deadline passed before delivery, makes the response `failed`, sends the merchant `dispute.response_failed` and queues the dispute for review (`response_failed`). The merchant may respond again while the dispute is still open and in time.
- **The PSP's answer moves the dispute**, as any PSP report does: a contest normally to `under_review`, an acceptance to `lost`.
  - Accepting books no new entry. The chargeback was posted when the dispute opened (ADR-018), and `lost` leaves it in place: the loss stands in the ledger, and the amount stays out of the refundable amount.
- **Only PSPs that declare dispute responses** take them through the gateway: `MOCK_ALPHA` and Razorpay. On other PSPs (Cashfree, `MOCK_BETA`) the endpoints answer 422 `dispute_response_unsupported`, and merchants keep answering on the PSP's dashboard.
- **Deadline notice.** A worker job sends `dispute.evidence_due` once per dispute, 3 days before `respond_by` **(assumed, `pg.disputes.evidence-due-notice`)**, while the dispute is open and no response is pending or sent.
  - The gauge `pg.disputes.evidence_due` counts the disputes in that state, including those whose deadline has passed without a PSP decision.
  - The `DisputeEvidenceDue` alert opens a ticket while the gauge is above zero.
  - Operators list those disputes with `GET /admin/v1/disputes?evidence_due=true` and contact the merchants.
- **Encrypted, never deleted.** Files are stored in PostgreSQL, each encrypted with AES-256-GCM under a key of its own, bound to the file's id.
  - That key is stored wrapped by the data key ring (ADR-025), so a key rotation re-wraps file keys, never the files.
  - Evidence is part of the dispute record (NFR-16): the application never deletes it (ADR-015). The application's database role can neither delete evidence rows nor change their content (ADR-026).
- **Mock PSP and Razorpay.** `MOCK_ALPHA` keeps what it receives, so tests can check that the evidence reached the PSP, and its scenarios cover timeouts and refusals. Razorpay uses the three calls above. A refusal because the dispute has moved on is checked with `GET /v1/disputes/{id}`.

## Alternatives

| Option | Trade-off |
|---|---|
| Multipart uploads, as Razorpay and Stripe take them | A third smaller, but a retried multipart body differs in its boundary, so an `Idempotency-Key` retry would be refused as a different request. The contract tests also cover only JSON |
| Send files to the PSP as they arrive (PSP drafts) | Nothing kept at the gateway, but evidence would not be kept with the dispute record (FR-D3), drafts differ between PSPs, and abandoned uploads would pile up at the PSP |
| Store files in S3 | Cheaper per GB and the usual home for files, but it needs a bucket, KMS keys and cross-region replication in Terraform and an AWS SDK. PostgreSQL handles the expected volume (hundreds of files a day). Revisit when evidence passes about 100 GB |
| Encrypt files directly with the data key ring | One scheme instead of two, but rotating a data key would mean re-encrypting every file |
| Partial contest | Razorpay supports it, but it is rare. A merchant can contest in full or accept |
| Drafts and edits before submitting | Matches Razorpay's `draft` action, but PSPs refuse changes after submission anyway, and the merchant can upload until it contests |
| Several reminders (7, 3 and 1 days) | More nudges, but one notice plus an alert for operators covers it. The window is configurable |

## Consequences
- On supported PSPs, merchants can answer disputes without the PSP's dashboard, and the dispute shows the response and whether it was delivered.
- A response that is not delivered is never silent: the merchant sees it `failed`, operations get a review item, and the evidence-due alert stays on until someone acts.
- Evidence lands in PostgreSQL, a few hundred MB a day at the planned volume. Its backup and archiving follow the dispute records (ADR-015).
- The upload path has its own body limit, a second number to keep in mind next to ADR-022's.
- Whether Razorpay applies the same rules in its sandbox is confirmed with keys (C1).
