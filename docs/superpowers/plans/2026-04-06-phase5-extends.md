# extensionPoint() / extendsRule() Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `extensionPoint()` / `extendsRule()` cross-rule pattern inheritance to the Drools DSL sandbox.

**Architecture:** `extensionPoint()` on `JoinNSecond` captures the current `RuleDefinition` in a typed `RuleExtendsPointN` handle. `extendsRule(ep)` on `RuleBuilder` creates a fresh child `RuleDefinition`, copies the base rule's sources/filters/scopes into it at build time (authoring-time deduplication — no runtime concept), and returns the matching `JoinNFirst` to continue the chain. Extend-of-extend works for free because `JoinNFirst extends JoinNSecond`.

**Tech Stack:** Java 17, Maven (`/opt/homebrew/bin/mvn`), Permuplate Maven plugin (inline template at `src/main/permuplate/`), reflection (same pattern as `join()`), JUnit 4, Google Truth.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleExtendsPoint.java` | **Create** | Container for `RuleExtendsPoint2..RuleExtendsPoint7` static inner classes |
| `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java` | Modify | Add package-private `copyInto()` |
| `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilder.java` | Modify | Add six `extendsRule()` overloads |
| `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java` | Modify | Add `extensionPoint()` to `Join0Second` |
| `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilderExamples.java` | Modify | Add `extendsExample()` |
| `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/ExtensionPointTest.java` | **Create** | 7 tests in a dedicated test class |

**Build commands:**
- Full build (required after template changes): `/opt/homebrew/bin/mvn clean install`
- Run ExtensionPointTest only: `/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=ExtensionPointTest`
- Run specific test: `/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=ExtensionPointTest#testName`
- Run all mvn-examples tests: `/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples`

**Test data (same as RuleBuilderTest):**
- Persons: `Alice(name="Alice", age=30)`, `Bob(name="Bob", age=17)`
- Accounts: `ACC1(id="ACC1", balance=1000.0)`, `ACC2(id="ACC2", balance=50.0)`
- Orders: `ORD1(id="ORD1", amount=150.0)`, `ORD2(id="ORD2", amount=25.0)`

---

## Task 1: RuleExtendsPoint + RuleDefinition.copyInto()

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleExtendsPoint.java`
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java`

- [ ] **Step 1.1: Create `RuleExtendsPoint.java`**

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Container for typed extension point handles produced by JoinNSecond.extensionPoint().
 * Each inner class captures the base rule's RuleDefinition and encodes the accumulated
 * fact types in its type parameters.
 *
 * Naming convention matches Drools vol2: the number = DS + fact count.
 * RuleExtendsPoint2 has 1 fact (A), RuleExtendsPoint7 has 6 facts (A..F).
 *
 * Design: extension is authoring-time deduplication only — extendsRule() copies
 * the base rule's sources and filters into the child at build time. The Rete network
 * handles node sharing automatically; no special runtime concept is needed.
 */
public class RuleExtendsPoint {

    public static class RuleExtendsPoint2<DS, A> {
        private final RuleDefinition<DS> baseRd;
        public RuleExtendsPoint2(RuleDefinition<DS> baseRd) { this.baseRd = baseRd; }
        RuleDefinition<DS> baseRd() { return baseRd; }
    }

    public static class RuleExtendsPoint3<DS, A, B> {
        private final RuleDefinition<DS> baseRd;
        public RuleExtendsPoint3(RuleDefinition<DS> baseRd) { this.baseRd = baseRd; }
        RuleDefinition<DS> baseRd() { return baseRd; }
    }

    public static class RuleExtendsPoint4<DS, A, B, C> {
        private final RuleDefinition<DS> baseRd;
        public RuleExtendsPoint4(RuleDefinition<DS> baseRd) { this.baseRd = baseRd; }
        RuleDefinition<DS> baseRd() { return baseRd; }
    }

    public static class RuleExtendsPoint5<DS, A, B, C, D> {
        private final RuleDefinition<DS> baseRd;
        public RuleExtendsPoint5(RuleDefinition<DS> baseRd) { this.baseRd = baseRd; }
        RuleDefinition<DS> baseRd() { return baseRd; }
    }

