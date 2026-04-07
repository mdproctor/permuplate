# Named Rules and Params Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `builder.rule("name")`, four param styles, and `fn().end()` support to the Drools DSL sandbox, closing the gap with the vol2 semantic model.

**Architecture:** `RuleBuilder.rule("name")` returns a new `ParametersFirst<DS>` supporting four param entry styles. Params are injected as the first fact at `rule.run(ctx, paramsValue)` time via a refactored `matchedTuplesFrom()` in `RuleDefinition`. `fn()` on the template changes to return `RuleResult<DS>` — a thin wrapper exposing the same API as `RuleDefinition` plus a no-op `end()`. The key subtlety: `params()` calls `rd.addParamsFact()` to increment `accumulatedFacts` so filter-trim logic works correctly when params occupy fact index 0.

**Tech Stack:** Java 17, Maven (`/opt/homebrew/bin/mvn`), Permuplate Maven plugin (template at `src/main/permuplate/`), JUnit 4, Google Truth.

---

## File Map

| File | Action | Notes |
|---|---|---|
| `src/main/java/.../drools/ArgList.java` | **Create** | Positional param container |
| `src/main/java/.../drools/ArgMap.java` | **Create** | Named param container |
| `src/main/java/.../drools/RuleResult.java` | **Create** | Thin wrapper for fn() return; exposes run(), executionCount(), etc + end() |
| `src/main/java/.../drools/RuleDefinition.java` | Modify | Add `addParamsFact()`, `run(ctx, params)`, extract `matchedTuplesFrom()` |
| `src/main/java/.../drools/ParametersFirst.java` | **Create** | Entry point from rule("name") |
| `src/main/java/.../drools/ParametersSecond.java` | **Create** | After param()/list()/map() chaining |
| `src/main/java/.../drools/RuleBuilder.java` | Modify | Add `rule(String name)` |
| `src/main/permuplate/.../drools/JoinBuilder.java` | Modify | Change fn() return type to RuleResult<DS> |
| `src/main/java/.../drools/RuleBuilderExamples.java` | Modify | Add named-rule + params examples |
| `src/test/java/.../drools/NamedRuleTest.java` | **Create** | 7 tests covering all param approaches |

**Build commands:**
- Full build (required after template change): `/opt/homebrew/bin/mvn clean install`
- Run NamedRuleTest: `/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=NamedRuleTest`
- Run all mvn-examples tests: `/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples`

**Test data (from setUp()):**
- Persons: Alice(name="Alice", age=30), Bob(name="Bob", age=17)
- Accounts: ACC1(id="ACC1", balance=1000.0), ACC2(id="ACC2", balance=50.0)
- Orders: ORD1(id="ORD1", amount=150.0), ORD2(id="ORD2", amount=25.0)

**Package:** `io.quarkiverse.permuplate.example.drools` (all files in `permuplate-mvn-examples/`)

---

## Key Implementation Note: addParamsFact()

When `params()` / `param()` / `map()` / `list()` is called on `ParametersFirst`, the params value will occupy fact index 0 at runtime. For filter-trim logic in `RuleDefinition.wrapPredicate()` to work correctly, `accumulatedFacts` must reflect this at build time. Without it, single-fact filters (`filterLatest`) trim to the wrong index.

**Fix:** Add `void addParamsFact()` to `RuleDefinition`:
```java
/** Marks that params will occupy fact index 0 at runtime. Called by ParametersFirst. */
void addParamsFact() {
    accumulatedFacts++;
}
```

Call this in every params-entry method on `ParametersFirst` before returning the chain builder.

---

## Task 1: ArgList + ArgMap

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/ArgList.java`
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/ArgMap.java`

- [ ] **Step 1.1: Create ArgList.java**

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.ArrayList;
import java.util.List;

/**
 * Positional parameter container for list-style rule params.
 * Built by the caller and passed to rule.run(ctx, argList).
 * Access params positionally in filters: (ctx, a, b) -> ((String) a.get(0))
 */
public class ArgList {
    private final List<Object> values = new ArrayList<>();

    public ArgList add(Object value) {
        values.add(value);
        return this;
    }

    public Object get(int index) {
        return values.get(index);
    }

