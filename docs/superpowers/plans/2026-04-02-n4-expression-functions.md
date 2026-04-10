# N4 Expression Functions + S3 Mojo Prefix Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `alpha(n)`, `lower(n)`, and `typeArgList(from, to, style)` built-in JEXL functions to `EvaluationContext`, making them available in all Permuplate annotation string attributes; also fix the Mojo's weaker prefix validation to match the APT processor's logic.

**Architecture:** A new `public static final class PermuplateStringFunctions` is nested inside `EvaluationContext`. The JEXL engine is rebuilt with a `namespaces` registration (empty-string key) so all three functions are callable without a prefix in any `${...}` expression. S3 replaces four lines of manual string slicing in `PermuteMojo.generateTopLevel()` with a single call to the existing `AnnotationStringAlgorithm.matches()`.

**Tech Stack:** Apache Commons JEXL3 (already on classpath), Google compile-testing (test), JUnit 4 (test pattern used throughout this codebase).

---

## File Map

| File | Change |
|---|---|
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java` | Add `PermuplateStringFunctions` nested class; rebuild `JEXL` with `namespaces` |
| `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/PermuteMojo.java` | Replace `leadingLiteral` check in `generateTopLevel()` with `AnnotationStringAlgorithm.matches()` |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/ExpressionFunctionsTest.java` | New test class — unit tests for all three functions + end-to-end compile-testing |

---

## Task 1: Fix S3 — PermuteMojo uses weak prefix check

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/PermuteMojo.java:187-200`

- [ ] **Step 1: Understand the current code**

In `PermuteMojo.generateTopLevel()` (around line 190), the current check is:
```java
// Leading literal prefix check
String leadingLiteral = config.className.contains("${")
        ? config.className.substring(0, config.className.indexOf("${"))
        : config.className;
if (!leadingLiteral.isEmpty() && !templateClassName.startsWith(leadingLiteral)) {
    throw new MojoExecutionException(entry.sourceFile() +
            ": @Permute className leading literal \"" + leadingLiteral +
            "\" is not a prefix of the template class name \"" + templateClassName + "\"");
}
```

The APT processor uses `AnnotationStringAlgorithm.matches(template, className)` which does full substring matching (not just prefix). Replace the block above with the equivalent.

- [ ] **Step 2: Add the import**

At the top of `PermuteMojo.java`, add imports (they're available transitively via `permuplate-core → permuplate-ide-support`):
```java
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
```

- [ ] **Step 3: Replace the prefix check**

Delete the six-line `leadingLiteral` block and replace with:
```java
// Prefix check using full substring matching — consistent with APT processor
AnnotationStringTemplate classNameTemplate = AnnotationStringAlgorithm.parse(config.className);
if (!AnnotationStringAlgorithm.matches(classNameTemplate, templateClassName)) {
    throw new MojoExecutionException(entry.sourceFile() +
            ": @Permute className \"" + config.className +
            "\" does not match the template class name \"" + templateClassName + "\"");
}
```

- [ ] **Step 4: Build and verify no regressions**

```bash
/opt/homebrew/bin/mvn clean install
```

Expected: BUILD SUCCESS. All existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/PermuteMojo.java
git commit -m "fix(mojo): use AnnotationStringAlgorithm.matches() for className prefix check (S3)

Replaces the weaker leadingLiteral/startsWith check in PermuteMojo with the
same full-substring matching used by the APT processor, making both paths
consistent. No behaviour change for simple patterns; catches multi-literal
patterns that the old check would silently accept or wrongly reject."
```

---

## Task 2: Write failing tests for `alpha`, `lower`, `typeArgList`