    public static class RuleExtendsPoint6<DS, A, B, C, D, E> {
        private final RuleDefinition<DS> baseRd;
        public RuleExtendsPoint6(RuleDefinition<DS> baseRd) { this.baseRd = baseRd; }
        RuleDefinition<DS> baseRd() { return baseRd; }
    }

    public static class RuleExtendsPoint7<DS, A, B, C, D, E, F> {
        private final RuleDefinition<DS> baseRd;
        public RuleExtendsPoint7(RuleDefinition<DS> baseRd) { this.baseRd = baseRd; }
        RuleDefinition<DS> baseRd() { return baseRd; }
    }
}
```

- [ ] **Step 1.2: Add `copyInto()` to `RuleDefinition.java`**

Add this package-private method inside `RuleDefinition`, after `addOOPathPipeline()` and the three `addVariableFilter()` methods (around line 180):

```java
/**
 * Copies this rule's sources, accumulated fact count, filters, and constraint
 * scopes (not/exists) into the target RuleDefinition. Called by
 * RuleBuilder.extendsRule() to inline base patterns into the child at build time.
 *
 * Does NOT copy: action, executions, or OOPath pipeline — those belong to each
 * rule independently.
 */
void copyInto(RuleDefinition<DS> target) {
    target.sources.addAll(this.sources);
    target.accumulatedFacts = this.accumulatedFacts;
    target.filters.addAll(this.filters);
    target.negations.addAll(this.negations);
    target.existences.addAll(this.existences);
}
```

- [ ] **Step 1.3: Verify compilation:**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples --also-make -q && echo "OK"
```

Expected: `OK`

- [ ] **Step 1.4: Commit:**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleExtendsPoint.java
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java
git commit -m "feat(drools): add RuleExtendsPoint classes and RuleDefinition.copyInto()"
```

---

## Task 2: extensionPoint() on JoinBuilder template + extendsRule() on RuleBuilder

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilder.java`

- [ ] **Step 2.1: Add `extensionPoint()` to `Join0Second` in the template**

In `JoinBuilder.java`, find `Join0Second`. Add `extensionPoint()` after the last `path6()` method and before `var()`. Use reflection to instantiate the right `RuleExtendsPointN` — the same pattern as `join()` which reads the current arity from the class name:

```java
/**
 * Captures the current accumulated fact state as a typed extension point.
 * Pass the result to RuleBuilder.extendsRule() to start a child rule that
 * inherits all sources, filters, and constraint scopes up to this point.
 *
 * Uses reflection to instantiate RuleExtendsPoint.RuleExtendsPoint(N+1) where N
 * is the current arity — same pattern as join().
 */
@SuppressWarnings("unchecked")
@PermuteReturn(className = "RuleExtendsPoint.RuleExtendsPoint${i+1}",
               typeArgs = "'DS, ' + typeArgList(1, i, 'alpha')",
               when = "true")
public Object extensionPoint() {
    String cn = getClass().getSimpleName();
    int n = Integer.parseInt(cn.replaceAll("[^0-9]", ""));
    String className = RuleExtendsPoint.class.getName() + "$RuleExtendsPoint" + (n + 1);
    try {
        return cast(Class.forName(className)
                .getConstructor(RuleDefinition.class)
                .newInstance(rd));
    } catch (Exception e) {
        throw new RuntimeException("Failed to instantiate " + className, e);
    }
}
```

- [ ] **Step 2.2: Add six `extendsRule()` overloads to `RuleBuilder.java`**

Add all six overloads inside `RuleBuilder<DS>`, after the existing `from()` method. Each creates a fresh child `RuleDefinition`, copies the base into it, and returns the matching `JoinNFirst`:

