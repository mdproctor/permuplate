# Drools RuleBuilder Example — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-contained Drools RuleBuilder DSL approximation in `permuplate-mvn-examples` with 9 hand-written classes, 4 Permuplate templates generating 24 classes, and a comprehensive test suite covering arities 1–6.

**Architecture:** Infrastructure classes (`DataSource`, domain types, `Ctx`, `RuleDefinition`, `RuleBuilder`) live in `src/main/java/...drools/`. Templates (`Consumer1`, `Predicate1`, `Join0Second`, `Join0First`) live in `src/main/permuplate/...drools/` — processed by the Maven plugin before javac. Tests in `src/test/java/...drools/`. Alpha naming (`${alpha(j)}`) throughout to match Drools conventions; explicit `@PermuteReturn`/`@PermuteDeclr` required everywhere.

**Tech Stack:** Java 17 records, Maven plugin (`permuplate-maven-plugin`), JUnit 4, JavaParser (via Permuplate). Build: `/opt/homebrew/bin/mvn clean install` from worktree root.

**Key facts:**
- Package: `io.quarkiverse.permuplate.example.drools`  
- Max arity: 6 fact types → Consumer/Predicate generate to `to=7` (DS + 6 facts = 7 params)
- Templates in `src/main/permuplate/` because all use `keepTemplate=true`
- `join()` returns `rd.asNext()` — unchecked cast via Java type erasure (safe for fluent chain)
- `filter()` uses explicit `@PermuteReturn(when="true")` — self-return inference not yet implemented
- `fn()` uses explicit `@PermuteReturn(when="true")` — RuleDefinition not in generated set
- All templates: `inline=false` (default) → generated classes are top-level, not nested

---

## File Map

| File | Task |
|---|---|
| `permuplate-mvn-examples/pom.xml` | 1 — add JUnit test dependency |
| `src/main/java/.../drools/Person.java` | 1 |
| `src/main/java/.../drools/Account.java` | 1 |
| `src/main/java/.../drools/Order.java` | 1 |
| `src/main/java/.../drools/Product.java` | 1 |
| `src/main/java/.../drools/Transaction.java` | 1 |
| `src/main/java/.../drools/DataSource.java` | 1 |
| `src/main/java/.../drools/Ctx.java` | 1 |
| `src/main/java/.../drools/RuleDefinition.java` | 2 |
| `src/main/java/.../drools/RuleBuilder.java` | 4 |
| `src/main/permuplate/.../drools/Consumer1.java` | 3 |
| `src/main/permuplate/.../drools/Predicate1.java` | 3 |
| `src/main/permuplate/.../drools/Join0Second.java` | 4 |
| `src/main/permuplate/.../drools/Join0First.java` | 4 |
| `src/test/java/.../drools/RuleBuilderTest.java` | 5 |

All paths relative to `permuplate-mvn-examples/`.

---

## Task 1: Module setup — pom.xml + domain types + DataSource + Ctx

**Files:** `pom.xml`, 5 domain records, `DataSource.java`, `Ctx.java`

- [ ] **Step 1: Add JUnit test dependency to `pom.xml`**

Find the `<dependencies>` block in `permuplate-mvn-examples/pom.xml` and add:

```xml
<dependency>
    <groupId>junit</groupId>
    <artifactId>junit</artifactId>
    <version>4.13.2</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.google.truth</groupId>
    <artifactId>truth</artifactId>
    <version>1.1.5</version>
    <scope>test</scope>
</dependency>
```