**Files:**
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/ExpressionFunctionsTest.java`

- [ ] **Step 1: Create the test class with unit tests**

These tests call `PermuplateStringFunctions` static methods directly and verify `EvaluationContext.evaluate()` resolves the functions. They will fail until Task 3 is done.

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

import java.util.Map;

/**
 * Tests for built-in JEXL expression functions: alpha(n), lower(n), typeArgList(from,to,style).
 * Unit tests verify the functions directly; end-to-end tests verify they work inside
 * @Permute annotation string attributes evaluated by the processor.
 */
public class ExpressionFunctionsTest {

    // -------------------------------------------------------------------------
    // alpha(n) unit tests
    // -------------------------------------------------------------------------

    @Test
    public void testAlphaA() {
        assertThat(EvaluationContext.PermuplateStringFunctions.alpha(1)).isEqualTo("A");
    }

    @Test
    public void testAlphaMidpoint() {
        assertThat(EvaluationContext.PermuplateStringFunctions.alpha(13)).isEqualTo("M");
    }

    @Test
    public void testAlphaZ() {
        assertThat(EvaluationContext.PermuplateStringFunctions.alpha(26)).isEqualTo("Z");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAlphaBelowRange() {
        EvaluationContext.PermuplateStringFunctions.alpha(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAlphaAboveRange() {
        EvaluationContext.PermuplateStringFunctions.alpha(27);
    }

    // -------------------------------------------------------------------------
    // lower(n) unit tests
    // -------------------------------------------------------------------------

    @Test
    public void testLowerA() {
        assertThat(EvaluationContext.PermuplateStringFunctions.lower(1)).isEqualTo("a");
    }

    @Test
    public void testLowerMidpoint() {
        assertThat(EvaluationContext.PermuplateStringFunctions.lower(13)).isEqualTo("m");
    }

    @Test
    public void testLowerZ() {
        assertThat(EvaluationContext.PermuplateStringFunctions.lower(26)).isEqualTo("z");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLowerBelowRange() {
        EvaluationContext.PermuplateStringFunctions.lower(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLowerAboveRange() {
        EvaluationContext.PermuplateStringFunctions.lower(27);
    }

    // -------------------------------------------------------------------------
    // typeArgList(from, to, style) unit tests
    // -------------------------------------------------------------------------

    @Test
    public void testTypeArgListTStyle() {
        assertThat(EvaluationContext.PermuplateStringFunctions.typeArgList(2, 4, "T"))
                .isEqualTo("T2, T3, T4");
    }

    @Test
    public void testTypeArgListAlphaStyle() {
        assertThat(EvaluationContext.PermuplateStringFunctions.typeArgList(2, 4, "alpha"))
                .isEqualTo("B, C, D");
    }

    @Test
    public void testTypeArgListLowerStyle() {
        assertThat(EvaluationContext.PermuplateStringFunctions.typeArgList(2, 4, "lower"))
                .isEqualTo("b, c, d");
    }

    @Test
    public void testTypeArgListSingleElement() {
        assertThat(EvaluationContext.PermuplateStringFunctions.typeArgList(2, 2, "alpha"))
                .isEqualTo("B");
    }

    @Test
    public void testTypeArgListEmptyRange() {
        assertThat(EvaluationContext.PermuplateStringFunctions.typeArgList(3, 2, "T"))
                .isEqualTo("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTypeArgListUnknownStyle() {
        EvaluationContext.PermuplateStringFunctions.typeArgList(1, 3, "X");
    }

    // -------------------------------------------------------------------------
    // End-to-end: alpha in @Permute.className
    // -------------------------------------------------------------------------

    @Test
    public void testAlphaInClassName() {
        // @Permute(className="Step${alpha(i)}") generates StepA..StepE
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.StepA",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                @Permute(varName="i", from=1, to=5, className="Step${alpha(i)}")
                public class StepA { }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("io.quarkiverse.permuplate.example.StepA")).isPresent();
        assertThat(compilation.generatedSourceFile("io.quarkiverse.permuplate.example.StepC")).isPresent();
        assertThat(compilation.generatedSourceFile("io.quarkiverse.permuplate.example.StepE")).isPresent();
    }

    // -------------------------------------------------------------------------
    // End-to-end: lower in @PermuteParam.name
    // -------------------------------------------------------------------------

    @Test
    public void testLowerInPermuteParamName() {
        // @PermuteParam(name="fact${lower(j)}") generates facta, factb, factc for i=3
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.JoinLower3",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteParam;
                @Permute(varName="i", from=3, to=3, className="JoinLower${i}")
                public class JoinLower3 {
                    public void join(
                        @PermuteParam(varName="j", from="1", to="${i}", type="Object", name="fact${lower(j)}")
                        Object facta) { }
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.JoinLower3")
                .orElseThrow());
        assertThat(src).contains("Object facta");
        assertThat(src).contains("Object factb");
        assertThat(src).contains("Object factc");
    }
}
```

- [ ] **Step 2: Run the tests — confirm they all fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am 2>&1 | grep -E "FAIL|ERROR|Tests run"
```

Expected: Compilation errors or test failures. `PermuplateStringFunctions` does not exist yet.

---

## Task 3: Implement `PermuplateStringFunctions` and register with JEXL

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java`

