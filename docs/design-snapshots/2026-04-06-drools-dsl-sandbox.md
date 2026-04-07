# Permuplate Drools DSL Sandbox — Design Snapshot
**Date:** 2026-04-06
**Topic:** Full design state of the Drools DSL sandbox — Phases 1 through 3b, post-ADR formalisation
**Supersedes:** [2026-04-05-drools-dsl-sandbox](2026-04-05-drools-dsl-sandbox.md)
**Superseded by:** [2026-04-07-drools-dsl-sandbox](2026-04-07-drools-dsl-sandbox.md)

---

## Where We Are

The Permuplate Drools DSL sandbox is a fully functional, type-safe approximation of the real
Drools RuleBuilder DSL, generated from annotated Java templates using Permuplate's annotation
processor. Phases 1–3b are complete: a fluent rule-builder chain with typed joins, dual filters,
First/Second class hierarchy with END phantom type, bi-linear joins (15 overloads),
`not()`/`exists()` constraint scopes, and `path2()..path6()` OOPath object-graph traversal.
The sandbox has 32 passing tests. Four key architectural decisions have been formalised as ADRs
(0001–0004). The development blog has 7 entries covering the full Permuplate implementation
journey, revised to the current writing style guide; entries 008–011 (work since blog 007) are
planned but not yet written.

---

## How We Got Here

The full decision table is in the [2026-04-05 snapshot](2026-04-05-drools-dsl-sandbox.md) and
carries forward unchanged. Key entries that now have formal ADRs:

| Decision | Chosen | Why | ADR |
|---|---|---|---|
| Standalone method-level `@PermuteTypeParam` with propagation | New Step 5 in `PermuteTypeParamTransformer` | Enables typed `join()` without explicit `@PermuteDeclr` on each param | [ADR-0001](../adr/0001-standalone-method-level-permutetypeparam-with-propagation.md) |
| OOPath runtime as pipeline on `RuleDefinition` | Separate `ooPathRootIndex` + `ooPathSteps`; correlated execution in `matchedTuples()` | No breaking changes to `TupleSource` interface | [ADR-0002](../adr/0002-oopath-runtime-as-pipeline-on-ruledefinition.md) |
| END phantom type alongside First/Second split (Phase 2) | Added at Phase 2, not deferred to Phase 3 | Adding later would require retroactive updates to all class signatures and call sites | [ADR-0003](../adr/0003-end-phantom-type-added-in-phase-2.md) |
| `NegationScope` as separate class, not `JoinNSecond` subtype | `NegationScope<OUTER,DS>` is independent | Avoids `rd`-conflict: scope's `join()` must not add to the outer rule's sources | [ADR-0004](../adr/0004-negationscope-as-separate-class.md) |

---

## Where We're Going

**Next steps:**
- Write blog entries 008–011: typed joins, First/Second split, not()/exists(), OOPath traversal
- `Variable<T>` cross-fact binding — `var(v1).filter(v1, v2, pred)` pattern; required for real Drools migration
- `extensionPoint()` / `extendsRule()` — cross-rule fact inheritance
- DROOLS-DSL.md comprehensive refresh — Phase 3a/3b decisions not yet documented there
- CLAUDE.md non-obvious decisions table — Phase 3a/3b entries missing (fn() on Second, `when="i<6"`, `Iterable<B>`, PathContext clean impl)
- Real Drools migration: Consumer family first (pure G1), then Predicate (G1), then Join chain (G1+G2+G3)

**Open questions:**
- `ctx` position in lambda signatures — first in every lambda `(ctx, a, b) -> ...`; lock in before migration?
- `fn()` return type — currently `RuleDefinition<DS>`; real Drools returns `BaseRuleBuilder<END>` for scope chain-back
- `NegationScope` type safety — `join()` inside not-scope is untyped; accept or redesign before migration?
- `Variable<T>` design — how to add cross-fact binding without breaking existing `filter()` API
- Migration priority — which specific Drools files first, in what order?

---

## Linked ADRs

| ADR | Decision |
|---|---|
| [ADR-0001](../adr/0001-standalone-method-level-permutetypeparam-with-propagation.md) | Standalone method-level `@PermuteTypeParam` with rename propagation |
| [ADR-0002](../adr/0002-oopath-runtime-as-pipeline-on-ruledefinition.md) | OOPath runtime as pipeline on `RuleDefinition` |
| [ADR-0003](../adr/0003-end-phantom-type-added-in-phase-2.md) | END phantom type added alongside First/Second split in Phase 2 |
| [ADR-0004](../adr/0004-negationscope-as-separate-class.md) | `NegationScope` as separate builder class, not `JoinNSecond` subtype |

---

## Context Links

- Drools DSL design doc: [`permuplate-mvn-examples/DROOLS-DSL.md`](../../permuplate-mvn-examples/DROOLS-DSL.md)
- Writing style guide: `~/claude-workspace/writing-styles/blog-technical.md`
- 2000AD publication system (shelved): `~/claude-workspace/2000AD/`
- Real Drools reference: `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/java/org/drools/core/RuleBuilder.java`