    public int size() {
        return values.size();
    }
}
```

- [ ] **Step 1.2: Create ArgMap.java**

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named parameter container for map-style rule params.
 * Built by the caller and passed to rule.run(ctx, argMap).
 * Access params by name in filters: (ctx, a, b) -> ((String) a.get("name"))
 */
public class ArgMap {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public ArgMap put(String name, Object value) {
        values.put(name, value);
        return this;
    }

    public Object get(String name) {
        return values.get(name);
    }

    public int size() {
        return values.size();
    }
}
```

- [ ] **Step 1.3: Verify compilation:**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples --also-make -q && echo "OK"
```

Expected: `OK`

- [ ] **Step 1.4: Commit:**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/ArgList.java
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/ArgMap.java
git commit -m "feat(drools): add ArgList and ArgMap param containers"
```

---

## Task 2: RuleResult<DS>

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleResult.java`

- [ ] **Step 2.1: Create RuleResult.java**

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.List;

/**
 * Returned by fn() — wraps a completed RuleDefinition and exposes the same
 * query API (run, executionCount, capturedFact, etc.) plus a no-op end().
 *
 * end() returns null (Void) for top-level rules — it exists so that
 * .fn(...).end() compiles, matching vol2's chain syntax.
 */
public class RuleResult<DS> {
    private final RuleDefinition<DS> rd;

    public RuleResult(RuleDefinition<DS> rd) {
        this.rd = rd;
    }

    public RuleResult<DS> run(DS ctx) {
        rd.run(ctx);
        return this;
    }

    public RuleResult<DS> run(DS ctx, Object params) {
        rd.run(ctx, params);
        return this;
    }

    public int executionCount()                        { return rd.executionCount(); }
    public Object capturedFact(int exec, int pos)      { return rd.capturedFact(exec, pos); }
    public List<Object> capturedFacts(int exec)        { return rd.capturedFacts(exec); }
    public int filterCount()                           { return rd.filterCount(); }
    public int sourceCount()                           { return rd.sourceCount(); }
    public boolean hasAction()                         { return rd.hasAction(); }
    public String name()                               { return rd.name(); }

    /**
     * No-op terminator. Returns null (Void). Allows .fn(...).end() to compile
     * for rules written in vol2 style requiring explicit scope termination.
     */
    public Void end() { return null; }
}
```

- [ ] **Step 2.2: Verify compilation:**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples --also-make -q && echo "OK"
```

Expected: `OK`

- [ ] **Step 2.3: Commit:**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleResult.java
git commit -m "feat(drools): add RuleResult<DS> wrapper with end() no-op"
```

---

## Task 3: RuleDefinition — addParamsFact(), run(ctx, params), matchedTuplesFrom()

**Files:**
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java`

Read the file first. The existing `matchedTuples(DS ctx)` method needs to be refactored and a new `run(DS ctx, Object params)` overload added.

- [ ] **Step 3.1: Add `addParamsFact()` to RuleDefinition**

Add this package-private method after `addOOPathPipeline()` (around line 147):

```java
/**
 * Marks that a params value will occupy fact index 0 at runtime.
 * Called by ParametersFirst before returning a chain builder, so that
 * wrapPredicate()'s trim logic accounts for the injected params fact.
 */
void addParamsFact() {
    accumulatedFacts++;
}
```

- [ ] **Step 3.2: Refactor matchedTuples() to delegate to matchedTuplesFrom()**

Find the existing `matchedTuples(DS ctx)` method. Replace it with:

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

- [ ] **Step 3.3: Add `run(DS ctx, Object params)` overload**

Add after the existing `run(DS ctx)` method:

```java
/**
 * Executes this rule with a params value as the first fact (index 0).
 * The params value is injected as a single-element initial combination;
 * all data sources cross-product on top of it.
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

- [ ] **Step 3.4: Verify compilation and existing tests still pass:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -q && echo "ALL PASS"
```

Expected: `ALL PASS` — all 60 existing tests pass unchanged.

- [ ] **Step 3.5: Commit:**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java
git commit -m "feat(drools): add addParamsFact(), run(ctx,params), and matchedTuplesFrom() to RuleDefinition"
```

---

## Task 4: ParametersFirst + ParametersSecond + RuleBuilder.rule()

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/ParametersFirst.java`
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/ParametersSecond.java`
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilder.java`

- [ ] **Step 4.1: Create ParametersFirst.java**

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Entry point returned by RuleBuilder.rule("name"). Supports four param styles
 * plus from() to skip params entirely:
 *
 * Approach 1 — Typed: .<P3>params() — P3 flows as first typed fact
 * Approach 2 — Individual: .param("name", String.class).param("age", int.class) → ArgList
 * Approach 3 — Map: .map().param("name", String.class) → ArgMap
 * Approach 4 — Typed individual: .<String>param("name").<Integer>param("age") → ArgList
 * No params: .from(source) — skip straight to join chain
 */