- [ ] **Step 1: Add `PermuplateStringFunctions` nested class**

Inside `EvaluationContext`, after the existing fields, add:

```java
/**
 * Built-in JEXL functions available in all Permuplate annotation string attributes.
 * Registered under the empty-string namespace key so functions are called without prefix:
 * {@code ${alpha(j)}}, {@code ${lower(j)}}, {@code ${typeArgList(1, i, 'T')}}.
 */
public static final class PermuplateStringFunctions {

    public static String alpha(int n) {
        if (n < 1 || n > 26)
            throw new IllegalArgumentException(
                "alpha(n): n must be between 1 and 26, got " + n);
        return String.valueOf((char) ('A' + n - 1));
    }

    public static String lower(int n) {
        if (n < 1 || n > 26)
            throw new IllegalArgumentException(
                "lower(n): n must be between 1 and 26, got " + n);
        return String.valueOf((char) ('a' + n - 1));
    }

    public static String typeArgList(int from, int to, String style) {
        if (from > to) return "";
        StringBuilder sb = new StringBuilder();
        for (int k = from; k <= to; k++) {
            if (k > from) sb.append(", ");
            switch (style) {
                case "T":     sb.append("T").append(k); break;
                case "alpha": sb.append((char) ('A' + k - 1)); break;
                case "lower": sb.append((char) ('a' + k - 1)); break;
                default: throw new IllegalArgumentException(
                    "typeArgList: unknown style \"" + style + "\" — use \"T\", \"alpha\", or \"lower\"");
            }
        }
        return sb.toString();
    }
}
```

- [ ] **Step 2: Register functions with the JEXL engine**

Change the `JEXL` field from:
```java
private static final JexlEngine JEXL = new JexlBuilder().silent(false).strict(true).create();
```
To:
```java
private static final JexlEngine JEXL = new JexlBuilder()
        .silent(false).strict(true)
        .namespaces(Map.of("", PermuplateStringFunctions.class))
        .create();
```

Add import at the top of the file (check if `Map` is already imported — if not, add it):
```java
import java.util.Map;
```

The full updated file top section will look like:
```java
package io.quarkiverse.permuplate.core;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.jexl3.*;

public class EvaluationContext {

    private static final JexlEngine JEXL = new JexlBuilder()
            .silent(false).strict(true)
            .namespaces(Map.of("", PermuplateStringFunctions.class))
            .create();
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^}]+)}");
    // ... rest unchanged
```

- [ ] **Step 3: Run the tests — confirm all pass**

```bash
/opt/homebrew/bin/mvn clean install
```

Expected: BUILD SUCCESS. All `ExpressionFunctionsTest` tests pass. All existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/ExpressionFunctionsTest.java
git commit -m "feat(n4): add alpha(), lower(), typeArgList() built-in JEXL functions

Registers PermuplateStringFunctions with the JEXL engine so alpha(n),
lower(n), and typeArgList(from,to,style) are available in every annotation
string attribute throughout Permuplate. Enables Drools-style A,B,C type
parameter naming and comma-separated type argument list generation."
```

---

## Self-Review

**Spec coverage check:**
- `alpha(1)→"A"`, `alpha(26)→"Z"` ✓ testAlphaA, testAlphaZ
- `lower(1)→"a"`, `lower(26)→"z"` ✓ testLowerA, testLowerZ
- Out-of-range errors ✓ testAlphaBelowRange/AboveRange, testLowerBelowRange/AboveRange
- `typeArgList("T")` ✓ testTypeArgListTStyle
- `typeArgList("alpha")` ✓ testTypeArgListAlphaStyle
- `typeArgList("lower")` ✓ testTypeArgListLowerStyle
- `typeArgList` empty range → `""` ✓ testTypeArgListEmptyRange
- `typeArgList` unknown style error ✓ testTypeArgListUnknownStyle
- `alpha` in `@Permute.className` ✓ testAlphaInClassName
- `lower` in `@PermuteParam.name` ✓ testLowerInPermuteParamName
- S3 Mojo prefix fix ✓ Task 1

**Tests not included (require G1/G2 not yet implemented):**
- `alpha` in `@PermuteTypeParam.name` — deferred to G1 test class
- `alpha` in `@PermuteReturn.typeArgName` — deferred to G2 test class
- Full Drools chain end-to-end — deferred to G2 test class

**Placeholder scan:** None found.

**Type consistency:** `EvaluationContext.PermuplateStringFunctions` used in test matches the nested class name defined in Task 3.
