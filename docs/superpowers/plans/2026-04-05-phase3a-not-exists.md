# Phase 3a — not() and exists() Scopes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `not()` and `exists()` constraint scopes to the Drools DSL sandbox, allowing rules to express "no X exists" and "at least one X exists" constraints.

**Architecture:** Two hand-written scope classes (`NegationScope`, `ExistenceScope`) accumulate facts/filters into a private `RuleDefinition`. `RuleDefinition.matchedTuples()` gains two new filter stages for negation and existence constraints. `Join0Second` template gains `not()` and `exists()` methods via `@PermuteReturn`. No Permuplate core changes required.

**Tech Stack:** Java 21, JavaParser (Permuplate template processing), Maven (`/opt/homebrew/bin/mvn`).

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `permuplate-mvn-examples/src/main/java/.../drools/NegationScope.java` | **Create** | Scope builder for `not()` — accumulates into `notRd`, `end()` returns outer builder |
| `permuplate-mvn-examples/src/main/java/.../drools/ExistenceScope.java` | **Create** | Scope builder for `exists()` — same structure, different semantics |
| `permuplate-mvn-examples/src/main/java/.../drools/RuleDefinition.java` | Modify | Add `negations`/`existences` lists, `addNegation()`, `addExistence()`, update `matchedTuples()` |
| `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java` | Modify | Add `not()` and `exists()` to `Join0Second` template |
| `permuplate-mvn-examples/src/test/java/.../drools/RuleBuilderTest.java` | Modify | Add 3 new tests documenting sandbox semantics |

---

## Task 1: Create NegationScope and ExistenceScope

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/NegationScope.java`
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/ExistenceScope.java`

- [ ] **Step 1: Create NegationScope.java**

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Scope builder returned by {@code JoinNSecond.not()}.
 *
 * <p>Accumulates sources and filters into a private {@link RuleDefinition} representing
 * the negation sub-network. The sub-network is registered with the outer chain's
 * RuleDefinition upfront (at {@code not()} call time), so it accumulates in-place as
 * the scope is built. {@code end()} returns the outer builder — restoring the outer
 * arity and type chain.
 *
 * <p>Inside the scope, {@code join()} and {@code filter()} are intentionally untyped —
 * the scope is a constraint, not a typed fact chain the caller accesses by name.
 * The outer chain remains fully typed throughout.
 *
 * <p>Sandbox execution: the scope evaluates independently of the outer facts
 * (it runs against {@code ctx} only). Cross-referencing outer facts inside scope
 * filters requires {@code Variable<T>} binding (Phase 3+ feature not yet implemented).
 */
public class NegationScope<OUTER, DS> {

    private final OUTER outer;
    private final RuleDefinition<DS> notRd;

    public NegationScope(OUTER outer, RuleDefinition<DS> notRd) {
        this.outer = outer;
        this.notRd = notRd;
    }

    /**
     * Adds a data source to the negation sub-network. Takes {@code Object} to accept
     * both {@code Function<DS, DataSource<?>>} lambdas and pre-built {@code JoinNFirst}
     * instances (bi-linear scope source). {@link RuleDefinition#addSource} handles casting.
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
     * The outer chain's type and arity are fully restored — the scope facts do NOT
     * accumulate into the outer chain.
     */
    public OUTER end() {
        return outer;
    }
}
```

- [ ] **Step 2: Create ExistenceScope.java**

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Scope builder returned by {@code JoinNSecond.exists()}.
 *
 * <p>Accumulates sources and filters into a private {@link RuleDefinition} representing
 * the existence sub-network. The sub-network is registered with the outer chain's
 * RuleDefinition upfront (at {@code exists()} call time). {@code end()} returns the
 * outer builder — restoring the outer arity and type chain.
 *
 * <p>Semantics: the outer tuple passes only when this scope produces AT LEAST ONE
 * matching result. Dual of {@link NegationScope} which requires ZERO results.
 *
 * <p>Inside the scope, {@code join()} and {@code filter()} are intentionally untyped.
 * Sandbox execution: scope evaluates independently of the outer facts.
 */
public class ExistenceScope<OUTER, DS> {

    private final OUTER outer;
    private final RuleDefinition<DS> existsRd;

    public ExistenceScope(OUTER outer, RuleDefinition<DS> existsRd) {
        this.outer = outer;
        this.existsRd = existsRd;
    }

    /**
     * Adds a data source to the existence sub-network.
     */
    public ExistenceScope<OUTER, DS> join(Object source) {
        existsRd.addSource(source);
        return this;
    }

    /**
     * Adds a filter predicate to the existence sub-network.
     */
    public ExistenceScope<OUTER, DS> filter(Object predicate) {
        existsRd.addFilter(predicate);
        return this;
    }

    /**
     * Ends the existence scope and returns to the outer builder at its original arity.
     */
    public OUTER end() {
        return outer;
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/NegationScope.java \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/ExistenceScope.java
git commit -m "$(cat <<'EOF'
feat(drools-example): add NegationScope and ExistenceScope builders

NegationScope<OUTER,DS>: scope builder for not() — accumulates sources/filters
into a private RuleDefinition, end() returns the typed outer builder.

ExistenceScope<OUTER,DS>: identical structure, dual semantics (at-least-one
vs zero). Both intentionally untyped inside (constraint, not fact chain).

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Update RuleDefinition — Negation and Existence Constraints

**Files:**
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java`

