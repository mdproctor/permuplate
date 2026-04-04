# G3 Alpha-Naming Extends Expansion — Design Spec

**Date:** 2026-04-04  
**Status:** Approved  
**Module:** `permuplate-maven-plugin` (`InlineGenerator`), `permuplate-tests` (`PermuteMethodTest`)

---

## Problem

G3 extends clause auto-expansion (`applyExtendsExpansion()`) has two bugs:

### Bug 1 — Forward-reference formula

The current formula `newNum = currentEmbeddedNum + 1` produces **forward-reference** extends clauses:

- Template: `Join1First<T1> extends Join1Second<T1>`
- Generated at i=2 (`Join2First`): `extends Join3Second<T1, T2, T3>`

`Join2First extends Join3Second` is semantically wrong — each class should extend its same-arity peer. The correct same-N result is `Join2First extends Join2Second<T1, T2>`. The existing test even has an incorrect comment ("At i=2: Join3First") masking the bug.

### Bug 2 — Alpha naming silently skipped

The expansion checks `allTNumber` (all extends type args must match `T<digits>`) and hardcodes `T1, T2, ..., T(N)` for new type args. When extends type args use alpha naming (`A, B` or `DS, A`) the check fails and the clause is silently left unchanged. This blocks the Drools Phase 2 First/Second split where `JoinNFirst<DS, A, B, C> extends JoinNSecond<DS, A, B, C>` with alpha naming.

A `postG1TypeParams` capture already exists at line 91-92 of `InlineGenerator.generate()` with the comment "used in Task 5" — but it is never passed to `applyExtendsExpansion`.

---

## Solution

Two targeted changes to `applyExtendsExpansion()`:

### Fix 1 — Same-N formula

Change `newNum = currentEmbeddedNum + 1` → `newNum = currentEmbeddedNum`.

| Template | Generated class | Old (broken) | New (correct) |
|---|---|---|---|
| `Join1First extends Join1Second` | `Join2First` | `extends Join3Second` | `extends Join2Second` |
| `Join0First extends Join0Second` | `Join1First` | `extends Join2Second` | `extends Join1Second` |

### Fix 2 — Alpha naming via postG1TypeParams

Pass `List<String> postG1TypeParams` (the class type parameter names after G1 expansion) as a new parameter. After the existing `allTNumber` check, add a second detection branch:

**Alpha branch condition:** the extends type args are a prefix (or full match) of `postG1TypeParams`.

```
extArgNames = ["DS", "A"]        ← template-level extends type args
postG1TypeParams at i=3 = ["DS", "A", "B", "C"]   ← after @PermuteTypeParam
isPrefix? YES → new type args = "DS, A, B, C"
```

**New type args:** use the full `postG1TypeParams` list (not hardcoded T1..Tn).

### Detection branches (in order)

1. `allTNumber(extArgNames)` → hardcode `T1, T2, ..., T(newNum)` — preserves existing T+number behavior for templates without `@PermuteTypeParam`
2. `isPrefix(extArgNames, postG1TypeParams)` → use `postG1TypeParams` — handles alpha naming (requires `@PermuteTypeParam` to have fired)
3. Neither matches → skip (unchanged existing behaviour for unrelated classes)

**Why two branches:** Templates without `@PermuteTypeParam` (using implicit T+number expansion via `@PermuteParam`) have `postG1TypeParams` unchanged from template level at the time `applyExtendsExpansion` runs; the alpha branch wouldn't detect expansion. The `allTNumber` branch correctly handles this case by building the type args from the arity number directly.

---

## Worked Examples

### T+number (existing, same-N fix only)

```java
public static class Join1First<T1> extends Join1Second<T1> {}
```

| i | Generated class | Extends | Type args |
|---|---|---|---|
| 1 | `Join1First<T1>` | `Join1Second` | `T1` |
| 2 | `Join2First<T1, T2>` | `Join2Second` | `T1, T2` |
| 3 | `Join3First<T1, T2, T3>` | `Join3Second` | `T1, T2, T3` |

### Alpha naming (new)

```java
public static class Join0First<DS,
        @PermuteTypeParam(varName="k", from="1", to="${i}", name="${alpha(k)}") A>
        extends Join0Second<DS, A> {}
```

| i | Generated class | Extends | Type args |
|---|---|---|---|
| 1 | `Join1First<DS, A>` | `Join1Second` | `DS, A` |
| 2 | `Join2First<DS, A, B>` | `Join2Second` | `DS, A, B` |
| 3 | `Join3First<DS, A, B, C>` | `Join3Second` | `DS, A, B, C` |

---

## Implementation

### `InlineGenerator.applyExtendsExpansion()`

**Signature change:**
```java
private static void applyExtendsExpansion(
        ClassOrInterfaceDeclaration classDecl,
        String templateName,
        int templateEmbeddedNum,
        int currentEmbeddedNum,
        List<String> postG1TypeParams)   // NEW
```

