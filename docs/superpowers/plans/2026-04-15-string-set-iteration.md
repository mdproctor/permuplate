# String-Set Iteration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `String[] values()` to `@Permute` and `@PermuteVar` so templates can iterate over a named set of strings (e.g. `{"Byte","Short","Int"}`) instead of integer ranges, eliminating the need for `alpha(i)`/`lower(i)` workarounds.

**Architecture:** `values` is an optional alternative to `from`/`to` — mutually exclusive. When present, `buildAllCombinations()` in `PermuteConfig` binds `varName` to each string in turn (instead of an integer). Both `@Permute` and `@PermuteVar` support `values`, enabling cross-product string iteration. The APT processor validates the XOR constraint; the IntelliJ indexes compute generated names by string substitution when `values` is present.

**Tech Stack:** Java 17, JavaParser (Maven plugin), JEXL3 (EvaluationContext), IntelliJ Platform SDK (PSI), JUnit 4 + Google compile-testing.

**GitHub:** Refs #27, epic #26.

---

## File Map

| File | Change |
|---|---|
| `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/Permute.java` | Add `String[] values() default {}`, change `from()`/`to()` to `default ""` |
| `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteVar.java` | Same: `values`, `from`/`to` default `""` |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteConfig.java` | Add `String[] values` field; update `from()` factory; update `buildAllCombinations()` for string-set path |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteVarConfig.java` | Add `String[] values` field |
| `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | Validate `values` XOR `from`/`to`; skip `evaluateInt` in string-set mode |
| `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java` | Read `values` from JavaParser AST; make `from`/`to` optional |
| `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateIndex.java` | Read `values` via PSI; update `computeGeneratedNames()`; bump version |
| `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteGeneratedIndex.java` | Same |
| `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteElementResolver.java` | Update PSI fallback scan for string-set |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java` | Create — all APT + InlineGenerator tests |
| `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateIndexTest.java` | Add string-set test |
| `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/index/PermuteElementResolverTest.java` | Add string-set fallback scan test |
| `CLAUDE.md` | Update annotation roster; add non-obvious decisions entries |

---

## Task 1: Update `@Permute` and `@PermuteVar` annotations

**Files:**
- Modify: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/Permute.java`
- Modify: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteVar.java`
- Test: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java`

- [ ] **Step 1.1: Write the failing test**

Create `permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java`:

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.quarkiverse.permuplate.processor.PermuteProcessor;
import org.junit.Test;

public class StringSetIterationTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    /**
     * @Permute with values={"Byte","Short","Int"} and className="To${T}Function"
     * generates ToByteFunction, ToShortFunction, ToIntFunction — verifies the
     * annotation is accepted and the three classes are produced.
     *
     * Template class is ToTypeFunction (not in the values set) to avoid name collision.
     * Leading literal "To" is a prefix of "ToTypeFunction" — R1 passes.
     */
    @Test
    public void testStringSetGeneratesClassForEachValue() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"T\", values={\"Byte\",\"Short\",\"Int\"},\n" +
                "         className=\"To${T}Function\")\n" +
                "public interface ToTypeFunction {}");

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("io.example.ToByteFunction").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.ToShortFunction").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.ToIntFunction").isPresent()).isTrue();
    }
}
```

- [ ] **Step 1.2: Run — expect FAIL (values attribute does not exist on @Permute)**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest="StringSetIterationTest#testStringSetGeneratesClassForEachValue" 2>&1 | tail -15
```

Expected: FAIL — `values` not found on `@Permute`.

- [ ] **Step 1.3: Update Permute.java**

Replace the `from()` and `to()` declarations and add `values()`:

```java
    /**
     * Inclusive lower bound of the permutation range — JEXL expression string.
     * Omit (defaults to {@code ""}) when using {@link #values()}.
     */
    String from() default "";

    /**
     * Inclusive upper bound of the permutation range — JEXL expression string.
     * Omit (defaults to {@code ""}) when using {@link #values()}.
     */
    String to() default "";

    /**
     * String values to iterate over instead of an integer range.
     * Mutually exclusive with {@link #from()} and {@link #to()}.
     *
     * <p>Example:
     * <pre>{@code
     * @Permute(varName="T", values={"Byte","Short","Int","Long"},
     *          className="To${T}Function")
     * public interface ToTypeFunction { ... }
     * // Generates: ToByteFunction, ToShortFunction, ToIntFunction, ToLongFunction
     * }</pre>
     */
    String[] values() default {};
