# Drools RuleBuilder Example — Design Spec

**Date:** 2026-04-04  
**Status:** Approved  
**Module:** `permuplate-mvn-examples`  
**Package:** `io.quarkiverse.permuplate.example.drools`  
**Reference:** `permuplate-mvn-examples/DROOLS-DSL.md` — design rationale, phase roadmap, open questions

---

## Purpose

A self-contained, testable approximation of the Drools RuleBuilder DSL built entirely with Permuplate. Serves as the primary playground for evolving and validating the Drools DSL using Permuplate's annotation-driven permutation before applying changes to the real Drools codebase.

**Phase 1 scope (this spec):** sequential join chain — `from → join → filter → fn` — up to 6 accumulated fact types.

---

## Naming Convention

Type parameters use **single-letter names** (`A, B, C, D, E, F`) matching Drools conventions, generated via `${alpha(j)}`. This requires explicit `@PermuteTypeParam`, `@PermuteReturn`, and `@PermuteDeclr` annotations throughout — single-letter names disable Permuplate's implicit inference (which requires the `T+number` pattern). See `DROOLS-DSL.md` for rationale.

---

## File Structure

### `src/main/java/.../drools/` — Hand-written infrastructure (9 classes)

**Domain types (5):**

| Class | Fields | Purpose |
|---|---|---|
| `Person` | `String name`, `int age` | Fact type for position A |
| `Account` | `String id`, `double balance` | Fact type for position B |
| `Order` | `String id`, `double amount` | Fact type for position C |
| `Product` | `String id`, `double price` | Fact type for position D |
| `Transaction` | `String id`, `double amount` | Fact type for position E |

**Infrastructure (4):**

| Class | Purpose |
|---|---|
| `DataSource<T>` | `List<T>` wrapper: `add(T)`, `stream()`, `asList()`, static `DataSource.of(T...)` |
| `Ctx` | Record holding one `DataSource<T>` per domain type — `persons()`, `accounts()`, `orders()`, `products()`, `transactions()` |
| `RuleDefinition<DS>` | Captures rule configuration; executes via `run(DS ctx)`; exposes recorded state for test assertions |
| `RuleBuilder<DS>` | Entry point: `rule(String name)` → returns a `Join1First<DS, Person>` via `.from(Function<DS, DataSource<A>>)` |

### `src/main/permuplate/.../drools/` — Permuplate templates (4 templates → 22 generated classes)

| Template | Generates | Max arity |
|---|---|---|
| `Consumer1<DS, A>` | `Consumer2..Consumer6` (`keepTemplate=true`) | 6 total params (DS + 5 facts) |
| `Predicate1<DS, A>` | `Predicate2..Predicate6` (`keepTemplate=true`) | 6 total params (DS + 5 facts) |
| `Join0Second<DS, A>` | `Join1Second..Join6Second` | `Join6Second` = leaf (no `join()`) |
| `Join0First<DS, A>` | `Join1First..Join6First` | extends corresponding Second |

### `src/test/java/.../drools/` — Tests

| Class | Purpose |
|---|---|
| `RuleBuilderTest` | Structural + behavioural assertions across arities 1–6 |

---

## Section 1: Domain Types

Simple records, enough fields for meaningful filter predicates:

```java
public record Person(String name, int age) {}
public record Account(String id, double balance) {}
public record Order(String id, double amount) {}
public record Product(String id, double price) {}
public record Transaction(String id, double amount) {}
```

---

## Section 2: Infrastructure

### `DataSource<T>`

```java
public class DataSource<T> {
    private final List<T> items = new ArrayList<>();
    public DataSource<T> add(T item) { items.add(item); return this; }
    public List<T> asList() { return Collections.unmodifiableList(items); }
    public Stream<T> stream() { return items.stream(); }
    public static <T> DataSource<T> of(T... items) {
        DataSource<T> ds = new DataSource<>();
        Arrays.stream(items).forEach(ds::add);
        return ds;
    }
}
```

### `Ctx`

```java
public record Ctx(
    DataSource<Person>      persons,
    DataSource<Account>     accounts,
    DataSource<Order>       orders,
    DataSource<Product>     products,
    DataSource<Transaction> transactions
) {}
```

### `RuleDefinition<DS>`

**Internal types:**

```java
@FunctionalInterface interface NaryPredicate { boolean test(Object ctx, Object[] facts); }
@FunctionalInterface interface NaryConsumer  { void accept(Object ctx, Object[] facts); }
```