public class ParametersFirst<DS> {
    private final String name;

    public ParametersFirst(String name) {
        this.name = name;
    }

    /**
     * Typed params via varargs type-capture. P becomes the first fact type.
     * At run time: rule.run(ctx, new P(...)) injects the P instance as fact[0].
     */
    @SuppressWarnings({"unchecked", "varargs"})
    public <P> JoinBuilder.Join1First<Void, DS, P> params(P... cls) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new JoinBuilder.Join1First<>(null, rd);
    }

    /**
     * Individual typed param — varargs type capture per param, accumulates into ArgList.
     * Chain more .param() calls on the returned ParametersSecond, then call .from().
     */
    @SuppressWarnings({"unchecked", "varargs"})
    public <T> ParametersSecond<DS, ArgList> param(String paramName, T... cls) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new ParametersSecond<>(name, rd, false);
    }

    /**
     * Explicit list-based params. Chain .param() calls on the returned ParametersSecond.
     * Access positionally in filters: (ctx, a, b) -> ((String) a.get(0))
     */
    public ParametersSecond<DS, ArgList> list() {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new ParametersSecond<>(name, rd, false);
    }

    /**
     * Map-based params. Chain .param() calls on the returned ParametersSecond.
     * Access by name in filters: (ctx, a, b) -> ((String) a.get("name"))
     */
    public ParametersSecond<DS, ArgMap> map() {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new ParametersSecond<>(name, rd, true);
    }

    /**
     * Skip params — start the join chain directly. Uses the rule name in RuleDefinition.
     */
    public <A> JoinBuilder.Join1First<Void, DS, A> from(
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addSource(firstSource);
        return new JoinBuilder.Join1First<>(null, rd);
    }

    /** Zero-arg action — rule fires with only ctx, no facts or params. */
    public RuleResult<DS> ifn(Runnable action) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.setAction((ctx, facts) -> action.run());
        return new RuleResult<>(rd);
    }

    // extendsRule() — all 6 overloads, one per base arity (RuleExtendsPoint2..7)

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
}
```

- [ ] **Step 4.2: Create ParametersSecond.java**

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Returned after param()/list()/map() on ParametersFirst. Allows chaining
 * additional param() calls then starting joins with from().
 *
 * B is either ArgList (positional access) or ArgMap (named access).
 * The params value (an ArgList or ArgMap instance) is passed at run time:
 * rule.run(ctx, new ArgList().add("Alice").add(18))
 */
public class ParametersSecond<DS, B> {
    private final String name;
    private final RuleDefinition<DS> rd;

    public ParametersSecond(String name, RuleDefinition<DS> rd, boolean mapMode) {
        this.name = name;
        this.rd = rd;
    }

    /**
     * Adds another named param declaration. Returns this for chaining.
     * The actual param value is provided by the caller at run time in the ArgList/ArgMap.
     */
    @SuppressWarnings({"unchecked", "varargs"})
    public <T> ParametersSecond<DS, B> param(String paramName, T... cls) {
        return this;
    }

    /**
     * Starts the join chain. B (ArgList or ArgMap) becomes fact[0];
     * the source's facts start at fact[1].
     * Pass an ArgList/ArgMap instance to rule.run(ctx, instance) at execution time.
     */
    public <A> JoinBuilder.Join2First<Void, DS, B, A> from(
            Function<DS, DataSource<A>> firstSource) {
        rd.addSource(firstSource);
        return new JoinBuilder.Join2First<>(null, rd);
    }

    /**
     * Terminates with a single-fact consumer — params only, no joins.
     */
    @SuppressWarnings("unchecked")
    public RuleResult<DS> fn(java.util.function.BiConsumer<DS, B> action) {
        rd.setAction((ctx, facts) -> action.accept((DS) ctx, (B) facts[0]));
        return new RuleResult<>(rd);
    }
}
```

