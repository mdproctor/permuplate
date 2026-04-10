# G2a — @PermuteDeclr on Method Parameters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable `@PermuteDeclr` on method parameters so that a parameter's declared type (and optionally name) can be replaced per permutation — the prerequisite for APT-mode `@PermuteReturn` templates.

**Architecture:** `@PermuteDeclr` already targets `ElementType.PARAMETER` (the annotation is already correct). Two changes needed: (1) make the `name` attribute optional (`default ""`), and (2) add `transformMethodParams()` to `PermuteDeclrTransformer` and call it from `transform()`. When `name` is empty, only the type changes; when non-empty, type AND name change with body rename propagation. Both APT and Maven plugin automatically benefit — `PermuteDeclrTransformer.transform()` is already called in both pipelines.

**Tech Stack:** JavaParser (AST manipulation), Google compile-testing (tests), JUnit 4.

---

## Key Discovery

`@PermuteDeclr` already has `ElementType.PARAMETER` in its `@Target`. The annotation target change is **already done**. What remains:
1. `name()` has no default — make it `default ""`
2. `extractTwoParams()` requires both `type` and `name` — update to allow `name` absent (defaults to `""`)
3. No `transformMethodParams()` method exists — add it
4. `validatePrefixes()` only validates fields and constructor params — add method param validation

## File Map

| File | Change |
|---|---|
| `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDeclr.java` | Add `default ""` to `name()` attribute; update Javadoc |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java` | Update `extractTwoParams()` to handle absent `name`; add `transformMethodParams()`; call it from `transform()` |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDeclrTest.java` | Add method parameter tests |

---

## Task 1: Make `name` optional in `@PermuteDeclr`

**Files:**
- Modify: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDeclr.java`

- [ ] **Step 1: Update the annotation**

Replace the entire file with:

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Renames a declaration's type and (optionally) identifier for each permutation,
 * and propagates the rename to all usages of the original name within the
 * declaration's scope.
 *
 * <p>
 * Supported placements and their rename scope:
 * <ul>
 * <li><b>Field</b> — entire class body (all methods and constructors)</li>
 * <li><b>Constructor parameter</b> — the constructor body only</li>
 * <li><b>For-each loop variable</b> — the loop body only</li>
 * <li><b>Method parameter</b> — the method body only; name rename is optional</li>
 * </ul>
 *
 * <p>
 * Fields are processed before constructor parameters, and constructor parameters
 * before for-each variables, so that broader-scope renames are already applied
 * when narrower scopes are walked.
 *
 * <p>
 * Both {@code type} and {@code name} (when non-empty) support {@code ${varName}}
 * interpolation and arithmetic expressions such as {@code ${i-1}}. The static
 * (non-{@code ${...}}) part of each must be a prefix of the actual declaration's
 * type and name respectively — a mismatch is reported as a compile error.
 *
 * <p>
 * For <b>method parameters</b>, {@code name} may be omitted (defaults to {@code ""})
 * to change only the type, leaving the parameter name unchanged. This is the common
 * case in APT mode where the sentinel parameter uses {@code Object} as its type:
 *
 * <pre>{@code
 * // APT mode: only the type changes, name stays "src"
 * public Object join(@PermuteDeclr(type="Source<T${i+1}>") Object src) { ... }
 * }</pre>
 *
 * <p>
 * Examples:
 *
 * <pre>{@code
 * // Field:
 * private @PermuteDeclr(type="Callable${i}", name="c${i}") Callable2 c2;
 *
 * // Constructor parameter:
 * public Join2(@PermuteDeclr(type="Callable${i}", name="c${i}") Callable2 c2) { ... }
 *
 * // For-each loop variable:
 * for (@PermuteDeclr(type="Object", name="o${i}") Object o2 : right) { ... }
 *
 * // Method parameter (type only — name unchanged):
 * public Object join(@PermuteDeclr(type="Source<T${i+1}>") Object src) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.PARAMETER })
public @interface PermuteDeclr {
    String type();

    /** New parameter name template. Empty string (the default) means keep the original name. */
    String name() default "";
}
```

- [ ] **Step 2: Build annotations module**

```bash
/opt/homebrew/bin/mvn clean install -DskipTests -pl permuplate-annotations -am --no-transfer-progress
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDeclr.java
git commit -m "feat(g2a): make @PermuteDeclr name optional (default \"\") for method params"
```

---

