---
layout: post
title: "Naming the Gate"
date: 2026-04-24
type: phase-update
entry_type: note
subtype: diary
projects: [permuplate]
phase: 4
phase_label: "Phase 4 — JEXL String Assistance"
---

There's a moment in design work where you realise the name you've been using for something is technically correct but conceptually wrong — it describes *when* something happens rather than *what* it does. That moment came today with `JoinNSecond`.

The class family has always had two halves: the one you work with most of the time, holding `filter()`, `var()`, and `index()` — things you call repeatedly, before you're done accumulating facts — and the one holding `join()`, `fn()`, and the scope methods, the operations that advance the chain or terminate it. I'd named them `First` and `Second`. First for the initial work; Second for what comes after.

That's defensible, but it implies a sequence. Claude pushed back: "Second implies temporal order, not capability." I explained what I'd meant — First is what the DSL author does first, repeatedly; Second is where they go after. As I was explaining it, the better name surfaced: **Gate**. Not second chronologically — the gateway into the next stage of the chain.

`JoinNGate` says something. `JoinNSecond` just counts.

The rename was one line of sed in each repo.

---

While reviewing the design, three other things surfaced.

The first was the `END` phantom type. Every generated class carries it to enable typed scope chain-back — `not().join().filter().end()`, where `end()` returns the outer builder with the correct arity restored. I'd built this deliberately.

Claude's question was simple: is `END` actually in vol2?

I checked. `Join2<CTX, B, C>`. Three type parameters. No `END`. The phantom type was a sandbox invention to support a scope pattern that vol2 doesn't have — and since we've already agreed that `not()` and `exists()` should take a lambda rather than chain through `end()`, there's no reason to keep it. That's queued for the next session: signatures go from `Join2First<END, DS, A, B>` to `Join2First<DS, A, B>`. Cleaner, and more faithful to the real codebase.

The second thing was vol2 itself. I went to apply the Gate rename and found it further along than I'd assumed. `Consumer2` already has no `ctx` parameter — `accept(A a, B b)`, not `accept(DS ctx, A a, B b)`. The `RuleBuilder` is already a Permuplate template, generating up to arity 10, living in a `src/main/permuplate/` directory alongside `RuleExtendsPoint.java`, `RuleOOPathBuilder.java`, and `BaseTuple.java`.

Vol2 has been building the real version while the sandbox was proving the concept. The Gate rename there was one file, no test updates.

---

Three things from one design review: a name that finally says what it means, a phantom type to remove, and a real codebase that's further ahead than I thought. Not bad.
