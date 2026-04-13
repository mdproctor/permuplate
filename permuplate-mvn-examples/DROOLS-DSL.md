# Permuplate Drools DSL Sandbox

**Location:** `permuplate-mvn-examples/`  
**Package:** `io.quarkiverse.permuplate.example.drools`  
**Purpose:** A fully functional, type-safe approximation of the Drools RuleBuilder DSL, built
using Permuplate's annotation-driven permutation. This sandbox validates Permuplate's ability
to generate the arity-indexed class families that make up the real Drools API.

---

## Overview

The Drools RuleBuilder DSL is a fluent, type-safe rule-construction API where the type
parameter list grows with each `join()` call. The hand-written Drools codebase has
`Join2First`, `Join3First`, ..., `Join5First` — all nearly identical, differing only in
arity. Permuplate eliminates that boilerplate: write one template, generate N classes.

Rather than modifying the Drools codebase directly as the first experiment, this module
provides a safe sandbox where the API can be broken, rethought, and rebuilt without touching
production Drools code. The sandbox has a comprehensive test suite (32+ passing tests) and
has been extended across six implementation phases.

The key principle of the sandbox is **the template is valid, compilable Java**. The IDE can
navigate it, refactor it, and type-check it. The generated DSL classes are indistinguishable
from hand-written ones.

---

## Phase Status

| Phase | What it adds | Status | Key classes |
|---|---|---|---|
| 1 | Linear join chain: `from()`, `join()`, `filter()`, `fn()` | Complete | `JoinBuilder` (templates), `DataSource`, `RuleDefinition`, `RuleResult` |
| 2 | First/Second split, END phantom type, bi-linear joins (15 overloads) | Complete | `Join0Second`, `Join0First`, `BaseRuleBuilder`, `JoinSecond` |
| 3a | `not()`/`exists()` constraint scopes | Complete | `NegationScope`, `ExistenceScope` |
| 3b | OOPath traversal: `path2()..path6()` | Complete | `RuleOOPathBuilder`, `BaseTuple`, `PathContext`, `OOPathStep` |
| 4 | `Variable<T>` cross-fact binding | Complete | `Variable`, `Predicate3`, `Predicate4` |
| 5 | `extensionPoint()`/`extendsRule()` cross-rule inheritance | Complete | `RuleExtendsPoint`, `RuleBuilder.extendsRule()`, `ParametersFirst.extendsRule()` |
| 6 | Named rules, four param styles | Complete | `ParametersFirst`, `ParametersSecond`, `ArgList`, `ArgMap` |

---

## Architecture: Two Families

The `JoinNFirst` / `JoinNSecond` split mirrors the real Drools design:

- **`JoinNSecond<END, DS, A, B, ...>`** — the "gateway" class. Holds `join()` (both
  single-source and bi-linear overloads), `not()`, `exists()`, `path2()..path6()`,
  `extensionPoint()`, and `fn()`. After a `not(...).end()` or `exists(...).end()` scope
  chain-back, the returned type is `JoinNSecond` — and `fn()` is available because it
  lives here.

- **`JoinNFirst<END, DS, A, B, ...> extends JoinNSecond`** — the "full" class. Adds
  `filter()` (two overloads: all-facts and single-fact), `var()`, and variable-based
  `filter(v1, v2, pred)` overloads. `JoinNFirst extends JoinNSecond` means a pre-built
  chain can be passed anywhere `JoinNSecond` is expected — enabling bi-linear joins.

Both families are generated from templates inside `JoinBuilder.java` using `inline = true`.
The `Join0Second` template is declared first so that PermuteMojo processes it before
`Join0First` — boundary omission on `join()` (which references `Join${i+1}First`) must see
the complete generated-class set.

See [ADR-0003](../docs/adr/0003-end-phantom-type-added-in-phase-2.md) for why END was added
in Phase 2 rather than deferred to Phase 3.

### The END Phantom Type

Every generated class carries `END` — the type of the outer builder context that `end()`
returns to.

- **Top-level rules** (created via `from()` or `rule("name").from()`): `END = Void`.
  `end()` returns null and is never called in practice.