```java
public <A> JoinBuilder.Join1First<Void, DS, A>
        extendsRule(RuleExtendsPoint.RuleExtendsPoint2<DS, A> ep) {
    RuleDefinition<DS> child = new RuleDefinition<>("extends");
    ep.baseRd().copyInto(child);
    return new JoinBuilder.Join1First<>(null, child);
}

public <A, B> JoinBuilder.Join2First<Void, DS, A, B>
        extendsRule(RuleExtendsPoint.RuleExtendsPoint3<DS, A, B> ep) {
    RuleDefinition<DS> child = new RuleDefinition<>("extends");
    ep.baseRd().copyInto(child);
    return new JoinBuilder.Join2First<>(null, child);
}

public <A, B, C> JoinBuilder.Join3First<Void, DS, A, B, C>
        extendsRule(RuleExtendsPoint.RuleExtendsPoint4<DS, A, B, C> ep) {
    RuleDefinition<DS> child = new RuleDefinition<>("extends");
    ep.baseRd().copyInto(child);
    return new JoinBuilder.Join3First<>(null, child);
}

public <A, B, C, D> JoinBuilder.Join4First<Void, DS, A, B, C, D>
        extendsRule(RuleExtendsPoint.RuleExtendsPoint5<DS, A, B, C, D> ep) {
    RuleDefinition<DS> child = new RuleDefinition<>("extends");
    ep.baseRd().copyInto(child);
    return new JoinBuilder.Join4First<>(null, child);
}

public <A, B, C, D, E> JoinBuilder.Join5First<Void, DS, A, B, C, D, E>
        extendsRule(RuleExtendsPoint.RuleExtendsPoint6<DS, A, B, C, D, E> ep) {
    RuleDefinition<DS> child = new RuleDefinition<>("extends");
    ep.baseRd().copyInto(child);
    return new JoinBuilder.Join5First<>(null, child);
}

public <A, B, C, D, E, F> JoinBuilder.Join6First<Void, DS, A, B, C, D, E, F>
        extendsRule(RuleExtendsPoint.RuleExtendsPoint7<DS, A, B, C, D, E, F> ep) {
    RuleDefinition<DS> child = new RuleDefinition<>("extends");
    ep.baseRd().copyInto(child);
    return new JoinBuilder.Join6First<>(null, child);
}
```

- [ ] **Step 2.3: Full Maven build:**

```bash
/opt/homebrew/bin/mvn clean install
```

Expected: BUILD SUCCESS. The generated `Join1Second` through `Join6Second` now each have `extensionPoint()` returning the appropriate `RuleExtendsPointN`.

- [ ] **Step 2.4: Commit:**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilder.java
git commit -m "feat(drools): add extensionPoint() to JoinSecond template and extendsRule() to RuleBuilder"
```

---

## Task 3: ExtensionPointTest — basic extends tests (1, 2, 3, 4)

**Files:**
- Create: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/ExtensionPointTest.java`

