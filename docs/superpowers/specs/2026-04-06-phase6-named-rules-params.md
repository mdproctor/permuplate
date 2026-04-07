# Spec: Phase 6 — Named Rules and Params

**Date:** 2026-04-06
**Status:** Approved
**Motivation:** Fill the gap between the sandbox and vol2 semantic model. In vol2,
every rule starts with `builder.rule("name")` and supports four param-definition
styles. The sandbox's `builder.from("name", source)` was always an approximation.
This phase adds `rule()`, `params()`, and all param variants, closing the gap
before real Drools migration begins.

---

## Background: Vol2 Entry Patterns

```java
// Approach 1: Typed params — varargs capture, P3 flows as first typed fact
builder.rule("rule1").<Params3>params()
       .join(Ctx::persons)
       .filter((ctx, p, person) -> p.p3_1().length() > person.age())
       .fn((ctx, p, person) -> { })
       .end();

// Approach 2: Individual named params → ArgList, positional access
builder.rule("rule1").param("name", String.class).param("age", int.class)
       .join(Ctx::persons)
       .filter((ctx, a, b) -> ((String) a.get(0)).length() > b.age())
       .fn((ctx, a, b) -> { });

// Approach 3: Map-based params → ArgMap, named access
builder.rule("rule1").map().param("name", String.class).param("age", int.class)
       .join(Ctx::persons)
       .filter((ctx, a, b) -> ((String) a.get("name")).length() > b.age())
       .fn((ctx, a, b) -> { });

// Approach 4: Individual typed params via varargs capture per-param
builder.rule("rule1").<String>param("name").<Integer>param("age")
       .join(Ctx::persons)
       .fn((ctx, a, b) -> { });

// No params — straight to join chain
builder.rule("rule1").from(Ctx::persons)
       .filter((ctx, p) -> p.age() >= 18)
       .fn((ctx, p) -> { });
```

Reference: `RuleBuilderTest.java` in vol2 at
`/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/test/java/org/drools/core/`

---

## Files Changed

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/.../drools/ArgList.java` | **Create** | Positional param access — `get(int)` |
| `src/main/java/.../drools/ArgMap.java` | **Create** | Named param access — `get(String)` |
| `src/main/java/.../drools/ParametersFirst.java` | **Create** | Entry point from `rule("name")` — all four param approaches + `from()` |
| `src/main/java/.../drools/ParametersSecond.java` | **Create** | After `.param()` / `.list()` / `.map()` — chaining params + `from()` + `fn()` |
| `src/main/java/.../drools/RuleResult.java` | **Create** | Returned by `fn()` — exposes `run()`, `executionCount()`, `capturedFact()`, `end()` |
| `src/main/java/.../drools/RuleDefinition.java` | Modify | Add `run(DS ctx, Object params)` overload; extract `matchedTuplesFrom()` |
| `src/main/java/.../drools/RuleBuilder.java` | Modify | Add `rule(String name)` method |
| `src/main/permuplate/.../drools/JoinBuilder.java` | Modify | Change `fn()` return type from `RuleDefinition<DS>` to `RuleResult<DS>` on `Join0Second` |
| `src/main/java/.../drools/RuleBuilderExamples.java` | Modify | Add named-rule + params examples |
| `src/test/java/.../drools/NamedRuleTest.java` | **Create** | 7 tests covering all param approaches and named-rule semantics |

---

## ArgList

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.ArrayList;
import java.util.List;

/**
 * Positional parameter container for list-style rule params.
 * Built via ParametersSecond.param() chains; accessed positionally in filters.
 *
 * <p>Usage: {@code (ctx, a, b) -> ((String) a.get(0)).length() > b.age()}
 */
public class ArgList {
    private final List<Object> values = new ArrayList<>();

    /** Adds a value at the next positional index. */
    public ArgList add(Object value) {
        values.add(value);
        return this;
    }

    /** Returns the value at the given 0-based index. */
    public Object get(int index) {
        return values.get(index);
    }

    /** Returns the number of params. */
    public int size() {
        return values.size();
    }
}
```

---

## ArgMap

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named parameter container for map-style rule params.
 * Built via ParametersSecond.param() chains after .map(); accessed by name in filters.
 *
 * <p>Usage: {@code (ctx, a, b) -> ((String) a.get("name")).length() > b.age()}
 */
public class ArgMap {
    private final Map<String, Object> values = new LinkedHashMap<>();

    /** Puts a value under the given name. */
    public ArgMap put(String name, Object value) {
        values.put(name, value);
        return this;
    }

