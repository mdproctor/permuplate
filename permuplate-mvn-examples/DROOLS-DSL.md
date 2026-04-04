# Drools RuleBuilder DSL — Design Document

**Location:** `permuplate-mvn-examples/src/main/permuplate/.../drools/` and `src/main/java/.../drools/`  
**Package:** `io.quarkiverse.permuplate.example.drools`  
**Purpose:** A self-contained, testable approximation of the Drools RuleBuilder DSL, built using Permuplate. This is the **primary playground** for evolving the Drools DSL using Permuplate's annotation-driven permutation — validate ideas here before applying them in the Drools codebase.

---

## Why This Exists

The Drools RuleBuilder DSL (`droolsvol2/src/main/java/org/drools/core/RuleBuilder.java`) is a fluent, type-safe rule-construction API that accumulates fact types as you chain calls. The hand-written version has classes like `Join2First`, `Join3First`, ... `Join5First` — all nearly identical, differing only in arity. Permuplate is designed to eliminate exactly this boilerplate.

Rather than modifying the Drools codebase directly as the first experiment, this module provides:

1. **A safe sandbox** — break, rethink, and rebuild without touching production Drools code
2. **A regression suite** — tests verify that the DSL constructs rules correctly and that permuted arities behave identically
3. **A feature testbed** — new Permuplate features (G1–G4) can be validated here before being relied upon in Drools
4. **Living documentation** — the templates themselves show exactly how Permuplate generates the DSL

---

## The Core DSL Pattern

The DSL builds a rule by fluent chaining. Each step accumulates one more fact type into the rule's type signature:

```java
RuleDefinition<Ctx> rule = builder.from("age-comparison", ctx -> ctx.persons())
        .join(ctx -> ctx.persons())                          // 2 facts: Person, Person
        .filter((ctx, p1, p2) -> p1.age() > p2.age())       // still 2 facts
        .filter((ctx, p1, p2) -> p2.age() > 18)             // still 2 facts
        .fn((ctx, p1, p2) -> System.out.println("match"));  // terminal: produces RuleDefinition
```

The type parameter list grows with each `join()`: `Join1First<DS,A>` → `Join2First<DS,A,B>` → `Join3First<DS,A,B,C>`. The compiler enforces that your lambda signatures match the accumulated fact types exactly.

---

## Phase 1 Architecture: Single JoinFirst Family

Phase 1 uses a **single class family** — `Join1First` through `Join6First` — generated from one template (`Join0First` inside `JoinBuilder.java`). Each `JoinNFirst` holds all three operations:

- `join(source)` — advances to `Join(N+1)First` (omitted from `Join6First` via boundary omission — the leaf node)
- `filter(predicate)` — arity-preserving; returns `this` (same `JoinNFirst`)
- `fn(consumer)` — terminal; returns `RuleDefinition<DS>`

This is a pragmatic choice for Phase 1 given current Permuplate constraints (see "Phase 2 Design Intent" below).

### The Leaf Node

`Join6First` has no `join()` method — `@PermuteReturn` boundary omission silently removes it when `Join7First` is not in the generated set. `filter()` and `fn()` are still present: you can refine and terminate at arity 6.

### Container Class Pattern (inline=true)

The `JoinFirst` family lives inside a container class `JoinBuilder.java` and is generated with `inline=true`. This is required because `@PermuteReturn` boundary omission only works in InlineGenerator (inline mode) — the APT top-level generator does not support it. As a result, the generated classes are nested inside `JoinBuilder`:

- `JoinBuilder.Join1First<DS, A>`
- `JoinBuilder.Join2First<DS, A, B>`
- ...
- `JoinBuilder.Join6First<DS, A, B, C, D, E, F>`

When using `var` in tests, the qualified name is transparent:

```java
var rule = builder.from("name", ctx -> ctx.persons())
        .join(ctx -> ctx.accounts())
        .fn((ctx, a, b) -> {});
```

---

## Typed join() — Method-Level @PermuteTypeParam with Propagation

