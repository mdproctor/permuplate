# Spec: Method-Level @PermuteTypeParam Standalone Support with Propagation + Dual Filter Overloads

**Date:** 2026-04-04  
**Status:** Approved  
**Motivation:** Two related improvements to the Drools DSL example, informed by studying the real Drools `RuleBuilder.java` codebase:
1. Fix the raw-type problem on `join()` — fully typed chain at all arities
2. Add dual `filter()` overloads — single-fact (latest only) and all-facts — matching real Drools ergonomics

---

## Problem Statement

`@PermuteTypeParam` on method type parameters currently only works inside `@PermuteMethod`. When `join()` in `JoinBuilder.Join0First` needs a method-level type parameter that varies with arity, there is no mechanism to rename it — forcing the template to use a wildcard `DataSource<?>` and lose generic type information after the first join.

**Phase 1 pain (real code from RuleBuilderTest):**

```java
// Constants required because join() infers Function<Object, DataSource<?>> in raw context
private static final Function<Ctx, DataSource<?>> ACCOUNTS = c -> c.accounts();

@Test
@SuppressWarnings({"unchecked", "rawtypes"})   // required because join() returns raw Join2First
public void testArity2FilterOnBothFacts() {
    var rule = builder.from("adult-high-balance", ctx -> ctx.persons())
            .join(ACCOUNTS)                      // pre-typed constant required
            .filter((ctx, a, b) -> ((Person) a).age() >= 18   // explicit cast required
                                && ((Account) b).balance() > 500.0)
            .fn((ctx, a, b) -> {});
}
```

**Goal (after this spec):**

```java
@Test
public void testArity2FilterOnBothFacts() {
    var rule = builder.from("adult-high-balance", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())          // inline lambda; B inferred as Account
            .filter((ctx, a, b) -> a.age() >= 18  // a is Person, b is Account — no casts
                                && b.balance() > 500.0)
            .fn((ctx, a, b) -> {});
}
```

---

## Feature: Standalone Method-Level @PermuteTypeParam

### What it does

Allows `@PermuteTypeParam` to appear on method type parameters of **any method**, not just those inside `@PermuteMethod`. When present, the transformer:

1. Renames the type parameter declaration (e.g. `<B>` → `<C>`)
2. **Propagates** the rename through all parameter types in the same method signature
3. Leaves the method body unchanged (consistent with all other transformations)
4. Respects `@PermuteDeclr` as an explicit override (see Fallback section)

### Why propagation matters

Without propagation, the template author must manually repeat the rename expression wherever the type parameter appears in the method signature:

```java
// Without propagation — annotation noise, duplication, error-prone
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
@PermuteReturn(className = "Join${i+1}First", typeArgs = "'DS, ' + typeArgList(1, i+1, 'alpha')")
public <B> Object join(
        @PermuteDeclr(type = "java.util.function.Function<DS, DataSource<${alpha(i+1)}>>")
        java.util.function.Function<DS, DataSource<?>> source) { ... }
```

The `@PermuteDeclr` exists purely because the programmer had to figure out that `B` becomes `alpha(i+1)` and repeat it. With propagation:

```java
// With propagation — the template is natural, readable Java
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
@PermuteReturn(className = "Join${i+1}First", typeArgs = "'DS, ' + typeArgList(1, i+1, 'alpha')")
public <B> Object join(java.util.function.Function<DS, DataSource<B>> source) { ... }
```

`B` in `DataSource<B>` is renamed alongside the declaration automatically. The template is valid, compilable Java with no redundant annotation noise.

### The @PermuteDeclr fallback

When `@PermuteDeclr` is present on a parameter, it **always takes precedence** over propagated renames. This is the escape hatch for cases where the propagated type is insufficient:

```java
// Propagation would produce: Function<DS, DataSource<B>>
// @PermuteDeclr overrides to a different structure entirely
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
public <B> Object complexJoin(
        @PermuteDeclr(type = "BiFunction<DS, ${alpha(i+1)}, DataSource<${alpha(i+1)}>>")
        Object source) { ... }
```

**Rule:** propagation fills in what you don't annotate; `@PermuteDeclr` fills in what you explicitly annotate. They don't conflict — explicit always wins.

---

## Implementation

### PermuteTypeParamTransformer.transform() — new Step 5