## Task 2: Write failing tests for method parameter handling

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDeclrTest.java`

- [ ] **Step 1: Add tests to the existing `PermuteDeclrTest` class**

Read the existing test class first to find a good place to add:
```bash
tail -20 permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDeclrTest.java
```

Add these three tests at the end of the class (before the closing `}`):

```java
    // -------------------------------------------------------------------------
    // Method parameter @PermuteDeclr — G2a
    // -------------------------------------------------------------------------

    /**
     * @PermuteDeclr on a method parameter changes the type only (name unchanged).
     * name="" (default) means only the type is replaced.
     */
    @Test
    public void testMethodParamTypeonlyNorename() {
        // @PermuteDeclr(type="Source<T${i+1}>") on Object src — only type changes
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.ChainStep2",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteDeclr;
                @Permute(varName="i", from=2, to=3, className="ChainStep${i}")
                public class ChainStep2 {
                    public Object join(@PermuteDeclr(type="Source<T${i+1}>") Object src) {
                        return null;
                    }
                }
                """);

        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src2 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.ChainStep2")
                .orElseThrow());
        // Type changed to Source<T3>, name unchanged as "src"
        assertThat(src2).contains("Object join(Source<T3> src)");
        assertThat(src2).doesNotContain("@PermuteDeclr");

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.ChainStep3")
                .orElseThrow());
        // Type changed to Source<T4>, name unchanged as "src"
        assertThat(src3).contains("Object join(Source<T4> src)");
    }

    /**
     * @PermuteDeclr on a method parameter with name specified — both type and name change,
     * and the new name is propagated throughout the method body.
     */
    @Test
    public void testMethodParamTypeAndNameWithBodyPropagation() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.RenameParam2",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteDeclr;
                @Permute(varName="i", from=3, to=3, className="RenameParam${i}")
                public class RenameParam2 {
                    public String process(
                            @PermuteDeclr(type="Object", name="item${i}") Object item2) {
                        return item2.toString();
                    }
                }
                """);

        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.RenameParam3")
                .orElseThrow());
        // Parameter renamed to item3
        assertThat(src).contains("Object item3");
        // Body reference renamed from item2 to item3
        assertThat(src).contains("item3.toString()");
        assertThat(src).doesNotContain("item2");
        assertThat(src).doesNotContain("@PermuteDeclr");
    }

    /**
     * Multiple @PermuteDeclr on multiple method parameters in the same method.
     */
    @Test
    public void testMultipleMethodParamDeclr() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.MultiParam2",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteDeclr;
                @Permute(varName="i", from=3, to=3, className="MultiParam${i}")
                public class MultiParam2 {
                    public void process(
                            @PermuteDeclr(type="TypeA${i}") Object paramA,
                            @PermuteDeclr(type="TypeB${i}") Object paramB) {
                    }
                }
                """);

        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.MultiParam3")
                .orElseThrow());
        assertThat(src).contains("TypeA3");
        assertThat(src).contains("TypeB3");
        assertThat(src).doesNotContain("@PermuteDeclr");
    }
```

- [ ] **Step 2: Verify tests fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am --no-transfer-progress 2>&1 | grep -E "FAIL|ERROR|Tests run|BUILD" | tail -5
```

Expected: BUILD FAILURE or test failures — `transformMethodParams` is not yet implemented.

- [ ] **Step 3: Commit failing tests**

```bash
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDeclrTest.java
git commit -m "test(g2a): add failing tests for @PermuteDeclr on method parameters (TDD)"
```

---

## Task 3: Implement method parameter handling in `PermuteDeclrTransformer`

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java`

- [ ] **Step 1: Update `extractTwoParams()` to handle absent `name`**

Find `extractTwoParams()` and replace the full method body:

```java
    /**
     * Extracts the {@code type} and {@code name} parameters from a
     * {@code @PermuteDeclr(type=..., name=...)} annotation.
     * {@code name} is optional — defaults to {@code ""} (keep original name).
     */
    static String[] extractTwoParams(AnnotationExpr ann, Messager messager) {
        if (ann instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;
            String type = null, name = ""; // name is optional, default ""
            for (MemberValuePair pair : normal.getPairs()) {
                String val = stripQuotes(pair.getValue().toString());
                if (pair.getNameAsString().equals("type"))
                    type = val;
                else if (pair.getNameAsString().equals("name"))
                    name = val;
            }
            if (type != null)
                return new String[] { type, name };
        }
        if (messager != null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@PermuteDeclr must specify type: @PermuteDeclr(type=\"...\") " +
                            "or @PermuteDeclr(type=\"...\", name=\"...\")");
        }
        return null;
    }
```

- [ ] **Step 2: Add `transformMethodParams()` method**

Add this method after `transformForEachVars()` (around line 173), before the `// Shared utilities` comment block:

```java
    // -------------------------------------------------------------------------
    // Method parameters (G2a)
    // -------------------------------------------------------------------------

    private static void transformMethodParams(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager) {
        classDecl.getMethods().forEach(method -> {
            // Snapshot to avoid ConcurrentModification
            List<Parameter> annotated = new ArrayList<>();
            method.getParameters().forEach(p -> {
                if (hasPermuteDeclr(p.getAnnotations()))
                    annotated.add(p);
            });

            for (Parameter param : annotated) {
                AnnotationExpr ann = getPermuteDeclr(param.getAnnotations());
                String[] params = extractTwoParams(ann, messager);
                if (params == null)
                    continue;

                String newType = ctx.evaluate(params[0]);
                String newName = params[1]; // "" = keep original name

                param.setType(new ClassOrInterfaceType(null, newType));
                param.getAnnotations().remove(ann);

                if (!newName.isEmpty()) {
                    // Name also changes — propagate rename within the method body
                    String oldName = param.getNameAsString();
                    param.setName(newName);
                    method.getBody().ifPresent(body -> renameAllUsages(body, oldName, newName));
                }
            }
        });
    }
```

- [ ] **Step 3: Call `transformMethodParams()` from `transform()`**

Find `transform()`:
```java
    public static void transform(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager) {
        // Fields first (broadest scope — entire class body)
        transformFields(classDecl, ctx, messager);
        // Constructor parameters (scope = constructor body)
        transformConstructorParams(classDecl, ctx, messager);
        // For-each variables (narrowest scope — loop body only)
        transformForEachVars(classDecl, ctx, messager);
    }
```

Replace with:
```java
    public static void transform(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager) {
        // Fields first (broadest scope — entire class body)
        transformFields(classDecl, ctx, messager);
        // Constructor parameters (scope = constructor body)
        transformConstructorParams(classDecl, ctx, messager);
        // For-each variables (narrowest scope — loop body only)
        transformForEachVars(classDecl, ctx, messager);
        // Method parameters (scope = method body; name rename is optional)
        transformMethodParams(classDecl, ctx, messager);
    }
```

- [ ] **Step 4: Run full test suite — all tests should pass**

```bash
/opt/homebrew/bin/mvn clean install --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD" | tail -3
```

Expected: BUILD SUCCESS. Should be 102 (existing) + 3 (new) = 105 tests, 0 failures, 1 skipped.

If `testMethodParamTypeonlyNorename` fails with "Object join(...)" not found — double-check that `transformMethodParams` is called from `transform()` and the type is being set correctly.

- [ ] **Step 5: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java
git commit -m "feat(g2a): add transformMethodParams() to PermuteDeclrTransformer

Method parameters now support @PermuteDeclr — type is always replaced;
name is replaced and propagated through the method body only when name
attribute is non-empty. extractTwoParams() updated to treat absent name
as empty string (keep original parameter name)."
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task | Status |
|---|---|---|
| `@PermuteDeclr.name` defaults to `""` | Task 1 | ✓ |
| `name=""` → only type changes | Task 3 `transformMethodParams()` | ✓ |
| `name` non-empty → type AND name replaced, body rename propagated | Task 3 `transformMethodParams()` | ✓ |
| Works in APT mode (both paths call `PermuteDeclrTransformer.transform()`) | Task 3 (automatic) | ✓ |
| Works in Maven plugin (InlineGenerator calls `PermuteDeclrTransformer.transform()`) | Task 3 (automatic) | ✓ |
| `@PermuteDeclr` on method parameter stripped from generated output | Task 3 (`param.getAnnotations().remove(ann)`) | ✓ |

**Not included (separate concerns):**
- `validatePrefixes()` extension for method params — the current validation silently ignores method params; adding it would require care since `name=""` means no name validation. Deferred — not in scope for G2a. The transformer handles errors at generation time.
- Implicit parameter type inference (no annotation needed) — that is G2b's inference engine, not G2a.

**Placeholder scan:** None found.

**Type consistency:** `extractTwoParams()` returns `String[]` with `[type, name]` — consistent with all call sites.