**Formula change:** `int newNum = currentEmbeddedNum;` (was `currentEmbeddedNum + 1`)

**Detection logic (replace the `allTNumber` → skip block):**
```java
boolean allTNumber = extArgNames.stream().allMatch(InlineGenerator::isTNumberVar);
List<String> newTypeArgNames;
if (allTNumber) {
    // T+number case: build T1..T(newNum)
    newTypeArgNames = new ArrayList<>();
    for (int t = 1; t <= newNum; t++) newTypeArgNames.add("T" + t);
} else {
    // Alpha case: extends type args must be a prefix of postG1TypeParams
    boolean isPrefix = extArgNames.size() <= postG1TypeParams.size()
            && IntStream.range(0, extArgNames.size())
                        .allMatch(k -> extArgNames.get(k).equals(postG1TypeParams.get(k)));
    if (!isPrefix) continue;
    newTypeArgNames = postG1TypeParams;
}
String newTypeArgs = String.join(", ", newTypeArgNames);
```

### `InlineGenerator.generate()` call site

Convert the existing `Set<String> postG1TypeParams` (LinkedHashSet, already captured) to a `List<String>` and pass to `applyExtendsExpansion`:

```java
List<String> postG1TypeParamsList = new ArrayList<>(postG1TypeParams);
applyExtendsExpansion(generated, templateClassName, templateEmbeddedNum,
        currentEmbeddedNum, postG1TypeParamsList);
```

---

## Tests

### Update `PermuteMethodTest.testExtendsClauseImplicitExpansion()`

Change expected values from forward-reference to same-N:

```java
// at forI=1: Join1First extends Join1Second<T1>   (was Join2Second)
// at forI=2: Join2First extends Join2Second<T1, T2>  (was Join3Second<T1,T2,T3>)
assertThat(out1).contains("extends Join1Second");
assertThat(out1).contains("T1");     // single type arg
assertThat(out2).contains("extends Join2Second");
assertThat(out2).contains("T1, T2");
```

### Add `PermuteMethodTest.testExtendsClauseAlphaNaming()`

```java
@Test
public void testExtendsClauseAlphaNaming() {
    // Join0First<DS, A> extends Join0Second<DS, A> with @PermuteTypeParam alpha
    // i=1: Join1First<DS, A> extends Join1Second<DS, A>
    // i=2: Join2First<DS, A, B> extends Join2Second<DS, A, B>
    // i=3: Join3First<DS, A, B, C> extends Join3Second<DS, A, B, C>
    String template = """
            package com.example;
            public class Parent {
                public static class Join0First<DS,
                        @io.quarkiverse.permuplate.PermuteTypeParam(
                            varName="k", from="1", to="${i}", name="${alpha(k)}") A>
                        extends Join0Second<DS, A> {
                    public void filter() {}
                }
            }
            """;

    String out1 = generateInline(template, "Join0First", "i", 1, 3, "Join${i}First", 1);
    assertThat(out1).contains("Join1First<DS, A>");
    assertThat(out1).contains("extends Join1Second<DS, A>");

    String out2 = generateInline(template, "Join0First", "i", 1, 3, "Join${i}First", 2);
    assertThat(out2).contains("Join2First<DS, A, B>");
    assertThat(out2).contains("extends Join2Second<DS, A, B>");

    String out3 = generateInline(template, "Join0First", "i", 1, 3, "Join${i}First", 3);
    assertThat(out3).contains("Join3First<DS, A, B, C>");
    assertThat(out3).contains("extends Join3Second<DS, A, B, C>");
}
```

---

## Files Modified

| File | Change |
|---|---|
| `permuplate-maven-plugin/src/main/java/.../maven/InlineGenerator.java` | `applyExtendsExpansion()`: new param, formula fix, alpha branch |
| `permuplate-tests/src/test/java/.../PermuteMethodTest.java` | Update `testExtendsClauseImplicitExpansion`, add `testExtendsClauseAlphaNaming` |

No annotation changes. No APT processor changes. No new annotations. No CLAUDE.md changes beyond updating the G3 row.

---

## CLAUDE.md Update

The G3 entry in CLAUDE.md should be updated:

**Before:**
> Extends clause implicit expansion (G3) — `applyExtendsExpansion()` uses name-prefix family matching (everything before the first digit) + embedded number match to detect sibling classes. Third-party classes with different name prefixes are safely skipped. Prefix family `"Join"` expands; `"External"` does not.

**After:**
> Extends clause implicit expansion (G3) — `applyExtendsExpansion()` uses name-prefix family matching + embedded number match to detect sibling classes. Third-party classes are safely skipped. Generates same-N extends (`JoinNFirst extends JoinNSecond`). Two detection branches: (1) all-T+number type args → hardcodes `T1..TN`; (2) extends type args are a prefix of post-G1 type params → uses full post-G1 list (supports alpha naming when `@PermuteTypeParam` fires first). Both branches use `newNum = currentEmbeddedNum` (same-N formula).
