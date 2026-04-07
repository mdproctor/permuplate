# 0005 — Sandbox scope boundary: DSL API design validation only

Date: 2026-04-07
Status: Accepted

## Context and Problem Statement

The Permuplate Drools DSL sandbox is a type-safe approximation of the Drools RuleBuilder DSL, generated from annotated Java templates. Without an explicit scope boundary, future contributors may attempt to implement Rete network internals, rule registries, or event-propagation infrastructure inside the sandbox — work that is both large and unnecessary for the sandbox's actual purpose.

## Decision Drivers

* The sandbox exists to validate that Permuplate can generate the arity-indexed class families that make up the Drools API
* Real Drools migration is the end goal; the sandbox is the proving ground, not the destination
* Keeping the sandbox minimal preserves clarity and reduces maintenance burden

## Considered Options

* **Option A — Minimal sandbox:** DSL fluent API only; runtime uses a simple cross-product execution model (`RuleDefinition`); Rete engine, rule registry, and propagation infrastructure are out of scope
* **Option B — Full Rete sandbox:** Implement `PropagatingDataStore`, beta memory, `RuleBase`, `RuleBaseModifier`, tuple predicate caches, and the full propagation system inside the sandbox
* **Option C — Incremental expansion:** Start minimal, expand toward Rete as migration needs arise

## Decision Outcome

Chosen option: **Option A — Minimal sandbox**, because the purpose of the sandbox is DSL API design validation, not Rete engine reimplementation. Option B would consume months of effort for no migration benefit — the real Drools engine already exists. Option C risks creeping toward B without clear stopping criteria.

### Positive Consequences

* Sandbox remains small, comprehensible, and fast to iterate on
* All 68 tests execute in milliseconds using the cross-product model
* Clear boundary: `RuleDefinition.matchedTuples()` is the entire runtime
* Contributors know exactly what belongs in the sandbox and what doesn't

### Negative Consequences / Tradeoffs

* Sandbox cannot validate Rete-specific behaviours (node sharing, beta memory, incremental updates, propagation order)
* `not()`/`exists()` scopes evaluate globally rather than per outer-tuple (documented sandbox simplification)
* Performance characteristics differ from real Drools — sandbox is unsuitable for load testing

## Explicitly Out of Scope

The following vol2 components are out of scope for the sandbox:

| Component | vol2 Class | Reason |
|---|---|---|
| Rete propagation | `PropagatingDataStore`, `Filter1`, `Router` | Full Rete engine already exists in real Drools |
| Rule registry | `RuleBase`, `RuleBaseModifier` | Lifecycle management belongs in real Drools |
| Execution engine | `Executor`, `RuleUnit` | Run-time concern; sandbox uses `rule.run(ctx)` |
| Tuple cache | `LinearTuplePredicateCache`, `BiLinearTuplePredicateCache` | Memory optimization; irrelevant at sandbox scale |
| Context adapters | `ContextPojoDS`, `ContextMapDS`, `ContextRouterAdapter` | Infrastructure; sandbox uses plain `DS` records |

## Links

* [2026-04-07 design snapshot](../design-snapshots/2026-04-07-drools-dsl-sandbox.md) — current state confirming Phase 1–6 complete within this scope
* vol2 reference: `/Users/mdproctor/dev/droolsoct2025/droolsvol2/`