- [ ] **Step 4.3: Add `rule(String name)` to RuleBuilder.java**

Read `RuleBuilder.java`. Add after the existing `from()` overloads:

```java
/**
 * Starts building a named rule. Returns ParametersFirst which supports all
 * four param styles plus from() to skip params.
 *
 * <pre>{@code
 * builder.rule("findAdults")
 *        .from(ctx -> ctx.persons())
 *        .filter((ctx, p) -> p.age() >= 18)
 *        .fn((ctx, p) -> { });
 * }</pre>
 */
public ParametersFirst<DS> rule(String name) {
    return new ParametersFirst<>(name);
}
```

- [ ] **Step 4.4: Verify compilation:**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples --also-make -q && echo "OK"
```

Expected: `OK`

- [ ] **Step 4.5: Commit:**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/ParametersFirst.java
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/ParametersSecond.java
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilder.java
git commit -m "feat(drools): add ParametersFirst, ParametersSecond, and RuleBuilder.rule()"
```

---

## Task 5: JoinBuilder template — fn() return type + full Maven build

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`

This is the most impactful change — `fn()` currently returns `RuleDefinition<DS>`. Changing it to `RuleResult<DS>` affects all generated `JoinNSecond.fn()` methods. Existing tests continue to compile because `RuleResult` exposes the identical query API.

- [ ] **Step 5.1: Locate fn() on Join0Second in the template**

Read `JoinBuilder.java`. Find `fn()` on `Join0Second`. It currently looks like:

```java
public Object fn(...consumer...) {
    rd.setAction(typedConsumer);
    return rd;
}
```

- [ ] **Step 5.2: Change fn() to return RuleResult<DS>**

The `fn()` method has a `@PermuteReturn` annotation. Change it to return `RuleResult<DS>`:

Replace the existing `fn()` annotation and body with:

```java
@PermuteReturn(className = "RuleResult", typeArgs = "'DS'", when = "true")
public Object fn(
        @PermuteDeclr(type = "Consumer${i+1}<DS, ${typeArgList(1, i, 'alpha')}>")
        Object typedConsumer) {
    rd.setAction(typedConsumer);
    return new RuleResult<>(rd);
}
```

**Important:** Read the existing `fn()` carefully — match its exact `@PermuteDeclr` annotation and consumer type. Only change the `@PermuteReturn` annotation and the return statement (`return rd` → `return new RuleResult<>(rd)`). Do NOT change the parameter annotation.

- [ ] **Step 5.3: Full Maven build:**

```bash
/opt/homebrew/bin/mvn clean install
```

Expected: BUILD SUCCESS. Verify the generated `JoinBuilder.java` shows `fn()` returning `RuleResult<DS>`:

```bash
grep -A 3 "public RuleResult" /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java | head -20
```

- [ ] **Step 5.4: Commit:**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "feat(drools): change fn() to return RuleResult<DS> across all generated Join classes"
```

---

## Task 6: NamedRuleTest

