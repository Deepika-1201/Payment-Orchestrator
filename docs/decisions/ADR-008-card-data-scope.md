# ADR-008: PAN never enters V1; CDE boundary reserved

**Status:** Accepted (2026-09-26)

## Context
Handling raw card numbers puts the platform in full PCI DSS scope (Level 1 service provider at our volumes): an isolated CDE, HSM/KMS key ceremonies, and annual QSA audits. In India, RBI's card-on-file tokenization rules (since Oct 2022) forbid anyone except issuers and networks from storing card numbers at all. However, BIN-based routing across PSPs needs the card number before the PSP is chosen.

## Decision
- V1 collects card details **only on PSP-hosted pages/fields** (redirect). 3DS is handled by the PSP. We store only non-sensitive metadata (network, last 4, issuer) and PSP/network tokens.
- The PSP for a card payment is chosen **before** card entry (rules on merchant, amount, and capture mode). There is no BIN routing for new cards in V1.
- A future `card-data-service` boundary is reserved: an isolated deployable in its own network segment that would receive the PAN, route by BIN, tokenize via network token services, and never persist the PAN.

## Alternatives
| Option | Trade-off |
|---|---|
| **PSP-hosted collection (V1)** | Minimal PCI scope; less routing flexibility for new cards |
| Own PCI Level 1 card-data service | Full routing flexibility; months of compliance work, separate infrastructure, audits |
| Third-party vault/proxy | Reduces our scope; storing PANs in a third-party vault conflicts with RBI CoFT for Indian cards |

## Consequences
- Logs, DB, and APIs cannot contain PAN or CVV. Log-masking and request-shape tests enforce this.
- Card routing quality depends on rule-based pre-selection until the CDE exists.