```

(Keep all other attributes — `varName`, `className`, `strings`, `extraVars`, `inline`, `keepTemplate` — unchanged.)

- [ ] **Step 1.4: Update PermuteVar.java**

Add `values()` and make `from()`/`to()` default `""`:

```java
    /** Lower bound — JEXL expression. Omit when using {@link #values()}. */
    String from() default "";

    /** Upper bound — JEXL expression. Omit when using {@link #values()}. */
    String to() default "";

    /**
     * String values to iterate over instead of an integer range.
     * Mutually exclusive with {@link #from()} and {@link #to()}.
     */
    String[] values() default {};
```

- [ ] **Step 1.5: Run — expect compilation PASS but no filter applied yet (ToByteFunction generated)**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest="StringSetIterationTest#testStringSetGeneratesClassForEachValue" 2>&1 | tail -10
```

Expected at this stage: compilation of the template succeeds (annotation is accepted), but the processor likely still fails because it tries to evaluate `from=""` as a JEXL integer. The test may still fail — that's expected. The goal of this step is that the annotation compiles.

- [ ] **Step 1.6: Run full module build to confirm annotations compile**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS on the annotations module.

- [ ] **Step 1.7: Commit annotations only**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/Permute.java \
        permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteVar.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java
git commit -m "feat(annotations): add values[] to @Permute and @PermuteVar; make from/to optional

Refs #27.

String[] values() — alternative to from/to for named-set iteration.
from() and to() default to empty string when values is used.
No processor behavior yet.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Update PermuteConfig and buildAllCombinations()

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteConfig.java`
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteVarConfig.java` (find this file — it may be a nested class in PermuteConfig.java or a separate file; read it first)

- [ ] **Step 2.1: Read PermuteConfig.java and PermuteVarConfig.java in full**

Read `/Users/mdproctor/claude/permuplate/permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteConfig.java` in full. Search for `PermuteVarConfig` to find where it is defined (nested class or separate file). Read that definition in full too.

- [ ] **Step 2.2: Update PermuteVarConfig to add String[] values**

Find the `PermuteVarConfig` definition. It has `varName`, `from`, `to` fields. Add `String[] values`:

```java
public final class PermuteVarConfig {
    public final String varName;
    public final String from;
    public final String to;
    public final String[] values;   // NEW

    public PermuteVarConfig(String varName, String from, String to) {
        this(varName, from, to, new String[0]);
    }

    public PermuteVarConfig(String varName, String from, String to, String[] values) {
        this.varName = varName;
        this.from = from;
        this.to = to;
        this.values = values == null ? new String[0] : values;
    }
}
```

If `PermuteVarConfig` is a nested class inside `PermuteConfig.java`, make the same changes there.

- [ ] **Step 2.3: Update PermuteConfig to add String[] values field**

In `PermuteConfig`, add the `values` field and update the constructor:

```java
public final String[] values;  // NEW — non-null, empty when from/to is used
```

Update the constructor to accept `String[] values` (add after `to` or near the end — check existing parameter order). Add:
```java
this.values = values == null ? new String[0] : values;
```

Update `PermuteConfig.from(Permute permute)` factory to pass the values:
```java
public static PermuteConfig from(io.quarkiverse.permuplate.Permute permute) {
    return new PermuteConfig(
            permute.varName(),
            permute.from(),             // "" when values is used
            permute.to(),               // "" when values is used
            permute.values(),           // NEW
            permute.className(),
            permute.strings(),
            PermuteVarConfig.from(permute.extraVars()),
            permute.inline(),
            permute.keepTemplate()
    );
}
```

Also update `PermuteVarConfig.from(PermuteVar[])`:
```java
public static PermuteVarConfig[] from(io.quarkiverse.permuplate.PermuteVar[] extraVars) {
    PermuteVarConfig[] result = new PermuteVarConfig[extraVars.length];
    for (int i = 0; i < extraVars.length; i++) {
        result[i] = new PermuteVarConfig(
                extraVars[i].varName(),
                extraVars[i].from(),
                extraVars[i].to(),
                extraVars[i].values()   // NEW
        );
    }
    return result;
}
```

- [ ] **Step 2.4: Update buildAllCombinations() to handle string-set path**

In `PermuteConfig.buildAllCombinations()`, replace the current primary-variable loop with a branch:

```java
    public static List<Map<String, Object>> buildAllCombinations(
            PermuteConfig config, Map<String, Object> externalProps) {
        // Build the base context: external props + annotation strings (strings win)
        Map<String, Object> baseVars = new LinkedHashMap<>(externalProps);
        for (String entry : config.strings) {
            int sep = entry.indexOf('=');
            if (sep >= 0)
                baseVars.put(entry.substring(0, sep).trim(), entry.substring(sep + 1));
        }

        List<Map<String, Object>> result = new ArrayList<>();

        if (config.values.length > 0) {
            // String-set path: bind varName to each string value (no JEXL evaluation needed)
            for (String value : config.values) {
                Map<String, Object> vars = new HashMap<>(baseVars);
                vars.put(config.varName, value);
                result.add(vars);
            }
        } else {
            // Integer path: evaluate from/to as JEXL and iterate
            EvaluationContext baseCtx = new EvaluationContext(baseVars);
            int fromVal = baseCtx.evaluateInt(config.from);
            int toVal = baseCtx.evaluateInt(config.to);
            for (int i = fromVal; i <= toVal; i++) {
                Map<String, Object> vars = new HashMap<>(baseVars);
                vars.put(config.varName, i);
                result.add(vars);
            }
        }

        // Expand by each extraVar (cross-product) — each extraVar can be integer or string-set
        for (PermuteVarConfig extra : config.extraVars) {
            List<Map<String, Object>> expanded = new ArrayList<>();
            for (Map<String, Object> base : result) {
                if (extra.values.length > 0) {
                    // String-set extraVar
                    for (String value : extra.values) {
                        Map<String, Object> copy = new HashMap<>(base);
                        copy.put(extra.varName, value);
                        expanded.add(copy);
                    }
                } else {
                    // Integer extraVar
                    EvaluationContext innerCtx = new EvaluationContext(base);
                    int extraFrom = innerCtx.evaluateInt(extra.from);
                    int extraTo = innerCtx.evaluateInt(extra.to);
                    for (int v = extraFrom; v <= extraTo; v++) {
                        Map<String, Object> copy = new HashMap<>(base);
                        copy.put(extra.varName, v);
                        expanded.add(copy);
                    }
                }
            }
            result = expanded;
        }

        return result;
    }
```

- [ ] **Step 2.5: Run the failing test — expect the test to NOW PASS (once APT validation is updated in Task 3)**

Note: The test may still fail here because the APT processor tries `evaluateInt("")` when `from=""`. Task 3 fixes this. Run just to confirm the core change compiles:

```bash
/opt/homebrew/bin/mvn install -pl permuplate-core -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS on the core module.

- [ ] **Step 2.6: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteConfig.java \
        permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteVarConfig.java
git commit -m "feat(core): string-set path in buildAllCombinations(); add values[] to PermuteConfig/PermuteVarConfig

Refs #27.

When config.values is non-empty, varName is bound to each string in turn
instead of an integer. ExtraVars support both string-set and integer modes.
PermuteConfig.from(Permute) and PermuteVarConfig.from(PermuteVar[]) pass values through.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: APT processor — validation and string-set support

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Test: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java`

- [ ] **Step 3.1: Write failing tests**

Add to `StringSetIterationTest.java` after `testStringSetGeneratesClassForEachValue`:

```java
    /**
     * The string variable T is available in JEXL expressions throughout the template.
     * @PermuteDeclr(type="To${T}Function") renames a field type using the string value.
     */
    @Test
    public void testStringVariableAvailableInJexlExpressions() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteDeclr;\n" +
                "@Permute(varName=\"T\", values={\"Byte\",\"Short\"},\n" +
                "         className=\"To${T}Function\")\n" +
                "public class ToTypeFunction {\n" +
                "    @PermuteDeclr(type=\"${T}\")\n" +
                "    private Object value;\n" +
                "}");

        assertThat(compilation).succeeded();
        String byteSrc = ProcessorTestSupport.sourceOf(
                compilation.generatedSourceFile("io.example.ToByteFunction").get());
        assertThat(byteSrc).contains("Byte value");

        String shortSrc = ProcessorTestSupport.sourceOf(
                compilation.generatedSourceFile("io.example.ToShortFunction").get());
        assertThat(shortSrc).contains("Short value");
    }

    /**
     * Specifying both values and from/to is a compile error.
     */
    @Test
    public void testValuesAndFromToMutuallyExclusiveError() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"T\", values={\"Byte\"}, from=\"1\", to=\"3\",\n" +
                "         className=\"To${T}Function\")\n" +
                "public interface ToTypeFunction {}");

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("mutually exclusive");
    }

    /**
     * Specifying neither values nor from/to is a compile error.
     */
    @Test
    public void testMissingBothValuesAndFromToIsError() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"T\", className=\"To${T}Function\")\n" +
                "public interface ToTypeFunction {}");

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("values");
    }

    /**
     * Empty values array is a compile error.
     */
    @Test
    public void testEmptyValuesArrayIsError() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"T\", values={}, className=\"To${T}Function\")\n" +
                "public interface ToTypeFunction {}");

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("values");
    }
```