**State:**

```java
private final String name;
private final List<Function<DS, DataSource<?>>> sources = new ArrayList<>();
private final List<NaryPredicate> filters = new ArrayList<>();
private NaryConsumer action;
private final List<List<Object>> executions = new ArrayList<>(); // one per fn() invocation
```

**Reflection wrapper** — converts any typed lambda into the internal `NaryPredicate`/`NaryConsumer`. Called once at rule-build time (not on every `run()`):

```java
static NaryPredicate wrapPredicate(Object typedPredicate) {
    Method m = Arrays.stream(typedPredicate.getClass().getMethods())
                     .filter(x -> x.getName().equals("test")).findFirst().orElseThrow();
    return (ctx, facts) -> {
        Object[] args = new Object[facts.length + 1];
        args[0] = ctx;
        System.arraycopy(facts, 0, args, 1, facts.length);
        return (Boolean) m.invoke(typedPredicate, args);
    };
}

static NaryConsumer wrapConsumer(Object typedConsumer) {
    Method m = Arrays.stream(typedConsumer.getClass().getMethods())
                     .filter(x -> x.getName().equals("accept")).findFirst().orElseThrow();
    return (ctx, facts) -> {
        Object[] args = new Object[facts.length + 1];
        args[0] = ctx;
        System.arraycopy(facts, 0, args, 1, facts.length);
        m.invoke(typedConsumer, args);
    };
}
```

**Builder methods** (called by generated JoinFirst/Second classes):

```java
void addSource(Object sourceSupplier)   // stores Function<DS, DataSource<?>>
void addFilter(Object typedPredicate)   // wraps + stores
void setAction(Object typedConsumer)    // wraps + stores
```

**Execution:**

```java
public RuleDefinition<DS> run(DS ctx) {
    executions.clear();
    List<Object[]> combinations = List.of(new Object[0]);
    for (var supplier : sources) {
        List<Object[]> next = new ArrayList<>();
        for (Object item : ((Function<DS, DataSource<?>>) supplier).apply(ctx).asList()) {
            for (Object[] combo : combinations) {
                Object[] extended = Arrays.copyOf(combo, combo.length + 1);
                extended[combo.length] = item;
                next.add(extended);
            }
        }
        combinations = next;
    }
    for (Object[] facts : combinations) {
        if (filters.stream().allMatch(f -> f.test(ctx, facts))) {
            if (action != null) action.accept(ctx, facts);
            executions.add(Arrays.asList(facts));
        }
    }
    return this;
}
```

**Test API:**

```java
public int sourceCount()                               // number of .from()/.join() calls
public int filterCount()                               // number of .filter() calls
public boolean hasAction()                             // was .fn() called?
public int executionCount()                            // times fn() fired after run()
public List<Object> capturedFacts(int execution)       // facts for one execution
public Object capturedFact(int execution, int pos)     // single fact at position
```

**Runtime factory:** All JoinFirst/Second classes hold a `RuleDefinition<DS>` reference. `join()` returns it with an unchecked cast — Java's type erasure allows this for the fluent chain. `filter()` returns `this` (the JoinFirst instance, correct at runtime). The unchecked cast is documented in `DROOLS-DSL.md`.

```java
@SuppressWarnings("unchecked")
public <T> T asNext() { return (T) this; }
```

---

## Section 3: Templates

### `Consumer1<DS, A>` (generates Consumer2–Consumer6)

```java
@Permute(varName="i", from=2, to=6, className="Consumer${i}", inline=true, keepTemplate=true)
public interface Consumer1<DS,
        @PermuteTypeParam(varName="j", from="1", to="${i}", name="${alpha(j)}") A> {
    void accept(
            DS ctx,
            @PermuteParam(varName="j", from="1", to="${i}",
                          type="${alpha(j)}", name="${lower(j)}") A a);
}
```

At `i=2`: `Consumer2<DS, A, B>` with `accept(DS ctx, A a, B b)`  
At `i=6`: `Consumer6<DS, A, B, C, D, E, F>` with `accept(DS ctx, A a, B b, C c, D d, E e, F f)`

### `Predicate1<DS, A>` (generates Predicate2–Predicate6)

Identical shape to Consumer1, return type `boolean`, method name `test`.

### `Join0Second<DS, A>` (generates Join1Second–Join6Second)

`Join6Second` is the **leaf node** — `@PermuteMethod.to` is inferred as `@Permute.to - i = 6 - 6 = 0`, producing zero `join()` overloads. Filter and fn still work via `Join6First`.