    /** Returns the value for the given name. */
    public Object get(String name) {
        return values.get(name);
    }

    /** Returns the number of params. */
    public int size() {
        return values.size();
    }
}
```

---

## RuleResult

Thin wrapper returned by `fn()`. Exposes the same query API as `RuleDefinition`
plus `end()` as a no-op, enabling `.fn(...).end()` to compile for top-level rules.

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Returned by fn() — wraps the completed RuleDefinition and exposes run(),
 * executionCount(), capturedFact(), and end().
 *
 * <p>end() returns null (Void) for top-level rules — it exists to allow
 * .fn(...).end() to compile, matching vol2's chain syntax where fn() may
 * appear inside a named-rule scope that requires explicit termination.
 */
public class RuleResult<DS> {
    private final RuleDefinition<DS> rd;

    public RuleResult(RuleDefinition<DS> rd) {
        this.rd = rd;
    }

    /** Executes the rule against the given context. */
    public RuleResult<DS> run(DS ctx) {
        rd.run(ctx);
        return this;
    }

    /** Executes the rule with typed params as the first fact. */
    public RuleResult<DS> run(DS ctx, Object params) {
        rd.run(ctx, params);
        return this;
    }

    public int executionCount() { return rd.executionCount(); }

    public Object capturedFact(int execution, int position) {
        return rd.capturedFact(execution, position);
    }

    public java.util.List<Object> capturedFacts(int execution) {
        return rd.capturedFacts(execution);
    }

    public int filterCount() { return rd.filterCount(); }

    public boolean hasAction() { return rd.hasAction(); }

    public String name() { return rd.name(); }

    /**
     * No-op terminator — returns null (Void). Exists so that .fn(...).end()
     * compiles for rules written in vol2 style where end() closes a named-rule scope.
     */
    public Void end() { return null; }
}
```

---

## RuleDefinition Changes

### New `run(DS ctx, Object params)` overload

Params are injected as the first element of the initial fact combination, before
the cross-product with any joined sources:

```java
/**
 * Executes this rule with params as the first fact. The params value is treated
 * as a single-element initial combination — all joins cross-product on top of it.
 * Used by rules built via ParametersFirst.params() / param() / map().
 */
public RuleDefinition<DS> run(DS ctx, Object params) {
    executions.clear();
    List<Object[]> initial = new java.util.ArrayList<>();
    initial.add(new Object[]{params});
    for (Object[] facts : matchedTuplesFrom(ctx, initial)) {
        if (action != null)
            action.accept(ctx, facts);
        executions.add(java.util.Arrays.asList(facts));
    }
    return this;
}
```

### Extract `matchedTuplesFrom()`

Refactor the existing `matchedTuples(DS ctx)` to delegate to a new internal method
that accepts a starting combination list:

```java
List<Object[]> matchedTuples(DS ctx) {
    List<Object[]> initial = new java.util.ArrayList<>();
    initial.add(new Object[0]);
    return matchedTuplesFrom(ctx, initial);
}

List<Object[]> matchedTuplesFrom(DS ctx, List<Object[]> initial) {
    List<Object[]> combinations = initial;

    for (TupleSource<DS> source : sources) {
        List<Object[]> next = new java.util.ArrayList<>();
        for (Object[] tuple : source.tuples(ctx)) {
            for (Object[] combo : combinations) {
                Object[] extended = java.util.Arrays.copyOf(combo, combo.length + tuple.length);
                System.arraycopy(tuple, 0, extended, combo.length, tuple.length);
                next.add(extended);
            }
        }
        combinations = next;
    }

    List<Object[]> filtered = combinations.stream()
            .filter(facts -> filters.stream().allMatch(f -> f.test(ctx, facts)))
            .filter(facts -> negations.stream()
                    .allMatch(neg -> neg.matchedTuples(ctx).isEmpty()))
            .filter(facts -> existences.stream()
                    .allMatch(ex -> !ex.matchedTuples(ctx).isEmpty()))
            .collect(java.util.stream.Collectors.toList());
    return applyOOPath(filtered, ctx);
}
```

---

## ParametersFirst

Entry point returned by `builder.rule("name")`. All four param approaches plus
`from()` (skip params) and `extendsRule()`.

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Entry point returned by RuleBuilder.rule("name"). Supports four param styles:
 * typed capture (params()), individual list (param()), map (map()), and
 * individual typed (param with type witness). Also supports from() to skip
 * params entirely and go straight to the join chain.
 */
public class ParametersFirst<DS> {
    private final String name;

    public ParametersFirst(String name) {
        this.name = name;
    }