- [ ] **Step 3.1: Create `ExtensionPointTest.java` with tests 1–4:**

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class ExtensionPointTest {

    private RuleBuilder<Ctx> builder;
    private Ctx ctx;

    @Before
    public void setUp() {
        builder = new RuleBuilder<>();
        ctx = new Ctx(
                DataSource.of(new Person("Alice", 30), new Person("Bob", 17)),
                DataSource.of(new Account("ACC1", 1000.0), new Account("ACC2", 50.0)),
                DataSource.of(new Order("ORD1", 150.0), new Order("ORD2", 25.0)),
                DataSource.of(new Product("PRD1", 99.0), new Product("PRD2", 9.0)),
                DataSource.of(new Transaction("TXN1", 200.0), new Transaction("TXN2", 15.0)),
                DataSource.of(
                        new Library("ScienceLib", List.of()),
                        new Library("ArtsLib", List.of())));
    }

    @Test
    public void testExtends1FilterOnly() {
        // Base: 1 join. Child: adds filter on inherited facts, no new joins.
        // extensionPoint() after 1 join → RuleExtendsPoint.RuleExtendsPoint2<DS, Person>
        var ep = builder.from("persons", ctx -> ctx.persons())
                        .extensionPoint();

        var rule = builder.extendsRule(ep)
                          .filter((ctx, p) -> p.age() >= 18)
                          .fn((ctx, p) -> { });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }

    @Test
    public void testExtends2AddsJoin() {
        // Base: 1 join + filter. Child: adds a second join.
        var ep = builder.from("persons", ctx -> ctx.persons())
                        .filter((ctx, p) -> p.age() >= 18)
                        .extensionPoint();

        var rule = builder.extendsRule(ep)
                          .join(ctx -> ctx.accounts())
                          .filter((ctx, p, a) -> a.balance() > 500.0)
                          .fn((ctx, p, a) -> { });

        rule.run(ctx);
        // Alice(30) × ACC1(1000) only
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person)  rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
        assertThat(((Account) rule.capturedFact(0, 1)).id()).isEqualTo("ACC1");
    }

    @Test
    public void testExtends3TwoBaseJoins() {
        // Base: 2 joins + filter. Child: adds a third join.
        var ep = builder.from("persons", ctx -> ctx.persons())
                        .join(ctx -> ctx.accounts())
                        .filter((ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0)
                        .extensionPoint();

        var rule = builder.extendsRule(ep)
                          .join(ctx -> ctx.orders())
                          .filter((ctx, p, a, o) -> o.amount() > 100.0)
                          .fn((ctx, p, a, o) -> { });

        rule.run(ctx);
        // Alice(30) × ACC1(1000) × ORD1(150)
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Order) rule.capturedFact(0, 2)).id()).isEqualTo("ORD1");
    }

    @Test
    public void testExtends4FanOut() {
        // Two child rules from the same extension point — each gets an independent copy.
        var ep = builder.from("persons", ctx -> ctx.persons())
                        .filter((ctx, p) -> p.age() >= 18)
                        .extensionPoint();

        var rule1 = builder.extendsRule(ep)
                           .join(ctx -> ctx.accounts())
                           .filter((ctx, p, a) -> a.balance() > 500.0)
                           .fn((ctx, p, a) -> { });

        var rule2 = builder.extendsRule(ep)
                           .join(ctx -> ctx.orders())
                           .filter((ctx, p, o) -> o.amount() > 100.0)
                           .fn((ctx, p, o) -> { });

        rule1.run(ctx);
        rule2.run(ctx);

        // rule1: Alice × ACC1
        assertThat(rule1.executionCount()).isEqualTo(1);
        assertThat(((Account) rule1.capturedFact(0, 1)).id()).isEqualTo("ACC1");

        // rule2: Alice × ORD1
        assertThat(rule2.executionCount()).isEqualTo(1);
        assertThat(((Order) rule2.capturedFact(0, 1)).id()).isEqualTo("ORD1");
    }
}
```

- [ ] **Step 3.2: Run tests 1–4:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=ExtensionPointTest#testExtends1FilterOnly+testExtends2AddsJoin+testExtends3TwoBaseJoins+testExtends4FanOut
```

Expected: 4 tests PASS.

- [ ] **Step 3.3: Commit:**

```bash
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/ExtensionPointTest.java
git commit -m "test(drools): add ExtensionPointTest — basic extends (filter, join, two-base-joins, fan-out)"
```

---

## Task 4: ExtensionPointTest — chaining and scope inheritance (tests 5, 6, 7)

