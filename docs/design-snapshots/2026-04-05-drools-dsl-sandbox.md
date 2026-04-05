# Permuplate Drools DSL Sandbox — Design Snapshot
**Date:** 2026-04-05
**Topic:** Full design state of the Drools RuleBuilder sandbox — Phases 1 through 3b
**Supersedes:** *(none — first snapshot)*
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

The Permuplate Drools DSL sandbox is a fully functional, type-safe approximation of the real
Drools RuleBuilder DSL (`droolsvol2/src/main/java/org/drools/core/RuleBuilder.java`), generated
from annotated Java templates using Permuplate's annotation processor. Phases 1–3b are complete:
a fluent rule-builder chain with typed joins, dual filters, First/Second class hierarchy with END
phantom type, bi-linear Rete-style joins (15 overloads), `not()`/`exists()` constraint scopes, and
`path2()..path6()` OOPath object-graph traversal. The sandbox has 32 passing tests across all
features and serves as the primary evolution testbed for the Drools API before applying changes to
the production codebase.

---

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Template location: `src/main/permuplate/` with `inline=true` | Inline Maven plugin mode | `@PermuteReturn` boundary omission only works in InlineGenerator; APT can't modify parent files | APT mode (`src/main/java/`) — lacks boundary omission, can't write nested sibling classes |
| Alpha naming (A, B, C, D...) | Single-letter Drools-style | Faithful to real Drools conventions; makes migration direct | T${j} naming — enables implicit zero-annotation inference but diverges from Drools convention |
| Container class pattern (JoinBuilder) | All generated classes nested in JoinBuilder | `inline=true` requires a container parent class | Top-level generated classes — APT could do it but loses boundary omission |
| Method bodies not transformed by Permuplate | Use reflection for `new Join${i+1}First(rd)` | Permuplate doesn't evaluate expressions in method bodies | Template body transformation — would require fundamentally new Permuplate features |
| `fn()` on Join0Second (not just First) | Moved fn() from First to Second | `not()…end()` returns JoinNSecond; calling fn() after end() requires it on Second | fn() only on First — discovered to cause compile errors when chaining after not() |
| OOPath runtime: Option A (pipeline on RuleDefinition) | Separate `ooPathRootIndex` + `ooPathSteps` list; correlated execution in `matchedTuples()` | No breaking changes to TupleSource interface; isolated OOPath mode | Option B: generalise TupleSource to take current facts — changes existing interface |
| BaseTuple hierarchy: mutable inheritance chain | Tuple1 extends BaseTuple, Tuple2 extends Tuple1, etc. with mutable `set(int,T)` | Incremental population during traversal — records are immutable | Java records for TupleN — immutable, can't be populated step-by-step during traversal |
| PathContext clean implementation | Simple wrapper holding the tuple-in-progress | Drools' own PathContext has a buggy constructor (fall-through switch without breaks) | Copy Drools PathContext — would propagate the bug |
| `Iterable<B>` in PathN.path() (vs `?`) | `Function2<PathContext<T>, A, Iterable<B>>` | Enforces that traversal fn and filter pred agree on the child element type B | Wildcard `?` — compiles but allows silent type mismatch between fn return and pred parameter |
| PermuteMojo multi-template chaining | Group inline templates from same parent; chain generate() calls | Two templates (Join0Second, Join0First) in same JoinBuilder.java — second write would overwrite first | Process independently — second template's output overwrites the first (the old bug) |
| END phantom type added in Phase 2 | Added alongside First/Second split | Would have been a breaking API change if deferred to Phase 3 when `not()` needed it | Add in Phase 3 with `not()` — forced retroactive update to every class signature and call site |
| NegationScope as separate class | `NegationScope<OUTER,DS>` not extending JoinNSecond | Simpler; avoids the rd-conflict problem (not-scope vs outer rd share same generated rd field) | Not2 extending Join2Second — conflicts: join() inside scope would add to outer rd, not scope rd |
| `when="i < 6"` for pathN() boundary suppression | JEXL `when` evaluates to "true" for i<6 → keep; "false" for i=6 → apply boundary omission | Path2..6 are hand-written classes, not in Permuplate's generated set; boundary omission would remove all pathN() | `when="true"` — would keep pathN() on Join6Second, referencing non-existent Join7First |
| typeArgList() with dynamic from/to in JEXL | `typeArgList(i, i+2, 'alpha')` for path3() Tuple3 content | Consistent with the existing typeArgList function; generates correct alpha sequences at any arity | Hardcoded alpha() calls — verbose and error-prone for 5 different pathN() methods |
| Standalone method-level @PermuteTypeParam | New Step 5 in PermuteTypeParamTransformer.transform() | Enables typed join() where `<B>` is renamed per arity and propagated into DataSource<B> | @PermuteMethod with degenerate range — hack; or Object parameter requiring casts |
| Propagation of type param renames | Inside transformMethod(): after rename, walk parameters updating type strings (word-boundary safe) | Eliminates need for @PermuteDeclr on parameters when the rename is single-value (from==to) | No propagation — requires explicit @PermuteDeclr on every parameter referencing the renamed type |
| RuleDefinition.addSource takes Object | `addSource(Object)` with internal unchecked cast | Template's `join()` passes `Function<DS,DataSource<B>>` — incompatible with `Function<DS,DataSource<?>>` via invariance; Object avoids the issue | Typed `Function<DS,DataSource<?>>` — fails template compilation due to generic invariance |
| wrapPredicate captures registration position | `int registeredFactCount = accumulatedFacts` at addFilter() time | `sources.size()` was incorrect once bi-linear sources (1 entry, N fact columns) were added | `sources.size()` — wrong for bi-linear sources; caused single-fact filter to pick wrong fact |