`join()` uses `@PermuteTypeParam` on its own type parameter `<B>` to rename it to the next alpha letter per arity. The rename automatically propagates into the parameter type `DataSource<B>` — no `@PermuteDeclr` needed.

```java
// Template:
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
@PermuteReturn(className = "Join${i+1}First", typeArgs = "'DS, ' + typeArgList(1, i+1, 'alpha')")
public <B> Object join(java.util.function.Function<DS, DataSource<B>> source) { ... }

// Generated at i=1 (Join1First<DS, A>):
public <B> Join2First<DS, A, B> join(Function<DS, DataSource<B>> source)

// Generated at i=2 (Join2First<DS, A, B>):
public <C> Join3First<DS, A, B, C> join(Function<DS, DataSource<C>> source)
```

This matches the real Drools pattern exactly. No pre-typed constants, no `@SuppressWarnings`, no explicit casts at call sites.

### Before and After

```java
// Before (Phase 1) — raw types, constants, casts
private static final Function<Ctx, DataSource<?>> ACCOUNTS = c -> c.accounts();

@SuppressWarnings({"unchecked", "rawtypes"})
var rule = builder.from("adults", ctx -> ctx.persons())
        .join(ACCOUNTS)                      // pre-typed constant required
        .filter((ctx, a, b) -> ((Person) a).age() >= 18 && ((Account) b).balance() > 500.0)
        .fn((ctx, a, b) -> {});

// After — fully typed, inline lambdas, no casts
var rule = builder.from("adults", ctx -> ctx.persons())
        .join(ctx -> ctx.accounts())          // B inferred as Account
        .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)  // a: Person, b: Account
        .fn((ctx, a, b) -> {});
```

## Dual filter() Overloads

Every `JoinNFirst` at arity N≥2 has two `filter()` overloads, matching real Drools ergonomics:

- **Single-fact** — tests only the most recently joined fact:
  `filter(Predicate2<DS, alpha(N)> predicate)` — ergonomic for post-join checks on one fact
- **All-facts** — tests all accumulated facts:
  `filter(Predicate${N+1}<DS, A, B, …> predicate)` — cross-fact comparisons

`Join1First` has only the all-facts overload (which IS the single-fact overload at arity 1 — same signature).

### The Arity-1 Suppression Mechanism

At arity 1, both overloads would have `filter(Predicate2<DS, A>)` — identical. Java rejects duplicates. The template uses a `@PermuteMethod` JEXL ternary in `from` to suppress the single-fact sentinel when i=1:

```java
// from="${i > 1 ? i : i+1}" produces from=2, to=1 at i=1 → empty range → method omitted.
// At i=2+: from=i, to=i → one clone per arity.
@PermuteMethod(varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}", name = "filter")
public Object filterLatest(@PermuteDeclr(type = "Predicate2<DS, ${alpha(i)}>") Object p) { ... }
```

The inner variable `x` is a loop counter only; annotations use the outer `i`. Sentinel named `filterLatest`; `name="filter"` renames the output.

### Usage

```java
// All-facts filter — cross-fact comparison
.filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)

// Single-fact filter — just the latest joined fact
.join(ctx -> ctx.accounts())
.filter((ctx, b) -> b.balance() > 500.0)   // b is Account only

// Chained — single-fact then cross-fact
.filter((ctx, b) -> b.balance() > 500.0)   // Account passes this
.filter((ctx, a, b) -> a.age() >= 18)       // both facts needed here
```

## Comparison with Real Drools RuleBuilder

Studied at `droolsvol2/src/main/java/org/drools/core/RuleBuilder.java`:

| Feature | Real Drools | This Example |
|---|---|---|
| Typed `join(Function<DS, DataSource<C>>)` | ✅ `<C> Join2First<...,B,C> join(...)` | ✅ identical pattern |
| Single-fact `filter(Predicate2<DS, C>)` | ✅ per arity | ✅ per arity (arity 2+) |
| All-facts `filter(PredicateN+1<DS,...>)` | ✅ per arity | ✅ per arity |
| First extends Second hierarchy | ✅ | Phase 2 |
| `join(Join2Second)` multi-step | ✅ | Phase 2 |
| `not()` scoped negation | ✅ | Phase 3+ |
| Boundary omission (leaf node) | ❌ manually written | ✅ automatic via `@PermuteReturn` |
| Extend to arity 10 | Requires editing N classes | Change `to=6` → `to=10` |