**Files:**
- Modify: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/ExtensionPointTest.java`

- [ ] **Step 4.1: Add the three remaining tests to `ExtensionPointTest.java`**

Add these three test methods inside the class, after `testExtends4FanOut()`:

```java
@Test
public void testExtendsChaining() {
    // Extend-of-extend: ep1 captures 1 join; ep2 extends ep1 and captures 2 joins;
    // rule extends ep2 and adds a third join.
    // extensionPoint() is available on JoinNFirst because JoinNFirst extends JoinNSecond.
    var ep1 = builder.from("persons", ctx -> ctx.persons())
                     .filter((ctx, p) -> p.age() >= 18)
                     .extensionPoint();

    var ep2 = builder.extendsRule(ep1)
                     .join(ctx -> ctx.accounts())
                     .filter((ctx, p, a) -> a.balance() > 500.0)
                     .extensionPoint();

    var rule = builder.extendsRule(ep2)
                      .join(ctx -> ctx.orders())
                      .filter((ctx, p, a, o) -> o.amount() > 100.0)
                      .fn((ctx, p, a, o) -> { });

    rule.run(ctx);
    // Alice(30) × ACC1(1000) × ORD1(150) — only one combination passes all three layers
    assertThat(rule.executionCount()).isEqualTo(1);
    assertThat(((Person)  rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    assertThat(((Account) rule.capturedFact(0, 1)).id()).isEqualTo("ACC1");
    assertThat(((Order)   rule.capturedFact(0, 2)).id()).isEqualTo("ORD1");
}

@Test
public void testExtendsInheritsNotScope() {
    // Base has a not() scope. copyInto() must transfer negations to the child.
    // not().join(accounts).filter(balance > 500).end():
    // ACC1 has balance=1000 > 500, so the not() is globally UNSATISFIED → 0 persons survive.
    // Note: sandbox evaluates not/exists globally against ctx, not per outer-tuple.
    var ep = builder.from("persons", ctx -> ctx.persons())
                    .not()
                        .join(ctx -> ctx.accounts())
                        .filter((ctx, a) -> a.balance() > 500.0)
                    .end()
                    .extensionPoint();

    var rule = builder.extendsRule(ep)
                      .fn((ctx, p) -> { });

    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(0);
}

@Test
public void testExtendsInheritsExistsScope() {
    // Base has an exists() scope. copyInto() must transfer existences to the child.
    // exists().join(orders).filter(amount > 100).end():
    // ORD1 has amount=150 > 100, so the exists() is globally SATISFIED → all 2 persons survive.
    // Child adds age filter → only Alice (age=30 >= 18) passes.
    var ep = builder.from("persons", ctx -> ctx.persons())
                    .exists()
                        .join(ctx -> ctx.orders())
                        .filter((ctx, o) -> o.amount() > 100.0)
                    .end()
                    .extensionPoint();

    var rule = builder.extendsRule(ep)
                      .filter((ctx, p) -> p.age() >= 18)
                      .fn((ctx, p) -> { });

    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(1);
    assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
}
```

- [ ] **Step 4.2: Run all 7 ExtensionPointTest tests:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=ExtensionPointTest
```

Expected: 7 tests PASS.

- [ ] **Step 4.3: Run full RuleBuilderTest suite for regressions:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=RuleBuilderTest
```

Expected: 36 tests PASS.

- [ ] **Step 4.4: Commit:**

```bash
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/ExtensionPointTest.java
git commit -m "test(drools): add extend-of-extend, not/exists scope inheritance tests"
```

---

## Task 5: RuleBuilderExamples + full build verification

**Files:**
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilderExamples.java`

- [ ] **Step 5.1: Add `extendsExample()` to `RuleBuilderExamples.java`**

Add this method inside `RuleBuilderExamples`, after the existing `adultHighBalanceNamed()` method:

```java
/**
 * extensionPoint() / extendsRule() — authoring-time pattern reuse.
 * Both child rules inherit Person + the age filter from the base.
 * The Rete network handles node sharing automatically; no special
 * runtime inheritance concept exists.
 */
public static void extendsExample(RuleBuilder<Ctx> builder) {
    var ep = builder.from("persons", ctx -> ctx.persons())
                    .filter((ctx, p) -> p.age() >= 18)
                    .extensionPoint();

    // Child 1: high-balance accounts for adult persons
    var highBalance = builder.extendsRule(ep)
                             .join(ctx -> ctx.accounts())
                             .filter((ctx, p, a) -> a.balance() > 500.0)
                             .fn((ctx, p, a) -> { });

    // Child 2: large orders for adult persons
    var largeOrders = builder.extendsRule(ep)
                             .join(ctx -> ctx.orders())
                             .filter((ctx, p, o) -> o.amount() > 100.0)
                             .fn((ctx, p, o) -> { });
}
```

- [ ] **Step 5.2: Full build to verify examples compile:**

```bash
/opt/homebrew/bin/mvn clean install
```

Expected: BUILD SUCCESS. All tests across all modules pass.

- [ ] **Step 5.3: Commit:**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilderExamples.java
git commit -m "docs(drools): add extendsExample() to RuleBuilderExamples"
```