---

## Where We're Going

The sandbox is feature-complete enough to begin validating the real Drools migration path for the
Consumer and Predicate families (pure G1). The Join chain (G1+G2+G3) requires two more sandbox
features before migration.

**Next steps:**
- `Variable<T>` cross-fact binding — `var(v1).filter(v1, v2, pred)` pattern from Drools `testCompactFilter`; required for the real migration
- `extensionPoint()` / `extendsRule()` — cross-rule fact inheritance; used in real Drools for rule hierarchies
- DROOLS-DSL.md comprehensive refresh — many outdated sections, missing rationale for key design decisions
- CLAUDE.md updates — Phase 3a/3b non-obvious decisions not yet added (fn() on Second, when="i<6", Iterable<B>, PathContext clean impl)
- Real Drools migration: Consumer family first (pure G1), then Predicate family (G1), then Join chain (G1+G2+G3)

**Open questions:**
- `ctx` position in lambda signatures — currently `ctx` is first in every lambda `(ctx, a, b) -> ...`. Deferred since Phase 1; may be worth locking in before migration begins.
- `fn()` return type — currently returns `RuleDefinition<DS>`. Real Drools returns `BaseRuleBuilder<END>` for nested scope chain-back. Does this matter for the first migration targets?
- `NegationScope` type safety — inside a not-scope, `join()` is untyped. Real Drools has fully typed `Not2 extends Join2Second`. Accept this limitation or redesign before migration?
- `Variable<T>` design — how to add cross-fact binding without breaking the existing `filter()` API.
- Migration priority — which specific Drools files to migrate first and in what order?

---

## Linked ADRs

*(No formal ADRs have been created for this project yet. Key decisions are captured in CLAUDE.md's
non-obvious decisions table and the spec files under `docs/superpowers/specs/`.)*

---

## Context Links

- Drools DSL design doc: [`permuplate-mvn-examples/DROOLS-DSL.md`](../../permuplate-mvn-examples/DROOLS-DSL.md)
- Specs: [`docs/superpowers/specs/`](../superpowers/specs/) — 14 spec files covering N4 through Phase 3b
- Plans: [`docs/superpowers/plans/`](../superpowers/plans/) — implementation plans for each feature
- Real Drools reference: `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/java/org/drools/core/RuleBuilder.java`
- Session handoff: [`docs/handoffs/session-handoff-2026-04-04.md`](../handoffs/session-handoff-2026-04-04.md)