---

## Phase 2 Design Intent: First / Second Split (Future)

The real Drools codebase uses a **two-family design** that Phase 2 of this example will eventually implement:

- **`JoinNSecond<DS, T1,...,TN>`** — the "gateway" class, accepting only arity-advancing operations (`join()`).
- **`JoinNFirst<DS, T1,...,TN> extends JoinNSecond`** — the "full" class with all operations.

The split enables multi-step joins in Phase 2:

```java
// Phase 2 multi-step join — join(Join2First) via Second supertype:
join1First.join(existingJoin2First)   // Join2First satisfies Join2Second
```

`JoinNSecond` acts as the **input contract** for pre-built N-arity structures. This is why `fn()` lives on First — you can only terminate when holding a fully-assembled structure.

### Why Not Phase 1?

G3 extends clause auto-expansion uses name-prefix + embedded numeric suffix detection (`"Join"` + `"2"` → sibling `"Join2Second"`). Alpha naming (`A, B, C`) has no numeric suffix — G3 cannot locate siblings. Until Permuplate gains alpha-aware G3 expansion, the First/Second split requires either `T${j}` naming (breaking Drools convention) or manual maintenance. Phase 1 defers this as accepted design debt.

**When Phase 2 is implemented:** The single `JoinNFirst` family will be split into separate Second and First families. The `join()` parameter type will change from `Function<DS, DataSource<?>>` to the typed `JoinNSecond` supertype. This is a breaking API change that will require test updates.

---

## Naming Convention: `A, B, C, D` — Matching Drools Conventions

This example uses single-letter type parameter names (`A, B, C, D, E, F`) throughout — exactly as the real Drools codebase does. This is a deliberate choice to keep the example faithful to Drools and make the eventual migration as direct as possible.

**Implication:** single-letter names disable Permuplate's implicit inference, which requires the `T+number` pattern (T1, T2, T3) to identify the growing type parameter tip. As a result, this example uses **explicit annotations throughout** — `@PermuteTypeParam(name="${alpha(j)}")`, `@PermuteReturn`, `@PermuteDeclr` — rather than relying on zero-annotation inference.

This is not a problem — it shows the explicit path clearly and the templates remain readable and maintainable. The alpha functions (`alpha(j)` → A, B, C...) and `typeArgList(..., "alpha")` handle the naming. The implicit path (T${j} convention) is validated separately in `PermuteReturnTest` and `PermuteTypeParamTest`.

**Rule:** when evolving this example, keep using single-letter type parameter names (`${alpha(j)}`) to stay consistent with Drools. Do not switch to `T${j}` — that would diverge from the Drools convention this example is designed to approximate.

**Open question — ctx position in lambda signatures:** Currently `ctx` is the first parameter of every lambda: `(ctx, a, b, c) -> ...`. This is visually consistent — every arity has ctx in the same position. However it means type parameter `A` maps to the *second* lambda argument (index 1), which can be confusing from an indexing perspective. The alternative — `ctx` last: `(a, b, c, ctx) -> ...` — gives `A` a clean index-0 mapping but buries ctx at the end where it's less visible. Decision deferred; review before finalising the API.

**Future idea:** provide a second variant of the DSL using `T${j}` naming to demonstrate the zero-annotation implicit inference path side-by-side with the alpha explicit path. Both would produce the same runtime behaviour, showing the Permuplate trade-off between naming convention and annotation burden.

---

## What Permuplate Features Each Phase Exercises

### Phase 1 (implemented)
Sequential join chain: `from → join → filter → fn`