```java
@Permute(varName="i", from=1, to=6, className="Join${i}Second", inline=true, keepTemplate=true)
public class Join0Second<DS,
        @PermuteTypeParam(varName="k", from="1", to="${i}", name="${alpha(k)}") A> {

    protected final RuleDefinition<DS> rd;

    public Join0Second(RuleDefinition<DS> rd) { this.rd = rd; }

    // join() advances arity — boundary omission at i=6 (Join7First not in generated set)
    @PermuteReturn(className="Join${i+1}First",
                   typeArgs="DS, ${typeArgList(1, i+1, 'alpha')}")
    public Object join(
            @PermuteDeclr(type="java.util.function.Function<DS, DataSource<${alpha(i+1)}>>")
            Object source) {
        rd.addSource(source);
        return rd.asNext();
    }

    // fn() is terminal — RuleDefinition not in generated set, when="true" prevents omission
    @PermuteReturn(className="RuleDefinition", typeArgs="DS", when="true")
    public Object fn(
            @PermuteDeclr(type="Consumer${i+1}<DS, ${typeArgList(1, i, 'alpha')}>")
            Object action) {
        rd.setAction(action);
        return rd;
    }
}
```

### `Join0First<DS, A>` extends `Join0Second<DS, A>` (generates Join1First–Join6First)

Extends clause is auto-expanded by G3 implicit inference — `Join1First extends Join1Second`, `Join2First extends Join2Second`, etc.

```java
@Permute(varName="i", from=1, to=6, className="Join${i}First", inline=true, keepTemplate=true)
public class Join0First<DS,
        @PermuteTypeParam(varName="k", from="1", to="${i}", name="${alpha(k)}") A>
        extends Join0Second<DS, A> {

    public Join0First(RuleDefinition<DS> rd) { super(rd); }

    // filter() self-returns — explicit @PermuteReturn required (self-return inference TODO-2)
    @PermuteReturn(className="Join${i}First",
                   typeArgs="DS, ${typeArgList(1, i, 'alpha')}",
                   when="true")
    public Object filter(
            @PermuteDeclr(type="Predicate${i+1}<DS, ${typeArgList(1, i, 'alpha')}>")
            Object predicate) {
        rd.addFilter(predicate);
        return this;
    }
}
```

### `RuleBuilder<DS>` (hand-written entry point)

```java
public class RuleBuilder<DS> {
    public <A> Join1First<DS, A> from(
            java.util.function.Function<DS, DataSource<A>> source) {
        RuleDefinition<DS> rd = new RuleDefinition<>();
        rd.addSource(source);
        return new Join1First<>(rd);
    }
}
```

---

## Section 4: Tests

**Test class:** `RuleBuilderTest`  
**Shared setup:**

```java
Ctx ctx = new Ctx(
    DataSource.of(new Person("Alice", 30), new Person("Bob", 17)),
    DataSource.of(new Account("ACC1", 1000.0), new Account("ACC2", 50.0)),
    DataSource.of(new Order("ORD1", 150.0), new Order("ORD2", 25.0)),
    DataSource.of(new Product("PRD1", 99.0), new Product("PRD2", 9.0)),
    DataSource.of(new Transaction("TXN1", 200.0), new Transaction("TXN2", 15.0))
);
RuleBuilder<Ctx> builder = new RuleBuilder<>();
```

**Coverage matrix:**

| Test | Arity | Structural | Behavioural | Special |
|---|---|---|---|---|
| `testArity1Structural` | 1 | sourceCount=1, filterCount=1, hasAction=true | — | |
| `testArity1AdultFilter` | 1 | — | executionCount=1, Alice captured | Filter rejects Bob |
| `testArity1NoFilter` | 1 | — | executionCount=2, both persons | No filter = all match |
| `testArity2CrossProduct` | 2 | sourceCount=2 | executionCount=4, all pairs | No filter |
| `testArity2FilterBothFacts` | 2 | — | executionCount=1, Alice×ACC1 | Both predicates applied |
| `testArity2MultipleFilters` | 2 | filterCount=2 | executionCount=1 | Chained .filter().filter() |
| `testArity3ThreeFacts` | 3 | sourceCount=3 | executions contain Person+Account+Order | |
| `testArity3IntermediateFilter` | 3 | filterCount=2 | only correct combination survives | Filter between joins |
| `testArity4FourFacts` | 4 | sourceCount=4 | executions contain all 4 types | |
| `testArity5FiveFacts` | 5 | sourceCount=5 | executions contain all 5 types | |
| `testArity6LeafNode` | 6 | — | — | Compile-time only: chain compiles without 7th join |