Also add `<build><testSourceDirectory>` if not already present (Maven standard layout handles `src/test/java/` automatically — no change needed if it's standard Maven).

- [ ] **Step 2: Create the 5 domain records**

Create each in `src/main/java/io/quarkiverse/permuplate/example/drools/`:

```java
// Person.java
package io.quarkiverse.permuplate.example.drools;
public record Person(String name, int age) {}
```

```java
// Account.java
package io.quarkiverse.permuplate.example.drools;
public record Account(String id, double balance) {}
```

```java
// Order.java
package io.quarkiverse.permuplate.example.drools;
public record Order(String id, double amount) {}
```

```java
// Product.java
package io.quarkiverse.permuplate.example.drools;
public record Product(String id, double price) {}
```

```java
// Transaction.java
package io.quarkiverse.permuplate.example.drools;
public record Transaction(String id, double amount) {}
```

- [ ] **Step 3: Create `DataSource.java`**

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class DataSource<T> {
    private final List<T> items = new ArrayList<>();

    public DataSource<T> add(T item) {
        items.add(item);
        return this;
    }

    public List<T> asList() {
        return Collections.unmodifiableList(items);
    }

    public Stream<T> stream() {
        return items.stream();
    }

    @SafeVarargs
    public static <T> DataSource<T> of(T... items) {
        DataSource<T> ds = new DataSource<>();
        Arrays.stream(items).forEach(ds::add);
        return ds;
    }
}
```

- [ ] **Step 4: Create `Ctx.java`**

```java
package io.quarkiverse.permuplate.example.drools;

public record Ctx(
        DataSource<Person>      persons,
        DataSource<Account>     accounts,
        DataSource<Order>       orders,
        DataSource<Product>     products,
        DataSource<Transaction> transactions) {}
```

- [ ] **Step 5: Build to verify everything compiles**

```bash
cd /Users/mdproctor/claude/permuplate/.worktrees/feature/drools-example
/opt/homebrew/bin/mvn clean compile --no-transfer-progress -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add permuplate-mvn-examples/pom.xml \
        permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/
git commit -m "feat(drools): add domain types, DataSource, Ctx and test dependency"
```

---

## Task 2: `RuleDefinition`

**Files:** `src/main/java/.../drools/RuleDefinition.java`

- [ ] **Step 1: Create `RuleDefinition.java`**

```java
package io.quarkiverse.permuplate.example.drools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Captures the structure of a rule built via the DSL and executes it.
 *
 * <p>All JoinFirst and JoinSecond generated classes hold a reference to a shared
 * RuleDefinition. Typed predicates and consumers are wrapped into internal
 * NaryPredicate/NaryConsumer representations via reflection — called once at
 * rule-build time, not on every run().
 *
 * <p>{@code asNext()} uses an unchecked cast: the fluent chain relies on Java's
 * type erasure so that the same RuleDefinition object satisfies any Join(N)First
 * declared return type. This is documented in DROOLS-DSL.md.
 */
public class RuleDefinition<DS> {

    @FunctionalInterface
    interface NaryPredicate {
        boolean test(Object ctx, Object[] facts);
    }

    @FunctionalInterface
    interface NaryConsumer {
        void accept(Object ctx, Object[] facts);
    }

    private final String name;
    private final List<Function<DS, DataSource<?>>> sources = new ArrayList<>();
    private final List<NaryPredicate> filters = new ArrayList<>();
    private NaryConsumer action;
    private final List<List<Object>> executions = new ArrayList<>();

    public RuleDefinition(String name) {
        this.name = name;
    }

    // -------------------------------------------------------------------------
    // Builder methods — called by generated JoinFirst/Second classes
    // -------------------------------------------------------------------------

    public void addSource(Object sourceSupplier) {
        @SuppressWarnings("unchecked")
        Function<DS, DataSource<?>> typed = (Function<DS, DataSource<?>>) sourceSupplier;
        sources.add(typed);
    }

    public void addFilter(Object typedPredicate) {
        filters.add(wrapPredicate(typedPredicate));
    }

    public void setAction(Object typedConsumer) {
        this.action = wrapConsumer(typedConsumer);
    }

    /**
     * Returns this RuleDefinition as the next JoinFirst type in the chain.
     * Uses an unchecked cast — safe because the fluent chain never stores the
     * intermediate type; the JVM erases generics and the reference is valid.
     */
    @SuppressWarnings("unchecked")
    public <T> T asNext() {
        return (T) this;
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    public RuleDefinition<DS> run(DS ctx) {
        executions.clear();
        // Build cross-product of all sources
        List<Object[]> combinations = List.of(new Object[0]);
        for (Function<DS, DataSource<?>> supplier : sources) {
            List<Object[]> next = new ArrayList<>();
            for (Object item : supplier.apply(ctx).asList()) {
                for (Object[] combo : combinations) {
                    Object[] extended = Arrays.copyOf(combo, combo.length + 1);
                    extended[combo.length] = item;
                    next.add(extended);
                }
            }
            combinations = next;
        }
        // Apply filters, then execute action for each matching combination
        for (Object[] facts : combinations) {
            if (filters.stream().allMatch(f -> f.test(ctx, facts))) {
                if (action != null) {
                    action.accept(ctx, facts);
                }
                executions.add(Arrays.asList(facts));
            }
        }
        return this;
    }

    // -------------------------------------------------------------------------
    // Test assertions API
    // -------------------------------------------------------------------------

    public String name() { return name; }

    public int sourceCount() { return sources.size(); }

    public int filterCount() { return filters.size(); }

    public boolean hasAction() { return action != null; }

    public int executionCount() { return executions.size(); }

    public List<Object> capturedFacts(int execution) {
        return Collections.unmodifiableList(executions.get(execution));
    }

    public Object capturedFact(int execution, int position) {
        return executions.get(execution).get(position);
    }

    // -------------------------------------------------------------------------
    // Reflection wrappers — called once at rule-build time
    // -------------------------------------------------------------------------

    private static NaryPredicate wrapPredicate(Object typed) {
        Method m = findMethod(typed, "test");
        return (ctx, facts) -> {
            try {
                Object[] args = buildArgs(ctx, facts);
                return (Boolean) m.invoke(typed, args);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Predicate invocation failed", e);
            }
        };
    }

    private static NaryConsumer wrapConsumer(Object typed) {
        Method m = findMethod(typed, "accept");
        return (ctx, facts) -> {
            try {
                Object[] args = buildArgs(ctx, facts);
                m.invoke(typed, args);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Consumer invocation failed", e);
            }
        };
    }

    private static Method findMethod(Object target, String name) {
        return Arrays.stream(target.getClass().getMethods())
                .filter(m -> m.getName().equals(name) && !m.isSynthetic())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No method '" + name + "' on " + target.getClass()));
    }

    private static Object[] buildArgs(Object ctx, Object[] facts) {
        Object[] args = new Object[facts.length + 1];
        args[0] = ctx;
        System.arraycopy(facts, 0, args, 1, facts.length);
        return args;
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
/opt/homebrew/bin/mvn clean compile --no-transfer-progress -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java
git commit -m "feat(drools): add RuleDefinition — cross-product evaluator + test assertion API"
```

---

## Task 3: `Consumer1` and `Predicate1` templates

**Files:** `src/main/permuplate/.../drools/Consumer1.java`, `Predicate1.java`

Note: templates go in `src/main/permuplate/` because `keepTemplate=true` would cause a duplicate class error if the template file were also in `src/main/java/`.

Consumer and Predicate generate to `to=7` (not 6!) because `Join6Second.fn()` needs `Consumer7<DS, A, B, C, D, E, F>` (DS + 6 facts = 7 total parameters). Same for Predicate.

- [ ] **Step 1: Create `Consumer1.java`**

Create `src/main/permuplate/io/quarkiverse/permuplate/example/drools/Consumer1.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Template generating Consumer2 through Consumer7.
 * Consumer1 itself (keepTemplate=true) provides the arity-1 version.
 *
 * Generated: Consumer2<DS,A,B> through Consumer7<DS,A,B,C,D,E,F>
 * to=7 because Join6Second.fn() needs Consumer7 (DS + 6 facts = 7 params).
 */
@Permute(varName = "i", from = 2, to = 7, className = "Consumer${i}",
         inline = false, keepTemplate = true)
public interface Consumer1<DS,
        @PermuteTypeParam(varName = "j", from = "1", to = "${i}", name = "${alpha(j)}") A> {
    void accept(
            DS ctx,
            @PermuteParam(varName = "j", from = "1", to = "${i}",
                          type = "${alpha(j)}", name = "${lower(j)}") A a);
}
```

- [ ] **Step 2: Create `Predicate1.java`**

Create `src/main/permuplate/io/quarkiverse/permuplate/example/drools/Predicate1.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Template generating Predicate2 through Predicate7.
 * Predicate1 itself (keepTemplate=true) provides the arity-1 version.
 *
 * Generated: Predicate2<DS,A,B> through Predicate7<DS,A,B,C,D,E,F>
 */
@Permute(varName = "i", from = 2, to = 7, className = "Predicate${i}",
         inline = false, keepTemplate = true)
public interface Predicate1<DS,
        @PermuteTypeParam(varName = "j", from = "1", to = "${i}", name = "${alpha(j)}") A> {
    boolean test(
            DS ctx,
            @PermuteParam(varName = "j", from = "1", to = "${i}",
                          type = "${alpha(j)}", name = "${lower(j)}") A a);
}
```

- [ ] **Step 3: Build and verify Consumer2–7 and Predicate2–7 are generated**

```bash
/opt/homebrew/bin/mvn clean generate-sources --no-transfer-progress -pl permuplate-mvn-examples -am 2>&1 | tail -10
ls permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/drools/
```

Expected: `Consumer2.java Consumer3.java ... Consumer7.java Predicate2.java ... Predicate7.java`

Then full compile:

```bash
/opt/homebrew/bin/mvn clean compile --no-transfer-progress -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Spot-check Consumer3 source**

```bash
cat permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/drools/Consumer3.java
```

Expected to contain: `Consumer3<DS, A, B, C>` and `void accept(DS ctx, A a, B b, C c)`

- [ ] **Step 5: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/
git commit -m "feat(drools): add Consumer1 + Predicate1 templates — generates Consumer2..7, Predicate2..7"
```

---

## Task 4: `Join0Second`, `Join0First` templates + `RuleBuilder`

**Files:** `Join0Second.java`, `Join0First.java`, `RuleBuilder.java`

This is the core of the DSL chain. Key design points:
- `Join0Second` generates `Join1Second..Join6Second`; `Join6Second` is the leaf (no `join()`)
- `Join0First` generates `Join1First..Join6First`; each extends the corresponding Second (G3 auto-expanded)
- `join()` returns `rd.asNext()` — unchecked cast, safe via type erasure
- `filter()` uses `@PermuteReturn(when="true")` — self-return, prevents boundary omission
- `fn()` uses `@PermuteReturn(when="true")` — RuleDefinition not in generated set

- [ ] **Step 1: Create `Join0Second.java`**

Create `src/main/permuplate/io/quarkiverse/permuplate/example/drools/Join0Second.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteMethod;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteReturn;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Template generating Join1Second through Join6Second — the "gateway" classes
 * that advance the arity. Join6Second is the leaf: @PermuteMethod.to is inferred
 * as @Permute.to - i = 6 - 6 = 0, producing zero join() overloads automatically.
 *
 * <p>join() returns rd.asNext() — an unchecked cast relying on Java's type erasure.
 * The declared return type (Join(i+1)First) is correct at compile time; at runtime
 * the same RuleDefinition object satisfies any JoinFirst reference. Documented in
 * DROOLS-DSL.md.
 *
 * <p>fn() uses when="true" because RuleDefinition is not in the generated set;
 * without it, boundary omission would silently remove fn() from every class.
 */
@Permute(varName = "i", from = 1, to = 6, className = "Join${i}Second",
         inline = false, keepTemplate = true)
public class Join0Second<DS,
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A> {

    protected final RuleDefinition<DS> rd;

    public Join0Second(RuleDefinition<DS> rd) {
        this.rd = rd;
    }

    /**
     * Advances the arity by one fact type. Returns the next JoinFirst in the chain.
     * Join6Second has no join() — @PermuteMethod.to is inferred as 6-i=0 (leaf node).
     *
     * Boundary omission handles the leaf: Join7First is not in the generated set,
     * so join() is silently omitted from Join6Second without any annotation needed.
     */
    @PermuteMethod(varName = "j")
    @PermuteReturn(className = "Join${i+1}First",
                   typeArgs = "DS, ${typeArgList(1, i+1, 'alpha')}")
    public Object join(
            @PermuteDeclr(type = "java.util.function.Function<DS, DataSource<${alpha(i+1)}>>")
            Object source) {
        rd.addSource(source);
        return rd.asNext();
    }

    /**
     * Terminal operation — records the action and returns the RuleDefinition.
     * when="true" prevents boundary omission (RuleDefinition is not in the generated set).
     */
    @PermuteReturn(className = "RuleDefinition", typeArgs = "DS", when = "true")
    public Object fn(
            @PermuteDeclr(type = "Consumer${i+1}<DS, ${typeArgList(1, i, 'alpha')}>")
            Object action) {
        rd.setAction(action);
        return rd;
    }
}
```

- [ ] **Step 2: Create `Join0First.java`**

Create `src/main/permuplate/io/quarkiverse/permuplate/example/drools/Join0First.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteReturn;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Template generating Join1First through Join6First — the "full" classes that
 * also provide arity-preserving filter(). Extends the corresponding Second class;
 * the extends clause is auto-expanded by G3 implicit inference.
 *
 * <p>filter() self-returns (same arity, same type). Requires explicit @PermuteReturn
 * because self-return inference (TODO-2) is not yet implemented. when="true"
 * prevents boundary omission.
 */
@Permute(varName = "i", from = 1, to = 6, className = "Join${i}First",
         inline = false, keepTemplate = true)
public class Join0First<DS,
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
        extends Join0Second<DS, A> {

    public Join0First(RuleDefinition<DS> rd) {
        super(rd);
    }

    /**
     * Applies a predicate to the accumulated facts. Returns this — the same
     * JoinFirst instance — allowing repeated filter() chaining at the same arity.
     *
     * Explicit @PermuteReturn required: self-return inference (TODO-2) not yet
     * implemented. when="true" prevents boundary omission.
     */
    @PermuteReturn(className = "Join${i}First",
                   typeArgs = "DS, ${typeArgList(1, i, 'alpha')}",
                   when = "true")
    public Object filter(
            @PermuteDeclr(type = "Predicate${i+1}<DS, ${typeArgList(1, i, 'alpha')}>")
            Object predicate) {
        rd.addFilter(predicate);
        return this;
    }
}
```

- [ ] **Step 3: Create `RuleBuilder.java`**

Create `src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilder.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Entry point for the rule-building DSL.
 *
 * <pre>{@code
 * RuleBuilder<Ctx> builder = new RuleBuilder<>();
 * RuleDefinition<Ctx> rule = builder.rule("adults")
 *         .from(ctx -> ctx.persons())
 *         .filter((ctx, a) -> a.age() >= 18)
 *         .fn((ctx, a) -> System.out.println(a.name()));
 * rule.run(ctx);
 * }</pre>
 */
public class RuleBuilder<DS> {

    public <A> Join1First<DS, A> rule(String name,
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addSource(firstSource);
        return new Join1First<>(rd);
    }
}
```

Wait — looking at the spec, `RuleBuilder` has a `rule(String name)` method that returns something you then call `.from()` on. But that requires an intermediate object. For simplicity let's combine `rule()` and `from()` into one call on `RuleBuilder`. If a separate `rule()` step is needed later (Phase 2+), it can be added. For Phase 1 this single-method entry point is clean.

Actually to match the DSL syntax from the spec more closely:
```java
builder.rule("adults")
       .from(ctx -> ctx.persons())
```

This needs `rule()` to return something with a `from()` method. Let's keep it two-step but use a minimal intermediate:

```java
public class RuleBuilder<DS> {

    public RuleStarter<DS> rule(String name) {
        return new RuleStarter<>(name);
    }

    public class RuleStarter<DS2> {
        private final String name;
        RuleStarter(String name) { this.name = name; }

        public <A> Join1First<DS2, A> from(
                java.util.function.Function<DS2, DataSource<A>> source) {
            RuleDefinition<DS2> rd = new RuleDefinition<>(name);
            rd.addSource(source);
            return new Join1First<>(rd);
        }
    }
}
```

Actually this gets complex with inner class generics. Simplest working version — combine into one method:

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

public class RuleBuilder<DS> {

    /**
     * Starts building a rule with its first fact source.
     * The rule name is recorded in the resulting RuleDefinition.
     */
    public <A> Join1First<DS, A> from(String name,
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addSource(firstSource);
        return new Join1First<>(rd);
    }
}
```

This is the simplest API for Phase 1. The DSL then reads:

```java
var rule = builder.from("adults", ctx -> ctx.persons())
        .filter((ctx, a) -> a.age() >= 18)
        .fn((ctx, a) -> {});
```

- [ ] **Step 4: Build and verify Join classes are generated**

```bash
/opt/homebrew/bin/mvn clean generate-sources --no-transfer-progress -pl permuplate-mvn-examples -am 2>&1 | tail -10
ls permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/drools/
```

Expected: `Join1First.java Join2First.java ... Join6First.java Join1Second.java ... Join6Second.java Consumer2.java ... Consumer7.java Predicate2.java ... Predicate7.java`

- [ ] **Step 5: Full build**

```bash
/opt/homebrew/bin/mvn clean compile --no-transfer-progress -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. If there are compilation errors, check:
- `Join0First extends Join0Second` — ensure the extends clause in the generated file shows `Join${i}First extends Join${i}Second` (G3 auto-expansion)
- `RuleDefinition` import in the generated classes
- `join()` method present on Join1-5Second, absent on Join6Second

- [ ] **Step 6: Spot-check key generated files**

```bash
# Verify Join2First extends Join2Second and has filter()
grep -A3 "class Join2First" permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/drools/Join2First.java

# Verify Join6Second has no join() method (leaf node)
grep "join\|fn\|filter" permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/drools/Join6Second.java
```

Expected for Join2First: `class Join2First<DS, A, B> extends Join2Second<DS, A, B>`  
Expected for Join6Second: contains `fn(` but NOT `join(`

- [ ] **Step 7: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/Join0Second.java \
        permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/Join0First.java \
        permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilder.java
git commit -m "feat(drools): add Join0Second + Join0First templates + RuleBuilder — full DSL chain generated"
```

---

## Task 5: `RuleBuilderTest` — write and verify all tests

**Files:** `src/test/java/.../drools/RuleBuilderTest.java`

- [ ] **Step 1: Create the test class**

Create `src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

public class RuleBuilderTest {

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
                DataSource.of(new Transaction("TXN1", 200.0), new Transaction("TXN2", 15.0))
        );
    }

    // =========================================================================
    // Arity 1 — structural
    // =========================================================================

    @Test
    public void testArity1Structural() {
        var rule = builder.from("structural", ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() >= 18)
                .fn((ctx, a) -> {});

        assertThat(rule.sourceCount()).isEqualTo(1);
        assertThat(rule.filterCount()).isEqualTo(1);
        assertThat(rule.hasAction()).isTrue();
    }

    // =========================================================================
    // Arity 1 — behavioural
    // =========================================================================

    @Test
    public void testArity1AdultFilterMatchesOnlyAlice() {
        var rule = builder.from("adults", ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() >= 18)
                .fn((ctx, a) -> {});

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
    }

    @Test
    public void testArity1NoFilterMatchesAll() {
        var rule = builder.from("all", ctx -> ctx.persons())
                .fn((ctx, a) -> {});

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(2);
    }

    @Test
    public void testArity1MultipleFiltersChained() {
        var rule = builder.from("chained", ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() >= 18)
                .filter((ctx, a) -> a.name().startsWith("A"))
                .fn((ctx, a) -> {});

        assertThat(rule.filterCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
    }

    // =========================================================================
    // Arity 2 — structural + behavioural
    // =========================================================================

    @Test
    public void testArity2CrossProductNoFilter() {
        var rule = builder.from("all-pairs", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .fn((ctx, a, b) -> {});

        assertThat(rule.sourceCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(4); // 2 persons × 2 accounts
    }

    @Test
    public void testArity2FilterOnBothFacts() {
        var rule = builder.from("adult-high-balance", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
                .fn((ctx, a, b) -> {});

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testArity2CapturedFactsDistinguishByType() {
        var rule = builder.from("typed", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .fn((ctx, a, b) -> {});

        rule.run(ctx);

        // Each execution contains one Person and one Account — types are distinct
        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Account.class);
    }

    // =========================================================================
    // Arity 3 — with intermediate filter
    // =========================================================================

    @Test
    public void testArity3IntermediateFilter() {
        var rule = builder.from("filtered-triple", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
                .join(ctx -> ctx.orders())
                .filter((ctx, a, b, c) -> c.amount() > 100.0)
                .fn((ctx, a, b, c) -> {});

        assertThat(rule.sourceCount()).isEqualTo(3);
        assertThat(rule.filterCount()).isEqualTo(2);

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
        assertThat(rule.capturedFact(0, 2)).isEqualTo(new Order("ORD1", 150.0));
    }

    // =========================================================================
    // Arity 4, 5 — type differentiation
    // =========================================================================

    @Test
    public void testArity4AllTypesDistinct() {
        var rule = builder.from("four-facts", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .join(ctx -> ctx.products())
                .fn((ctx, a, b, c, d) -> {});

        assertThat(rule.sourceCount()).isEqualTo(4);
        rule.run(ctx);

        // Verify fact types at each position
        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Account.class);
        assertThat(rule.capturedFact(0, 2)).isInstanceOf(Order.class);
        assertThat(rule.capturedFact(0, 3)).isInstanceOf(Product.class);
    }

    @Test
    public void testArity5AllTypesDistinct() {
        var rule = builder.from("five-facts", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .join(ctx -> ctx.products())
                .join(ctx -> ctx.transactions())
                .fn((ctx, a, b, c, d, e) -> {});

        assertThat(rule.sourceCount()).isEqualTo(5);
        rule.run(ctx);

        assertThat(rule.capturedFact(0, 4)).isInstanceOf(Transaction.class);
    }

    // =========================================================================
    // Arity 6 — leaf node compile-time test
    // Verifies Join6Second has no join() method — the type system prevents
    // advancing beyond 6 facts. If this compiles, the leaf node is correct.
    // =========================================================================

    @Test
    public void testArity6LeafNodeCompiles() {
        var rule = builder.from("six-facts", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .join(ctx -> ctx.products())
                .join(ctx -> ctx.transactions())
                .join(ctx -> ctx.persons())   // 6th fact (reusing persons source)
                .fn((ctx, a, b, c, d, e, f) -> {});

        assertThat(rule.sourceCount()).isEqualTo(6);
        // No runtime assertion needed — the compile-time type check is the guarantee
    }

    // =========================================================================
    // Filter rejection — verify cross-product is correct
    // =========================================================================

    @Test
    public void testFilterRejectsAllCombinations() {
        var rule = builder.from("impossible", ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() > 200)  // No person is over 200
                .fn((ctx, a) -> {});

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(0);
    }

    @Test
    public void testMultipleExecutionsRecordedSeparately() {
        var rule = builder.from("multi", ctx -> ctx.persons())
                .fn((ctx, a) -> {});  // no filter — all match

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(2);
        assertThat(rule.capturedFacts(0)).containsExactly(new Person("Alice", 30));
        assertThat(rule.capturedFacts(1)).containsExactly(new Person("Bob", 17));
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
cd /Users/mdproctor/claude/permuplate/.worktrees/feature/drools-example
/opt/homebrew/bin/mvn test --no-transfer-progress -pl permuplate-mvn-examples -am 2>&1 | grep -E "Tests run|BUILD|FAIL" | tail -5
```

Expected: `BUILD SUCCESS`, all tests pass.

If tests fail:
- `testArity6LeafNodeCompiles` fails with compile error → `Join6Second` still has `join()`. Check `@PermuteMethod(varName="j")` on `Join0Second.join()`. The `to` is inferred as `@Permute.to - i = 6 - 6 = 0`. If generation is wrong, check if `@PermuteMethod` is on the correct method.
- `testArity2FilterOnBothFacts` fails with wrong captured facts → check the cross-product order in `RuleDefinition.run()` (outer loop = first source, inner = second).
- Any test fails with `NullPointerException` in `wrapPredicate` → check `findMethod()` is finding the right `test()`/`accept()` method on the lambda's class.

- [ ] **Step 3: Full project build to verify no regressions**

```bash
/opt/homebrew/bin/mvn clean install --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD" | tail -5
```

Expected: `BUILD SUCCESS`, all existing 129 tests still pass plus the new drools tests.

- [ ] **Step 4: Commit**

```bash
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java
git commit -m "test(drools): add RuleBuilderTest covering arities 1-6, structural + behavioural assertions"
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task | Status |
|---|---|---|
| 5 domain records (Person, Account, Order, Product, Transaction) | Task 1 | ✓ |
| `DataSource<T>` list wrapper | Task 1 | ✓ |
| `Ctx` record with 5 typed DataSources | Task 1 | ✓ |
| `RuleDefinition` — structural recording | Task 2 | ✓ |
| `RuleDefinition` — cross-product join evaluator | Task 2 | ✓ |
| `RuleDefinition` — reflection wrapper (once at build time) | Task 2 | ✓ |
| Test assertion API (`sourceCount`, `filterCount`, `executionCount`, `capturedFact`) | Task 2 | ✓ |
| `Consumer1` template → Consumer2..Consumer7 | Task 3 | ✓ (to=7, not 6) |
| `Predicate1` template → Predicate2..Predicate7 | Task 3 | ✓ (to=7, not 6) |
| `Join0Second` → Join1Second..Join6Second, leaf at 6 | Task 4 | ✓ |
| `Join0First` → Join1First..Join6First, extends auto-expanded | Task 4 | ✓ |
| `filter()` explicit `@PermuteReturn(when="true")` | Task 4 | ✓ |
| `fn()` explicit `@PermuteReturn(when="true")` | Task 4 | ✓ |
| `join()` unchecked cast via `rd.asNext()` | Task 4 | ✓ |
| `RuleBuilder` entry point | Task 4 | ✓ |
| 11 tests covering structural + behavioural + leaf node | Task 5 | ✓ (12 tests) |
| JUnit test dependency in pom.xml | Task 1 | ✓ |

**Placeholder scan:** None found.

**Type consistency:**
- `RuleDefinition<DS>` used consistently across all tasks
- `DataSource<T>` referenced in both templates (`DataSource<${alpha(i+1)}>`) and hand-written classes
- `Join1First<DS, A>` returned from `RuleBuilder.from()` matches the generated class in Task 4
- `Consumer${i+1}` in `fn()` and `Predicate${i+1}` in `filter()` consistently reference `i+1` (the total param count including DS)
