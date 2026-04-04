# Spec: Phase 2 — First/Second Split, END Phantom Type, and Bi-Linear Joins

**Date:** 2026-04-05
**Status:** Approved
**Motivation:** Model the real Drools bi-linear Rete network pattern in the sandbox DSL: pre-build a fact chain as a reusable sub-network, then join it into another chain to promote node sharing. Also adds the `END` phantom type needed for future nested scopes (`not()`, `exists()`).

---

## Background: Why This Matters (Rete Networks)

In a Drools Rete network, a **bi-linear** join has two distinct sub-networks feeding into a single beta node:

- **Left input** — a linear chain producing tuples, e.g. `[Person, Account]`
- **Right input** — an independent sub-network producing its own tuples, e.g. `[Order, Product]`

The beta node joins them: for each left tuple, it finds matching right tuples. The result is a combination `[Person, Account, Order, Product]`. The key property: **the right sub-network is executed independently** — its internal filters apply only within the sub-network, not across the combined tuple. This is fundamentally different from a linear chain where all sources participate in a single unified cross-product.

**Why bi-linear matters for Drools:** Two rules that share a common sub-network (e.g., both require "adult person with high-balance account") can reuse the same Rete beta memory for that sub-network rather than computing it independently. This is bi-linear node sharing — a core Rete optimisation.

**Sandbox representation:** We don't implement Rete beta memory. Instead, `addBilinearSource` records a `RuleDefinition` as an independent sub-execution. At `run()` time, the sub-network's `RuleDefinition` is executed against the same `ctx` to produce its matched tuple-list. That list is then cross-producted with the current chain's tuples. The right chain's filters gate which of its own tuples enter the cross-product — they don't filter the combined tuple. Facts are flattened for method calls (predicates, consumers see a flat `Object[]`), matching the Rete convention of traversing the tuple tree when invoking lambdas.

---

## The END Phantom Type

### What it is

`END` is a phantom type parameter carried by every generated `JoinNFirst` and `JoinNSecond` class. It holds the type of the outer builder context — the object that `end()` returns to.

```java
public class BaseRuleBuilder<END> {
    private final END end;
    public BaseRuleBuilder(END end) { this.end = end; }
    public END end() { return end; }
}
```

### Top-level usage — END = Void

`RuleBuilder.from()` creates the initial `Join1First<Void, DS, A>`. `Void` means no outer context — `end()` returns null and is never called.

```java
public <T> JoinBuilder.Join1First<Void, DS, T> from(String name,
        Function<DS, DataSource<T>> source) { ... }
```

### Nested scopes — END = outer builder type

When `not()` (Phase 3) is called on `Join2First<END, DS, B, C>`, it captures `this` as the END for the inner Not2 scope:

```java
public Not2<Join2Second<END,DS,B,C>, DS, B, C> not() {
    return new Not2<>(this);   // this becomes END for the inner scope
}
```

Inside the `not()` scope, arity keeps increasing as you join more facts. When `end()` is called, it returns `this.end` — the outer `Join2Second<END,DS,B,C>` at its original arity. The not-scope facts are **not added** to the outer chain's arity; they only constrain which outer tuples are valid.

**Arity trace (from real Drools test):**
```
.params()              → From1First<Void,DS,Params3>              arity: 1
.join(persons)         → Join2First<Void,DS,Params3,Person>        arity: 2
.not()                 → Not2<Join2Second<Void,DS,Params3,Person>, DS, Params3, Person>
    .join(misc)        → Join3First<Join2Second<Void,...>, ...>     arity: 3 (inside scope)
    .join(libs)        → Join4First<Join2Second<Void,...>, ...>     arity: 4 (inside scope)
.end()                 → Join2Second<Void,DS,Params3,Person>        arity: 2 (reset!)
.fn((a,b,c) -> ...)    → Consumer3<Context<DS>,Params3,Person>     arity: 2 confirmed
```

### Why add END in Phase 2 (not Phase 3)

