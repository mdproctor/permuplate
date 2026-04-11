---
layout: post
title: "Ruling Things Out: not() and exists()"
date: 2026-04-06
phase: 2
phase_label: "Phase 2 — The Drools DSL Sandbox"
---
# Ruling Things Out: not() and exists()

*Part 10 of the Permuplate development series.*

---

## Two Modes of "No Match"

Rule engines have two fundamental constraint patterns beyond joining. A fact combination is valid ONLY IF some condition has no matches — "there is no account with a balance over £1000." Or ONLY IF some condition has at least one match — "there exists such an account." The outer chain's arity is preserved in both cases — scope facts constrain, then disappear.

---

## Separate Builders, Not Separate Classes

The obvious design for `not()` was a `JoinNNot` class extending `JoinNSecond`. Claude and I considered it and rejected it. The problem: inheriting `join()` from `JoinNSecond` would add sources to the outer `RuleDefinition`, but inside a scope all sources must go to the scope's own private `RuleDefinition`. The two are directly in conflict.

`NegationScope<OUTER, DS>` is an independent class instead. It captures the outer builder as a typed `OUTER` parameter, builds its own internal `RuleDefinition`, and returns `OUTER` from `end()`.

```java
public class NegationScope<OUTER, DS> {
    private final OUTER outer;
    private final RuleDefinition<DS> notRd;

    public NegationScope<OUTER, DS> join(Function<DS, DataSource<?>> source) {
        notRd.addSource(source);   // own RuleDefinition, not the outer
        return this;
    }

    public OUTER end() { return outer; }
}
```

The `not()` method on the template uses a JEXL expression that constructs the return type string dynamically per arity:

```java
@PermuteReturn(
    className = "NegationScope",
    typeArgs = "'Join' + i + 'Second<END, DS, ' + typeArgList(1, i, 'alpha') + '>, DS'",
    when = "true")
public Object not() {
    RuleDefinition<DS> notRd = new RuleDefinition<>("not-scope");
    rd.addNegation(notRd);
    return new NegationScope<>(this, notRd);
}
```

At i=2, the return type resolves to `NegationScope<Join2Second<END, DS, A, B>, DS>`. Calling `end()` returns `Join2Second<END, DS, A, B>` — the outer chain exactly as it was. `ExistenceScope` follows the same pattern with `rd.addExistence(notRd)` and different runtime evaluation logic.

---

## The fn() Placement Bug

Testing the chain revealed a problem:

```java
builder.from("persons", ctx -> ctx.persons())
       .not()
           .join(ctx -> ctx.accounts())
           .filter(...)
       .end()    // returns Join1Second<Void, Ctx, Person>
       .fn(...)  // compile error — fn() not on JoinNSecond
```

`fn()` had been on `Join0First` only. After `end()` the chain is back at `JoinNSecond`, not `JoinNFirst`. The fix was moving `fn()` to `Join0Second`. `Join0First` inherits it via `extends`, so nothing broke for direct-chain tests.

---

## Inside a Scope, the Types Go Dark

Inside a scope, `join()` and `filter()` are intentionally untyped:

```java
builder.from("persons", ctx -> ctx.persons())
       .not()
           .join(ctx -> ctx.accounts())
           .filter((Object) (Predicate2<Ctx, Account>) (ctx, b) -> b.balance() > 500.0)
       .end()
       .fn((ctx, a) -> { });
```

The explicit cast is the limitation. `NegationScope.filter()` takes `Object` — the typed predicate API requires a generated class with per-fact type parameters, and `NegationScope` is handwritten. The outer chain stays fully typed throughout; only the internals of the scope are untyped. For the sandbox this is acceptable. Full Drools has `Not2 extends Join2Second` — typed throughout — at the cost of the `rd`-conflict problem that drove the independent-class decision in the first place.

Three scope tests all passing.