- [ ] **Step 3.2: Run — expect FAIL**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest="StringSetIterationTest#testStringSetGeneratesClassForEachValue+testStringVariableAvailableInJexlExpressions+testValuesAndFromToMutuallyExclusiveError+testMissingBothValuesAndFromToIsError+testEmptyValuesArrayIsError" \
  2>&1 | tail -20
```

Expected: All FAIL (processor doesn't handle values yet).

- [ ] **Step 3.3: Update processTypePermutation() in PermuteProcessor**

Find the section in `processTypePermutation()` where the annotation is read and from/to are validated. Read the current code first to find the exact location. Make these changes:

**After reading the permute annotation and creating permuteConfig**, add the values/from/to validation. Find where the existing `from > to` check is (around line 175). Replace the from/to validation block with:

```java
        // Validate: values XOR from/to
        String[] values = permute.values();
        boolean hasValues = values.length > 0;
        boolean hasFrom = !permute.from().isEmpty();
        boolean hasTo = !permute.to().isEmpty();

        if (hasValues && (hasFrom || hasTo)) {
            AnnotationMirror permuteMirror = findAnnotationMirror(typeElement,
                    "io.quarkiverse.permuplate.Permute");
            AnnotationValue valuesVal = findAnnotationValue(permuteMirror, "values");
            error("@Permute 'values' and 'from'/'to' are mutually exclusive — use one or the other",
                    typeElement, permuteMirror, valuesVal);
            return;
        }
        if (!hasValues && (!hasFrom || !hasTo)) {
            AnnotationMirror permuteMirror = findAnnotationMirror(typeElement,
                    "io.quarkiverse.permuplate.Permute");
            error("@Permute requires either 'values' (non-empty) or both 'from' and 'to'",
                    typeElement, permuteMirror, null);
            return;
        }
        if (hasValues && values.length == 0) {
            // Covered by !hasValues check above, but explicit for clarity
            AnnotationMirror permuteMirror = findAnnotationMirror(typeElement,
                    "io.quarkiverse.permuplate.Permute");
            error("@Permute 'values' must not be empty — provide at least one string",
                    typeElement, permuteMirror, findAnnotationValue(permuteMirror, "values"));
            return;
        }

        // Integer-mode only: validate from <= to
        if (!hasValues) {
            EvaluationContext baseCtx = new EvaluationContext(
                    PermuteConfig.buildBaseVars(permuteConfig, externalProperties));
            int fromVal = baseCtx.evaluateInt(permute.from());
            int toVal = baseCtx.evaluateInt(permute.to());
            if (fromVal > toVal) {
                AnnotationMirror permuteMirror = findAnnotationMirror(typeElement,
                        "io.quarkiverse.permuplate.Permute");
                error("@Permute from=" + fromVal + " must be <= to=" + toVal,
                        typeElement, permuteMirror,
                        findAnnotationValue(permuteMirror, "from"));
                return;
            }
        }