| Feature | Used by |
|---|---|
| **G1** `@PermuteTypeParam` (explicit, alpha) | `Consumer1`, `Predicate1`, `Join0First` — `name="${alpha(k)}"` |
| **G2** `@PermuteReturn` (explicit) | All return types — alpha naming disables implicit inference; `join()`, `filter()`, `fn()` all use explicit `@PermuteReturn` |
| **G2** Boundary omission | `Join6First` — leaf node, `join()` omitted since `Join7First` not in generated set |
| **N4** `typeArgList()` | `filter()` and `fn()` parameter types — `Predicate${i+1}<DS, ${typeArgList(1,i,'alpha')}>` |

### Phase 2 (future)
Multi-step joins + First/Second split

- G3 extends expansion — requires either `T${j}` naming or alpha-aware G3 improvement
- `@PermuteMethod` with j>1 on `JoinNSecond` — overloads that accept `JoinNSecond` parameters
- `@PermuteDeclr` on parameters for typed `join(JoinNSecond)` overloads

### Phase 3+ (future)
- `not()` / negation groups (`Not2`, `Group2` pattern)
- `path()` traversal (OOPath — `path2()`, `path3()`, ... via G4 `@PermuteMethod.name`)
- Variable-based cross-fact filters (`Variable<T>` pattern)
- `params()` typed parameter binding

---

## The `RuleDefinition` Recording Structure

`RuleDefinition` is what the DSL produces. It serves two purposes:

1. **Record the configured rule** — sources (DataSource references), predicates (filter lambdas), and the terminal action. These support *structural assertions*: "this rule has 2 sources and 1 filter."

2. **Execute the rule** — `run(DS ctx)` evaluates the rule against actual data, recording which fact combinations were matched. These support *behavioural assertions*: "running this rule against [Alice(30), Bob(25)] matched exactly one pair."

```java
// Structural assertion
assertThat(rule.sourceCount()).isEqualTo(2);
assertThat(rule.filterCount()).isEqualTo(1);

// Behavioural assertion
rule.run(ctx);
assertThat(rule.executionCount()).isEqualTo(1);
assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
assertThat(rule.capturedFact(0, 1)).isEqualTo(new Person("Bob", 25));
```

The behavioural tests are the most valuable long-term: they verify that the type-safe permuted API correctly routes facts through the filter chain to the action, regardless of arity.

---

## The Permutation Correctness Tests

The key assertion this example makes about Permuplate: **every generated arity behaves identically in structure, varying only in fact count**. Tests should cover:

- Arity 1 (from → fn directly)
- Arity 2 (one join)
- Arity 3 (two joins)
- Arity 6 (maximum — leaf node confirms no join() exists at compile time)
- Multiple filters at the same arity level
- Filter predicates that reject some combinations

If any arity behaves differently from the others in equivalent scenarios, that is a Permuplate regression.

---

## Known Limitations and Deferred Items

| Limitation | Root cause | Workaround / Note |
|---|---|---|
| No First/Second split in Phase 1 | G3 extends expansion requires `T+number` suffix; alpha naming has no suffix | Single `JoinFirst` family; Phase 2 will retrofit the split |
| `join()` return type is raw (no type args) | Next arity's type param not in scope | Raw return + reflective instantiation; arity-2+ lambdas require explicit casts |
| `wrapPredicate` truncates facts to filter's arity | Filters registered at different arities share the same NaryPredicate/run() loop | `m.getParameterCount() - 1` gives the filter's fact count; extra facts are truncated |
| `JoinNFirst` nested inside `JoinBuilder` | `inline=true` requires a container class | Use `var` in tests; qualified name `JoinBuilder.Join1First` when explicit |
| `filter()` requires explicit `@PermuteReturn` | Self-return inference (TODO-2) not yet implemented | Explicit `@PermuteReturn(typeArgs=..., when="true")` |
| `fn()` requires explicit `@PermuteReturn(when="true")` | `RuleDefinition` not in the generated set | `when="true"` overrides boundary check |
| All return types require explicit `@PermuteReturn` | Alpha naming disables implicit inference — deliberate | `@PermuteReturn` + `@PermuteDeclr` throughout; implicit path tested in `PermuteReturnTest` |
| Maximum arity is 6 | Practical limit for the example | Change `to=6` on all templates to extend; tests should scale automatically |

