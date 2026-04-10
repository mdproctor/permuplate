# G4 — Method-Level @PermuteTypeParam Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable `@PermuteTypeParam` on method-level type parameters so that named method series like `path${k}()` can have growing method type parameter lists (e.g., `<PB>` for k=2, `<PB, PC>` for k=3, ...).

**Architecture:** `PermuteTypeParamTransformer` already handles class-level type parameter expansion via `expandExplicit()` and `buildTypeParam()`. A new `public static transformMethod(MethodDeclaration, EvaluationContext, Messager, Element)` method reuses the same private helpers but operates on `MethodDeclaration.getTypeParameters()` instead of the class. It is called inside `applyPermuteMethod()` (InlineGenerator) and `applyPermuteMethodApt()` (PermuteProcessor) for each clone, using the (i,k) inner context — so `@PermuteTypeParam(to="${k-1}")` correctly references the @PermuteMethod loop variable.

**Tech Stack:** JavaParser, Google compile-testing (tests), JUnit 4, EvaluationContext (N4 functions available including `alpha(j)`).

**Already implemented (no work needed):**
- `@PermuteMethod.name` — working in both InlineGenerator and APT  
- `@PermuteReturn.typeArgs` — working in both InlineGenerator and APT

---

## Key discovery: `expandExplicit()` is already private but all helpers are accessible within the class

`PermuteTypeParamTransformer.expandExplicit()` works on `ClassOrInterfaceDeclaration`. The new `transformMethod()` needs to do the same on `MethodDeclaration`. Since all helpers (`buildTypeParam`, `findTypeParamAnnotation`, `getAttr`, R3/R4 checks) are private statics in the same class, `transformMethod()` simply calls them directly — no duplication needed.

---

## File Map

| File | Change |
|---|---|
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java` | **Modify** — add `public static transformMethod()` |
| `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | **Modify** — call `transformMethod()` on each clone inside `applyPermuteMethod()` |
| `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | **Modify** — call `transformMethod()` in `applyPermuteMethodApt()` |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java` | **Modify** — add method-level @PermuteTypeParam tests |

---

## Task 1: Add `transformMethod()` to `PermuteTypeParamTransformer`

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java`

- [ ] **Step 1: Read the existing class to understand the helpers**

```bash
cat /Users/mdproctor/claude/permuplate/.worktrees/feature/g4-method-type-params/permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java
```

The key private helpers already available:
- `buildTypeParam(String newName, TypeParameter sentinel, String sentinelName)` — creates a new TypeParameter with substituted bounds
- `findTypeParamAnnotation(TypeParameter tp)` — finds `@PermuteTypeParam` on a type param
- `getAttr(NormalAnnotationExpr ann, String attrName)` — reads annotation attribute value

- [ ] **Step 2: Add `transformMethod()` after the `transform()` method**

Add this method immediately after the `transform()` method (around line 80):

```java
    /**
     * Expands method-level type parameters annotated with {@code @PermuteTypeParam}.
     * Used by {@code applyPermuteMethod()} in both InlineGenerator and PermuteProcessor
     * to expand the sentinel method's type parameters with the inner {@code (i,k)} context.
     *
     * <p>This is the method-level equivalent of {@link #transform} for class-level type params.
     * The {@code ctx} must include the {@code @PermuteMethod} inner variable (e.g., {@code k})
     * so expressions like {@code to="${k-1}"} evaluate correctly.
     *
     * @param method  the cloned method declaration being transformed (modified in-place)
     * @param ctx     the inner context containing both outer {@code i} and inner {@code k}
     * @param messager for error reporting; {@code null} in Maven plugin mode
     * @param element  the annotated element for error location; {@code null} in Maven plugin mode
     * @return names of the sentinel type parameters that were expanded
     */
    public static Set<String> transformMethod(MethodDeclaration method,
            EvaluationContext ctx,
            Messager messager,
            Element element) {

        NodeList<TypeParameter> current = method.getTypeParameters();
        NodeList<TypeParameter> result = new NodeList<>();
        Set<String> expanded = new HashSet<>();

        for (TypeParameter tp : current) {
            Optional<NormalAnnotationExpr> ann = findTypeParamAnnotation(tp);
            if (ann.isEmpty()) {
                result.add(tp);
                continue;
            }

            NormalAnnotationExpr normal = ann.get();
            String varName = getAttr(normal, "varName");
            String fromStr = getAttr(normal, "from");
            String toStr   = getAttr(normal, "to");
            String nameTemplate = getAttr(normal, "name");
            String sentinelName = tp.getNameAsString();

            // R4: evaluate range
            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(fromStr);
                toVal   = ctx.evaluateInt(toStr);
            } catch (Exception e) {
                if (messager != null) messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteTypeParam: cannot evaluate range: " + e.getMessage(), element);
                result.add(tp);
                continue;
            }
            if (fromVal > toVal) {
                if (messager != null) messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteTypeParam has invalid range: from=" + fromVal
                                + " is greater than to=" + toVal, element);
                result.add(tp);
                continue;
            }

            // R3: name leading literal must be a prefix of sentinel name
            String leadingLiteral = nameTemplate.contains("${")
                    ? nameTemplate.substring(0, nameTemplate.indexOf("${"))
                    : nameTemplate;
            if (!leadingLiteral.isEmpty() && !sentinelName.startsWith(leadingLiteral)) {
                if (messager != null) messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteTypeParam name literal part \"" + leadingLiteral
                                + "\" is not a prefix of type parameter \"" + sentinelName + "\"",
                        element);
                result.add(tp);
                continue;
            }

            // Expand: generate one TypeParameter per j value
            expanded.add(sentinelName);
            for (int j = fromVal; j <= toVal; j++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, j);
                String newName = innerCtx.evaluate(nameTemplate);
                result.add(buildTypeParam(newName, tp, sentinelName));
            }
        }

        method.setTypeParameters(result);
        return expanded;
    }