```

**Remove** the old from/to validation that unconditionally called `evaluateInt(permute.from())` and `evaluateInt(permute.to())` — those calls fail when from/to are empty strings (string-set mode).

Also find and update `processMethodPermutation()` to apply the same validation pattern (values XOR from/to).

- [ ] **Step 3.4: Run the 5 tests — expect PASS**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest="StringSetIterationTest#testStringSetGeneratesClassForEachValue+testStringVariableAvailableInJexlExpressions+testValuesAndFromToMutuallyExclusiveError+testMissingBothValuesAndFromToIsError+testEmptyValuesArrayIsError" \
  2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 3.5: Run full test suite — expect BUILD SUCCESS**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS (174+ tests, 0 failures).

- [ ] **Step 3.6: Commit**

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java
git commit -m "feat(processor): validate values XOR from/to; string-set generation working

Refs #27.

- Compile error if values and from/to both specified
- Compile error if neither values nor from/to specified
- Compile error if values is empty
- from>to check only runs in integer mode
- buildAllCombinations() handles string-set transparently via Task 2 changes

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Maven plugin — AnnotationReader reads values

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java`
- Test: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java`

- [ ] **Step 4.1: Write the failing InlineGenerationTest**

Add to `StringSetIterationTest.java`:

```java
    /**
     * String-set iteration works in inline mode (Maven plugin path).
     * @Permute(values={"Byte","Short"}, inline=true) generates ToByteFunction and
     * ToShortFunction as nested siblings inside the parent class.
     */
    @Test
    public void testStringSetInInlineMode() throws Exception {
        String parentSrc =
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteDeclr;\n" +
                "public class Converters {\n" +
                "    @Permute(varName=\"T\", values={\"Byte\",\"Short\"},\n" +
                "             className=\"To${T}Function\", inline=true, keepTemplate=false)\n" +
                "    public static class ToTypeFunction {\n" +
                "        @PermuteDeclr(type=\"${T}\")\n" +
                "        private Object value;\n" +
                "    }\n" +
                "}";

        com.github.javaparser.ast.CompilationUnit cu =
                com.github.javaparser.StaticJavaParser.parse(parentSrc);
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration parent =
                cu.getClassByName("Converters").orElseThrow();
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration template =
                parent.getMembers().stream()
                        .filter(m -> m instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)
                        .map(m -> (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) m)
                        .findFirst().orElseThrow();

        io.quarkiverse.permuplate.maven.AnnotationReader reader =
                new io.quarkiverse.permuplate.maven.AnnotationReader();
        io.quarkiverse.permuplate.core.PermuteConfig config = reader.readPermuteConfig(template);
        java.util.List<java.util.Map<String, Object>> allCombinations =
                io.quarkiverse.permuplate.core.PermuteConfig.buildAllCombinations(config);

        com.github.javaparser.ast.CompilationUnit result =
                new io.quarkiverse.permuplate.maven.InlineGenerator()
                        .generate(cu, template, config, allCombinations);

        String output = result.toString();
        assertThat(output).contains("ToByteFunction");
        assertThat(output).contains("ToShortFunction");
        assertThat(output).doesNotContain("ToTypeFunction"); // keepTemplate=false
        assertThat(output).contains("Byte value");   // @PermuteDeclr applied
        assertThat(output).contains("Short value");
    }
```

- [ ] **Step 4.2: Run — expect FAIL (AnnotationReader doesn't read values)**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest="StringSetIterationTest#testStringSetInInlineMode" 2>&1 | tail -15
```

Expected: FAIL — `config.values` is empty, so AnnotationReader ignores `values` attribute.

- [ ] **Step 4.3: Update AnnotationReader to read values**

Open `AnnotationReader.java`. Read it in full first to understand `readPermute()` and the helper methods.

**Step 4.3a: Add an `optionalStringOrInt` helper** that returns `""` if the attribute is not present (instead of throwing):

```java
    /**
     * Reads a string or int attribute; returns empty string if the attribute is absent.
     * Used for from/to which are optional when values is specified.
     */
    private String optionalStringOrInt(NormalAnnotationExpr ann, String name) {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(name)) {
                return requireStringOrInt(ann, name); // delegate to existing method
            }
        }
        return "";
    }
```

**Step 4.3b: Update `readPermute()` (or `readPermuteConfig()` — find the correct method name)** to read `values` and use optional from/to:

```java
    public PermuteConfig readPermuteConfig(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration templateClass) {
        // Find the @Permute annotation on the class
        com.github.javaparser.ast.expr.NormalAnnotationExpr ann =
                // ... existing lookup code — don't change this part
        
        String varName   = requireString(ann, "varName");
        String className = requireString(ann, "className");
        String[] values  = readStringArray(ann, "values");   // NEW
        String from      = values.length > 0 ? "" : optionalStringOrInt(ann, "from");  // optional now
        String to        = values.length > 0 ? "" : optionalStringOrInt(ann, "to");    // optional now
        String[] strings = readStringArray(ann, "strings");
        io.quarkiverse.permuplate.core.PermuteVarConfig[] extraVars = readExtraVars(ann);
        boolean inline        = readBoolean(ann, "inline",        false);
        boolean keepTemplate  = readBoolean(ann, "keepTemplate",  false);

        return new io.quarkiverse.permuplate.core.PermuteConfig(
                varName, from, to, values, className, strings, extraVars, inline, keepTemplate);
    }
```

Note: Read the file to find the exact method name and pattern — it may be `readPermute()` or `readPermuteConfig()`. Find the `PermuteConfig` construction call and update it to pass `values`.

**Step 4.3c: Update `readExtraVars()`** to also read `values` from each `@PermuteVar`:

