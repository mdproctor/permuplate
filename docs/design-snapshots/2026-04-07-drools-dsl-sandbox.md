# Permuplate Drools DSL Sandbox — Design Snapshot
**Date:** 2026-04-07
**Topic:** Full design state of the Drools DSL sandbox — Phases 1 through 6, post-documentation refresh and vol2 cross-reference validation
**Supersedes:** [2026-04-06-drools-dsl-sandbox](2026-04-06-drools-dsl-sandbox.md)
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

The Permuplate Drools DSL sandbox is a fully functional, type-safe approximation of the real Drools RuleBuilder DSL, generated from annotated Java templates. All six phases are complete: linear join chain with typed joins and dual filters (Phase 1), First/Second class hierarchy with END phantom type and bi-linear joins (Phase 2), `not()`/`exists()` constraint scopes (Phase 3a), `path2()..path6()` OOPath traversal (Phase 3b), `Variable<T>` named cross-fact binding (Phase 4), `extensionPoint()`/`extendsRule()` cross-rule inheritance (Phase 5), and `builder.rule("name")` with four param styles plus `RuleResult<DS>` and `fn().end()` chain-back (Phase 6). The sandbox has 68 passing tests across four test classes. A comprehensive cross-reference against the full vol2 implementation (all source + all 14 test files) confirmed 95%+ API fidelity and accurate documentation. All documentation has been refreshed: DROOLS-DSL.md fully rewritten for Phases 1–6, CLAUDE.md updated with all non-obvious decisions, README.md and OVERVIEW.md updated with two-tier architecture references, ADR-0005 formalises the scope boundary, and three vol2 bugs are logged in the idea log for fixing during real Drools migration.

---

## How We Got Here

Key decisions made to reach this point:

| Decision | Chosen | Why | ADR |
|---|---|---|---|
| Standalone method-level `@PermuteTypeParam` with propagation | New Step 5 in `PermuteTypeParamTransformer` | Enables typed `join()` without explicit `@PermuteDeclr` on each param | [ADR-0001](../adr/0001-standalone-method-level-permutetypeparam-with-propagation.md) |
| OOPath runtime as pipeline on `RuleDefinition` | Separate `ooPathRootIndex` + `ooPathSteps`; correlated execution in `matchedTuplesFrom()` | No breaking changes to `TupleSource` interface | [ADR-0002](../adr/0002-oopath-runtime-as-pipeline-on-ruledefinition.md) |
| END phantom type added proactively in Phase 2 | Added at Phase 2 alongside First/Second split | Adding later would require retroactive updates to all signatures and call sites | [ADR-0003](../adr/0003-end-phantom-type-added-in-phase-2.md) |
| `NegationScope` as separate class, not `JoinNSecond` subtype | `NegationScope<OUTER,DS>` is independent | Avoids `rd`-conflict: scope's `join()` must not add to outer rule's sources | [ADR-0004](../adr/0004-negationscope-as-separate-class.md) |
| Sandbox scope boundary: DSL design only | `RuleDefinition.matchedTuples()` is the entire runtime; Rete out of scope | Sandbox is a proving ground for API design, not a Rete reimplementation | [ADR-0005](../adr/0005-sandbox-scope-boundary.md) |
| `path2()..path6()` fixed arity (no `end()`) | Fixed-arity methods | Depth visible at a glance; no `end()` tail noise | — |
| `Variable<T>` index-binding approach | `Variable.of("$name")` with `bind(int)` | Bypasses `wrapPredicate()` reflection; named for DRL migration fidelity | — |
| `extendsRule()` as build-time deduplication | `copyInto()` at build time | Rete handles node-sharing automatically; no runtime concept needed | — |
| `from(String, Function)` removed | Only `from(Function)` and `rule("name").from()` remain | Not in vol2, not type-safe; `rule("name")` now provides the name | — |
| `builder.rule("name")` as canonical entry | `ParametersFirst<DS>` with four param styles | Matches vol2 API exactly; enables `fn().end()` chain-back via `RuleResult` | — |
| `addParamsFact()` for filter-trim correctness | Increments `accumulatedFacts` at build time | `wrapPredicate()` uses `registeredFactCount` to trim; params at fact[0] must be accounted for | — |
| `RuleResult<DS>` as `fn()` return type | Thin wrapper with `end()` no-op | Enables `.fn(...).end()` to compile; same query API — no test changes | — |
| `index()` as no-op DSL hint | `return this` on `Join0First` | vol2 API completeness; Rete optimisation hint ignored in sandbox runtime | — |

---

## Where We're Going

**Next steps:**
- Lock in `ctx` position in lambda signatures — `(ctx, a, b) -> ...` is the convention; this is the last decision before migration begins
- Real Drools migration — Consumer family first (pure G1), then Predicate (G1), then Join chain (G1+G2+G3+extends)
- Fix three vol2 bugs identified in cross-reference analysis (see `docs/ideas/IDEAS.md`)

**Open questions:**
- `ctx` position in lambda signatures — currently always first `(ctx, a, b) -> ...`; must be confirmed and locked before migration touches real Drools files
- `NegationScope` type safety — `join()` and `filter()` inside `not()`/`exists()` scopes require double-cast `(Object)(Predicate2<Ctx,A>)`; accept as sandbox limitation or redesign before migration?
- Migration priority — which specific real Drools files first, and in what order?

---

## Linked ADRs

| ADR | Decision |
|---|---|
| [ADR-0001](../adr/0001-standalone-method-level-permutetypeparam-with-propagation.md) | Standalone method-level `@PermuteTypeParam` with rename propagation |
| [ADR-0002](../adr/0002-oopath-runtime-as-pipeline-on-ruledefinition.md) | OOPath runtime as pipeline on `RuleDefinition` |
| [ADR-0003](../adr/0003-end-phantom-type-added-in-phase-2.md) | END phantom type added alongside First/Second split in Phase 2 |
| [ADR-0004](../adr/0004-negationscope-as-separate-class.md) | `NegationScope` as separate builder class, not `JoinNSecond` subtype |
| [ADR-0005](../adr/0005-sandbox-scope-boundary.md) | Sandbox scope boundary: DSL API design only; Rete engine out of scope |

---

## Context Links

- Drools DSL design doc: [`permuplate-mvn-examples/DROOLS-DSL.md`](../../permuplate-mvn-examples/DROOLS-DSL.md) *(fully rewritten 2026-04-07)*
- Vol2 cross-reference analysis: completed 2026-04-07; confirmed 95%+ API fidelity, all docs accurate
- Vol2 bugs to fix: [`docs/ideas/IDEAS.md`](../ideas/IDEAS.md)
- Knowledge garden entry: `~/claude/knowledge-garden/drools/rule-builder-dsl.md` — `extendsRule()` as authoring-time deduplication
- Real Drools reference: `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/java/org/drools/core/RuleBuilder.java`