The existing pipeline in `transform()` is:
1. Detect expansions (dry run for R1)
2. R1 validate
3. `expandExplicit()` — class-level type params
4. `expandImplicit()` — from `@PermuteParam` with `T${j}`

**New Step 5:** After class-level expansion, scan all methods in the class. For each method that:
- Does **not** have `@PermuteMethod` (those are handled later by `applyPermuteMethod()`)
- Has at least one type parameter annotated with `@PermuteTypeParam`

Call `transformMethod(method, ctx, ...)` and then immediately propagate the renames into parameter types.

**Guard against double-processing:** `@PermuteMethod` methods are explicitly skipped. This is essential — `applyPermuteMethod()` calls `transformMethod()` for those methods later with the inner (i,j) context; processing them here with only the outer context would corrupt them.

### Propagation implementation

Propagation happens **inside `transformMethod()`**, not in the caller — because that is where both the old name (`sentinelName`) and the new name (`newName`) are known at the point of each expansion. Doing it in the caller would require changing the return type and re-deriving the mapping.

After expanding each sentinel type parameter, immediately propagate the rename into all parameter types in the same method:

```java
// Inside the expansion loop in transformMethod(), after building result:
String oldName = sentinelName;   // e.g. "B"
String newName = innerCtx.evaluate(nameTemplate);  // e.g. "C"

for (Parameter param : method.getParameters()) {
    // Skip params with @PermuteDeclr — explicit annotation takes precedence
    if (hasPermuteDeclrAnnotation(param)) continue;
    String newTypeStr = replaceTypeRef(param.getTypeAsString(), oldName, newName);
    if (!newTypeStr.equals(param.getTypeAsString())) {
        param.setType(StaticJavaParser.parseType(newTypeStr));
    }
}
```

`replaceTypeRef` uses the existing `containsTypeRef` word-boundary logic to replace `B` as a standalone Java identifier — not as a substring — so `Boolean`, `Builder`, `BiFunction` are never corrupted.

**Benefit of doing propagation inside `transformMethod()`:** All callers get propagation automatically — both the new Step 5 standalone path and the existing `@PermuteMethod` path. The `@PermuteMethod` case was silently broken before (parameter types were not updated after type param renames); propagation fixes this for free. `@PermuteDeclr` still overrides in both paths.

### Effect within @PermuteMethod (existing code)

Adding propagation to `transformMethod()` also benefits methods inside `@PermuteMethod`. Previously, if a method had `@PermuteTypeParam` renaming `PB` → `T2`, the parameter type `Predicate<PB>` would be left as `Predicate<PB>` (broken). `@PermuteDeclr` was required to fix it. With propagation, `Predicate<PB>` automatically becomes `Predicate<T2>`. Existing tests use `@PermuteDeclr` explicitly, so they remain unaffected — `@PermuteDeclr` overrides.

---

## JoinBuilder.java Template Changes

### join() — before and after

**Before (Phase 1):**
```java
@PermuteReturn(className = "Join${i+1}First")
public Object join(java.util.function.Function<DS, DataSource<?>> source) {
    rd.addSource(source);
    String cn = getClass().getSimpleName();
    int n = Integer.parseInt(cn.replaceAll("[^0-9]", ""));
    String nextName = getClass().getEnclosingClass().getName() + "$Join" + (n + 1) + "First";
    try {
        return cast(Class.forName(nextName).getConstructor(RuleDefinition.class).newInstance(rd));
    } catch (Exception e) {
        throw new RuntimeException("Failed to instantiate " + nextName, e);
    }
}
```

**After (this spec):**
```java
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
@PermuteReturn(className = "Join${i+1}First",
               typeArgs = "'DS, ' + typeArgList(1, i+1, 'alpha')")
public <B> Object join(java.util.function.Function<DS, DataSource<B>> source) {
    rd.addSource(source);
    String cn = getClass().getSimpleName();
    int n = Integer.parseInt(cn.replaceAll("[^0-9]", ""));
    String nextName = getClass().getEnclosingClass().getName() + "$Join" + (n + 1) + "First";
    try {
        return cast(Class.forName(nextName).getConstructor(RuleDefinition.class).newInstance(rd));
    } catch (Exception e) {
        throw new RuntimeException("Failed to instantiate " + nextName, e);
    }
}
```