```java
    private io.quarkiverse.permuplate.core.PermuteVarConfig[] readExtraVars(
            NormalAnnotationExpr ann) {
        // ... existing code to get the extraVars array annotation
        // For each inner @PermuteVar NormalAnnotationExpr:
        String varName   = requireString(innerAnn, "varName");
        String[] values  = readStringArray(innerAnn, "values");  // NEW
        String from      = values.length > 0 ? "" : optionalStringOrInt(innerAnn, "from");
        String to        = values.length > 0 ? "" : optionalStringOrInt(innerAnn, "to");
        return new io.quarkiverse.permuplate.core.PermuteVarConfig(varName, from, to, values);
    }
```

- [ ] **Step 4.4: Run test — expect PASS**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest="StringSetIterationTest#testStringSetInInlineMode" 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 4.5: Run full test suite**

```bash
/opt/homebrew/bin/mvn test -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 4.6: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java
git commit -m "feat(maven-plugin): AnnotationReader reads values[] from @Permute and @PermuteVar

Refs #27.

optionalStringOrInt() handles absent from/to when values is present.
readExtraVars() passes values[] to PermuteVarConfig constructor.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: IntelliJ plugin — indexes and element resolver

**Files:**
- Modify: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateIndex.java`
- Modify: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteGeneratedIndex.java`
- Modify: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteElementResolver.java`
- Test: `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateIndexTest.java`
- Test: `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/index/PermuteElementResolverTest.java`

- [ ] **Step 5.1: Write failing tests**

In `PermuteTemplateIndexTest.java`, add after the existing tests:

```java
    /**
     * When @Permute uses values={"Byte","Short","Int"}, the forward index must compute
     * generatedNames as ["ToByteFunction","ToShortFunction","ToIntFunction"].
     */
    @Test
    public void testForwardIndexWithStringSetValues() {
        myFixture.configureByText("ToTypeFunction.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"T\", values={\"Byte\",\"Short\",\"Int\"},\n" +
                "         className=\"To${T}Function\")\n" +
                "public interface ToTypeFunction {}");

        Map<String, PermuteTemplateData> result = invokeForwardIndexer();
        assertEquals(1, result.size());

        PermuteTemplateData data = result.get("ToTypeFunction");
        assertNotNull("Expected key 'ToTypeFunction' in index", data);
        assertEquals("T", data.varName);
        assertEquals("To${T}Function", data.classNameTemplate);
        assertEquals(List.of("ToByteFunction", "ToShortFunction", "ToIntFunction"),
                data.generatedNames);
    }
```

In `PermuteElementResolverTest.java`, add:

```java
    /**
     * PSI fallback scan finds the template class for a string-set generated name.
     * Template "ToTypeFunction" with values={"Byte","Short"} — looking up "ToByteFunction"
     * should resolve to "ToTypeFunction".
     */
    @Test
    public void testFindTemplateClassViaFallbackPsiScanWithStringSet() {
        myFixture.addFileToProject("ToTypeFunction.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"T\", values={\"Byte\",\"Short\"},\n" +
                "         className=\"To${T}Function\")\n" +
                "public interface ToTypeFunction {}");

        PsiClass template = PermuteElementResolver.findTemplateClass(
                "ToByteFunction", getProject());

        assertNotNull("findTemplateClass must find ToTypeFunction for ToByteFunction", template);
        assertEquals("ToTypeFunction", template.getName());
    }
```

- [ ] **Step 5.2: Run — expect FAIL**

```bash
cd /Users/mdproctor/claude/permuplate/permuplate-intellij-plugin
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.index.PermuteTemplateIndexTest.testForwardIndexWithStringSetValues" \
  --tests "io.quarkiverse.permuplate.intellij.index.PermuteElementResolverTest.testFindTemplateClassViaFallbackPsiScanWithStringSet" \
  2>&1 | grep -E "PASSED|FAILED|BUILD" | head -10
```

Expected: FAIL — indexes don't handle `values` attribute.

- [ ] **Step 5.3: Add getStringArrayAttr() helper to PermuteTemplateIndex**

In `PermuteTemplateIndex.java`, add this private static helper after `getIntAttr()`:

```java
    /**
     * Reads a String[] annotation attribute value from PSI.
     * Handles both array form {@code values={"Byte","Short"}} and
     * single-element form {@code values="Byte"}.
     * Returns an empty array if the attribute is absent or not a string literal.
     */
    private static String[] getStringArrayAttr(PsiAnnotation ann, String attr) {
        PsiAnnotationMemberValue v = ann.findAttributeValue(attr);
        if (v == null) return new String[0];
        if (v instanceof PsiArrayInitializerMemberValue arr) {
            return Arrays.stream(arr.getInitializers())
                    .filter(i -> i instanceof PsiLiteralExpression)
                    .map(i -> ((PsiLiteralExpression) i).getValue())
                    .filter(val -> val instanceof String)
                    .map(val -> (String) val)
                    .toArray(String[]::new);
        }
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
            return new String[]{ s };
        }
        return new String[0];
    }
```