    /**
     * Typed params — varargs type capture. P is the params type; it flows
     * as the first typed fact through the join chain.
     *
     * <pre>{@code
     * builder.rule("r1").<Params3>params()
     *        .join(Ctx::persons)
     *        .filter((ctx, p, person) -> p.p3_1().length() > person.age())
     *        .fn((ctx, p, person) -> { });
     * }</pre>
     */
    @SuppressWarnings({"unchecked", "varargs"})
    public <P> JoinBuilder.Join1First<Void, DS, P> params(P... cls) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        return new JoinBuilder.Join1First<>(null, rd);
    }

    /**
     * Individual typed param — varargs type capture per param, builds ArgList.
     * Chain .param() calls to add more params; call .from() to start joins.
     *
     * <pre>{@code
     * builder.rule("r1").<String>param("name").<Integer>param("age")
     *        .join(Ctx::persons)
     *        .fn((ctx, a, b) -> { });
     * }</pre>
     */
    @SuppressWarnings({"unchecked", "varargs"})
    public <T> ParametersSecond<DS, ArgList> param(String paramName, T... cls) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        return new ParametersSecond<>(name, rd, false);
    }

    /**
     * Explicit list-based params. Chain .param() calls on the returned
     * ParametersSecond to add named params; access positionally via ArgList.get(int).
     */
    public ParametersSecond<DS, ArgList> list() {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        return new ParametersSecond<>(name, rd, false);
    }

    /**
     * Map-based params. Chain .param() calls on the returned ParametersSecond
     * to add named params; access by name via ArgMap.get(String).
     */
    public ParametersSecond<DS, ArgMap> map() {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        return new ParametersSecond<>(name, rd, true);
    }

    /**
     * Skip params — start the join chain directly with the first data source.
     * Equivalent to builder.from("name", source) but uses the rule name.
     */
    public <A> JoinBuilder.Join1First<Void, DS, A> from(
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addSource(firstSource);
        return new JoinBuilder.Join1First<>(null, rd);
    }

    /**
     * Extends a base rule's patterns — all six overloads, one per base arity.
     * Identical to RuleBuilder.extendsRule() but creates a named RuleDefinition.
     *
     * Implementation note: vol2 shows salient examples; implement ALL permutations
     * in ALL appropriate places. Every arity that exists on RuleBuilder.extendsRule()
     * must also exist here on ParametersFirst.extendsRule().
     */
    public <A> JoinBuilder.Join1First<Void, DS, A>
            extendsRule(RuleExtendsPoint.RuleExtendsPoint2<DS, A> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join1First<>(null, child);
    }

    public <A, B> JoinBuilder.Join2First<Void, DS, A, B>
            extendsRule(RuleExtendsPoint.RuleExtendsPoint3<DS, A, B> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join2First<>(null, child);
    }

    public <A, B, C> JoinBuilder.Join3First<Void, DS, A, B, C>
            extendsRule(RuleExtendsPoint.RuleExtendsPoint4<DS, A, B, C> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join3First<>(null, child);
    }

    public <A, B, C, D> JoinBuilder.Join4First<Void, DS, A, B, C, D>
            extendsRule(RuleExtendsPoint.RuleExtendsPoint5<DS, A, B, C, D> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join4First<>(null, child);
    }

    public <A, B, C, D, E> JoinBuilder.Join5First<Void, DS, A, B, C, D, E>
            extendsRule(RuleExtendsPoint.RuleExtendsPoint6<DS, A, B, C, D, E> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join5First<>(null, child);
    }

    public <A, B, C, D, E, F> JoinBuilder.Join6First<Void, DS, A, B, C, D, E, F>
            extendsRule(RuleExtendsPoint.RuleExtendsPoint7<DS, A, B, C, D, E, F> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join6First<>(null, child);
    }

    /**
     * Zero-arg action — rule fires with only ctx, no facts.
     */
    public RuleResult<DS> ifn(Runnable action) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.setAction((ctx, facts) -> action.run());
        return new RuleResult<>(rd);
    }
}
```

---

## ParametersSecond

Returned after `.param()`, `.list()`, or `.map()` — allows chaining more params
then starting the join chain.

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Returned after param()/list()/map() on ParametersFirst. Allows chaining
 * additional .param() calls before starting joins with .from().
 *
 * B is either ArgList (positional) or ArgMap (named).
 */
public class ParametersSecond<DS, B> {
    private final String name;
    private final RuleDefinition<DS> rd;
    private final boolean mapMode;  // true = ArgMap, false = ArgList

    public ParametersSecond(String name, RuleDefinition<DS> rd, boolean mapMode) {
        this.name = name;
        this.rd = rd;
        this.mapMode = mapMode;
    }

    /**
     * Adds another named param. Returns this for chaining.
     */
    @SuppressWarnings({"unchecked", "varargs"})
    public <T> ParametersSecond<DS, B> param(String paramName, T... cls) {
        return this;
    }

    /**
     * Starts the join chain. B (ArgList or ArgMap) becomes the first fact type.
     * Pass the populated B instance at rule.run(ctx, bInstance) time.
     */
    public <A> JoinBuilder.Join2First<Void, DS, B, A> from(
            Function<DS, DataSource<A>> firstSource) {
        rd.addSource(firstSource);
        return new JoinBuilder.Join2First<>(null, rd);
    }

    /**
     * Terminates with an action — no additional joins.
     */
    public RuleResult<DS> fn(java.util.function.BiConsumer<DS, B> action) {
        rd.setAction((ctx, facts) -> action.accept((DS) ctx, (B) facts[0]));
        return new RuleResult<>(rd);
    }
}
```

