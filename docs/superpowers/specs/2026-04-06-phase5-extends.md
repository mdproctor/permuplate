# Spec: Phase 5 — extensionPoint() / extendsRule() Cross-Rule Inheritance

**Date:** 2026-04-06
**Status:** Approved
**Motivation:** Add cross-rule fact inheritance to the Drools DSL sandbox.
`extensionPoint()` captures a rule's accumulated fact state; `extendsRule()` starts
a new rule that inherits all of the base rule's patterns. This is authoring-time
deduplication — the Rete network performs node sharing automatically regardless of
how rules are authored. See knowledge garden entry `drools/rule-builder-dsl.md`.

---

## Background: Pattern in Practice

```java
// Base rule establishes common patterns and marks an extension point
var ep = builder.from("persons", ctx -> ctx.persons())
               .join(ctx -> ctx.accounts())
               .filter((ctx, p, a) -> p.age() >= 18)
               .extensionPoint();  // → RuleExtendsPoint.RuleExtendsPoint3<DS, Person, Account>

// Child rule 1: add a filter on the inherited facts
var rule1 = builder.extendsRule(ep)
                   .filter((ctx, p, a) -> a.balance() > 500.0)
                   .fn((ctx, p, a) -> { });

// Child rule 2: add another join on top of the inherited facts
var rule2 = builder.extendsRule(ep)
                   .join(ctx -> ctx.orders())
                   .filter((ctx, p, a, o) -> o.amount() > 100.0)
                   .fn((ctx, p, a, o) -> { });
```

Both child rules independently inherit Person + Account + the base filter. Neither
modifies the base rule's `RuleDefinition` — each gets its own copy.

Reference: `ExtensionPointTest.java` in vol2 at
`/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/test/java/org/drools/core/`

---

## Files Changed

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/.../drools/RuleExtendsPoint.java` | **Create** | Container for `RuleExtendsPoint2..RuleExtendsPoint7` inner classes |
| `src/main/java/.../drools/RuleDefinition.java` | Modify | Add package-private `copyInto()` method |
| `src/main/java/.../drools/RuleBuilder.java` | Modify | Add six `extendsRule()` overloads |
| `src/main/permuplate/.../drools/JoinBuilder.java` | Modify | Add `extensionPoint()` to `Join0Second` |
| `src/main/java/.../drools/RuleBuilderExamples.java` | Modify | Add extends example |
| `src/test/java/.../drools/ExtensionPointTest.java` | **Create** | New test class mirroring vol2 structure |

No new Permuplate core changes required.

---

## RuleExtendsPoint

Single file containing six static inner classes. Number = DS + fact count (matching
vol2 naming: `RuleExtendsPoint2<DS,A>` = 1 fact, `RuleExtendsPoint3<DS,A,B>` = 2 facts):

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Container for typed extension point handles produced by JoinNSecond.extensionPoint().
 * Each inner class captures the base rule's RuleDefinition and encodes the accumulated
 * fact types in its type parameters.
 *
 * <p>Naming convention matches Drools vol2: the number = DS + fact count.
 * RuleExtendsPoint2 has 1 fact (A), RuleExtendsPoint7 has 6 facts (A..F).
 *
 * <p>Design: extension is authoring-time deduplication only — extendsRule() copies
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

`baseRd()` is package-private — only accessed by `RuleBuilder.extendsRule()` in the
same package.

---

## RuleDefinition.copyInto()

Package-private method that inlines the base rule's state into a fresh child
`RuleDefinition` at build time:

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

---

## RuleBuilder.extendsRule() — six overloads

Each overload creates a fresh child `RuleDefinition`, copies the base into it, and
returns the matching `JoinNFirst<Void, DS, ...>` so the chain continues with the
inherited fact types in scope:

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

The pattern matches `RuleBuilder.from()` exactly: `null` for END (Void top-level
rule), fresh `RuleDefinition`, return matching `JoinNFirst`.

---

## JoinBuilder Template — extensionPoint() on Join0Second

One method in `Join0Second`, generating `extensionPoint()` on all `Join1Second`
through `Join6Second`. Uses `cast()` — the same unchecked-cast helper already used
by `path2()`..`path6()` methods in `Join0Second`:

```java
/**
 * Captures the current accumulated fact state as a typed extension point.
 * Pass the result to RuleBuilder.extendsRule() to start a child rule that
 * inherits all sources and filters up to this point.
 */
@SuppressWarnings("unchecked")
@PermuteReturn(className = "RuleExtendsPoint.RuleExtendsPoint${i+1}",
               typeArgs = "'DS, ' + typeArgList(1, i, 'alpha')",
               when = "true")
public Object extensionPoint() {
    return cast(new RuleExtendsPoint.RuleExtendsPoint${i+1}<>(rd));
}
```

Generated on `Join1Second<END, DS, A>` (i=1):
```java
public RuleExtendsPoint.RuleExtendsPoint2<DS, A> extensionPoint() {
    return new RuleExtendsPoint.RuleExtendsPoint2<>(rd);
}
```

Generated on `Join2Second<END, DS, A, B>` (i=2):
```java
public RuleExtendsPoint.RuleExtendsPoint3<DS, A, B> extensionPoint() {
    return new RuleExtendsPoint.RuleExtendsPoint3<>(rd);
}
```

Note: `extensionPoint()` is on `Join0Second` (not `Join0First`) because it is a
chain-terminating operation — like `fn()`, it ends the current rule's construction.
It does not return `this` and cannot be followed by `.filter()` or `.join()`.

---

## ExtensionPointTest (new test class)

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;
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
                DataSource.of(new Library("ScienceLib", java.util.List.of()),
                             new Library("ArtsLib", java.util.List.of())));
    }

    @Test
    public void testExtends1FilterOnly() {
        // Base: 1 join. Child: adds filter on inherited facts, no new joins.
        // extensionPoint() after 1 join → RuleExtendsPoint2<DS, Person>
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
        // Base: 1 join. Child: adds a second join on top of inherited facts.
        var ep = builder.from("persons", ctx -> ctx.persons())
                        .filter((ctx, p) -> p.age() >= 18)
                        .extensionPoint();

        var rule = builder.extendsRule(ep)
                          .join(ctx -> ctx.accounts())
                          .filter((ctx, p, a) -> a.balance() > 500.0)
                          .fn((ctx, p, a) -> { });

        rule.run(ctx);
        // Alice(18) × ACC1(1000) only
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
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
        // Two child rules from the same extension point.
        // Each child gets an independent copy — neither affects the other.
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

`testExtends4FanOut` is the key correctness test — it verifies that calling
`extendsRule(ep)` twice produces two independent child rules, not two rules sharing
a mutating `RuleDefinition`.

---

## RuleBuilderExamples addition

Add to `RuleBuilderExamples.java`:

```java
/**
 * extensionPoint() / extendsRule() — authoring-time pattern reuse.
 * Both child rules inherit Person + the age filter from the base.
 * The Rete network handles node sharing automatically.
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

---

## What This Does Not Include

- `extensionPoint()` before any joins (would need `RuleBuilder` to support it
  without `Join0Second`) — YAGNI; the sandbox always has at least one `from()`.
- Chaining `extensionPoint()` from another child (extend of extend) — not tested
  in vol2 `ExtensionPointTest`; deferred.
- `not()` / `exists()` scopes copied from base into child — `copyInto()` includes
  `negations` and `existences` lists, so it works; but there are no tests for this
  combination. Add if needed.