### Generated output per arity

| i | Generated method signature |
|---|---|
| 1 | `public <B> Join2First<DS, A, B> join(Function<DS, DataSource<B>> source)` |
| 2 | `public <C> Join3First<DS, A, B, C> join(Function<DS, DataSource<C>> source)` |
| 3 | `public <D> Join4First<DS, A, B, C, D> join(Function<DS, DataSource<D>> source)` |
| 4 | `public <E> Join5First<DS, A, B, C, D, E> join(Function<DS, DataSource<E>> source)` |
| 5 | `public <F> Join6First<DS, A, B, C, D, E, F> join(Function<DS, DataSource<F>> source)` |
| 6 | *(omitted — boundary omission: Join7First not in generated set)* |

### Annotation mechanics

- `@PermuteTypeParam(varName="m", from="${i+1}", to="${i+1}", name="${alpha(m)}")`:
  - Range `from=${i+1}` to `${i+1}` generates exactly one type parameter per arity
  - `name="${alpha(m)}"` produces B (i=1), C (i=2), D (i=3), E (i=4), F (i=5)
  - Propagation renames `B` in `DataSource<B>` automatically
- `@PermuteReturn(typeArgs="'DS, ' + typeArgList(1, i+1, 'alpha')")`:
  - For i=1: `"DS, A, B"` → `Join2First<DS, A, B>`
  - For i=2: `"DS, A, B, C"` → `Join3First<DS, A, B, C>`

---

## APT Parity

Both APT and Maven plugin share `PermuteTypeParamTransformer` — the Step 5 addition applies identically to both.

**Template validity:** `public <B> Object join(Function<DS, DataSource<B>> source)` is valid, compilable Java. APT templates must compile as written — this template does. ✓

**@PermuteReturn in APT:** Already supported in explicit mode. ✓

**What APT cannot do (existing limitations, unchanged):**
- Implicit inference (zero-annotation T${j} path) — requires inline/Maven plugin
- Boundary omission on `@PermuteReturn` — requires inline/Maven plugin
- `inline=true` — APT can only create new files

**What APT can do with this feature:**
- `@PermuteTypeParam` on a non-`@PermuteMethod` method — fully supported ✓
- Propagation of type param renames into parameter types — fully supported ✓
- `@PermuteDeclr` fallback on parameters — fully supported ✓

---

## Test Plan

### New tests in PermuteTypeParamTest

These tests use the APT compile-testing path (Google `compile-testing`).

#### 1. Basic standalone method type param — rename and propagation
Verifies that `@PermuteTypeParam` on a non-`@PermuteMethod` method renames the declaration and propagates to parameter types.

```java
// Template (i from 2 to 3):
// public <B> void process(@PermuteTypeParam... <B>, DataHolder<B> holder)
// Expected i=2: public <B> void process(DataHolder<B> holder)  (B stays B at i=2 with alpha(2)=B)
// Expected i=3: public <C> void process(DataHolder<C> holder)  (propagated)
```

#### 2. Propagation into nested generic type
Verifies propagation reaches type arguments nested two or more levels deep (e.g. `Function<DS, DataSource<B>>`).

#### 3. @PermuteDeclr fallback overrides propagation
Verifies that when `@PermuteDeclr` is present on a parameter, the explicit type wins over the propagated rename.

```java
// @PermuteTypeParam renames B → C
// @PermuteDeclr(type="Wrapper<${alpha(i+1)}, Extra>") on the parameter
// Expected: Wrapper<C, Extra>  (not DataHolder<C> from propagation)
```

#### 4. Multiple method type params — all renamed and propagated
Verifies a method with two `@PermuteTypeParam`-annotated type params where both are renamed and both propagate.

#### 5. @PermuteMethod guard — no double processing
Verifies that a method marked `@PermuteMethod` is NOT processed by Step 5 of `transform()`. The inner context values are not present in the outer context — processing it twice would cause evaluation errors or corrupt the output.

#### 6. APT mode end-to-end: typed join() pattern
Compiles a mini join template in APT mode with `@PermuteTypeParam` on the method and `@PermuteReturn`. Asserts the generated source contains correctly typed signatures.

