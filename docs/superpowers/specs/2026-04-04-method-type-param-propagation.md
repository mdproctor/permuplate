# Spec: Method-Level @PermuteTypeParam Standalone Support with Propagation

**Date:** 2026-04-04  
**Status:** Approved  
**Motivation:** Fix the raw-type problem on `join()` in the Drools DSL example — making the entire fluent chain fully type-safe at all arities.

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

## What This Does NOT Change

- `@PermuteReturn` boundary omission for `Join6First` — still works, `Join7First` not in generated set
- `filter()` and `fn()` return types — unchanged
- Runtime reflection in `join()` body — unchanged (still needed; method bodies are not transformed)
- All existing `@PermuteMethod` + `@PermuteTypeParam` behaviour — unchanged; `@PermuteDeclr` still used there and takes precedence

---

## Files Changed

| File | Change |
|---|---|
| `permuplate-core/.../PermuteTypeParamTransformer.java` | Add Step 5 to `transform()`; add propagation logic in `transformMethod()` |
| `permuplate-mvn-examples/.../drools/JoinBuilder.java` | Update `join()` with `@PermuteTypeParam` + `<B>` + `DataSource<B>` + typeArgs on `@PermuteReturn` |
| `permuplate-mvn-examples/.../RuleBuilderTest.java` | Remove constants, `@SuppressWarnings`, explicit casts from all 15 tests |
| `permuplate-tests/.../PermuteTypeParamTest.java` | Add 6 new tests covering standalone method, propagation, fallback, guard |
| `CLAUDE.md` | Add entries to non-obvious decisions table |