---

## RuleBuilder Changes

One new method:

```java
/**
 * Starts building a named rule. Returns ParametersFirst which supports all
 * four param styles (typed, list, map, individual typed) as well as from()
 * to skip params and go straight to the join chain.
 *
 * <pre>{@code
 * builder.rule("findAdults")
 *        .from(Ctx::persons)
 *        .filter((ctx, p) -> p.age() >= 18)
 *        .fn((ctx, p) -> { });
 * }</pre>
 */
public ParametersFirst<DS> rule(String name) {
    return new ParametersFirst<>(name);
}
```

---

## JoinBuilder Template Change

`fn()` on `Join0Second` — change return type from `RuleDefinition<DS>` to
`RuleResult<DS>`. Body wraps the existing `rd` in a `RuleResult`:

```java
// Before:
public Object fn(...) {
    rd.setAction(typedConsumer);
    return rd;
}

// After:
@PermuteReturn(className = "RuleResult", typeArgs = "'DS'", when = "true")
public Object fn(...) {
    rd.setAction(typedConsumer);
    return new RuleResult<>(rd);
}
```

**Impact on existing tests:** Tests that do `var rule = builder.from(...).fn(...)` will
now have `rule` typed as `RuleResult<DS>`. Since `RuleResult` exposes the same
`run()`, `executionCount()`, `capturedFact()` API, existing tests compile unchanged.
The `RuleDefinition<DS>` return type is no longer directly exposed from `fn()` — use
`RuleResult` for the rule handle.

---

## NamedRuleTest (new test class)

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;
import org.junit.Before;
import org.junit.Test;

public class NamedRuleTest {

    private RuleBuilder<Ctx> builder;
    private Ctx ctx;

