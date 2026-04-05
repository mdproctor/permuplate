# Spec: Phase 3a — not() and exists() Scopes

**Date:** 2026-04-05
**Status:** Approved
**Motivation:** Add negation (`not()`) and existence (`exists()`) constraint scopes to the Drools DSL sandbox. These are the first Phase 3 features — they use the END phantom type infrastructure from Phase 2 and complete the core rule-constraint vocabulary before OOPath.

---

## Background: Rete NegativeExistsNode

In Drools Rete networks, `not()` and `exists()` create constraint sub-networks:

- **`not()`** — an outer fact combination is valid ONLY IF the inner sub-network produces **zero** matching tuples. Models "there is no X that satisfies condition Y."
- **`exists()`** — an outer fact combination is valid ONLY IF the inner sub-network produces **at least one** matching tuple. Models "there exists an X that satisfies condition Y."

The inner sub-network (scope) builds independently: you can join additional facts and add filters inside it. The `end()` call returns to the outer builder at its original arity — the scope facts do NOT accumulate into the outer chain.

**DSL pattern (from real Drools test):**

```java
builder.rule("rule1").<Params3>params()
       .join(Ctx::persons).filter((ctx, b) -> b.age() > 20)   // outer: arity 2
       .not()
           .join(builder.from(Ctx::misc).type().filter(...))   // scope: arity 3
           .join(Ctx::libraries)                               // scope: arity 4
       .end()                                                  // returns to arity 2
       .fn((a, b, c) -> ...)                                   // outer fn: (ctx, Params3, Person)
       .end();
```

**END phantom type integration:** `not()` / `exists()` are the first callers of the END infrastructure built in Phase 2. When `end()` is called on the inner join chain, it returns the `NegationScope`/`ExistenceScope` (which is the END of those inner joins). The scope's own `end()` then returns the outer builder — restoring the outer arity and type chain.

---

## Sandbox Execution Simplification