- **Nested scopes** (`not()`, `exists()`): `END = outer JoinNSecond type`. `end()` returns
  the outer builder at its original arity.

The arity freezes at scope entry and is restored at scope exit — scope facts do not
accumulate into the outer chain.

---

## Naming Convention: Single-Letter Type Parameters

All generated classes use single-letter type parameter names (`A, B, C, D, E, F`), matching
the real Drools codebase. This is a deliberate choice to keep the sandbox faithful to Drools
and make the eventual migration direct.

Single-letter names disable Permuplate's zero-annotation implicit inference (which requires
the `T+number` pattern). This sandbox therefore uses explicit annotations throughout:
`@PermuteTypeParam(name="${alpha(k)}")`, `@PermuteReturn`, `@PermuteDeclr`. The `alpha(j)`
and `typeArgList(..., "alpha")` built-in JEXL functions handle the naming.

---

## Phase 1: Linear Join Chain

Phase 1 establishes the core DSL pattern: a fluent chain where each `join()` advances the
arity by one.

```java
RuleBuilder<Ctx> builder = new RuleBuilder<>();

RuleResult<Ctx> rule = builder.from(ctx -> ctx.persons())
        .join(ctx -> ctx.accounts())
        .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
        .fn((ctx, a, b) -> System.out.println(a.name() + " / " + b.id()));

rule.run(ctx);
assertThat(rule.executionCount()).isEqualTo(1);
```

The type parameter list grows with each `join()`:
`Join1First<Void, DS, A>` → `Join2First<Void, DS, A, B>` → `Join3First<Void, DS, A, B, C>`.
The compiler enforces that lambda signatures match the accumulated fact types exactly.

### Template Structure

Both `Join0Second` and `Join0First` live inside `JoinBuilder.java` as nested static classes
annotated with `@Permute(inline=true)`. Each generates `Join1..Join6`. The `inline=true` mode
is required because `@PermuteReturn` boundary omission (needed for the leaf-node `join()`)
only works in the Maven plugin's `InlineGenerator`.

### Typed join() via Method-Level @PermuteTypeParam

`join()` uses standalone method-level `@PermuteTypeParam` to rename its type parameter `<B>`
to the next alpha letter per arity, with propagation automatically renaming `DataSource<B>`
— no `@PermuteDeclr` on the parameter. See [ADR-0001](../docs/adr/0001-standalone-method-level-permutetypeparam-with-propagation.md).

```java
// Generated at i=1 (Join1First<END, DS, A>):
public <B> Join2First<END, DS, A, B> join(Function<DS, DataSource<B>> source)

// Generated at i=2 (Join2First<END, DS, A, B>):
public <C> Join3First<END, DS, A, B, C> join(Function<DS, DataSource<C>> source)
```

No pre-typed constants, no `@SuppressWarnings`, no explicit casts at call sites.

### Dual filter() Overloads

Every `JoinNFirst` at arity N has two `filter()` overloads:

- **All-facts** — tests all accumulated facts: `filter(Predicate${N+1}<DS, A, B, ...>)`
- **Single-fact** — tests only the most recently joined fact: `filter(Predicate2<DS, alpha(N)>)`

`Join1First` has only the all-facts overload (which is also the single-fact overload at
arity 1 — same signature). The single-fact overload at arity 2+ is suppressed at i=1 using
a `@PermuteMethod` JEXL ternary:

```java
@PermuteMethod(varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}", name = "filter")
```

At i=1 this produces `from=2, to=1` — an empty range — silently omitting the method.

```java
// All-facts filter — cross-fact comparison
.filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)

// Single-fact filter — just the latest joined fact
.filter((ctx, b) -> b.balance() > 500.0)
```

### The Leaf Node

`Join6Second` has no `join()` method — `@PermuteReturn` boundary omission silently removes
it when `Join7First` is not in the generated set. `filter()` and `fn()` are still present.

### Permuplate Features Exercised

