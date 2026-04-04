# G3 Alpha-Naming Extends Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix G3 extends clause expansion to produce same-N extends (`JoinNFirst extends JoinNSecond`) and support alpha-named type parameters (`A, B, C`) in addition to T+number.

**Architecture:** Two changes to `applyExtendsExpansion()` in `InlineGenerator.java`: (1) change `newNum = currentEmbeddedNum + 1` to `newNum = currentEmbeddedNum` for same-N behavior; (2) add an alpha branch that uses the already-captured `postG1TypeParams` list as a prefix-match oracle. The existing `allTNumber` branch is preserved for backwards compatibility. No new annotations, no APT changes.

**Tech Stack:** Java, JavaParser (AST manipulation), JUnit 4, Google Truth assertions. Build: `/opt/homebrew/bin/mvn clean install`. Module under test: `permuplate-tests`.

---

## File Map

| File | Change |
|---|---|
| `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | Fix formula in `applyExtendsExpansion()`, add alpha branch, add `postG1TypeParams` param; fix call site in `generate()` |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java` | Update `testExtendsClauseImplicitExpansion` + add `testExtendsClauseAlphaNaming` |
| `CLAUDE.md` | Update the G3 row in the key decisions table |

---

## Task 1: Fix same-N formula — update existing test first, then fix the code

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java:101-136`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java:416-472` (applyExtendsExpansion) and lines 112-114 (call site)

- [ ] **Step 1: Update `testExtendsClauseImplicitExpansion` to expect same-N behavior**

In `PermuteMethodTest.java`, replace the body of `testExtendsClauseImplicitExpansion` (lines 101-121) with:

```java
@Test
public void testExtendsClauseImplicitExpansion() {
    // Same-N: JoinNFirst extends JoinNSecond with N type args (not forward-reference)
    String template = """
            package com.example;
            public class Parent {
                public static class Join1First<T1> extends Join1Second<T1> {
                    public void filter() {}
                }
            }
            """;

    // forI=1: Join1First<T1> extends Join1Second<T1>
    String out1 = generateInline(template, "Join1First", "i", 1, 4, "Join${i}First", 1);
    assertThat(out1).contains("extends Join1Second");
    assertThat(out1).contains("T1");
    assertThat(out1).doesNotContain("T2");

    // forI=2: Join2First<T1, T2> extends Join2Second<T1, T2>
    String out2 = generateInline(template, "Join1First", "i", 1, 4, "Join${i}First", 2);
    assertThat(out2).contains("extends Join2Second");
    assertThat(out2).contains("T1, T2");
    assertThat(out2).doesNotContain("T3");

    // forI=3: Join3First<T1, T2, T3> extends Join3Second<T1, T2, T3>
    String out3 = generateInline(template, "Join1First", "i", 1, 4, "Join${i}First", 3);
    assertThat(out3).contains("extends Join3Second");
    assertThat(out3).contains("T1, T2, T3");
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test --no-transfer-progress -pl permuplate-tests -Dtest=PermuteMethodTest#testExtendsClauseImplicitExpansion 2>&1 | tail -20
```

Expected: FAIL — the assertions `doesNotContain("T2")` at forI=1 will fail because the old code produces `Join2Second<T1, T2>` (forward reference), and `doesNotContain("T3")` at forI=2 will fail because it produces `Join3Second<T1, T2, T3>`.

- [ ] **Step 3: Fix `applyExtendsExpansion()` — change formula and add `postG1TypeParams` parameter**

In `InlineGenerator.java`, replace the entire `applyExtendsExpansion` method (lines ~416-472) with:

```java
/**
 * Expands the extends/implements clause of a generated class to match the current arity.
 *
 * <p>Fires only when both the template class name and the generated class name contain
 * an embedded number (the arity discriminator). Only expands extends base classes that
 * share the same name prefix and the template's embedded number.
 *
 * <p>Two detection branches:
 * <ol>
 *   <li>All-T+number type args → hardcodes T1..T(newNum) (existing T+number behaviour)</li>
 *   <li>Extends type args are a prefix of postG1TypeParams → uses full postG1TypeParams
 *       list (alpha naming support; requires {@code @PermuteTypeParam} to have fired)</li>
 * </ol>
 *
 * <p>Formula: {@code newNum = currentEmbeddedNum} — produces same-N extends
 * (Join2First extends Join2Second, not forward-reference Join3Second).
 */
private static void applyExtendsExpansion(ClassOrInterfaceDeclaration classDecl,
        String templateName,
        int templateEmbeddedNum,
        int currentEmbeddedNum,
        List<String> postG1TypeParams) {

    String templateNamePrefix = prefixBeforeFirstDigit(templateName);
    int newNum = currentEmbeddedNum;  // same-N: Join2First → extends Join2Second

    com.github.javaparser.ast.NodeList<com.github.javaparser.ast.type.ClassOrInterfaceType> extended = classDecl
            .getExtendedTypes();
    for (int idx = 0; idx < extended.size(); idx++) {
        com.github.javaparser.ast.type.ClassOrInterfaceType ext = extended.get(idx);
        String baseName = ext.getNameAsString();

        // Guard: only expand if the base class shares the same prefix-before-digit
        // as the template class. This prevents incorrectly expanding third-party
        // classes (e.g. External1Lib) that happen to share the template's embedded digit.
        if (!prefixBeforeFirstDigit(baseName).equals(templateNamePrefix))
            continue;

        // Only expand if extends base class has same first embedded number as the template
        int extNum = firstEmbeddedNumber(baseName);
        if (extNum < 0 || extNum != templateEmbeddedNum)
            continue;

        java.util.Optional<com.github.javaparser.ast.NodeList<com.github.javaparser.ast.type.Type>> typeArgsOpt = ext
                .getTypeArguments();
        if (typeArgsOpt.isEmpty() || typeArgsOpt.get().isEmpty())
            continue;

        List<String> extArgNames = typeArgsOpt.get().stream()
                .map(com.github.javaparser.ast.type.Type::asString)
                .collect(java.util.stream.Collectors.toList());

        // Determine new type args via one of two detection branches.
        List<String> newTypeArgNames;
        boolean allTNumber = extArgNames.stream().allMatch(InlineGenerator::isTNumberVar);
        if (allTNumber) {
            // T+number case: build T1..T(newNum) from scratch
            newTypeArgNames = new java.util.ArrayList<>();
            for (int t = 1; t <= newNum; t++)
                newTypeArgNames.add("T" + t);
        } else {
            // Alpha case: extends type args must be a prefix of postG1TypeParams.
            // This fires when @PermuteTypeParam has already expanded the class type
            // params (postG1TypeParams is longer than the template-level extends args).
            boolean isPrefix = extArgNames.size() <= postG1TypeParams.size()
                    && java.util.stream.IntStream.range(0, extArgNames.size())
                              .allMatch(k -> extArgNames.get(k).equals(postG1TypeParams.get(k)));
            if (!isPrefix)
                continue;
            newTypeArgNames = postG1TypeParams;
        }

        // Build new base class name: replace embedded number with newNum
        String newBaseName = replaceFirstEmbeddedNumber(baseName, newNum);
        String newExtStr = newBaseName + "<" + String.join(", ", newTypeArgNames) + ">";
        try {
            extended.set(idx, StaticJavaParser.parseClassOrInterfaceType(newExtStr));
        } catch (Exception ignored) {
        }
    }
}
```

- [ ] **Step 4: Fix the call site in `generate()` to pass `postG1TypeParams` as a list**

In `InlineGenerator.java`, find the block at lines ~109-114 that calls `applyExtendsExpansion`:

```java
// OLD:
int templateEmbeddedNum = firstEmbeddedNumber(templateClassName);
int currentEmbeddedNum = firstEmbeddedNumber(newClassName);
if (templateEmbeddedNum >= 0 && currentEmbeddedNum >= 0) {
    applyExtendsExpansion(generated, templateClassName, templateEmbeddedNum, currentEmbeddedNum);
}
```

Replace with:

```java
// NEW: pass postG1TypeParams as a List (LinkedHashSet preserves declaration order)
int templateEmbeddedNum = firstEmbeddedNumber(templateClassName);
int currentEmbeddedNum = firstEmbeddedNumber(newClassName);
if (templateEmbeddedNum >= 0 && currentEmbeddedNum >= 0) {
    applyExtendsExpansion(generated, templateClassName, templateEmbeddedNum, currentEmbeddedNum,
            new java.util.ArrayList<>(postG1TypeParams));
}
```

(`postG1TypeParams` is the `Set<String>` already captured at line 91-92 just above this block.)

- [ ] **Step 5: Run the updated test and confirm it passes**

```bash
/opt/homebrew/bin/mvn test --no-transfer-progress -pl permuplate-tests -Dtest=PermuteMethodTest#testExtendsClauseImplicitExpansion 2>&1 | tail -10
```

Expected: PASS

- [ ] **Step 6: Run the full test suite to confirm no regressions**

```bash
/opt/homebrew/bin/mvn test --no-transfer-progress -pl permuplate-tests 2>&1 | grep -E "Tests run|BUILD" | tail -5
```

Expected: BUILD SUCCESS, all existing tests pass.

