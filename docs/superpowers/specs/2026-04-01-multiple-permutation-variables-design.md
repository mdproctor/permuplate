# Multiple Permutation Variables — Design Spec

**Date:** 2026-04-01
**Status:** Approved

---

## Problem

`@Permute` currently supports a single integer loop variable (`varName`). Some use cases require two independent axes — e.g. a `Matrix${i}x${k}` class that varies both the input arity (`i`) and output arity (`k`) independently. `strings` constants cover fixed secondary names but cannot iterate.

The solution is a **cross-product** of all variable ranges, generating one class per combination. Parallel iteration (variables advance together) is not needed: same-range parallel is already expressible with a single variable.

---

## New Annotation: `@PermuteVar`

```java
@Retention(RetentionPolicy.SOURCE)
@Target({})                     // only valid inside @Permute.extraVars
public @interface PermuteVar {
    String varName();
    int from();
    int to();
}
```

`@Target({})` is the Java idiom for annotation types that may only appear as array element values inside another annotation. It mirrors the three core fields of `@Permute` (`varName`, `from`, `to`) without `className`, `strings`, or `extraVars`.

---

## `@Permute` extension

```java
public @interface Permute {
    String varName();
    int from();
    int to();
    String className();
    String[] strings() default {};
    PermuteVar[] extraVars() default {};    // ← new
}
```

All variables in `extraVars` are available in every `${...}` expression alongside `varName` and `strings`. Example:

```java
@Permute(varName = "i", from = 2, to = 3,
         className = "Matrix${i}x${k}",
         extraVars = { @PermuteVar(varName = "k", from = 2, to = 3) })
public class Matrix2x2 { ... }
```

Generates `Matrix2x2`, `Matrix2x3`, `Matrix3x2`, `Matrix3x3` (cross-product of i∈[2,3] and k∈[2,3]).

---

## Cross-Product Iteration

`PermuteProcessor.buildAllCombinations(Permute)` replaces the direct `buildVars(permute, i)` call inside the permutation loop. It starts with the primary variable's range, then for each `@PermuteVar` expands the list by the cross-product:

```
Start:            [{i=2}, {i=3}]
Apply k∈[2,3]:   [{i=2,k=2}, {i=2,k=3}, {i=3,k=2}, {i=3,k=3}]
Merge strings:   each map also gets string constants from permute.strings()
```

**Iteration order is deterministic:** the primary variable (`varName`) is the outermost loop; `extraVars` are applied as inner loops in declaration order. This means for i∈[2,3] and k∈[2,3], generation order is `(i=2,k=2)`, `(i=2,k=3)`, `(i=3,k=2)`, `(i=3,k=3)`. Files are written in this order but since each has a distinct name, order does not affect correctness.

The loop becomes:

```java
for (Map<String, Object> vars : buildAllCombinations(permute)) {
    generatePermutation(templateCu, typeElement, permute, new EvaluationContext(vars));
}
```

`generatePermutation` is unchanged — it accepts whatever `EvaluationContext` it receives. The same cross-product logic applies to `processMethodPermutation`.

---

## Prefix Check Fix

The current className prefix check extracts *all* literal segments, so `"Combo${i}x${k}"` yields static prefix `"Combox"` — which wrongly fails against template class `Combo2x2`. The fix: use only the **leading** literal segment (everything before the first `${`).

- `"Join${i}"` → leading `"Join"` → check template starts with `"Join"` ✓
- `"Combo${i}x${k}"` → leading `"Combo"` → check template starts with `"Combo"` ✓
- `"${base}Join${i}"` → starts with `${`, skip check ✓
- `"Bar${i}"` on `class Foo2` → leading `"Bar"` → `"Foo2".startsWith("Bar")` → error ✓

This is a net improvement and applies even without `extraVars`.

---

## Validation

Checked before any permutation is generated, with `AnnotationValue` precision pointing to the `extraVars` attribute:

| Check | Error |
|---|---|
| Any `extraVars` entry has `from > to` | `@PermuteVar has invalid range: from=X is greater than to=Y` |
| Any `extraVars` `varName` duplicates primary `varName` | conflict error |
| Any `extraVars` `varName` duplicates another `extraVars` name | conflict error |
| Any `extraVars` `varName` duplicates a `strings` key | conflict error |

`validateStrings` is extended to also check against all `extraVars` variable names. The transformer validators (`PermuteDeclrTransformer.validatePrefixes`, `PermuteParamTransformer.validatePrefixes`) require no changes — they are variable-count agnostic.

---

## Test Template: `Combo2x2.java`

Uses both `i` and `k` via dual `@PermuteParam` sentinels:

```java
@Permute(varName = "i", from = 2, to = 3,
         className = "Combo${i}x${k}",
         extraVars = { @PermuteVar(varName = "k", from = 2, to = 3) })
public class Combo2x2 {
    public final List<Object> results = new ArrayList<>();

    public void combine(
            @PermuteParam(varName="j", from="1", to="${i}", type="Object", name="left${j}") Object left1,
            @PermuteParam(varName="m", from="1", to="${k}", type="Object", name="right${m}") Object right1) {
        Collections.addAll(results, left1, right1);
    }
}
```

Generated classes for i∈[2,3] × k∈[2,3]:

| Class | Signature |
|---|---|
| `Combo2x2` | `combine(left1, left2, right1, right2)` |
| `Combo2x3` | `combine(left1, left2, right1, right2, right3)` |
| `Combo3x2` | `combine(left1, left2, left3, right1, right2)` |
| `Combo3x3` | `combine(left1, left2, left3, right1, right2, right3)` |

---

## Tests

**`PermuteTest.testExtraVarsCrossProductGeneratesAllCombinations`**
- All 4 class names present
- Each has the correct method signature (structural)
- Each collects the right args at runtime (behavioural via `results` field)

**`DegenerateInputTest` additions:**
- `extraVars` entry with `from > to` → error
- `extraVars` `varName` conflicts with primary `varName` → error
- `extraVars` `varName` conflicts with another `extraVars` name → error
- `extraVars` `varName` conflicts with a `strings` key → error

---

## Files Changed

| File | Change |
|---|---|
| `permuplate-annotations/.../PermuteVar.java` | New annotation |
| `permuplate-annotations/.../Permute.java` | Add `extraVars`, update Javadoc |
| `permuplate-processor/.../PermuteProcessor.java` | `buildAllCombinations`, fix prefix check, add extraVars validation |
| `permuplate-tests/.../example/Combo2x2.java` | New template |
| `permuplate-tests/.../PermuteTest.java` | New cross-product test |
| `permuplate-tests/.../DegenerateInputTest.java` | 4 new degenerate tests |
| `README.md` | Update `@Permute` table and Expression syntax |
| `OVERVIEW.md` | Update annotation detail, remove roadmap item |
| `CLAUDE.md` | Update `buildVars` → `buildAllCombinations` description |