| Feature | Used by |
|---|---|
| **G1** `@PermuteTypeParam` (explicit, alpha) | Class type params `A..F` — `name="${alpha(k)}"` |
| **G2** `@PermuteReturn` (explicit) | All return types — alpha naming disables implicit inference |
| **G2** Boundary omission | `Join6Second.join()` omitted — `Join7First` not in generated set |
| **N4** `typeArgList()` | `filter()`, `fn()`, `join()` — `typeArgList(1, i, 'alpha')` |
| **N4** `alpha()` | Single-fact filter parameter — `Predicate2<DS, ${alpha(i)}>` |

---

## Phase 2: Scope Chain-Back and Bi-Linear Joins

Phase 2 introduces the First/Second hierarchy, the END phantom type, and bi-linear joins.
See [ADR-0003](../docs/adr/0003-end-phantom-type-added-in-phase-2.md).

### First/Second Split

`fn()` was moved from `Join0First` to `Join0Second` so that after `not(...).end()` or
`exists(...).end()` — which return `JoinNSecond` — the fluent chain can call `fn()` directly.

```java
builder.from(ctx -> ctx.persons())
        .not()                                // returns NegationScope<Join1Second<...>, DS>
        .join(ctx -> ctx.accounts())
        .filter(...)
        .end()                                // returns Join1Second<Void, DS, Person>
        .fn((ctx, p) -> { ... });             // fn() is on JoinNSecond — works here
```

### Bi-Linear Joins

`JoinNSecond.join(JoinMSecond<Void, DS, ...>)` joins two independent fact chains:

```java
// Pre-build a sub-network: adult persons with high-balance accounts
var personAccounts = builder.from(ctx -> ctx.persons())
        .join(ctx -> ctx.accounts())
        .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0);

// Two rules sharing the same sub-network (Rete node-sharing)
var rule1 = builder.from(ctx -> ctx.orders())
        .join(personAccounts)     // Join2First accepted via JoinNFirst extends JoinNSecond
        .fn((ctx, a, b, c) -> { }); // a: Order, b: Person, c: Account

var rule2 = builder.from(ctx -> ctx.products())
        .join(personAccounts)
        .fn((ctx, a, b, c) -> { });
```

The right chain executes independently against `ctx`; its matched tuples are
cross-producted with the current chain's tuples. The right chain's internal filters gate
which of its tuples enter the cross-product — they do not re-run against the combined tuple.

15 bi-linear overloads are generated across the complete (i, j) matrix: for i=1..5, j runs
from 1 to (6-i). The right chain always uses `Void` as END — it was built at the top level.
`to` is omitted on `@PermuteMethod`, inferred as `@Permute.to - i = 6 - i`.

Inside `@PermuteMethod`, `@PermuteTypeParam` (G4) expands the sentinel `<C>` into j new
alpha-named method type params `alpha(i+1)..alpha(i+j)`.

### Permuplate Features Exercised

| Feature | Used by |
|---|---|
| **G3** Extends clause auto-expansion (alpha branch) | `Join0First extends Join0Second<END, DS, A>` |
| **G4** Method-level `@PermuteTypeParam` inside `@PermuteMethod` | `joinBilinear()` |
| **@PermuteMethod** inferred `to` | Bi-linear join — `to` omitted, inferred as `6 - i` |
| PermuteMojo multi-template chaining | Two templates in `JoinBuilder.java` |

---

## Phase 3a: Constraint Scopes (not/exists)

`not()` and `exists()` are methods on `Join0Second` that return typed scope builders.
See [ADR-0004](../docs/adr/0004-negationscope-as-separate-class.md).

```java
builder.from(ctx -> ctx.persons())
        .not()
        .join(ctx -> ctx.accounts())
        .filter((Object) (Predicate2<Ctx, Account>) (ctx, b) -> b.balance() > 500.0)
        .end()
        .fn((ctx, p) -> { });
```

The `(Object) (Predicate2<...>)` double-cast is required because `NegationScope.filter()`
takes `Object` — the scope's `join()` and `filter()` are intentionally untyped inside the
scope. This is the known type-safety limitation (see Known Limitations).

### NegationScope and ExistenceScope

Both are independent classes (not subtypes of `JoinNSecond`) — this was the key design
choice. The inheritance approach was rejected because `Join2Second` has one `rd` field, and
inheriting it means `join()` inside the not-scope would add to the outer `rd` instead of the
scope's private `rd`.