**Sample tests (representative):**

```java
@Test
public void testArity1AdultFilter() {
    var rule = builder.rule("adults")
            .from(ctx -> ctx.persons())
            .filter((ctx, a) -> a.age() >= 18)
            .fn((ctx, a) -> {});

    rule.run(ctx);

    assertThat(rule.executionCount()).isEqualTo(1);
    assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
}

@Test
public void testArity2CrossProduct() {
    var rule = builder.rule("all-pairs")
            .from(ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .fn((ctx, a, b) -> {});

    assertThat(rule.sourceCount()).isEqualTo(2);
    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(4);  // 2 persons × 2 accounts
}

@Test
public void testArity2FilterBothFacts() {
    var rule = builder.rule("adult-high-balance")
            .from(ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
            .fn((ctx, a, b) -> {});

    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(1);
    assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
    assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
}

@Test
public void testArity3IntermediateFilter() {
    var rule = builder.rule("filtered-triple")
            .from(ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
            .join(ctx -> ctx.orders())
            .filter((ctx, a, b, c) -> c.amount() > 100.0)
            .fn((ctx, a, b, c) -> {});

    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(1);
    assertThat(rule.capturedFact(0, 2)).isEqualTo(new Order("ORD1", 150.0));
}

@Test
public void testArity6LeafNodeCompilesWithoutJoin() {
    // Compile-time test: building a 6-fact chain and calling fn() compiles.
    // If Join6Second accidentally had a join() method, the type system would
    // offer it and this test would need to be updated — flagging the regression.
    var rule = builder.rule("six-facts")
            .from(ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .join(ctx -> ctx.orders())
            .join(ctx -> ctx.products())
            .join(ctx -> ctx.transactions())
            .join(ctx -> ctx.persons())    // 6th fact
            .fn((ctx, a, b, c, d, e, f) -> {});

    assertThat(rule.sourceCount()).isEqualTo(6);
}
```

---

## Known Implementation Notes

| Item | Note |
|---|---|
| `join()` unchecked cast | `rd.asNext()` returns `RuleDefinition` as the declared `Join(i+1)First`. Java erasure makes this safe for the fluent chain; documented in `DROOLS-DSL.md`. |
| `filter()` explicit `@PermuteReturn` | Self-return inference (TODO-2) not yet implemented; `when="true"` prevents boundary omission. |
| `fn()` explicit `@PermuteReturn` | `RuleDefinition` not in generated set; `when="true"` prevents omission. |
| ctx position in lambdas | Open question — currently ctx-first `(ctx, a, b)`. See `DROOLS-DSL.md` for trade-offs. Decision deferred. |
| Reflection wrapper performance | Wrapper created once at rule-build time; `run()` invokes the cached `NaryPredicate`/`NaryConsumer`. Acceptable for tests. |

---

## Files Created or Modified

| File | Change |
|---|---|
| `permuplate-mvn-examples/src/main/java/.../drools/Person.java` | New record |
| `permuplate-mvn-examples/src/main/java/.../drools/Account.java` | New record |
| `permuplate-mvn-examples/src/main/java/.../drools/Order.java` | New record |
| `permuplate-mvn-examples/src/main/java/.../drools/Product.java` | New record |
| `permuplate-mvn-examples/src/main/java/.../drools/Transaction.java` | New record |
| `permuplate-mvn-examples/src/main/java/.../drools/DataSource.java` | New class |
| `permuplate-mvn-examples/src/main/java/.../drools/Ctx.java` | New record |
| `permuplate-mvn-examples/src/main/java/.../drools/RuleDefinition.java` | New class |
| `permuplate-mvn-examples/src/main/java/.../drools/RuleBuilder.java` | New class |
| `permuplate-mvn-examples/src/main/permuplate/.../drools/Consumer1.java` | New template |
| `permuplate-mvn-examples/src/main/permuplate/.../drools/Predicate1.java` | New template |
| `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinChain.java` | New parent class containing Join0Second and Join0First templates |
| `permuplate-mvn-examples/src/test/java/.../drools/RuleBuilderTest.java` | New test class |
| `permuplate-mvn-examples/pom.xml` | Add JUnit test dependency |