Add `import java.util.Arrays;` if not already present.

- [ ] **Step 5.4: Update computeGeneratedNames() to handle string-set**

In `PermuteTemplateIndex.java`, add a `String[] values` parameter to `computeGeneratedNames()`:

```java
    private static List<String> computeGeneratedNames(
            String varName, int from, int to, String[] values, String template) {
        String placeholder = "${" + varName + "}";
        if (values.length > 0) {
            return Arrays.stream(values)
                    .map(v -> template.replace(placeholder, v))
                    .collect(java.util.stream.Collectors.toList());
        }
        List<String> names = new ArrayList<>(to - from + 1);
        for (int v = from; v <= to; v++) {
            names.add(template.replace(placeholder, String.valueOf(v)));
        }
        return names;
    }
```

Update the call site in `getIndexer()` to read `values` and pass it:

```java
                String[] values = getStringArrayAttr(permute, "values");
                int    from = values.length > 0 ? 0 : getIntAttr(permute, "from", 1);
                int    to   = values.length > 0 ? 0 : getIntAttr(permute, "to",   1);
                // ...
                List<String> generatedNames = computeGeneratedNames(varName, from, to, values, className);
```

Bump the index version:
```java
@Override public int getVersion() { return 5; } // bumped: string-set values[] support
```

- [ ] **Step 5.5: Update PermuteGeneratedIndex similarly**

Apply the same changes to `PermuteGeneratedIndex.java`:

1. Add `getStringArrayAttr()` private static helper (identical to the one in PermuteTemplateIndex)
2. In the indexer loop, read `values` and branch:

```java
                String[] values = getStringArrayAttr(permute, "values");
                String placeholder = "${" + varName + "}";
                if (values.length > 0) {
                    for (String v : values) {
                        result.put(className.replace(placeholder, v), templateName);
                    }
                } else {
                    int from = getIntAttr(permute, "from", 1);
                    int to   = getIntAttr(permute, "to",   1);
                    for (int v = from; v <= to; v++) {
                        result.put(className.replace(placeholder, String.valueOf(v)), templateName);
                    }
                }
```

Bump the version:
```java
@Override public int getVersion() { return 5; } // bumped: string-set values[] support
```

- [ ] **Step 5.6: Update PermuteElementResolver PSI fallback scan**

In `PermuteElementResolver.java`, in the PSI fallback scan loop (the lambda inside `iterateContent`), after reading `varName` and `className`, add the string-set branch:

```java
                    PsiAnnotationMemberValue valuesVal = ann.findAttributeValue("values");
                    String[] values = getStringArrayFromPsi(valuesVal);
                    String placeholder = "${" + varName + "}";
                    if (values.length > 0) {
                        for (String v : values) {
                            if (generatedName.equals(className.replace(placeholder, v))) {
                                found.set(cls);
                                return false;
                            }
                        }
                    } else {
                        int from = parseLiteralInt(fromVal, 1);
                        int to   = parseLiteralInt(toVal,   1);
                        for (int v = from; v <= to; v++) {
                            if (generatedName.equals(className.replace(placeholder, String.valueOf(v)))) {
                                found.set(cls);
                                return false;
                            }
                        }
                    }
```

Add the helper:

```java
    private static String[] getStringArrayFromPsi(@Nullable PsiAnnotationMemberValue v) {
        if (v == null) return new String[0];
        if (v instanceof PsiArrayInitializerMemberValue arr) {
            return Arrays.stream(arr.getInitializers())
                    .filter(i -> i instanceof PsiLiteralExpression)
                    .map(i -> ((PsiLiteralExpression) i).getValue())
                    .filter(val -> val instanceof String)
                    .map(val -> (String) val)
                    .toArray(String[]::new);
        }
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
            return new String[]{ s };
        }
        return new String[0];
    }
```

Add `import java.util.Arrays;` if needed.

Also replace the existing single-level `fromVal`/`toVal` reads with:
```java
                    PsiAnnotationMemberValue fromVal = ann.findAttributeValue("from");
                    PsiAnnotationMemberValue toVal   = ann.findAttributeValue("to");
                    PsiAnnotationMemberValue valuesVal = ann.findAttributeValue("values");
```