`NegationScope<OUTER, DS>` and `ExistenceScope<OUTER, DS>`:
- Hold a private `notRd`/`existsRd` separate from the outer chain's `rd`
- `join(Function<DS, DataSource<?>> source)` adds to the scope's private `rd`
- `filter(Object predicate)` adds to the scope's private `rd`
- `end()` returns `OUTER` — the typed outer builder, fully restoring arity

The `@PermuteReturn` annotation on `not()` and `exists()` uses `when="true"` to prevent
boundary omission (these methods should exist on all arities). The return type string
constructs `NegationScope<Join${i}Second<END, DS, ...>, DS>`.

### Sandbox Simplification: Global Scope Evaluation

The scope sub-network is registered with the outer `rd` upfront at `not()`/`exists()` call
time. During `matchedTuples()`, it evaluates independently of the specific outer fact
combination — it runs against `ctx` globally:

```java
.filter(facts -> negations.stream()
        .allMatch(neg -> neg.matchedTuples(ctx).isEmpty()))
.filter(facts -> existences.stream()
        .allMatch(ex -> !ex.matchedTuples(ctx).isEmpty()))
```

Full Drools tracks the per-outer-tuple connection via beta memory. The sandbox simplification
is sufficient for API design validation.

---

## Phase 3b: OOPath Traversal

`path2()..path6()` methods on `Join0Second` start typed OOPath traversal chains. Fixed arity
was chosen over an `end()`-terminated chain to avoid the scope-chain complexity of nested
builders.

```java
// path3(): Library → Room → Book — produces Tuple3<Library, Room, Book>
RuleOOPathBuilder.Path3<...> path3Builder = builder
        .from(ctx -> ctx.libraries()).path3();

var rule = path3Builder.path(
        (pathCtx, lib) -> lib.rooms(),
        (pathCtx, room) -> true)
    .path(
        (pathCtx, room) -> room.books(),
        (pathCtx, book) -> book.published())
    .fn((ctx, lib, t) -> { });
```

Each `path(fn, pred)` call provides a traversal function and a filter predicate. The result
fact is a `BaseTuple.TupleN` containing the root plus N traversal results.

### BaseTuple Hierarchy

`BaseTuple.Tuple1..Tuple6` form a mutable inheritance chain (`Tuple2 extends Tuple1`, etc.).
Records were rejected — OOPath traversal populates tuples incrementally slot by slot;
records are immutable. Each inner class adds one typed field with `getA()`..`getF()` and
supports `set(int, T)` for incremental population.

`BaseTuple.as()` uses the Java varargs type-capture trick to project a tuple into an
arbitrary target type (record or class) by matching constructor parameter count:

```java
record LibRoom(Library library, Room room) {}
BaseTuple.Tuple2<Library, Room> t = ...;
LibRoom result = t.as();  // T inferred from assignment context
```

### PathContext and Cross-Step Cross-Reference

`PathContext<T>` wraps the in-progress `BaseTuple` as traversal proceeds. Step predicates
receive `pathCtx` so they can cross-reference earlier steps:

```java
.path(
    (pathCtx, room) -> room.books(),
    (pathCtx, book) -> pathCtx.getTuple().getA().name().startsWith("Science"))
```

### OOPath Runtime Pipeline

See [ADR-0002](../docs/adr/0002-oopath-runtime-as-pipeline-on-ruledefinition.md).

`RuleDefinition` holds a separate OOPath pipeline (`ooPathRootIndex` + `ooPathSteps` list).
`matchedTuples()` switches to correlated execution when `ooPathRootIndex >= 0`: for each
outer combination, the pipeline runs from the root fact in that combination. The standard
`TupleSource`/cross-product loop is unchanged — OOPath is purely additive.

`copyTuple()` at the leaf node is essential: the `PathContext` tuple is shared and mutable
across sibling branches. Collecting the reference directly would cause all results to point
to the same (last-mutated) tuple.

### path2()..path6() Suppression

All `pathN()` methods use `when="i < 6"` — suppressed on `Join6Second` since there is no
`Join7First` to receive the traversal result.

---

## Phase 4: Variable Binding

`Variable<T>` provides named cross-fact bindings for predicates that need to reference
non-adjacent facts.