Read the file first. The changes are: (1) add two new lists after `executions`, (2) add two new public methods after `setAction()`, (3) update `matchedTuples()` to apply the constraints.

- [ ] **Step 1: Add the two new list fields**

After the line `private final List<List<Object>> executions = new ArrayList<>();`, add:

```java
private final List<RuleDefinition<DS>> negations = new ArrayList<>();
private final List<RuleDefinition<DS>> existences = new ArrayList<>();
```

- [ ] **Step 2: Add addNegation() and addExistence() methods**

After the `setAction()` method, add:

```java
/**
 * Registers a negation sub-network. During {@link #matchedTuples}, outer tuples
 * are excluded if this sub-network produces ANY matching result (zero-match required).
 * Called by {@code JoinNSecond.not()} before returning the NegationScope.
 */
public void addNegation(RuleDefinition<DS> notScope) {
    negations.add(notScope);
}

/**
 * Registers an existence sub-network. During {@link #matchedTuples}, outer tuples
 * are excluded if this sub-network produces ZERO matching results (at-least-one required).
 * Called by {@code JoinNSecond.exists()} before returning the ExistenceScope.
 */
public void addExistence(RuleDefinition<DS> existsScope) {
    existences.add(existsScope);
}
```

- [ ] **Step 3: Update matchedTuples() to apply negation and existence constraints**

Find the `return combinations.stream()` block in `matchedTuples()`. It currently ends with:

```java
return combinations.stream()
        .filter(facts -> filters.stream().allMatch(f -> f.test(ctx, facts)))
        .collect(Collectors.toList());
```

Replace with:

```java
return combinations.stream()
        .filter(facts -> filters.stream().allMatch(f -> f.test(ctx, facts)))
        // not() constraint: outer tuple valid only if scope produces ZERO matches.
        // Scope evaluates independently against ctx (sandbox simplification —
        // full Drools tracks per-outer-tuple via beta memory).
        .filter(facts -> negations.stream()
                .allMatch(neg -> neg.matchedTuples(ctx).isEmpty()))
        // exists() constraint: outer tuple valid only if scope produces AT LEAST ONE match.
        .filter(facts -> existences.stream()
                .allMatch(ex -> !ex.matchedTuples(ctx).isEmpty()))
        .collect(Collectors.toList());
```

- [ ] **Step 4: Verify compilation**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java
git commit -m "$(cat <<'EOF'
feat(drools-example): add negation/existence constraints to RuleDefinition