The sandbox evaluates not/exists scopes **independently** of the outer facts (the scope's `matchedTuples()` runs against `ctx` only, not against a specific outer fact combination). A full Drools engine tracks this connection via beta memory and can cross-reference outer facts inside scope filters.

This limitation is **invisible at the current API level**: cross-referencing outer facts inside a scope requires `Variable<T>` binding (`var(v1).filter(v1, v2, pred)`), which is a Phase 3+ feature not yet implemented. The DSL shape is identical to real Drools; only the execution model is simplified.

---

## Files Changed

| File | Change |
|---|---|
| `permuplate-mvn-examples/src/main/java/.../drools/NegationScope.java` | **Create** — scope builder for `not()` |
| `permuplate-mvn-examples/src/main/java/.../drools/ExistenceScope.java` | **Create** — scope builder for `exists()` |
| `permuplate-mvn-examples/src/main/java/.../drools/RuleDefinition.java` | Add `negations`/`existences` lists, `addNegation()`, `addExistence()`, update `matchedTuples()` |
| `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java` | Add `not()` and `exists()` to `Join0Second` template |
| `permuplate-mvn-examples/src/test/java/.../drools/RuleBuilderTest.java` | Add 3 new tests |

No Permuplate core changes required.

---

## NegationScope and ExistenceScope

Two hand-written scope builder classes. Nearly identical — separate classes so the DSL reads distinctly (`not()` vs `exists()`).

### NegationScope.java

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Scope builder returned by {@code JoinNSecond.not()}.
 *
 * <p>Accumulates sources and filters into a private {@link RuleDefinition} that
 * represents the negation sub-network. The sub-network is registered with the
 * outer chain's RuleDefinition upfront (at {@code not()} call time), so it
 * accumulates as the scope is built. At {@code end()}, the outer builder is
 * returned — restoring the outer arity and type chain.
 *
 * <p>Inside the scope, {@code join()} and {@code filter()} are intentionally
 * untyped — the scope is a constraint, not a fact chain the caller accesses.
 * The outer chain remains fully typed throughout.
 *
 * <p>Sandbox execution simplification: the scope evaluates independently of
 * the outer facts. Cross-referencing outer facts inside scope filters requires
 * {@code Variable<T>} binding (Phase 3+ feature not yet implemented).
 */
public class NegationScope<OUTER, DS> {

    private final OUTER outer;
    private final RuleDefinition<DS> notRd;

    public NegationScope(OUTER outer, RuleDefinition<DS> notRd) {
        this.outer = outer;
        this.notRd = notRd;
    }

    /**
     * Adds a data source to the negation sub-network.
     * Takes Object to accept both Function<DS, DataSource<?>> and pre-built JoinNFirst
     * instances (bi-linear scope source). Untyped by design — the scope is a constraint,
     * not a typed fact chain the caller accesses. RuleDefinition.addSource() handles casting.
     */
    public NegationScope<OUTER, DS> join(Object source) {
        notRd.addSource(source);
        return this;
    }

    /**
     * Adds a filter predicate to the negation sub-network.
     */
    public NegationScope<OUTER, DS> filter(Object predicate) {
        notRd.addFilter(predicate);
        return this;
    }

    /**
     * Ends the negation scope and returns to the outer builder at its original arity.
     */
    public OUTER end() {
        return outer;
    }
}
```

### ExistenceScope.java

Identical to `NegationScope` with `ExistenceScope` as the class name. Registered via `rd.addExistence(existsRd)` in `exists()`.

---

## RuleDefinition Changes

Add two new lists and three new methods:

```java
private final List<RuleDefinition<DS>> negations = new ArrayList<>();
private final List<RuleDefinition<DS>> existences = new ArrayList<>();

public void addNegation(RuleDefinition<DS> notScope) {
    negations.add(notScope);
}

public void addExistence(RuleDefinition<DS> existsScope) {
    existences.add(existsScope);
}
```

Update `matchedTuples()` — after the existing cross-product + filter stream, add two more filter steps:

```java
List<Object[]> matchedTuples(DS ctx) {
    // ... existing cross-product logic ...

    return combinations.stream()
            .filter(facts -> filters.stream().allMatch(f -> f.test(ctx, facts)))
            // not() constraint: valid only if scope produces ZERO matches
            .filter(facts -> negations.stream()
                    .allMatch(neg -> neg.matchedTuples(ctx).isEmpty()))
            // exists() constraint: valid only if scope produces AT LEAST ONE match
            .filter(facts -> existences.stream()
                    .allMatch(ex -> !ex.matchedTuples(ctx).isEmpty()))
            .collect(Collectors.toList());
}
```

Note: `facts` is available to the lambda but intentionally NOT passed to `neg.matchedTuples(ctx)` — the scope evaluates against `ctx` only (sandbox simplification documented above).

---

## JoinBuilder.java Template Changes

Add `not()` and `exists()` to `Join0Second`. The `@PermuteReturn` typeArgs expression builds the fully parameterised scope type:

```java
/**
 * Starts a negation scope. The outer fact combination is valid ONLY IF this
 * scope produces zero matching tuples. Models "there is no X satisfying Y."
 *
 * <p>Inside the scope, join additional facts and add filters. Call {@code end()}
 * to close the scope and return to the outer builder at its original arity.
 * The scope facts do NOT add to the outer chain.
 *
 * <p>Corresponds to the Rete NegativeExistsNode.
 */
@PermuteReturn(className = "NegationScope",
               typeArgs = "'Join${i}Second<END, DS, ' + typeArgList(1, i, 'alpha') + '>, DS'",
               when = "true")
public Object not() {
    RuleDefinition<DS> notRd = new RuleDefinition<>("not-scope");
    rd.addNegation(notRd);
    return new NegationScope<>(this, notRd);
}

/**
 * Starts an existence scope. The outer fact combination is valid ONLY IF this
 * scope produces at least one matching tuple. Models "there exists an X satisfying Y."
 *
 * <p>Corresponds to the Rete PositiveExistsNode.
 */
@PermuteReturn(className = "ExistenceScope",
               typeArgs = "'Join${i}Second<END, DS, ' + typeArgList(1, i, 'alpha') + '>, DS'",
               when = "true")
public Object exists() {
    RuleDefinition<DS> existsRd = new RuleDefinition<>("exists-scope");
    rd.addExistence(existsRd);
    return new ExistenceScope<>(this, existsRd);
}
```

**Generated return types for `Join2Second<END,DS,A,B>` (i=2):**
```java
public NegationScope<Join2Second<END,DS,A,B>, DS> not()
public ExistenceScope<Join2Second<END,DS,A,B>, DS> exists()
```

`NegationScope.end()` returns `Join2Second<END,DS,A,B>` — the outer builder, typed so `fn()` and `filter()` work at the correct outer arity.

---

## Tests

Three new tests in `RuleBuilderTest`. The test data: 2 persons (Alice/30, Bob/17), 2 accounts (ACC1/1000.0, ACC2/50.0).

```java
@Test
public void testNotScopeExcludesPersonsWithHighBalanceAccount() {
    // not() scope: persons who have no high-balance account pass.
    // Scope (independent): ACC1 (1000) passes the balance > 500 filter → scope produces results.
    // Since scope produces results, ALL outer tuples are excluded.
    // Note: sandbox evaluates scope independently — "does any high-balance account exist?"
    // Both persons are blocked because the scope finds ACC1 globally.
    var rule = builder.from("persons", ctx -> ctx.persons())
            .not()
                .join(ctx -> ctx.accounts())
                .filter((ctx, b) -> b.balance() > 500.0)
            .end()
            .fn((ctx, a) -> {});

    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(0); // scope finds ACC1 → blocks all persons
}

@Test
public void testNotScopeWithEmptyMatchingScope() {
    // not() scope: when the scope finds NO match, outer tuples pass.
    // Scope: accounts with balance > 10000 — none qualify → scope empty → all persons pass.
    var rule = builder.from("persons", ctx -> ctx.persons())
            .not()
                .join(ctx -> ctx.accounts())
                .filter((ctx, b) -> b.balance() > 10000.0)
            .end()
            .fn((ctx, a) -> {});

    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(2); // scope empty → both persons pass
}

@Test
public void testExistsScopeRequiresAtLeastOneMatch() {
    // exists() scope: outer tuples pass only when scope finds at least one match.
    // Scope: high-balance accounts — ACC1 qualifies → scope non-empty → all persons pass.
    var rule = builder.from("persons", ctx -> ctx.persons())
            .exists()
                .join(ctx -> ctx.accounts())
                .filter((ctx, b) -> b.balance() > 500.0)
            .end()
            .fn((ctx, a) -> {});

    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(2); // scope finds ACC1 → all persons pass
}
```

The test comments explain the sandbox simplification clearly: the scope evaluates globally against `ctx`, not per outer fact. This documents the limitation in the tests themselves rather than surprising future readers.

---

## What This Does NOT Include

- Cross-referencing outer facts inside scope filters — requires `Variable<T>` (Phase 3+)
- Nested not-within-not scopes — works structurally but not tested
- `not()` on `From1First` (arity 1) — can be added in a follow-up; Phase 3a focuses on JoinNSecond
- `ifn()` inside scopes — not needed for migration