- [ ] **Step 7: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java
git commit -m "fix(g3): same-N extends formula + postG1TypeParams param (was currentEmbeddedNum+1)"
```

---

## Task 2: Add alpha naming support — new test first, then implement

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java` (add new test)
- The alpha branch is already implemented in `applyExtendsExpansion()` from Task 1 — this task verifies it works end-to-end with `@PermuteTypeParam`.

- [ ] **Step 1: Add `testExtendsClauseAlphaNaming` to `PermuteMethodTest`**

Add after `testExtendsClauseUnchangedWhenNotInGeneratedSet`:

```java
@Test
public void testExtendsClauseAlphaNaming() {
    // Join0First<DS, A> extends Join0Second<DS, A> with @PermuteTypeParam alpha naming.
    // G1 (@PermuteTypeParam) expands the class type params first (A → A,B,C,...).
    // G3 then sees postG1TypeParams = [DS, A, B, ...] and expands the extends clause.
    //
    // i=1: Join1First<DS, A>        extends Join1Second<DS, A>
    // i=2: Join2First<DS, A, B>     extends Join2Second<DS, A, B>
    // i=3: Join3First<DS, A, B, C>  extends Join3Second<DS, A, B, C>
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

- [ ] **Step 2: Run the new test**

```bash
/opt/homebrew/bin/mvn test --no-transfer-progress -pl permuplate-tests -Dtest=PermuteMethodTest#testExtendsClauseAlphaNaming 2>&1 | tail -15
```

Expected: PASS — the alpha branch added in Task 1 handles this case.

If it fails, diagnose:
- Does `out1` show the class type params expanded correctly (`DS, A, B, ...`)? If not, `@PermuteTypeParam` didn't fire — check that the annotation name `io.quarkiverse.permuplate.PermuteTypeParam` is recognized by `PermuteTypeParamTransformer`.
- Does `out1` show the right extends? If not, the prefix check in `applyExtendsExpansion` isn't matching — check that `postG1TypeParams` is populated (G1 must have fired before G3).

- [ ] **Step 3: Run the full test suite**

```bash
/opt/homebrew/bin/mvn test --no-transfer-progress -pl permuplate-tests 2>&1 | grep -E "Tests run|BUILD" | tail -5
```

Expected: BUILD SUCCESS, all tests pass including the new one.

- [ ] **Step 4: Commit**

```bash
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java
git commit -m "test(g3): add testExtendsClauseAlphaNaming — alpha type params expand in extends clause"
```

---

## Task 3: Update CLAUDE.md and run full build

**Files:**
- Modify: `CLAUDE.md` (line 181)

- [ ] **Step 1: Update the G3 row in CLAUDE.md**

Find the line in the "Key non-obvious decisions and past bugs" table (line ~181):

```
| Extends clause implicit expansion (G3) | `applyExtendsExpansion()` uses name-prefix family matching (everything before the first digit) + embedded number match to detect sibling classes. Third-party classes with different name prefixes are safely skipped. Prefix family `"Join"` expands; `"External"` does not. |
```

Replace with:

```
| Extends clause implicit expansion (G3) | `applyExtendsExpansion()` uses name-prefix family matching + embedded number match to detect sibling classes. Third-party classes are safely skipped. Generates same-N extends (`JoinNFirst extends JoinNSecond`). Two detection branches: (1) all-T+number type args → hardcodes `T1..TN`; (2) extends type args are a prefix of post-G1 type params → uses full post-G1 list (supports alpha naming when `@PermuteTypeParam` fires first). Both branches use `newNum = currentEmbeddedNum` (same-N formula). |
```

- [ ] **Step 2: Run the full project build**

```bash
/opt/homebrew/bin/mvn clean install --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD" | tail -8
```

Expected: BUILD SUCCESS, all 129+ tests pass.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(g3): update CLAUDE.md — same-N formula, alpha naming, two detection branches"
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task | Status |
|---|---|---|
| Fix `newNum = currentEmbeddedNum` (same-N formula) | Task 1 | ✓ |
| Add `List<String> postG1TypeParams` parameter | Task 1 | ✓ |
| Pass `postG1TypeParams` from call site | Task 1 | ✓ |
| Keep `allTNumber` branch for backwards compat | Task 1 | ✓ |
| Add alpha branch (prefix check against postG1TypeParams) | Task 1 | ✓ |
| Update `testExtendsClauseImplicitExpansion` to same-N values | Task 1 | ✓ |
| Add `testExtendsClauseAlphaNaming` | Task 2 | ✓ |
| Update CLAUDE.md G3 row | Task 3 | ✓ |

**Placeholder scan:** None found. All code is complete.

**Type consistency:** `postG1TypeParams` is `Set<String>` at capture point, passed as `new ArrayList<>(postG1TypeParams)` — consistent with `List<String>` parameter type throughout.
