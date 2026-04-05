# Handover — 2026-04-05

**Head commit:** `5e21a31` — docs: add design snapshot 2026-04-05-drools-dsl-sandbox
**Previous handover:** *(none — first handover)*

## What Changed This Session

- **Phase 1.5:** Typed `join()` via standalone method-level `@PermuteTypeParam` +
  propagation; dual `filter()` overloads (single-fact + all-facts)
- **Phase 2:** First/Second split, END phantom type, 15 bi-linear join overloads,
  PermuteMojo multi-template chaining fix
- **Phase 3a:** `not()` / `exists()` scopes with `NegationScope<OUTER,DS>` builder
- **Phase 3b:** OOPath `path2()..path6()` traversal — BaseTuple hierarchy,
  PathN builders, PathContext, correlated execution in RuleDefinition
- **Permuplate core:** `PermuteTypeParamTransformer` Step 5, rename propagation,
  `buildTypeParam` word-boundary fix
- 32 tests passing; pushed to `mdproctor/permuplate` main
- Design snapshot committed: `docs/design-snapshots/2026-04-05-drools-dsl-sandbox.md`
- **Pending:** 4 ADR drafts written but NOT committed (user said "handoff" before YES)

## State Right Now

Drools DSL sandbox complete through Phase 3b. Features match real Drools except:
- `Variable<T>` cross-fact binding (high priority — migration blocker)
- `extensionPoint()` / `extendsRule()` (high priority — migration blocker)
- `ifn()`, `fn()` returning `BaseRuleBuilder<END>`, `index()`, `type()` (lower priority)
- DROOLS-DSL.md needs comprehensive refresh (many outdated sections)
- CLAUDE.md missing Phase 3a/3b non-obvious decisions

## Immediate Next Step

Confirm the 4 ADR drafts (already written in conversation context — just need YES to write):
- 0001: Standalone method-level @PermuteTypeParam with propagation
- 0002: OOPath runtime as pipeline on RuleDefinition
- 0003: END phantom type added in Phase 2 not Phase 3
- 0004: NegationScope as separate class

Then: brainstorm `Variable<T>` — needed before real Drools migration.

## Open Questions

- `Variable<T>` design: how to add cross-fact binding without breaking existing `filter()` API
- `ctx` position in lambda signatures (deferred since Phase 1) — lock in before migration?
- `fn()` return type: `RuleDefinition<DS>` vs `BaseRuleBuilder<END>` — matters for first migration targets?
- Migration order: Consumer family first (pure G1) or start with Join chain?
- DROOLS-DSL.md refresh: defer or do before migration?

## References

| Context | Where |
|---|---|
| Design state | `docs/design-snapshots/2026-04-05-drools-dsl-sandbox.md` |
| Drools DSL design | `permuplate-mvn-examples/DROOLS-DSL.md` |
| Session specs (14 files) | `docs/superpowers/specs/` |
| Previous session handoff | `docs/handoffs/session-handoff-2026-04-04.md` |
| Real Drools reference | `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/java/org/drools/core/RuleBuilder.java` |
| Knowledge garden | `~/claude/knowledge-garden/GARDEN.md` |