```java
Variable<Person> $person = Variable.of("$person");
Variable<Account> $account = Variable.of("$account");

var rule = builder.from(ctx -> ctx.persons())
        .var($person)              // binds $person to index 0 (Person)
        .join(ctx -> ctx.accounts())
        .var($account)             // binds $account to index 1 (Account)
        .filter($person, $account,
                (ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0)
        .fn((ctx, p, a) -> { });
```

`Variable.of("$name")` matches the DRL naming convention. Anonymous variables (`new Variable<>()`)
are also supported.

### How It Works

`var(v)` calls `rd.bindVariable(v, rd.factArity() - 1)` — binding to the most recently
accumulated fact. `addVariableFilter(v1, v2, predicate)` snapshots the indices at
registration time (not at execution time) and builds a direct lambda bypassing the
`wrapPredicate()` reflection path:

```java
int i1 = v1.index(), i2 = v2.index();
filters.add((ctx, facts) -> predicate.test((DS) ctx, (V1) facts[i1], (V2) facts[i2]));
```

Snapshotting is critical: `Variable.index()` is mutable and `var()` can be called again
on the same variable. Snapshotting prevents post-registration rebinding from silently
corrupting the filter.

Two variable-filter overloads are available: `filter(v1, v2, Predicate3<DS, V1, V2>)` and
`filter(v1, v2, v3, Predicate4<DS, V1, V2, V3>)`. Both overloads check that all variables
are bound before constructing the lambda, throwing `IllegalStateException` with diagnostic
names if not.

---

## Phase 5: Cross-Rule Inheritance

`extensionPoint()` captures the current rule state (all sources, filters, and constraint
scopes) as a typed handle. `extendsRule()` starts a new child rule by copying that state in.

```java
// Build the base pattern
var ep = builder.from(ctx -> ctx.persons())
        .filter((ctx, p) -> p.age() >= 18)
        .extensionPoint();   // returns RuleExtendsPoint.RuleExtendsPoint2<DS, Person>

// Two child rules sharing the base pattern
var rule1 = builder.extendsRule(ep)
        .join(ctx -> ctx.accounts())
        .filter((ctx, p, a) -> a.balance() > 500.0)
        .fn((ctx, p, a) -> { });

var rule2 = builder.extendsRule(ep)
        .join(ctx -> ctx.orders())
        .filter((ctx, p, o) -> o.amount() > 100.0)
        .fn((ctx, p, o) -> { });
```

Extend-of-extend works naturally — `extensionPoint()` is available on `JoinNFirst` because
`JoinNFirst extends JoinNSecond`, which holds `extensionPoint()`.

### RuleExtendsPoint Container

`RuleExtendsPoint2..7` are static inner classes of `RuleExtendsPoint`, one per base arity.
The numbering convention follows Drools vol2: the number = DS + fact count
(`RuleExtendsPoint2` has DS + one fact type A; `RuleExtendsPoint7` has DS + six fact types
A..F).

`extensionPoint()` reflectively instantiates the appropriate `RuleExtendsPoint(N+1)` using
the same pattern as `join()` — deriving the class name from the current arity.

### copyInto() — Authoring-Time Deduplication

`extendsRule()` calls `ep.baseRd().copyInto(child)`:

```java
void copyInto(RuleDefinition<DS> target) {
    target.sources.addAll(this.sources);
    target.accumulatedFacts = this.accumulatedFacts;
    target.filters.addAll(this.filters);
    target.negations.addAll(this.negations);
    target.existences.addAll(this.existences);
    // OOPath pipeline intentionally NOT copied
}
```

Constraint scopes (`not()`/`exists()`) propagate — they are part of the pattern being
inherited. The OOPath pipeline is not copied — it is a post-match traversal applied per rule
independently.

In a real Rete network, node sharing is handled automatically at the network level. `copyInto()`
is authoring-time deduplication only — it gives each child rule its own `RuleDefinition` with
the same logical structure, without needing a special runtime node-sharing concept in the sandbox.

### ParametersFirst Integration

`ParametersFirst` also has six `extendsRule()` overloads, so named rules can extend base patterns:

```java
var ep = builder.rule("base")
        .from(ctx -> ctx.persons())
        .filter((ctx, p) -> p.age() >= 18)
        .extensionPoint();

var child = builder.rule("child")
        .extendsRule(ep)
        .join(ctx -> ctx.accounts())
        .fn((ctx, p, a) -> { });
```

---

## Phase 6: Named Rules and Params

`builder.rule("name")` is the canonical named-rule entry point, returning `ParametersFirst<DS>`.
`builder.from(Function)` (anonymous shorthand) remains available. The former `from(String, Function)`
overload accepting a string name as first argument was removed — it is not in the vol2 API.

```java
builder.rule("findAdults")
        .from(ctx -> ctx.persons())
        .filter((ctx, p) -> p.age() >= 18)
        .fn((ctx, p) -> { });
```

### Four Param Styles

`ParametersFirst` supports four ways to inject parameters at rule-run time.

**Style 1 — Typed params (`.<P>params()`):** P becomes the first fact type (index 0).

```java
record Params3(String name, int minAge, String category) { }
Params3 params = new Params3("Alice", 18, "VIP");

var rule = builder.rule("typed")
        .<Params3> params()
        .join(ctx -> ctx.persons())
        .filter((ctx, p, person) -> person.name().equals(p.name()))
        .fn((ctx, p, person) -> { });

rule.run(ctx, params);
```

**Style 2 — Individual named params (`.param("name", Type.class)` → `ArgList`):**

```java
ArgList args = new ArgList().add("Alice").add(18);

var rule = builder.rule("list")
        .param("name", String.class)
        .param("minAge", int.class)
        .from(ctx -> ctx.persons())
        .filter((ctx, a, p) -> p.name().equals((String) a.get(0)))
        .fn((ctx, a, p) -> { });

rule.run(ctx, args);  // args.get(0) returns "Alice", args.get(1) returns 18
```

**Style 3 — Map params (`.map().param(...)` → `ArgMap`):**

```java
ArgMap args = new ArgMap().put("name", "Alice").put("minAge", 18);

var rule = builder.rule("map")
        .map()
        .param("name", String.class)
        .param("minAge", int.class)
        .from(ctx -> ctx.persons())
        .filter((ctx, a, p) -> p.name().equals((String) a.get("name")))
        .fn((ctx, a, p) -> { });

rule.run(ctx, args);
```

**Style 4 — Typed per-param (`.<T>param("name")`):** Same runtime structure as Style 2
(`ArgList`), but allows per-param type capture at the call site.

```java
var rule = builder.rule("typed-per-param")
        .<String> param("name")
        .<Integer> param("minAge")
        .from(ctx -> ctx.persons())
        .filter((ctx, a, p) -> p.name().equals((String) a.get(0)))
        .fn((ctx, a, p) -> { });
```

### ParametersSecond

Styles 2, 3, and 4 chain through `ParametersSecond<DS, B>` where `B` is `ArgList` or `ArgMap`.
`ParametersSecond` supports additional `.param()` calls to declare more parameters, then
`.from(source)` to start the join chain.

### RuleResult

`fn()` returns `RuleResult<DS>` rather than raw `RuleDefinition<DS>`. `RuleResult` wraps
the `RuleDefinition` and exposes the same query API (`run()`, `executionCount()`,
`capturedFact()`, etc.) plus a no-op `end()`:

```java
builder.rule("r1")
        .from(ctx -> ctx.persons())
        .fn((ctx, p) -> { })
        .end();   // no-op; allows .fn(...).end() to compile in vol2 style
```

### run(ctx, params)

For rules with params, call `rule.run(ctx, params)` instead of `rule.run(ctx)`. The params
object is injected as a single-element initial combination (fact index 0); all data sources
cross-product on top of it. `addParamsFact()` increments `accumulatedFacts` so that
`wrapPredicate()`'s filter-trim logic correctly accounts for the params slot.

---

## Runtime Execution Model

`RuleDefinition` is the internal execution engine. It holds:

- `sources: List<TupleSource<DS>>` — each source produces `List<Object[]>` tuples
- `accumulatedFacts: int` — running count of fact columns; tracked separately from `sources.size()` because a bi-linear source is one list entry but contributes N columns
- `filters: List<NaryPredicate>` — registration-time snapshot of `accumulatedFacts`
- `negations`, `existences` — sub-network `RuleDefinition` instances
- `ooPathRootIndex`, `ooPathSteps` — OOPath pipeline; inactive when `ooPathRootIndex < 0`

### matchedTuples() Cross-Product Loop

```
initial = [[]]
for each source:
    next = []
    for each tuple from source(ctx):
        for each combo in current combinations:
            extend combo with tuple facts
    combinations = next

filter combinations through:
    all NaryPredicates pass
    all negations produce empty matchedTuples
    all existences produce non-empty matchedTuples
applyOOPath if ooPathRootIndex >= 0
```

### wrapPredicate() and the Filter-Trim Logic

`wrapPredicate` wraps a typed predicate (e.g., `Predicate3<DS, A, B>`) as an `NaryPredicate`
over the full `Object[]` facts array. It uses reflection once at build time to find the
`test` method and capture the parameter count.

Two trim cases:
1. **Single-fact filter** (`factArity == 1 && registeredFactCount > 1`): the filter was
   registered after a join and has arity 1. Uses `registeredFactCount - 1` as the 0-based
   index of the latest fact — passing only that one fact.
2. **Multi-fact filter registered early**: truncates to the facts in scope at registration
   time (`facts.length > factArity`).

### addParamsFact() Subtlety

When `ParametersFirst` creates a rule with params, it calls `rd.addParamsFact()` before any
source is added. This increments `accumulatedFacts` to 1 without adding a `TupleSource`. The
params object itself is injected by `run(ctx, params)` as the initial singleton combination,
not as a source. As a result, `rd.sourceCount()` does not include the params slot, but
`rd.factArity()` does — and `wrapPredicate()`'s trim logic sees the correct count.

---

## Known Limitations

| Limitation | Root cause | Workaround / Note |
|---|---|---|
| `NegationScope.filter()` requires double-cast | `filter(Object predicate)` — intentionally untyped inside the scope (see ADR-0004) | Cast: `filter((Object) (Predicate2<Ctx, T>) (ctx, t) -> ...)` |
| not()/exists() evaluate globally, not per outer-tuple | Sandbox simplification — full Drools uses beta memory for per-tuple scoping | Sufficient for API design validation; migration requires beta memory integration |
| OOPath + extends combination not tested | No test exercises a rule with both `path2()` and `extendsRule()` | Structurally supported — `copyInto()` deliberately skips the OOPath pipeline |
| Maximum arity is 6 | Practical limit for the sandbox | Change `to=6` to `to=10` on all templates; tests scale automatically |
| `wrapPredicate()` uses reflection at build time | Typed lambdas are erased at runtime; method lookup required | One-time cost at rule construction; negligible in practice |
| `Variable<T>` does not support `filter(v, pred)` — only `filter(v1, v2, pred)` | Single-variable filter is equivalent to a plain `filter(Predicate2)` | Use the standard `filter()` overload for single-fact predicates |
| ctx position locked at index 0 of every lambda | Design decision not yet locked; `(ctx, a, b)` vs `(a, b, ctx)` open | Review before Drools migration |

---

## Test Classes

All tests are in `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/`.

| Class | Coverage |
|---|---|
| `RuleBuilderTest` | Arities 1–6 (structural + behavioural), typed `join()`, dual `filter()`, bi-linear joins (15 overloads exercised), `not()`/`exists()` scopes (chained), OOPath `path2()`/`path3()` (with/without outer join, cross-step reference, filter), `Variable<T>` (2-variable, 3-variable, named, index-capture, unbound error), `type()` narrowing, `run()` reset semantics |
| `ExtensionPointTest` | `extensionPoint()`/`extendsRule()` — single filter, add join, two base joins, fan-out (two children), extend-of-extend, inherits `not()` scope, inherits `exists()` scope |
| `NamedRuleTest` | `builder.rule("name").from()`, `.fn().end()` no-op, all four param styles (typed, list, map, typed-per-param), named rule + extension point |
| `TupleAsTest` | `BaseTuple.as()` — projects `Tuple2`/`Tuple3` into records and plain classes |