Adding END later is a **breaking API change** — every generated class signature changes and every call site needs updating. Adding it now costs moderate upfront complexity (one extra type parameter, `Void` everywhere in tests) but avoids a forced retroactive refactor. Phase 2's bi-linear joins don't need END semantically, but the template accepts it cleanly as a regular non-permuted type parameter.

---

## Files Changed

| File | Change |
|---|---|
| `permuplate-maven-plugin/.../PermuteMojo.java` | Fix: chain inline templates from same parent file (output of first → input of second) |
| `permuplate-mvn-examples/src/main/java/.../drools/BaseRuleBuilder.java` | New: hand-written `BaseRuleBuilder<END>` with `end()` |
| `permuplate-mvn-examples/src/main/java/.../drools/JoinSecond.java` | New: `JoinSecond<DS>` interface with `getRuleDefinition()` |
| `permuplate-mvn-examples/src/main/java/.../drools/RuleDefinition.java` | Refactor: `TupleSource<DS>` abstraction; `addBilinearSource()`; `matchedTuples()` |
| `permuplate-mvn-examples/src/main/java/.../drools/RuleBuilder.java` | Update: `from()` returns `Join1First<Void, DS, T>` |
| `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java` | Rewrite: add `Join0Second` template; modify `Join0First` to extend Second; add END to all class signatures |
| `permuplate-mvn-examples/src/test/java/.../drools/RuleBuilderTest.java` | Update: all tests gain `Void` in type signatures; add bi-linear join tests |
| `permuplate-mvn-examples/DROOLS-DSL.md` | Major update: bi-linear network background, END documentation, Phase 2 architecture |

---

## PermuteMojo Fix: Chaining Multiple Inline Templates

**Problem:** When a parent file has two `@Permute(inline=true)` templates (e.g., `Join0Second` and `Join0First`), `PermuteMojo` processes each independently using the original source CU and writes to the same output file. The second write overwrites the first — only one template's generated classes survive.

**Fix:** Group inline templates by source file. Process them in declaration order, feeding the output CU of each call as the input CU to the next. Write the final combined output once.

**Why declaration order matters:** `Join0Second` must be processed first. Its boundary omission (`@PermuteReturn(className="Join${i+1}First")`) requires knowing the JoinFirst generated names — which come from `Join0First`'s `@Permute` annotation. When `Join0Second` is processed first against the original CU, both templates are still present and `scanAllGeneratedClassNames()` finds both families. If `Join0First` were processed first, the `JoinFirst` names would already be gone from the CU (replaced by generated classes without `@Permute`) and `Join0Second`'s boundary omission would incorrectly remove all `join()` methods.

---

## Class Hierarchy

### New: `BaseRuleBuilder<END>` (hand-written)

```java
public class BaseRuleBuilder<END> {
    private final END end;
    public BaseRuleBuilder(END end) { this.end = end; }
    public END end() { return end; }
}
```

Extracted from `RuleBuilder` inner class to a top-level file so generated classes can extend it directly without being inner classes of `RuleBuilder`.

### New: `JoinSecond<DS>` interface (hand-written)

```java
public interface JoinSecond<DS> {
    RuleDefinition<DS> getRuleDefinition();
}
```

All generated `JoinNSecond` classes implement this. Used in the `joinBilinear()` body to extract the right chain's `RuleDefinition` without reflection.

### `Join0Second` template → generates `Join1Second..Join6Second`