```java
assertThat(src).contains("public <B> Join2<A, B> join(DataSource<B> source)");
assertThat(src).contains("public <C> Join3<A, B, C> join(DataSource<C> source)");
```

### RuleBuilderTest — all 15 existing tests updated

Every test that currently has:
- `@SuppressWarnings({"unchecked", "rawtypes"})` → **removed**
- Pre-typed constants (`ACCOUNTS`, `PERSONS`, etc.) → **removed** (inline lambdas used instead)
- Explicit casts (`((Person) a)`, `((Account) b)`) → **removed** (params are now typed)

The constants field block in the test class is deleted entirely. All `join()` calls use inline lambdas. The test class becomes shorter and clearer.

### CLAUDE.md update

Add to the non-obvious decisions table:

| Topic | Decision / Fix |
|---|---|
| `@PermuteTypeParam` standalone on method | Step 5 of `transform()` processes methods without `@PermuteMethod`. `@PermuteMethod` methods are guarded (skipped) to prevent double-processing with wrong context. |
| Propagation scope | Rename propagates to parameter types only — not method body. `@PermuteDeclr` on a parameter always overrides the propagated type. |

---

## Dual filter() Overloads

### Background: what real Drools does

Reading the actual Drools `RuleBuilder.java` confirmed our raw-type fix is exactly right — Drools already has `public <C> Join2First<END,DS,B,C> join(Function1<DS,DataSource<C>> fromC)`. It also revealed a significant ergonomic gap: Drools has **two `filter()` overloads** on every JoinNFirst:

```java
// JoinNFirst in real Drools — both overloads exist at every arity N≥2
public Join2First<..., B, C> filter(Predicate2<Context<DS>, C> predicate)  // latest fact only
public Join2First<..., B, C> filter(Predicate3<Context<DS>, B, C> predicate)  // all facts
```

From the real Drools test:
```java
builder.rule("rule1")
       .from(Ctx::persons).filter((ctx, p) -> p.age() > 50)    // single-fact: just Person
       .join(Ctx::persons).filter((ctx, p) -> p.age() > 50)    // single-fact: just new Person
       .filter((ctx, p1, p2) -> p1.age() > p2.age())           // cross-fact: both Persons
       .join(Ctx::persons).filter((ctx, p) -> p.age() > 50)    // single-fact: just newest Person
       .filter((ctx, p1, p2, p3) -> p1.age() > p3.age());      // cross-fact: all three
```

The single-fact overload is the most common post-join filter — "does this new fact satisfy a condition?" — and having to pass all facts just to test the latest one is ergonomically poor. Our Phase 1 example only has the all-facts version.

### The arity-1 collision problem

At arity 1 (`Join1First<DS, A>`):
- All-facts filter: `filter(Predicate2<DS, A>)` — context + A
- Single-fact filter: also `filter(Predicate2<DS, A>)` — same type

Identical signatures — Java would reject the duplicate. Solution: suppress the single-fact filter entirely at arity 1, since the all-facts filter already serves that role.

### Suppression mechanism: @PermuteMethod with ternary range

`@PermuteMethod` silently omits a method when `from > to` (empty range). A JEXL ternary in `from` produces an empty range at i=1 and a single-clone range at i≥2:

```java
@PermuteMethod(varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}", name = "filter")
```

| i | from evaluates to | to | range | result |
|---|---|---|---|---|
| 1 | `i+1 = 2` | 1 | 2>1 → empty | method omitted ✓ |
| 2 | `i = 2` | 2 | 2=2 → one clone | `filter(Predicate2<DS, B>)` ✓ |
| 3 | `i = 3` | 3 | 3=3 → one clone | `filter(Predicate2<DS, C>)` ✓ |
| 4..6 | `i = N` | N | one clone each | `filter(Predicate2<DS, alpha(N)>)` ✓ |

The inner variable `x` is never used in the method body or annotations — it exists purely as a loop counter to control whether the clone is generated. `name="filter"` renames the sentinel `filterLatest` to `filter` in the output.

### Template

The sentinel method in `Join0First`:

```java
// Sentinel named filterLatest to avoid same-name ambiguity with the all-facts filter
// in the template. @PermuteMethod renames it to "filter" in the generated output.
// Empty range at i=1 (ternary) suppresses the method there — at arity 1, the
// all-facts filter already has the same signature.
@PermuteMethod(varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}", name = "filter")
@PermuteReturn(className = "Join${i}First",
               typeArgs = "'DS, ' + typeArgList(1, i, 'alpha')",
               when = "true")
public Object filterLatest(
        @PermuteDeclr(type = "Predicate2<DS, ${alpha(i)}>")
        Object predicate) {
    rd.addFilter(predicate);
    return this;
}
```

### Generated output per arity

| i | Single-fact filter | All-facts filter |
|---|---|---|
| 1 | *(omitted — would duplicate all-facts)* | `filter(Predicate2<DS, A> p)` |
| 2 | `filter(Predicate2<DS, B> p)` | `filter(Predicate3<DS, A, B> p)` |
| 3 | `filter(Predicate2<DS, C> p)` | `filter(Predicate4<DS, A, B, C> p)` |
| 4 | `filter(Predicate2<DS, D> p)` | `filter(Predicate5<DS, A, B, C, D> p)` |
| 5 | `filter(Predicate2<DS, E> p)` | `filter(Predicate6<DS, A, B, C, D, E> p)` |
| 6 | `filter(Predicate2<DS, F> p)` | `filter(Predicate7<DS, A, B, C, D, E, F> p)` |

### How @PermuteMethod + @PermuteDeclr interact

`applyPermuteMethod()` runs before `PermuteDeclrTransformer`. Inside the method loop it calls:
1. `transformMethod()` — method-level type params (none here)
2. `applyPermuteReturnToSingleMethod()` — sets return type from `@PermuteReturn`
3. `processMethodParamDeclr()` — applies `@PermuteDeclr` on the parameter

So `@PermuteDeclr(type="Predicate2<DS, ${alpha(i)}>")` is evaluated with the inner (i,x) context (where x=i, so alpha(i) is correct) and produces the right type per arity. The sentinel is then removed; the clones remain. The downstream `PermuteDeclrTransformer` then processes the all-facts `filter()` (which still has its `@PermuteDeclr`), leaving both overloads in the final class.

---

## Comparison with Real Drools

Studying `droolsvol2/src/main/java/org/drools/core/RuleBuilder.java` directly:

### Where we now match Drools
| Feature | Drools | After this spec |
|---|---|---|
| Typed `join(Function<DS, DataSource<C>>)` | ✅ `<C> Join2First<...,B,C> join(Function1<DS,DataSource<C>>)` | ✅ identical pattern |
| Single-fact filter | ✅ `filter(Predicate2<Context<DS>, C>)` | ✅ `filter(Predicate2<DS, alpha(i)>)` |
| All-facts filter | ✅ `filter(Predicate3<Context<DS>, B, C>)` | ✅ `filter(Predicate${i+1}<DS,...>)` |
| First extends Second hierarchy | ✅ | Phase 2 plan |

### Where Drools goes further (future phases)
| Feature | Drools | Our plan |
|---|---|---|
| `join(Join2Second)` multi-step | ✅ | Phase 2 |
| `extensionPoint()` / `extendsRule()` | ✅ typed cross-rule extension | Phase 3+ |
| `not()` scoped negation | ✅ | Phase 3+ |
| `Variable<T>` cross-fact binding | ✅ | Phase 3+ |
| `END` phantom type (nested contexts) | ✅ | Not planned |
| `from(From1First)` as join source | ✅ | Phase 2 |

### Where Permuplate improves on Drools
| Feature | Drools (hand-written) | Permuplate |
|---|---|---|
| Arity limit | Hard-coded at 5 (Join5Second is leaf) | Change `to=6` to `to=10`; everything regenerates |
| Leaf node | Manually written without `join()` | Automatic boundary omission via `@PermuteReturn` |
| Function type | Custom `Function1`, `Predicate2` etc. | Standard `java.util.function.Function` |
| Adding a new method | Edit N classes | Edit one template |

---

## Updated Test Plan

### New tests in PermuteTypeParamTest (APT compile-testing path)
1. Basic standalone method type param — rename and propagation
2. Propagation into nested generic (e.g. `Function<DS, DataSource<B>>`)
3. `@PermuteDeclr` fallback overrides propagation
4. Multiple method type params — all renamed and propagated
5. `@PermuteMethod` guard — no double processing
6. APT mode end-to-end: typed `join()` pattern

