---
layout: post
title: "Permuplate — Named Rules, Vol2 Bugs, and an Open Question"
date: 2026-04-07
phase: 2
phase_label: "Phase 2 — The Drools DSL Sandbox"
---
# Permuplate — Named Rules, Vol2 Bugs, and an Open Question

**Date:** 2026-04-07
**Type:** phase-update

*Part 12 of the Permuplate development series.*

---

## What I was trying to achieve: close the DSL, validate against vol2, start migration

The plan going into this session was simple: finish the remaining DSL phases, confirm everything aligned with the vol2 reference, then start the real Drools migration. We got through the first two. The third revealed something I hadn't expected.

---

## Phase 6: the sandbox now speaks vol2

The biggest piece was Phase 6 — named rules and typed parameters. In vol2, every rule starts with `builder.rule("name")`. Our sandbox had been using `builder.from("name", source)`, which was always an approximation — a string stuffed into a method that vol2 doesn't have.

Claude and I built `ParametersFirst<DS>`, the class that `builder.rule("name")` returns. It supports four param styles matching vol2's design: typed capture (`.<P3>params()`), individual list (`.param("name", Class)` → `ArgList`), map-based (`.map().param(...)` → `ArgMap`), and typed-per-param (`.<String>param("name")`). Each style runs params as fact[0] at rule execution time, cross-productting with any subsequent joins.

We also changed `fn()` to return `RuleResult<DS>` instead of `RuleDefinition<DS>`. The reason: vol2 chains `.fn(...).end()`, and `end()` needs to exist somewhere. `RuleResult` is a thin wrapper exposing the same query API plus a no-op `end()`. All 68 existing tests continued to pass unchanged — `RuleResult` is a drop-in.

Then I deleted `from(String, Function)`. It wasn't in vol2, it wasn't type-safe, and now that `rule("name").from(source)` exists, it was purely redundant. Small change, right principle.

---

## The cross-reference that found vol2 bugs

I asked Claude to do a full cross-reference against vol2 — reading all 14 test files and all source files. The sandbox came out well: 95%+ API fidelity, documentation accurate, tests more comprehensive than vol2's own suite for the same features.

What Claude flagged that surprised me: vol2 itself has bugs. `RuleExtensionPoint6` is named inconsistently — the rest of the family uses `RuleExtendsPoint`. `Join3Second.path5()` returns `Path4<...>` — wrong type. `ParametersFirst.params()` uses `Class... cls` when it should use `P... cls` (the varargs type-capture pattern). All three bugs are harmless in the sandbox since we either fixed them or abstracted over them. They go on the migration punch list.

The documentation refresh that followed was substantial. Claude rewrote DROOLS-DSL.md from scratch — the old version was Phase 1 focused with Phase 2 buried and Phases 3–6 absent. The new version covers all six phases with working code examples. CLAUDE.md got 15 new rows in the non-obvious decisions table covering Phase 2 through 6. README and OVERVIEW got two-tier architecture references.

---

## The ctx question that opened something bigger

I thought we were a decision away from migration. The last open question was where to position `ctx` in lambda signatures. Currently: `(ctx, a, b) -> ...`. Looks clean. But as I said it out loud I noticed — if the first `join()` gives you `b`, that's already a mild naming concern. People expect the first fact they care about to be `a`.

Then I realised something more significant: I'm going to need two contexts. One for the rule unit (the data sources), one for the node — for imperfect reasoning. Uncertainty can attach to rules, filters, or facts depending on the model. Bayesian probability, fuzzy logic, Dempster-Shafer — the combinator is pluggable, which means the second context's type, position, and threading are all TBD.

With two contexts at the front of every lambda, the first data fact shifts further right. That changes the API contract for every user. I can't lock in ctx position until I know how many contexts the DSL will carry.

So we parked it. The sandbox is migration-ready in every other sense. The ctx refactor is a mechanical one-pass change — once the two-context shape is known, we do it. Until then, migration proceeds with the current convention.

---

## Where this leaves us

Six phases complete. 68 tests. Documentation that actually reflects what was built. Three vol2 bugs logged for the migration punch list. And one open question that turned out to be more interesting than the implementation work that preceded it.

The imperfect reasoning system isn't on the immediate roadmap — but knowing it's coming shapes every API decision between now and then. That's the kind of thing you only learn by getting close enough to migration to feel the constraints.
