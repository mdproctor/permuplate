# Handover — 2026-04-07

**Head commit:** `7b62fbc` — docs(blog): add entry 012 — named rules, vol2 bugs, and open question
**Previous handover:** `git show HEAD~1:HANDOVER.md`

---

## What Changed This Session

- **Phase 6 complete:** `builder.rule("name")`, `ParametersFirst`, four param styles, `ArgList`, `ArgMap`, `RuleResult<DS>` (fn() return type). 68 tests, all pass.
- **API cleanup:** `from(String, Function)` removed — not in vol2, not type-safe. Only `from(Function)` remains.
- **API additions:** `Variable.of("name")`, `type()`, `as()`, `index()` (no-op DSL hint matching vol2).
- **Vol2 full cross-reference:** All 14 test files + source read. Sandbox 95%+ fidelity. Three vol2 bugs found — logged in `docs/ideas/IDEAS.md`.
- **Documentation refresh:** DROOLS-DSL.md fully rewritten (Phase 1–6). CLAUDE.md +15 non-obvious decision rows. README + OVERVIEW two-tier architecture sections. All accurate.
- **ADR-0005:** Sandbox scope boundary — DSL design only, Rete engine out of scope.
- **Design snapshot:** `docs/design-snapshots/2026-04-07-drools-dsl-sandbox.md`
- **Blog entry 012:** `docs/blog/2026-04-07-01-named-rules-vol2-bugs-open-question.md`
- **Garden submission:** GE-0057 — `addParamsFact()` silent wrong-fact extraction bug.
- **Ideas logged:** Vol2 bugs (3), ctx + two-context design, IDE refactoring safety.

---

## State Right Now

Sandbox is migration-ready. Six phases complete, 68 tests, documentation current.

**Two open questions (parked, do not block migration):**
1. `ctx` position in lambda signatures — deferred until two-context shape is known
2. Two-context design for imperfect reasoning (Bayesian/fuzzy/Dempster-Shafer) — pluggable, TBD on first implementation

**NegationScope type safety** — double-cast inside not()/exists() is sandbox-only; real Drools types scopes properly. Not a migration blocker.

---

## Immediate Next Step

Start real Drools migration. Order: Consumer family (pure G1) → Predicate (G1) → Join chain (G1+G2+G3+extends). Fix three vol2 bugs as they're encountered (see `docs/ideas/IDEAS.md`).

---

## References

| Context | Where | Retrieve with |
|---|---|---|
| Design state | `docs/design-snapshots/2026-04-07-drools-dsl-sandbox.md` | read directly |
| Open questions | `docs/ideas/IDEAS.md` | read directly |
| Vol2 reference | `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/java/org/drools/core/RuleBuilder.java` | read on demand |
| ADRs | `docs/adr/0001–0005` | read on demand |
| Last blog | `docs/blog/2026-04-07-01-named-rules-vol2-bugs-open-question.md` | read on demand |