### New tests in PermuteMethodTest (APT compile-testing path)
7. `@PermuteMethod` with ternary `from` expression — empty range at i=1, single clone at i≥2
8. Dual `filter()` overloads present at arity 2+ and single overload at arity 1

### Module-level tests in RuleBuilderTest (permuplate-mvn-examples)

**Updated existing tests** — remove `@SuppressWarnings`, `PERSONS`/`ACCOUNTS` etc. constants, explicit casts throughout all 15 tests.

**New tests for typed join:**
```java
@Test
public void testArity2FullyTyped() {
    // No casts, no constants — B inferred as Account from lambda return type
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
            .fn((ctx, a, b) -> {});
    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(1);
}

@Test
public void testArity3FullyTyped() {
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .join(ctx -> ctx.orders())
            .filter((ctx, a, b, c) -> a.age() >= 18 && c.amount() > 100.0)
            .fn((ctx, a, b, c) -> {});
    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(1);
}
```

**New tests for single-fact filter:**
```java
@Test
public void testArity2SingleFactFilter() {
    // filter on latest fact only — Predicate2<DS, Account>
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, b) -> b.balance() > 500.0)   // b is Account — no other facts needed
            .fn((ctx, a, b) -> {});
    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(2); // 2 persons × 1 high-balance account
}

@Test
public void testArity2ChainedBothFilterTypes() {
    // Chain single-fact then cross-fact on same join
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, b) -> b.balance() > 500.0)          // single-fact: balance check
            .filter((ctx, a, b) -> a.age() >= 18)              // cross-fact: age check
            .fn((ctx, a, b) -> {});
    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(1); // Alice(30) + ACC1(1000)
}

@Test
public void testArity3SingleFactFilterOnLatest() {
    // After two joins, single-fact filter applies to D (the latest-joined fact)
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .join(ctx -> ctx.orders())
            .filter((ctx, c) -> c.amount() > 100.0)  // c is Order — only latest fact
            .fn((ctx, a, b, c) -> {});
    rule.run(ctx);
    assertThat(rule.filterCount()).isEqualTo(1);
    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(4); // 2p × 2a × 1 qualifying order = 4
}

@Test
public void testArity1HasOnlyOneFilterOverload() {
    // Structural: at arity 1, Join1First has exactly one filter overload (all-facts = single-fact)
    // This is verified at compile time — if two identical overloads existed, this file wouldn't compile.
    var rule = builder.from("persons", ctx -> ctx.persons())
            .filter((ctx, a) -> a.age() >= 18)
            .fn((ctx, a) -> {});
    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(1);
}
```

---

## What This Does NOT Change

- `@PermuteReturn` boundary omission for `Join6First` — still works, `Join7First` not in generated set
- `fn()` return types — unchanged
- Runtime reflection in `join()` body — unchanged (still needed; method bodies are not transformed)
- All existing `@PermuteMethod` + `@PermuteTypeParam` behaviour — unchanged; `@PermuteDeclr` still used there and takes precedence

---

## Files Changed

| File | Change |
|---|---|
| `permuplate-core/.../PermuteTypeParamTransformer.java` | Add Step 5 to `transform()`; add propagation logic in `transformMethod()` |
| `permuplate-mvn-examples/.../drools/JoinBuilder.java` | Typed `join()` with `@PermuteTypeParam` + `<B>` + `DataSource<B>` + typeArgs; add `filterLatest` sentinel for single-fact overload |
| `permuplate-mvn-examples/.../RuleBuilderTest.java` | Remove constants/`@SuppressWarnings`/casts; add typed-join tests + dual-filter tests |
| `permuplate-tests/.../PermuteTypeParamTest.java` | 6 new tests: standalone method, propagation, fallback, guard, APT e2e |
| `permuplate-tests/.../PermuteMethodTest.java` | 2 new tests: ternary `from` expression, dual filter generation |
| `permuplate-mvn-examples/DROOLS-DSL.md` | Document dual filter pattern, Drools comparison table, ternary suppression mechanism |
| `CLAUDE.md` | Add entries: standalone method `@PermuteTypeParam`, propagation scope, `@PermuteDeclr` precedence, ternary `from` suppression |