    record Params3(String p1, String p2, String p3) {}

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
                        new Library("ScienceLib", java.util.List.of()),
                        new Library("ArtsLib", java.util.List.of())));
    }

    @Test
    public void testNamedRuleFromShorthand() {
        // builder.rule("name").from() is equivalent to builder.from("name", source)
        var rule = builder.rule("findAdults")
                          .from(ctx -> ctx.persons())
                          .filter((ctx, p) -> p.age() >= 18)
                          .fn((ctx, p) -> { });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }

    @Test
    public void testFnEndCompilesAsNoOp() {
        // .fn(...).end() compiles and works — end() is a no-op returning null.
        var rule = builder.rule("r1")
                          .from(ctx -> ctx.persons())
                          .filter((ctx, p) -> p.age() >= 18)
                          .fn((ctx, p) -> { })
                          .end();  // Void — null at runtime, but compiles
        // rule is null (Void) — just verify it compiled
    }

    @Test
    public void testTypedParams() {
        // Approach 1: .<P>params() — typed capture, P3 as first fact
        Params3 params = new Params3("hello", "world", "foo");

        var rule = builder.rule("withTypedParams")
                          .<Params3>params()
                          .join(ctx -> ctx.persons())
                          .filter((ctx, p, person) -> person.age() >= 18)
                          .fn((ctx, p, person) -> { });

        rule.run(ctx, params);
        assertThat(rule.executionCount()).isEqualTo(1);
        Params3 captured = (Params3) rule.capturedFact(0, 0);
        assertThat(captured.p1()).isEqualTo("hello");
        assertThat(((Person) rule.capturedFact(0, 1)).name()).isEqualTo("Alice");
    }

    @Test
    public void testListParams() {
        // Approach 2: .param() chains → ArgList, positional access
        ArgList argList = new ArgList().add("Alice").add(18);

        var rule = builder.rule("withListParams")
                          .param("name", String.class)
                          .param("minAge", int.class)
                          .from(ctx -> ctx.persons())
                          .filter((ctx, a, p) -> p.name().equals((String) a.get(0))
                                              && p.age() >= (int) a.get(1))
                          .fn((ctx, a, p) -> { });

        rule.run(ctx, argList);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 1)).name()).isEqualTo("Alice");
    }

    @Test
    public void testMapParams() {
        // Approach 3: .map().param() → ArgMap, named access
        ArgMap argMap = new ArgMap().put("name", "Alice").put("minAge", 18);

        var rule = builder.rule("withMapParams")
                          .map()
                          .param("name", String.class)
                          .param("minAge", int.class)
                          .from(ctx -> ctx.persons())
                          .filter((ctx, a, p) -> p.name().equals((String) a.get("name"))
                                              && p.age() >= (int) a.get("minAge"))
                          .fn((ctx, a, p) -> { });

        rule.run(ctx, argMap);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 1)).name()).isEqualTo("Alice");
    }

    @Test
    public void testIndividualTypedParams() {
        // Approach 4: .<T>param("name") — individual typed params, ArgList access
        ArgList args = new ArgList().add("Alice").add(18);

        var rule = builder.rule("withIndividualParams")
                          .<String>param("name")
                          .<Integer>param("minAge")
                          .from(ctx -> ctx.persons())
                          .filter((ctx, a, p) -> p.name().equals((String) a.get(0)))
                          .fn((ctx, a, p) -> { });

        rule.run(ctx, args);
        assertThat(rule.executionCount()).isEqualTo(1);
    }

    @Test
    public void testNamedRuleWithExtensionPoint() {
        // rule("name") + extendsRule — name is associated with the child rule
        var ep = builder.rule("base")
                        .from(ctx -> ctx.persons())
                        .filter((ctx, p) -> p.age() >= 18)
                        .extensionPoint();

        var rule = builder.rule("child")
                          .extendsRule(ep)
                          .join(ctx -> ctx.accounts())
                          .filter((ctx, p, a) -> a.balance() > 500.0)
                          .fn((ctx, p, a) -> { });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
    }
}
```

---

## RuleBuilderExamples Addition

```java
/**
 * Named rule with typed params — preferred style matching vol2 API.
 * Params3 flows as the first fact; joins cross-product on top.
 */
public static void namedRuleWithTypedParams(RuleBuilder<Ctx> builder) {
    record Params3(String p1, String p2, String p3) {}

    var rule = builder.rule("findMatchingAdults")
                      .<Params3>params()
                      .join(ctx -> ctx.persons())
                      .filter((ctx, p, person) -> person.name().equals(p.p1()))
                      .fn((ctx, p, person) -> System.out.println(person.name()));

    // rule.run(ctx, new Params3("Alice", "world", "foo"));
}

/**
 * Named rule with map params — named access, no record needed.
 */
public static void namedRuleWithMapParams(RuleBuilder<Ctx> builder) {
    var rule = builder.rule("filterByName")
                      .map()
                      .param("name", String.class)
                      .from(ctx -> ctx.persons())
                      .filter((ctx, a, p) -> p.name().equals((String) a.get("name")))
                      .fn((ctx, a, p) -> System.out.println(p.name()));

    // rule.run(ctx, new ArgMap().put("name", "Alice"));
}
```

---

## Implementation Completeness Principle

Vol2 shows salient working points — not exhaustive permutations. When implementing
any feature, apply it consistently across **all arities** and **all appropriate
places**. Specific guidance:

- **All arity overloads must be present everywhere they logically apply.** If a
  method exists at arity 2 it must exist at arities 3–7. If `extendsRule()` is on
  `RuleBuilder`, it must also be complete on `ParametersFirst`.
- **If in doubt, add the overload.** Missing arity overloads are silent compile
  errors during migration — the user gets "no matching method" with no explanation.

## What This Does Not Include

- `ifn()` overloads on `ParametersSecond` for multi-fact consumers — add if the
  migration reveals rules that terminate after params-only (no joins).
- Runtime type validation of params (e.g., verifying the passed instance matches
  the captured class from `params(P... cls)`) — YAGNI for the sandbox.
- `list()` as a separate shorthand on `ParametersFirst` — it's equivalent to the
  first `.param()` call; both return `ParametersSecond<DS, ArgList>`.