```

You will also need `import com.github.javaparser.ast.body.MethodDeclaration;` — check if it's already imported:
```bash
grep "MethodDeclaration" /Users/mdproctor/claude/permuplate/.worktrees/feature/g4-method-type-params/permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java | head -3
```

It is already imported (added during G2a). No import change needed.

- [ ] **Step 3: Build to confirm it compiles**

```bash
cd /Users/mdproctor/claude/permuplate/.worktrees/feature/g4-method-type-params
/opt/homebrew/bin/mvn clean install -DskipTests -pl permuplate-core -am --no-transfer-progress
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java
git commit -m "feat(g4): add transformMethod() to PermuteTypeParamTransformer for method-level type param expansion"
```

---

## Task 2: Write failing tests for method-level `@PermuteTypeParam`

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java`

- [ ] **Step 1: Add tests to `PermuteMethodTest`**

Add these tests at the end of `PermuteMethodTest` (before the closing `}`):

```java
    // =========================================================================
    // Method-level @PermuteTypeParam — G4
    // =========================================================================

    @Test
    public void testMethodLevelTypeParamExplicit() {
        // @PermuteMethod(k=2..4, name="path${k}") + @PermuteTypeParam(j=1..k-1, name="P${j}")
        // k=2: path2() with <P1>
        // k=3: path3() with <P1, P2>
        // k=4: path4() with <P1, P2, P3>
        String template = """
                package com.example;
                public class Parent {
                    public static class Step1<T1> {
                        @io.quarkiverse.permuplate.PermuteMethod(varName="k", from="2", to="4", name="path${k}")
                        public <@io.quarkiverse.permuplate.PermuteTypeParam(varName="j", from="1", to="${k-1}", name="P${j}") PB>
                               Object path2() { return null; }
                    }
                }
                """;

        // k=2: path2() with <P1>
        String out = generateInline(template, "Step1", "i", 1, 1, "Step${i}", 1);
        assertThat(out).contains("<P1>");
        assertThat(out).contains("path2()");

        // k=3: path3() with <P1, P2>
        assertThat(out).contains("<P1, P2>");
        assertThat(out).contains("path3()");

        // k=4: path4() with <P1, P2, P3>
        assertThat(out).contains("<P1, P2, P3>");
        assertThat(out).contains("path4()");

        // No annotation residue
        assertThat(out).doesNotContain("@PermuteTypeParam");
        assertThat(out).doesNotContain("@PermuteMethod");
    }

    @Test
    public void testMethodLevelTypeParamEmptyRange() {
        // When k=2, to="${k-1}"=1, j range=1..1 → one type param P1
        // When k=2 is the minimum, this is the simplest non-empty case
        String template = """
                package com.example;
                public class Parent {
                    public static class Base1<T1> {
                        @io.quarkiverse.permuplate.PermuteMethod(varName="k", from="2", to="3", name="step${k}")
                        public <@io.quarkiverse.permuplate.PermuteTypeParam(varName="j", from="1", to="${k-1}", name="T${j}") A>
                               Object step2() { return null; }
                    }
                }
                """;

        String out = generateInline(template, "Base1", "i", 1, 1, "Base${i}", 1);
        // k=2: step2<T1>
        assertThat(out).contains("step2");
        assertThat(out).contains("<T1>");
        // k=3: step3<T1, T2>
        assertThat(out).contains("step3");
        assertThat(out).contains("<T1, T2>");
    }

    @Test
    public void testMethodLevelTypeParamWithAlphaNaming() {
        // alpha(j) naming: k=2 → <B>, k=3 → <B, C>, k=4 → <B, C, D>
        String template = """
                package com.example;
                public class Parent {
                    public static class Join1<T1> {
                        @io.quarkiverse.permuplate.PermuteMethod(varName="k", from="2", to="4", name="path${k}")
                        public <@io.quarkiverse.permuplate.PermuteTypeParam(varName="j", from="1", to="${k-1}", name="${alpha(j+1)}") PB>
                               Object path2() { return null; }
                    }
                }
                """;

        String out = generateInline(template, "Join1", "i", 1, 1, "Join${i}", 1);
        // k=2: path2<B>
        assertThat(out).contains("path2");
        assertThat(out).contains("<B>");
        // k=3: path3<B, C>
        assertThat(out).contains("path3");
        assertThat(out).contains("<B, C>");
        // k=4: path4<B, C, D>
        assertThat(out).contains("path4");
        assertThat(out).contains("<B, C, D>");
    }
```