---

## Real Drools Migration

This sandbox exists to validate that Permuplate can generate the arity-indexed class families
that make up the real Drools RuleBuilder API. The real Drools code is at:

```
/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/java/org/drools/core/RuleBuilder.java
```

The hand-written Drools codebase has `Join2First..Join5First`, `Join2Second..Join5Second`,
`Consumer1..4`, `Predicate1..10`, `Function1..5` — all candidates for Permuplate generation.

**Planned migration order:**

1. Consumer family (pure G1 — simplest test)
2. Predicate family (G1)
3. Join chain (G1 + G2 + G3 — the full DSL)

**Open question — ctx position:** Currently `ctx` is the first parameter of every lambda:
`(ctx, a, b, c) -> ...`. The alternative `(a, b, c, ctx) -> ...` gives fact type parameters
clean index-0 mapping. This decision must be locked in before migration begins.

**Migration planning decisions:**

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Epic structure | Capability area (not phase/timing) as primary axis | Area-based epics remain meaningful after work ships; phase names go stale | Phase-based epics — "Phase 1: Foundation" meaningless once done |
| GitHub issue location | `apache/incubator-kie-drools` | Work belongs in the repo it affects; permuplate#5/#7 cross-reference | `mdproctor/drools` fork — issues disabled for forks of Apache projects |
| Permuplate wiring | APT processor in `droolsvol2/pom.xml` annotationProcessorPaths | Compile-time annotation processing; APT mode generates top-level files (correct for JoinNFirst family) | Maven plugin (inline mode) — generates nested classes, wrong for this use case |
| Order of work | droolsvol2 refactor first, then `@Permute` templates | Cannot add templates until module compiles cleanly | Adding templates now — impossible without compilation |

GitHub epics: main `apache/incubator-kie-drools#6639` → DSL sub-epic #6638 (child issues #6640–#6645) + Rule Base sub-epic #6646 (#6647). Permuplate tracking: mdproctor/permuplate#5, #7.

**Three vol2 bugs to fix before migration** (logged in `docs/ideas/IDEAS.md`).

**Comparison with real Drools:**

| Feature | Real Drools | This Sandbox |
|---|---|---|
| Typed `join(Function<DS, DataSource<C>>)` | Handwritten per arity | Generated via standalone method-level `@PermuteTypeParam` (ADR-0001) |
| Single-fact `filter(Predicate2<DS, C>)` | Handwritten per arity | Generated; suppressed at i=1 via JEXL ternary |
| All-facts `filter(PredicateN+1<DS,...>)` | Handwritten per arity | Generated |
| First extends Second | Handwritten | G3 auto-expands extends clause |
| `join(JoinNSecond)` bi-linear (15 overloads) | ~3 overloads, 1 commented out | Complete matrix (15) via `@PermuteMethod` |
| `not()`/`exists()` scopes | Full beta memory | Sandbox: global scope evaluation |
| Boundary omission (leaf node) | Manually written | Automatic via `@PermuteReturn` |
| Extend to arity 10 | Requires editing N classes | Change `to=6` on templates |

---

## Design Records

| ADR | Decision |
|---|---|
| [ADR-0001](../docs/adr/0001-standalone-method-level-permutetypeparam-with-propagation.md) | Standalone method-level `@PermuteTypeParam` with rename propagation |
| [ADR-0002](../docs/adr/0002-oopath-runtime-as-pipeline-on-ruledefinition.md) | OOPath runtime as pipeline on `RuleDefinition` |
| [ADR-0003](../docs/adr/0003-end-phantom-type-added-in-phase-2.md) | END phantom type added alongside First/Second split in Phase 2 |
| [ADR-0004](../docs/adr/0004-negationscope-as-separate-class.md) | `NegationScope` as separate builder class, not `JoinNSecond` subtype |
| [ADR-0005](../docs/adr/0005-sandbox-scope-boundary.md) | Sandbox scope boundary: DSL API design only; Rete engine out of scope |

**Blog series:** `docs/blog/` — entries 001–008 cover the Permuplate implementation journey
including typed joins, First/Second split, not()/exists(), and OOPath traversal.
