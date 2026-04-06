# Variable<T> Cross-Fact Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `Variable<T>` named bindings and variable-based `filter()` overloads to the Drools DSL sandbox for cross-fact predicates and DRL migration fidelity.

**Architecture:** `Variable<T>` is a typed index holder bound at `var()` call time. Variable-based `filter()` overloads on `Join0First` convert to `NaryPredicate` lambdas at registration time (bypassing `wrapPredicate()`), and delegate to new `addVariableFilter()` methods on `RuleDefinition`. No changes to `matchedTuples()`.

**Tech Stack:** Java 17, Maven (at `/opt/homebrew/bin/mvn`), Permuplate Maven plugin (inline template generation from `src/main/permuplate/`), JUnit 4, Google Truth assertions.

---

## File Map

| File | Action | What changes |
|---|---|---|
| `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Variable.java` | **Create** | Typed index holder |
| `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java` | **Modify** | Add `bindVariable()`, `addVariableFilter()` (2-var and 3-var) |
| `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java` | **Modify** | Add `var()`, 2-variable `filter()`, 3-variable `filter()` to `Join0First` |
| `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java` | **Modify** | Add 4 tests |

**Test data reference (from `setUp()`):**
- Persons: `Alice(name="Alice", age=30)`, `Bob(name="Bob", age=17)`
- Accounts: `ACC1(id="ACC1", balance=1000.0)`, `ACC2(id="ACC2", balance=50.0)`
- Orders: `ORD1(id="ORD1", amount=150.0)`, `ORD2(id="ORD2", amount=25.0)`

**Build commands:**
- Full build (required after any template change): `/opt/homebrew/bin/mvn clean install`
- Run specific test: `/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=RuleBuilderTest#<testName>`
- Run all RuleBuilderTests: `/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=RuleBuilderTest`

---

