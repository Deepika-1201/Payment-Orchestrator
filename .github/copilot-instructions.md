# Commit Naming

- Follow this project's phase-first commit subjects, not Conventional Commit prefixes such as `feat:` or `docs:`.
- Design work: `Phase <number> design: <specific summary> (ADR-<number>)`.
- Implementation and phase follow-ups: `Phase <number>: <specific summary> (ADR-<number>)`.
- Include the relevant ADR identifier when applicable; use the existing phase number for follow-ups.
- Example: `Phase 26 design: aggregator onboarding, encrypted KYC and independent approval (ADR-043)`.
- For work outside a phase, use a descriptive topic-first subject, matching earlier commits such as `Implementation plan: ...` or `Image: ...`; do not invent a phase number.
- Renaming a published commit requires explicit confirmation before rewriting history or force-pushing.