(Check exact names in the existing code — they may already be present.)

- [ ] **Step 5.7: Run the two plugin tests — expect PASS**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.index.PermuteTemplateIndexTest.testForwardIndexWithStringSetValues" \
  --tests "io.quarkiverse.permuplate.intellij.index.PermuteElementResolverTest.testFindTemplateClassViaFallbackPsiScanWithStringSet" \
  2>&1 | grep -E "PASSED|FAILED|BUILD" | head -10
```

Expected: PASS.

- [ ] **Step 5.8: Run full plugin test suite**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test 2>&1 | grep -E "BUILD|tests completed"
```

Expected: BUILD SUCCESSFUL, 66 tests, 0 failures.

- [ ] **Step 5.9: Commit**

```bash
cd /Users/mdproctor/claude/permuplate
git add permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateIndex.java \
        permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteGeneratedIndex.java \
        permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteElementResolver.java \
        permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateIndexTest.java \
        permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/index/PermuteElementResolverTest.java
git commit -m "feat(plugin): string-set values[] support in indexes and element resolver

Refs #27.

getStringArrayAttr() reads String[] values from PSI annotation.
computeGeneratedNames() branches on values vs from/to.
PermuteGeneratedIndex generates reverse mappings from each string value.
PermuteElementResolver PSI fallback scan checks string-set values.
Index versions bumped to 5 to invalidate stale cache.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Update CLAUDE.md, close issues

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 6.1: Update the annotation roster**

In `CLAUDE.md`, find the annotation roster table. The `@Permute` row currently says:
```
| `@Permute` | class, interface, method | Master: declares the permutation loop |
```

Update it to mention `values`:
```
| `@Permute` | class, interface, method | Master: declares the permutation loop (integer range via `from`/`to`, or string set via `values`) |
```

Add `@PermuteVar` update mention of values similarly.

- [ ] **Step 6.2: Add non-obvious decisions entries**

Add to the decisions table:

```
| `@Permute(values=...)` binds varName as String | When values is used, the loop variable is bound as `String`, not `Integer`. JEXL expressions that compare it to an integer (e.g. `@PermuteFilter("${T} > 2")`) will not work. Filter expressions for string-set templates must use string comparisons. |
| `@Permute` values XOR from/to | Both are optional with `default ""` in the annotation, but the APT processor validates that exactly one mode is specified. Empty string is the sentinel for "not provided". |
| String-set in IntelliJ index | `getStringArrayAttr()` reads the values array from PSI. The index version was bumped to 5 to invalidate stale caches when upgrading from an integer-only plugin. |
```

- [ ] **Step 6.3: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

Also run plugin tests:
```bash
cd permuplate-intellij-plugin
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test 2>&1 | grep "BUILD"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6.4: Commit and close issues**

```bash
cd /Users/mdproctor/claude/permuplate
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for string-set @Permute(values=...) feature

Closes #27. Refs #26.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

```bash
gh issue close 27 --repo mdproctor/permuplate \
  --comment "Implemented. @Permute and @PermuteVar gain values[] attribute as alternative to from/to. APT processor, Maven plugin, and IntelliJ plugin all support string-set iteration. 8 new tests."
gh issue edit 26 --repo mdproctor/permuplate \
  --body "$(gh issue view 26 --repo mdproctor/permuplate --json body -q .body | sed 's/\[ \] #27/[x] #27/')"
```

---

## Self-Review

**Spec coverage (from issue #27):**
- ✅ `@Permute` gains `String[] values() default {}` — Task 1
- ✅ `from()`/`to()` become optional (default `""`) — Task 1
- ✅ APT processor handles string-set loop — Task 3 (via `buildAllCombinations` from Task 2)
- ✅ Maven plugin handles string-set loop — Task 4
- ✅ `@PermuteVar` also gains `values` — Tasks 1 + 2 + 4
- ✅ Compile error if both `values` and `from`/`to` specified — Task 3
- ✅ Compile error if `values` is empty — Task 3
- ✅ Tests: basic generation, JEXL expressions with string vars, error paths — Tasks 3-4
- ✅ IntelliJ plugin updated — Task 5
- ✅ String-set cross-product with `@PermuteVar` — implemented in `buildAllCombinations` (Task 2); no dedicated cross-product test in this plan. **Add one if the reviewer flags it.**

**Placeholder scan:** No TBDs or incomplete sections found.

**Type consistency:** `String[] values` is used as the field name throughout PermuteConfig, PermuteVarConfig, and the annotation. `getStringArrayAttr()` returns `String[]`. All consistent.