```java
@Permute(varName = "i", from = 1, to = 6, className = "Join${i}Second",
         inline = true, keepTemplate = false)
public static class Join0Second<END, DS,
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
        extends BaseRuleBuilder<END>
        implements JoinSecond<DS> {

    protected final RuleDefinition<DS> rd;

    public Join0Second(END end, RuleDefinition<DS> rd) {
        super(end);
        this.rd = rd;
    }

    @Override
    public RuleDefinition<DS> getRuleDefinition() { return rd; }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) { return (T) o; }

    // Single-source typed join (moved from Join0First)
    @PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
    @PermuteReturn(className = "Join${i+1}First",
                   typeArgs = "'END, DS, ' + typeArgList(1, i+1, 'alpha')")
    public <B> Object join(java.util.function.Function<DS, DataSource<B>> source) {
        rd.addSource(source);
        // reflective instantiation of Join(i+1)First(end(), rd)
        ...
    }

    // Bi-linear join overloads — one per right-chain arity j.
    // to is omitted: inferred as @Permute.to - i = 6 - i (same-module inference).
    // For i=1: j=1..5. For i=5: j=1. For i=6: empty range → no overloads.
    @PermuteMethod(varName = "j", from = "1", name = "join")
    @PermuteReturn(className = "Join${i+j}First",
                   typeArgs = "'END, DS, ' + typeArgList(1, i+j, 'alpha')")
    public <@PermuteTypeParam(varName = "k", from = "${i+1}", to = "${i+j}",
                               name = "${alpha(k)}") C> Object joinBilinear(
            @PermuteDeclr(type = "Join${j}Second<Void, DS, ${typeArgList(i+1, i+j, 'alpha')}>")
            Object secondChain) {
        JoinSecond<DS> second = (JoinSecond<DS>) secondChain;
        rd.addBilinearSource(second.getRuleDefinition());
        // reflective instantiation of Join(i+j)First(end(), rd)
        ...
    }
}
```

**Bi-linear join overloads generated for `Join1Second<END,DS,A>` (i=1):**
```
j=1: public <B>       Join2First<END,DS,A,B>       join(Join1Second<Void,DS,B>       other)
j=2: public <B,C>     Join3First<END,DS,A,B,C>     join(Join2Second<Void,DS,B,C>     other)
j=3: public <B,C,D>   Join4First<END,DS,A,B,C,D>   join(Join3Second<Void,DS,B,C,D>   other)
j=4: public <B,C,D,E> Join5First<END,DS,A,B,C,D,E> join(Join4Second<Void,DS,B,C,D,E> other)
j=5: public <B,C,D,E,F> Join6First<END,...>        join(Join5Second<Void,...>         other)
```