addNegation(RuleDefinition) and addExistence(RuleDefinition) register scope
sub-networks. matchedTuples() gains two new filter stages: not() requires
zero scope matches; exists() requires at least one scope match. Both scopes
evaluate against ctx independently (sandbox simplification documented inline).

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Add not() and exists() to JoinBuilder Template

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`

Read the file first. The `not()` and `exists()` methods go inside the `Join0Second` template class, after the `joinBilinear` sentinel method and before the closing `}` of `Join0Second`.

- [ ] **Step 1: Add not() and exists() to Join0Second**

After the `joinBilinear` sentinel method (the one with `@PermuteMethod(varName="j",...)`), add:

```java
/**
 * Starts a negation scope. The outer fact combination is valid ONLY IF this
 * scope produces zero matching tuples. Models "there is no X satisfying Y."
 *
 * <p>Inside the scope: call {@code join()} to add sources, {@code filter()} to
 * add constraints, then {@code end()} to close the scope and return to the outer
 * builder at its original arity. The scope facts do NOT add to the outer chain.
 *
 * <p>Corresponds to the Rete NegativeExistsNode pattern.
 *
 * <p>Sandbox simplification: the scope evaluates independently of the specific
 * outer fact combination (runs against ctx globally). Full Drools tracks the
 * per-tuple connection via beta memory.
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
 * <p>Corresponds to the Rete PositiveExistsNode pattern.
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

- [ ] **Step 2: Run the full build**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`. All 24 existing tests pass.

If the build fails, check `target/generated-sources/permuplate/` for the generated `JoinBuilder.java` to inspect the generated `not()` / `exists()` signatures. Common issue: `@PermuteReturn typeArgs` expression — verify the string concatenation produces e.g. `"Join2Second<END, DS, A, B>, DS"` for i=2.

- [ ] **Step 3: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "$(cat <<'EOF'
feat(drools-example): add not() and exists() to Join0Second template

not() registers a NegationScope with the outer rd and returns
NegationScope<JoinNSecond<END,DS,...>, DS> via @PermuteReturn typeArgs.

exists() is the dual: registers ExistenceScope, same return type pattern.

Both generated at every arity (i=1..6) via the Join0Second template.
Join0First inherits them via extends Join0Second.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Add Tests and Verify Full Build

**Files:**
- Modify: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java`

Test data (from setUp): 2 persons (Alice/30, Bob/17), 2 accounts (ACC1/1000.0, ACC2/50.0), 2 orders (ORD1/150.0, ORD2/25.0).

- [ ] **Step 1: Add 3 new tests before the final `}` of RuleBuilderTest**

```java
// =========================================================================
// not() and exists() scopes
// =========================================================================

@Test
public void testNotScopeBlocksAllWhenScopeHasMatches() {
    // not() scope evaluates independently against ctx (sandbox simplification).
    // Scope: any account with balance > 500 — ACC1 qualifies.
    // Since the scope finds ACC1 globally, ALL outer person tuples are blocked.
    // (In full Drools, the not-scope would be evaluated per outer person tuple,
    // potentially blocking only persons linked to high-balance accounts.)
    var rule = builder.from("persons", ctx -> ctx.persons())
            .not()
                .join(ctx -> ctx.accounts())
                .filter((ctx, b) -> b.balance() > 500.0)
            .end()
            .fn((ctx, a) -> {});

    rule.run(ctx);
    // Scope finds ACC1 (1000 > 500) → non-empty → all outer tuples blocked
    assertThat(rule.executionCount()).isEqualTo(0);
}

@Test
public void testNotScopePassesAllWhenScopeIsEmpty() {
    // not() scope: when the scope finds NO match, all outer tuples pass.
    // Scope: accounts with balance > 10000 — none qualify → scope empty.
    var rule = builder.from("persons", ctx -> ctx.persons())
            .not()
                .join(ctx -> ctx.accounts())
                .filter((ctx, b) -> b.balance() > 10000.0)
            .end()
            .fn((ctx, a) -> {});

    rule.run(ctx);
    // Scope finds nothing → isEmpty() = true → all persons pass
    assertThat(rule.executionCount()).isEqualTo(2);
    assertThat(rule.filterCount()).isEqualTo(0);
}

@Test
public void testExistsScopePassesAllWhenScopeHasMatch() {
    // exists() scope: outer tuples pass when the scope produces at least one result.
    // Scope: high-balance accounts — ACC1 qualifies → scope non-empty → all persons pass.
    var rule = builder.from("persons", ctx -> ctx.persons())
            .exists()
                .join(ctx -> ctx.accounts())
                .filter((ctx, b) -> b.balance() > 500.0)
            .end()
            .fn((ctx, a) -> {});

    rule.run(ctx);
    // Scope finds ACC1 → non-empty → exists() passes → both persons fire fn()
    assertThat(rule.executionCount()).isEqualTo(2);
}
```

- [ ] **Step 2: Run the full build**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | tail -15
```

Expected: `BUILD SUCCESS` with 27 tests passing (24 existing + 3 new).

- [ ] **Step 3: Commit**

```bash
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java
git commit -m "$(cat <<'EOF'
test(drools-example): add 3 not()/exists() scope tests

testNotScopeBlocksAllWhenScopeHasMatches: documents sandbox simplification —
  scope runs globally, so any matching account blocks all outer persons.

testNotScopePassesAllWhenScopeIsEmpty: scope with no matches → all pass.

testExistsScopePassesAllWhenScopeHasMatch: exists() dual — scope finds ACC1
  → all outer persons pass.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- ✅ `NegationScope<OUTER, DS>` — Task 1
- ✅ `ExistenceScope<OUTER, DS>` — Task 1
- ✅ `RuleDefinition.addNegation()` and `addExistence()` — Task 2
- ✅ `matchedTuples()` updated with negation/existence filter stages — Task 2
- ✅ `not()` on `Join0Second` template with `@PermuteReturn` — Task 3
- ✅ `exists()` on `Join0Second` template with `@PermuteReturn` — Task 3
- ✅ 3 tests documenting sandbox semantics — Task 4

**Type consistency:** `NegationScope<OUTER, DS>` defined in Task 1, used in `not()` body in Task 3. `ExistenceScope<OUTER, DS>` same pattern. `addNegation(RuleDefinition<DS>)` / `addExistence(RuleDefinition<DS>)` defined in Task 2, called in Task 3. Consistent throughout.

**No placeholders:** All steps have complete code. ✓