- [ ] **Step 2: Verify tests fail**

```bash
cd /Users/mdproctor/claude/permuplate/.worktrees/feature/g4-method-type-params
/opt/homebrew/bin/mvn test -pl permuplate-tests -am --no-transfer-progress 2>&1 | grep -E "FAIL|ERROR|Tests run|BUILD" | tail -5
```

Expected: BUILD FAILURE — 3 new tests fail because `transformMethod()` is not yet called in `applyPermuteMethod()`.

- [ ] **Step 3: Commit failing tests**

```bash
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java
git commit -m "test(g4): add failing tests for method-level @PermuteTypeParam (TDD)"
```

---

## Task 3: Wire `transformMethod()` into InlineGenerator and PermuteProcessor

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

### InlineGenerator

- [ ] **Step 1: Find `applyPermuteMethod()` in InlineGenerator**

```bash
grep -n "applyPermuteMethod\|Strip @Perm\|processMethodParam\|clone.set\|clone = tmp" /Users/mdproctor/claude/permuplate/.worktrees/feature/g4-method-type-params/permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java | head -20
```

- [ ] **Step 2: Call `transformMethod()` on each clone before @PermuteReturn processing**

Inside the `for (int j = fromVal; j <= toVal; j++)` loop in `applyPermuteMethod()`, find where the clone is created and `@PermuteMethod` is stripped. Immediately after stripping `@PermuteMethod`, add:

```java
                // Expand method-level @PermuteTypeParam with the (i,k) inner context
                io.quarkiverse.permuplate.core.PermuteTypeParamTransformer
                        .transformMethod(clone, innerCtx, null, null);
```

This must come BEFORE the `tmpClass` wrapper that calls `applyPermuteReturn()`, because `@PermuteTypeParam` expansion changes the method signature that @PermuteReturn will reference.

Also, `@PermuteTypeParam` must be added to the set of stripped annotations on the clone. Find where `@PermuteMethod` is stripped:
```java
clone.getAnnotations().removeIf(a ->
        a.getNameAsString().equals(PM_SIMPLE) || a.getNameAsString().equals(PM_FQ));
```