### The `join()` Instantiation Pattern

`join()` creates the next `JoinNFirst` instance reflectively, deriving the target class name from the current class:

```java
String cn = getClass().getSimpleName();           // e.g. "Join1First"
int n = Integer.parseInt(cn.replaceAll("[^0-9]", ""));
String nextName = getClass().getEnclosingClass().getName() + "$Join" + (n + 1) + "First";
return cast(Class.forName(nextName).getConstructor(RuleDefinition.class).newInstance(rd));
```

This is necessary because `rd.asNext()` (an unchecked cast of `RuleDefinition` to `JoinNFirst`) causes `ClassCastException` at runtime — the Java compiler inserts a `checkcast JoinNFirst` in the bytecode at every `invokevirtual` call site, and `RuleDefinition` does not extend `JoinNFirst`. Reflective instantiation creates a real `JoinNFirst` wrapping the same `RuleDefinition`.

### Arity-2+ Type Safety Limitation

After the first `join()` call, the chain **loses compile-time type parameters**. The reason: `@PermuteReturn(className="Join${i+1}First")` without `typeArgs` generates a raw return type (`Join2First` without generics). Since the next arity's type parameter (`B`, `C`, etc.) is not in scope in the template class, there is no way to generate a fully parameterized return type.

**Practical consequence:** filter and fn lambdas at arity 2+ receive `Object`-typed parameters and require explicit casts:

```java
// Arity 1 — fully type-safe (from() returns Join1First<Ctx, Person>)
var rule = builder.from("adults", ctx -> ctx.persons())
        .filter((ctx, a) -> a.age() >= 18)   // a is Person — no cast needed
        .fn((ctx, a) -> {});

// Arity 2 — raw type after join() — explicit casts required
var rule = builder.from("adult-accounts", ctx -> ctx.persons())
        .join(ACCOUNTS)                        // pre-typed constant required
        .filter((ctx, a, b) -> ((Person) a).age() >= 18 && ((Account) b).balance() > 500.0)
        .fn((ctx, a, b) -> {});
```

This is a Phase 1 limitation. Phase 2's First/Second split and typed `join()` overloads will restore full type safety once Permuplate supports generating typed method bodies.

---

## How to Evolve This DSL

When adding Phase 2 (multi-step joins) or Phase 3 features:

1. **Add to the template, not by hand.** New methods on the JoinFirst family go in `Join0First` inside `JoinBuilder.java`. Permuplate generates the rest.

2. **Prefer implicit over explicit.** Before adding `@PermuteReturn`, check if the return type uses `T${j}` convention — if so, inference fires automatically.

3. **Write the test first.** Add a failing test in `RuleBuilderTest` that demonstrates the new DSL usage, then evolve the templates to make it pass.

4. **Check the leaf node.** Any new method on `Join0First` with `@PermuteReturn` pointing to `Join${i+1}First` must verify that `Join6First` correctly omits it (boundary omission).

5. **Run the full permutation matrix.** Tests should cover arities 1–6 for any new method, not just the typical 2–3 case.

6. **Validate in permuplate-tests first.** If a new Permuplate feature is needed, add a focused test in `permuplate-tests/` before relying on it here.

---

## Connection to the Real Drools Codebase

The real Drools code is at `/Users/mdproctor/dev/droolsoct2025/droolsvol2/`. Key files:

- `RuleBuilder.java` — the hand-written builder with `Join2First..Join5First`, `Join2Second..Join5Second`, `From1First`
- `RuleBuilderTest.java` — usage examples that this Permuplate example approximates
- `function/` directory — `Consumer1..4`, `Predicate1..10`, `Function1..5` (all hand-written, all G1 candidates)

Once this Permuplate example is validated and tests are comprehensive, the Drools classes can be replaced with Permuplate-generated equivalents one family at a time:
1. Consumer family (simplest — pure G1)
2. Predicate family (G1)
3. Join chain (G1 + G2 + G3)