## Task 1: Variable<T> + RuleDefinition infrastructure

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Variable.java`
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java`
- Test: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java`

- [ ] **Step 1.1: Write the failing test** — add to `RuleBuilderTest`:

```java
@Test
public void testUnboundVariableThrows() {
    Variable<Person>  personVar  = new Variable<>();  // never bound
    Variable<Account> accountVar = new Variable<>();  // never bound

    try {
        builder.from("persons", ctx -> ctx.persons())
               .join(ctx -> ctx.accounts())
               .filter(personVar, accountVar,
                       (ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0);
        org.junit.Assert.fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
        assertThat(e.getMessage()).contains("Variable not bound");
    }
}
```

- [ ] **Step 1.2: Run to verify it fails to compile** (Variable and filter overload don't exist yet):

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=RuleBuilderTest#testUnboundVariableThrows
```

Expected: COMPILATION ERROR — `Variable cannot be resolved to a type`

- [ ] **Step 1.3: Create `Variable.java`:**

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Typed named binding for cross-fact predicates in the rule DSL.
 * Created before the rule builder chain; bound to a specific accumulated fact
 * index via {@code JoinNFirst.var()}; then passed to variable-based
 * {@code filter()} overloads to reference that fact by name rather than position.
 */
public class Variable<T> {
    private int index = -1;

    /** Binds this variable to the given fact index. Called by RuleDefinition.bindVariable(). */
    public void bind(int index) {
        this.index = index;
    }

    /** Returns the 0-based index of the bound fact in the accumulated facts array. */
    public int index() {
        return index;
    }

    /** Returns true if this variable has been bound via var(). */
    public boolean isBound() {
        return index >= 0;
    }
}
```

- [ ] **Step 1.4: Add `bindVariable()` and `addVariableFilter()` to `RuleDefinition`**

Add these three methods to `RuleDefinition.java`, inside the class body after `addOOPathPipeline()`:

```java
/**
 * Binds a variable to a specific fact position. Called by JoinFirst.var().
 * factIndex is the 0-based index of the fact in the accumulated array —
 * rd.factArity() - 1 gives the most recently accumulated fact.
 */
public void bindVariable(Variable<?> v, int factIndex) {
    v.bind(factIndex);
}

/**
 * Registers a variable-based 2-variable cross-fact filter. Constructs the
 * NaryPredicate directly from variable indices — does NOT go through
 * wrapPredicate() (which would misidentify factArity from parameter count).
 */
@SuppressWarnings("unchecked")
public <V1, V2> void addVariableFilter(Variable<V1> v1, Variable<V2> v2,
                                        Predicate3<DS, V1, V2> predicate) {
    if (!v1.isBound() || !v2.isBound())
        throw new IllegalStateException(
            "Variable not bound — call var() before using it in filter()");
    int i1 = v1.index(), i2 = v2.index();
    filters.add((ctx, facts) ->
        predicate.test((DS) ctx, (V1) facts[i1], (V2) facts[i2]));
}

/**
 * Registers a variable-based 3-variable cross-fact filter.
 */
@SuppressWarnings("unchecked")
public <V1, V2, V3> void addVariableFilter(Variable<V1> v1, Variable<V2> v2,
                                             Variable<V3> v3,
                                             Predicate4<DS, V1, V2, V3> predicate) {
    if (!v1.isBound() || !v2.isBound() || !v3.isBound())
        throw new IllegalStateException(
            "Variable not bound — call var() before using it in filter()");
    int i1 = v1.index(), i2 = v2.index(), i3 = v3.index();
    filters.add((ctx, facts) ->
        predicate.test((DS) ctx, (V1) facts[i1], (V2) facts[i2], (V3) facts[i3]));
}
```

Note: `Predicate3` and `Predicate4` are in the same package — no import needed. `NaryPredicate` is a package-private interface defined in `RuleDefinition` itself; the lambda implements it implicitly.

- [ ] **Step 1.5: Full build to verify compilation:**

```bash
/opt/homebrew/bin/mvn clean install
```

Expected: BUILD SUCCESS

- [ ] **Step 1.6: Run the test:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=RuleBuilderTest#testUnboundVariableThrows
```

Expected: PASS — the filter() call is not yet on the generated JoinFirst classes, but `RuleDefinition.addVariableFilter()` exists and throws correctly. The test will fail to compile at the `.filter(personVar, accountVar, ...)` callsite because the `JoinNFirst` generated classes don't have that overload yet.

**If it fails to compile:** that is expected. The test references `filter(Variable, Variable, Predicate3)` which doesn't exist on the generated classes until Task 2. Move on to Task 2 — `testUnboundVariableThrows` will be verified together after Task 2's build.

- [ ] **Step 1.7: Commit what compiles:**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Variable.java
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java
git commit -m "feat(drools): add Variable<T> and RuleDefinition variable filter infrastructure"
```

---

## Task 2: var() and 2-variable filter() on JoinBuilder template

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`
- Test: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java`

- [ ] **Step 2.1: Write the failing test** — add to `RuleBuilderTest`:

```java
@Test
public void testVarTwoFactCrossFilter() {
    // Variable-based cross-fact filter: only Alice(age=30) + ACC1(balance=1000) passes.
    // 2 persons × 2 accounts = 4 combinations; only 1 passes both constraints.
    Variable<Person>  personVar  = new Variable<>();
    Variable<Account> accountVar = new Variable<>();

    var rule = builder.from("persons", ctx -> ctx.persons())
            .var(personVar)
            .join(ctx -> ctx.accounts())
            .var(accountVar)
            .filter(personVar, accountVar,
                    (ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0)
            .fn((ctx, p, a) -> { });

    rule.run(ctx);

    assertThat(rule.executionCount()).isEqualTo(1);
    Person  p = (Person)  rule.capturedFact(0, 0);
    Account a = (Account) rule.capturedFact(0, 1);
    assertThat(p.name()).isEqualTo("Alice");
    assertThat(a.id()).isEqualTo("ACC1");
}
```

- [ ] **Step 2.2: Add `var()` to `Join0First` in `JoinBuilder.java`**

Locate `Join0First` in `JoinBuilder.java` (after `Join0Second`, near the end of the file). Add this method inside `Join0First`, after the existing `filterLatest()` method:

```java
/**
 * Binds a variable to the most recently accumulated fact.
 * After this call, v.index() equals rd.factArity() - 1.
 * Chainable — returns this Join unchanged.
 */
@PermuteReturn(className = "Join${i}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i, 'alpha')",
               when = "true")
public <T> Object var(Variable<T> v) {
    rd.bindVariable(v, rd.factArity() - 1);
    return this;
}
```

- [ ] **Step 2.3: Add 2-variable `filter()` to `Join0First` in `JoinBuilder.java`**

Add this method immediately after `var()` in `Join0First`:

```java
/**
 * Cross-fact filter using two named variable bindings.
 * V1, V2 are the types of the bound variables — independent of the
 * enclosing class's arity type parameters A, B, etc.
 * Both variables must have been bound via var() before this call.
 */
@PermuteReturn(className = "Join${i}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i, 'alpha')",
               when = "true")
public <V1, V2> Object filter(Variable<V1> v1, Variable<V2> v2,
                               Predicate3<DS, V1, V2> predicate) {
    rd.addVariableFilter(v1, v2, predicate);
    return this;
}
```

- [ ] **Step 2.4: Full Maven build (regenerates all Join classes with the new methods):**

```bash
/opt/homebrew/bin/mvn clean install
```

Expected: BUILD SUCCESS. The generated `Join1First.java` through `Join6First.java` in `target/generated-sources/permuplate/` now contain both `var()` and the 2-variable `filter()`.

- [ ] **Step 2.5: Run both tests:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=RuleBuilderTest#testVarTwoFactCrossFilter+testUnboundVariableThrows
```

Expected: Both PASS.

- [ ] **Step 2.6: Commit:**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java
git commit -m "feat(drools): add var() and 2-variable filter() to JoinFirst via Permuplate template"
```

---

## Task 3: testVarIndexCapturedAtBindTime (no new code)

This test verifies that a variable bound to fact[0] correctly resolves to index 0 even after two more facts are joined. All required code exists after Task 2.

**Files:**
- Test: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java`

- [ ] **Step 3.1: Write and run the test** — add to `RuleBuilderTest`:

```java
@Test
public void testVarIndexCapturedAtBindTime() {
    // personVar bound to index 0 (Person). Two more facts joined after.
    // filter(personVar, orderVar, ...) cross-references index 0 and index 2,
    // skipping the intermediate Account at index 1.
    //
    // Combinations: 2 persons × 2 accounts × 2 orders = 8.
    // Passing: Alice(age=30) × any account × ORD1(amount=150) — age>=18, amount>100.
    //   Alice + ACC1 + ORD1 → pass
    //   Alice + ACC2 + ORD1 → pass
    //   All Bob rows       → fail (age=17)
    //   All ORD2 rows      → fail (amount=25)
    // Expected executionCount = 2.
    Variable<Person> personVar = new Variable<>();
    Variable<Order>  orderVar  = new Variable<>();

    var rule = builder.from("persons", ctx -> ctx.persons())
            .var(personVar)                    // personVar → index 0
            .join(ctx -> ctx.accounts())       // index 1 — no variable bound
            .join(ctx -> ctx.orders())
            .var(orderVar)                     // orderVar → index 2
            .filter(personVar, orderVar,
                    (ctx, p, o) -> p.age() >= 18 && o.amount() > 100.0)
            .fn((ctx, p, a, o) -> { });

    rule.run(ctx);

    assertThat(rule.executionCount()).isEqualTo(2);
    for (int i = 0; i < rule.executionCount(); i++) {
        Person p = (Person) rule.capturedFact(i, 0);
        Order  o = (Order)  rule.capturedFact(i, 2);
        assertThat(p.name()).isEqualTo("Alice");
        assertThat(o.amount()).isGreaterThan(100.0);
    }
}
```

- [ ] **Step 3.2: Run:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=RuleBuilderTest#testVarIndexCapturedAtBindTime
```

Expected: PASS.

- [ ] **Step 3.3: Commit:**

```bash
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java
git commit -m "test(drools): verify Variable index captured at bind time, skipping intermediate facts"
```

---

## Task 4: 3-variable filter() and testVarThreeVariableFilter

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`
- Test: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java`

- [ ] **Step 4.1: Write the failing test** — add to `RuleBuilderTest`:

```java
@Test
public void testVarThreeVariableFilter() {
    // 3-variable filter: Alice(age=30) + ACC1(balance=1000) + ORD1(amount=150) passes.
    // 2 × 2 × 2 = 8 combinations; only 1 passes all three constraints.
    Variable<Person>  pVar = new Variable<>();
    Variable<Account> aVar = new Variable<>();
    Variable<Order>   oVar = new Variable<>();

    var rule = builder.from("persons", ctx -> ctx.persons())
            .var(pVar)
            .join(ctx -> ctx.accounts())
            .var(aVar)
            .join(ctx -> ctx.orders())
            .var(oVar)
            .filter(pVar, aVar, oVar,
                    (ctx, p, a, o) -> p.age() >= 18
                                   && a.balance() > 500.0
                                   && o.amount() > 100.0)
            .fn((ctx, p, a, o) -> { });

    rule.run(ctx);

    assertThat(rule.executionCount()).isEqualTo(1);
    Person  p = (Person)  rule.capturedFact(0, 0);
    Account a = (Account) rule.capturedFact(0, 1);
    Order   o = (Order)   rule.capturedFact(0, 2);
    assertThat(p.name()).isEqualTo("Alice");
    assertThat(a.id()).isEqualTo("ACC1");
    assertThat(o.id()).isEqualTo("ORD1");
}
```

- [ ] **Step 4.2: Run to verify it fails** (3-variable `filter()` not yet on template):

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=RuleBuilderTest#testVarThreeVariableFilter
```

Expected: COMPILATION ERROR — no matching `filter(Variable, Variable, Variable, Predicate4)` on `Join3First`.

- [ ] **Step 4.3: Add 3-variable `filter()` to `Join0First` in `JoinBuilder.java`**

Add this method immediately after the 2-variable `filter()` in `Join0First`:

```java
/**
 * Cross-fact filter using three named variable bindings.
 * All three variables must have been bound via var() before this call.
 */
@PermuteReturn(className = "Join${i}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i, 'alpha')",
               when = "true")
public <V1, V2, V3> Object filter(Variable<V1> v1, Variable<V2> v2, Variable<V3> v3,
                                   Predicate4<DS, V1, V2, V3> predicate) {
    rd.addVariableFilter(v1, v2, v3, predicate);
    return this;
}
```

- [ ] **Step 4.4: Full Maven build:**

```bash
/opt/homebrew/bin/mvn clean install
```

Expected: BUILD SUCCESS.

- [ ] **Step 4.5: Run all four new tests:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=RuleBuilderTest#testVarTwoFactCrossFilter+testUnboundVariableThrows+testVarIndexCapturedAtBindTime+testVarThreeVariableFilter
```

Expected: All 4 PASS.

- [ ] **Step 4.6: Run the full RuleBuilderTest suite to confirm no regressions:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=RuleBuilderTest
```

Expected: All tests PASS.

- [ ] **Step 4.7: Commit:**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java
git commit -m "feat(drools): add 3-variable filter() to JoinFirst; all Variable<T> tests passing"
```