The individual method-level `@PermuteTypeParam` annotations are ON THE TYPE PARAMETERS, not on the method. After `transformMethod()` runs, the sentinel TypeParameter (which had `@PermuteTypeParam`) is replaced by freshly constructed TypeParameters with no annotations — so the annotation disappears by construction. No explicit strip needed.

### PermuteProcessor

- [ ] **Step 3: Call `transformMethod()` in `applyPermuteMethodApt()`**

Find `applyPermuteMethodApt()` in `PermuteProcessor`:
```bash
grep -n "applyPermuteMethodApt\|Strip @PermuteMethod\|clone.setName\|nameTempl\|toAdd.add" /Users/mdproctor/claude/permuplate/.worktrees/feature/g4-method-type-params/permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java | head -15
```

Inside the j-loop in `applyPermuteMethodApt()`, after stripping `@PermuteMethod` from the clone, add:

```java
                // Expand method-level @PermuteTypeParam with the (i,k) inner context
                io.quarkiverse.permuplate.core.PermuteTypeParamTransformer
                        .transformMethod(clone, innerCtx, element != null ? processingEnv.getMessager() : null, element);
```

Wait — in `applyPermuteMethodApt()`, the element is passed as a `TypeElement`. Use `processingEnv.getMessager()` for the messager. The call is:

```java
                io.quarkiverse.permuplate.core.PermuteTypeParamTransformer
                        .transformMethod(clone, innerCtx, processingEnv.getMessager(), element);
```

- [ ] **Step 4: Run full test suite — all tests should pass**

```bash
cd /Users/mdproctor/claude/permuplate/.worktrees/feature/g4-method-type-params
/opt/homebrew/bin/mvn clean install --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD|FAIL" | tail -5
```

Expected: BUILD SUCCESS. All 3 new `PermuteMethodTest` tests pass. Total ~122 tests, 0 failures, 1 skipped.

If tests fail, check:
- The `innerCtx` passed to `transformMethod()` must contain the `@PermuteMethod` loop variable (k). Verify this is the context from `ctx.withVariable(pmCfg.varName(), j)`.
- The `to="${k-1}"` expression: `k` must be in `innerCtx`. If `pmCfg.varName()` is `"k"` and `j` is the current value, then `innerCtx = ctx.withVariable("k", j)` and `innerCtx.evaluateInt("${k-1}")` = `j-1`. This is correct.

- [ ] **Step 5: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
        permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git commit -m "feat(g4): wire transformMethod() into applyPermuteMethod() and applyPermuteMethodApt()

Method-level @PermuteTypeParam now expands method type parameters using the
inner (i,k) context. Sentinel type param (e.g. PB) is replaced by j=from..to
type params per @PermuteTypeParam(to='${k-1}'). Works in both Maven plugin
inline mode and APT explicit mode. Annotation disappears by construction
(sentinel TypeParameter replaced by fresh ones with no annotations)."
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task | Status |
|---|---|---|
| `@PermuteMethod.name` — distinct method names | Already implemented in G3 | ✓ done |
| `@PermuteReturn.typeArgs` — full JEXL type arg list | Already implemented in G2b | ✓ done |
| Method-level `@PermuteTypeParam` in `PermuteTypeParamTransformer` | Task 1 | ✓ |
| Method-level type param expansion in InlineGenerator `applyPermuteMethod()` | Task 3 | ✓ |
| Method-level type param expansion in APT `applyPermuteMethodApt()` | Task 3 | ✓ |
| Bounds propagation (same `buildTypeParam()` helper) | Task 1 (reuses existing) | ✓ |
| R3 validation (name prefix check) | Task 1 | ✓ |
| R4 validation (from > to) | Task 1 | ✓ |
| `alpha(j)` works in method-level `@PermuteTypeParam.name` | Task 2 (test 3 covers this) | ✓ |

**Deferred (out of scope):**
- Method-level implicit expansion (when return type references method type params) — `transformMethod()` only handles explicit @PermuteTypeParam
- Three-level nesting validation (no R2 check for method-level duplicate expansion)

**Placeholder scan:** None found.

**Type consistency:** `transformMethod(MethodDeclaration, EvaluationContext, Messager, Element)` — consistent with `transform(ClassOrInterfaceDeclaration, EvaluationContext, Messager, Element)` signature pattern.