Total bi-linear overloads across all arities: 15 (vs Drools' ~3 hand-written, 1 commented out).

### `Join0First` modified → generates `Join1First..Join6First`

```java
@Permute(varName = "i", from = 1, to = 6, className = "Join${i}First",
         inline = true, keepTemplate = false)
public static class Join0First<END, DS,
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
        extends Join0Second<END, DS, A> {   // G3 auto-expands

    public Join0First(END end, RuleDefinition<DS> rd) {
        super(end, rd);
    }

    // filter() — single-fact and all-facts overloads (unchanged, minus rd field)
    // fn() — terminal, returns RuleDefinition<DS>
}
```

G3 extends expansion detects `Join0Second<END, DS, A>` as a sibling (same "Join" prefix, same embedded number 0). Alpha branch fires: `[END, DS, A]` is a prefix of post-G1 type params `[END, DS, A, B]` → expands to `Join1Second<END, DS, A>`, `Join2Second<END, DS, A, B>`, etc. ✓

**Note on G3 and the END prefix:** The current G3 alpha branch checks whether the extends type args are a prefix of `postG1TypeParams`. With END added, `postG1TypeParams = [END, DS, A, B, ...]` and the template's extends clause has `[END, DS, A]`. This is still a valid prefix. The G3 expansion uses the full postG1TypeParams list — so `Join2Second<END, DS, A, B>` is correctly generated. No changes to G3 logic needed.

---

## RuleDefinition Changes

### TupleSource abstraction

Replace the flat `List<Function<DS, DataSource<?>>>` with a unified `List<TupleSource<DS>>`:

```java
@FunctionalInterface
interface TupleSource<DS> {
    List<Object[]> tuples(DS ctx);
}
```

A new `int accumulatedFacts` field tracks the current fact column count. Each `addSource` increments by 1; each `addBilinearSource` increments by the sub-network's `factArity()`. This replaces the old `sources.size()` in `addFilter` — `sources.size()` no longer equals the fact column count once bi-linear sources (which contribute N facts as one entry) are in use.

```java
private int accumulatedFacts = 0;

// factArity(): total fact columns this RuleDefinition contributes per matched tuple.
// Linear source = 1 fact each; bi-linear source = that sub-network's factArity().
public int factArity() { return accumulatedFacts; }

public void addSource(Object sourceSupplier) {
    @SuppressWarnings("unchecked")
    Function<DS, DataSource<?>> fn = (Function<DS, DataSource<?>>) sourceSupplier;
    sources.add(ctx -> fn.apply(ctx).asList().stream()
                         .map(f -> new Object[]{f})
                         .collect(java.util.stream.Collectors.toList()));
    accumulatedFacts += 1;
}

public void addBilinearSource(RuleDefinition<DS> subNetwork) {
    sources.add(ctx -> subNetwork.matchedTuples(ctx));
    accumulatedFacts += subNetwork.factArity();
}

public void addFilter(Object typedPredicate) {
    // Capture the accumulated fact count at registration — not sources.size().
    // A bi-linear source is 1 entry but contributes N fact columns.
    int registeredFactCount = accumulatedFacts;
    filters.add(wrapPredicate(typedPredicate, registeredFactCount));
}
```

### matchedTuples() — new package-private method

```java
List<Object[]> matchedTuples(DS ctx) {
    List<Object[]> combinations = new ArrayList<>();
    combinations.add(new Object[0]);
    for (TupleSource<DS> source : sources) {
        List<Object[]> next = new ArrayList<>();
        for (Object[] tuple : source.tuples(ctx)) {
            for (Object[] combo : combinations) {
                Object[] extended = Arrays.copyOf(combo, combo.length + tuple.length);
                System.arraycopy(tuple, 0, extended, combo.length, tuple.length);
                next.add(extended);
            }
        }
        combinations = next;
    }
    // Apply filters
    return combinations.stream()
            .filter(facts -> filters.stream().allMatch(f -> f.test(ctx, facts)))
            .collect(java.util.stream.Collectors.toList());
}
```

`run()` becomes:
```java
public RuleDefinition<DS> run(DS ctx) {
    executions.clear();
    for (Object[] facts : matchedTuples(ctx)) {
        if (action != null) action.accept(ctx, facts);
        executions.add(Arrays.asList(facts));
    }
    return this;
}
```

### Reflective instantiation update

`join()` and `joinBilinear()` both need to instantiate the next `JoinFirst` class. The constructor now takes `(END end, RuleDefinition<DS> rd)` instead of just `(RuleDefinition<DS> rd)`. The reflective call:

```java
Class.forName(nextName)
     .getConstructor(Object.class, RuleDefinition.class)
     .newInstance(end(), rd)
```

`Object.class` matches the `END` parameter (erased at runtime). For bi-linear joins, `nextName` is derived from both `myN` (current arity from class name) and `otherN` (right chain's arity from its class name).

---

## RuleBuilder.from() Update

```java
public <T> JoinBuilder.Join1First<Void, DS, T> from(String name,
        Function<DS, DataSource<T>> source) {
    RuleDefinition<DS> rd = new RuleDefinition<>(name);
    rd.addSource(source);
    return new JoinBuilder.Join1First<>(null, rd);   // null = Void END
}
```

---

## Testing

### Existing 20 tests — updated

All tests gain `Void` in type signatures when explicit (most use `var` so no change). Constructor calls gain `null` as first arg. All existing behavioural assertions unchanged — this is a structural refactor, not a behaviour change.

### New tests — bi-linear joins

```java
@Test
public void testBilinearJoin2plus1gives3Facts() {
    // Pre-build a 2-fact chain: adult persons with high-balance accounts
    var personAccounts = builder.from("pa", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0);

    // Bi-linear join: orders × pre-built personAccounts → 3-fact rule
    var rule = builder.from("orders", ctx -> ctx.orders())
            .join(personAccounts)   // Join2Second accepts Join2First via extends
            .fn((ctx, a, b, c) -> {});

    rule.run(ctx);
    // Only ORD1(150) × Alice(30)+ACC1(1000) qualifies: 1 match
    assertThat(rule.executionCount()).isEqualTo(1);
    assertThat(rule.capturedFact(0, 0)).isInstanceOf(Order.class);
    assertThat(rule.capturedFact(0, 1)).isInstanceOf(Person.class);
    assertThat(rule.capturedFact(0, 2)).isInstanceOf(Account.class);
}

@Test
public void testBilinearSubnetworkFiltersApplyIndependently() {
    // The right chain's filter (balance > 500) applies within the sub-network only.
    // It should NOT be re-evaluated against the combined tuple.
    var highBalanceAccounts = builder.from("acc", ctx -> ctx.accounts())
            .filter((ctx, a) -> a.balance() > 500.0);  // only ACC1 passes

    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(highBalanceAccounts)
            .fn((ctx, a, b) -> {});

    rule.run(ctx);
    // 2 persons × 1 qualifying account = 2 combinations
    assertThat(rule.executionCount()).isEqualTo(2);
    assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    assertThat(rule.capturedFact(1, 1)).isEqualTo(new Account("ACC1", 1000.0));
}

@Test
public void testJoin2FirstSatisfiesJoin2SecondAtCompileTime() {
    // Structural test: Join2First<Void,DS,Person,Account> IS-A Join2Second
    // (assignable at compile time — if it doesn't compile, the extends relation is broken)
    JoinBuilder.Join2Second<Void, Ctx, Person, Account> asSecond =
            builder.from("p", ctx -> ctx.persons())
                   .join(ctx -> ctx.accounts());
    assertThat(asSecond).isNotNull();
}

@Test
public void testBilinearNodeSharing() {
    // Two rules sharing a common sub-network — core Rete node-sharing pattern.
    // Both rules pre-build the same personAccounts sub-network and join it differently.
    var personAccounts = builder.from("pa", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, a, b) -> a.age() >= 18);

    var rule1 = builder.from("orders", ctx -> ctx.orders())
            .join(personAccounts)
            .fn((ctx, a, b, c) -> {});

    var rule2 = builder.from("products", ctx -> ctx.products())
            .join(personAccounts)
            .fn((ctx, a, b, c) -> {});

    rule1.run(ctx);
    rule2.run(ctx);

    // Both rules use the same pre-filtered personAccounts sub-network
    assertThat(rule1.capturedFact(0, 1)).isInstanceOf(Person.class);
    assertThat(rule2.capturedFact(0, 1)).isInstanceOf(Person.class);
}
```

---

## Comparison with Real Drools

| Feature | Real Drools | Phase 2 |
|---|---|---|
| `END` phantom type | ✅ full support | ✅ added |
| `end()` return to outer scope | ✅ | ✅ (infrastructure ready; scopes arrive Phase 3) |
| First extends Second | ✅ `Join2First extends Join2Second` | ✅ G3 auto-expands |
| Bi-linear `join(JoinNSecond)` | ✅ partial (3 overloads, 1 commented out) | ✅ complete matrix (15 overloads) |
| Pre-built chain parameter type | Mixed First/Second inconsistently | ✅ consistently `JoinNSecond` |
| Boundary omission | ❌ manual leaf | ✅ automatic |

---

## What Phase 2 Does NOT Include

- `not()` / `exists()` scope implementations — Phase 3 (infrastructure is in place via END)
- `extensionPoint()` / `extendsRule()` cross-rule extension
- `Variable<T>` cross-fact filtering
- `ifn()` optional action
- `fn()` returning `BaseRuleBuilder<END>` (currently returns `RuleDefinition<DS>` for simplicity)