**Files:**
- Create: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/NamedRuleTest.java`

- [ ] **Step 6.1: Create NamedRuleTest.java with all 7 tests:**

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;

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
                        new Library("ScienceLib", List.of()),
                        new Library("ArtsLib", List.of())));
    }

    @Test
    public void testNamedRuleFromShorthand() {
        // builder.rule("name").from() works identically to builder.from("name", source)
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
        // .fn(...).end() compiles — end() is a no-op returning null (Void)
        // This mirrors vol2's chain syntax where end() closes a named-rule scope.
        builder.rule("r1")
               .from(ctx -> ctx.persons())
               .filter((ctx, p) -> p.age() >= 18)
               .fn((ctx, p) -> { })
               .end();  // compiles; returns null — no assertion needed
    }

    @Test
    public void testTypedParams() {
        // Approach 1: .<P>params() — typed capture, P3 injected as fact[0]
        Params3 params = new Params3("Alice", "world", "foo");

        var rule = builder.rule("withTypedParams")
                          .<Params3>params()
                          .join(ctx -> ctx.persons())
                          .filter((ctx, p, person) -> person.name().equals(p.p1()))
                          .fn((ctx, p, person) -> { });

        rule.run(ctx, params);
        // Alice matches p.p1() == "Alice"
        assertThat(rule.executionCount()).isEqualTo(1);
        Params3 captured = (Params3) rule.capturedFact(0, 0);
        assertThat(captured.p1()).isEqualTo("Alice");
        assertThat(((Person) rule.capturedFact(0, 1)).name()).isEqualTo("Alice");
    }

    @Test
    public void testListParams() {
        // Approach 2: .param() → ArgList, access positionally via get(int)
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
        // Approach 3: .map().param() → ArgMap, access by name via get(String)
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
        // Approach 4: .<T>param("name") — typed per-param, accumulates into ArgList
        ArgList args = new ArgList().add("Alice").add(18);

        var rule = builder.rule("withIndividualParams")
                          .<String>param("name")
                          .<Integer>param("minAge")
                          .from(ctx -> ctx.persons())
                          .filter((ctx, a, p) -> p.name().equals((String) a.get(0)))
                          .fn((ctx, a, p) -> { });

        rule.run(ctx, args);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 1)).name()).isEqualTo("Alice");
    }

    @Test
    public void testNamedRuleWithExtensionPoint() {
        // rule("name") integrates with extensionPoint()/extendsRule()
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
        assertThat(((Person)  rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
        assertThat(((Account) rule.capturedFact(0, 1)).id()).isEqualTo("ACC1");
    }
}
```

- [ ] **Step 6.2: Run NamedRuleTest:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -Dtest=NamedRuleTest
```

Expected: 7 tests PASS.

- [ ] **Step 6.3: Run full suite for regressions:**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples
```

Expected: all tests pass (60 existing + 7 new = 67 total).

- [ ] **Step 6.4: Commit:**

```bash
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/NamedRuleTest.java
git commit -m "test(drools): add NamedRuleTest — named rules, all four param approaches, extensionPoint integration"
```

---

## Task 7: RuleBuilderExamples + final build

**Files:**
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilderExamples.java`

Read the file first. Add these two methods after the existing `extendsExample()` method:

- [ ] **Step 7.1: Add named-rule examples to RuleBuilderExamples.java**

```java
/**
 * Named rule with typed params — preferred style matching vol2 API.
 * Params3 is injected as fact[0]; persons cross-product at fact[1].
 * At run time: rule.run(ctx, new Params3("Alice", "world", "foo"))
 */
public static RuleResult<Ctx> namedRuleWithTypedParams(RuleBuilder<Ctx> builder) {
    record Params3(String p1, String p2, String p3) {}

    return builder.rule("findMatchingAdults")
                  .<Params3>params()
                  .join(ctx -> ctx.persons())
                  .filter((ctx, p, person) -> person.name().equals(p.p1()))
                  .fn((ctx, p, person) -> System.out.println(person.name()));
}

/**
 * Named rule with map params — named access, no record needed.
 * At run time: rule.run(ctx, new ArgMap().put("name", "Alice"))
 */
public static RuleResult<Ctx> namedRuleWithMapParams(RuleBuilder<Ctx> builder) {
    return builder.rule("filterByName")
                  .map()
                  .param("name", String.class)
                  .from(ctx -> ctx.persons())
                  .filter((ctx, a, p) -> p.name().equals((String) a.get("name")))
                  .fn((ctx, a, p) -> System.out.println(p.name()));
}
```

- [ ] **Step 7.2: Full build:**

```bash
/opt/homebrew/bin/mvn clean install
```

Expected: BUILD SUCCESS.

- [ ] **Step 7.3: Commit:**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilderExamples.java
git commit -m "docs(drools): add named-rule typed and map-param examples to RuleBuilderExamples"
```
