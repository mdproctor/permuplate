# Enum Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow `@Permute` on `enum` types to generate renamed enum families, plus a new `@PermuteEnumConst` annotation to expand enum constants as an inner loop per permutation.

**Architecture:** Two-part feature: (1) extend `PermuteProcessor` and `InlineGenerator` to handle `EnumDeclaration` (currently only `ClassOrInterfaceDeclaration` and records are supported); (2) new `@PermuteEnumConst` annotation and `PermuteEnumConstTransformer` that expands a sentinel enum constant into multiple constants per permutation, analogous to `@PermuteCase` for switch statements.

**Tech Stack:** Java 17, JavaParser 3.28.0 (`EnumDeclaration`, `EnumConstantDeclaration`), APT + Maven plugin.

---

## Design

```java
// Template: generates Priority2, Priority3, Priority4
@Permute(varName="i", from="2", to="4", className="Priority${i}")
public enum Priority2 {
    LOW(1),
    MED(2),
    @PermuteEnumConst(varName="k", from="3", to="${i}", name="LEVEL${k}", args="${k}")
    HIGH_PLACEHOLDER(99); // sentinel — replaced per permutation

    private final int level;
    Priority2(int level) { this.level = level; }
}
// Priority3: LOW(1), MED(2), LEVEL3(3)
// Priority4: LOW(1), MED(2), LEVEL3(3), LEVEL4(4)
```

`@PermuteEnumConst` replaces the annotated sentinel constant with zero or more new constants generated from the inner loop. The sentinel itself is removed from the output.

The `args` attribute is optional. Without it, generated constants have no constructor arguments.

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Create | `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteEnumConst.java` | New annotation |
| Create | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteEnumConstTransformer.java` | Expand sentinel constant into constant list |
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | Handle `enum` `TypeElement.getKind()` + register transformer |
| Modify | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | Handle `EnumDeclaration` template + register transformer |
| Create | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/EnumGenerationTest.java` | Tests |
| Create | `permuplate-apt-examples/…/PriorityEnum2.java` | APT example |

---

### Task 1: Define `@PermuteEnumConst`

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteEnumConst.java`

- [ ] **Step 1: Write the annotation**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Expands a sentinel enum constant into a sequence of constants per permutation.
 *
 * <p>The annotated constant is removed and replaced by constants generated from the
 * inner loop [{@code from}, {@code to}]. Each constant's name is controlled by the
 * {@code name} attribute (a JEXL template). Optional constructor arguments are
 * provided via {@code args} (also a JEXL template).
 *
 * <p>Example:
 * <pre>
 * {@literal @}PermuteEnumConst(varName="k", from="3", to="${i}", name="LEVEL${k}", args="${k}")
 * HIGH_PLACEHOLDER(99);
 * // In Priority3 → LEVEL3(3)
 * // In Priority4 → LEVEL3(3), LEVEL4(4)
 * </pre>
 */
@Target(ElementType.FIELD)  // Enum constants are ElementType.FIELD in APT
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteEnumConst {
    /** Inner loop variable name. */
    String varName();
    /** Inclusive lower bound (JEXL expression). */
    String from();
    /** Inclusive upper bound (JEXL expression). */
    String to();
    /** JEXL template for the generated constant name. */
    String name();
    /** JEXL template for the constructor argument list (without parens). Empty = no args. */
    String args() default "";
}
```

- [ ] **Step 2: Build annotations module**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS

---

### Task 2: Write the failing tests

**Files:**
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/EnumGenerationTest.java`

- [ ] **Step 1: Create the test file**

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class EnumGenerationTest {

    @Test
    public void testBasicEnumRename() {
        // @Permute on an enum renames it, just like a class
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Color1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        @Permute(varName="i", from="2", to="3", className="Color${i}")
                        public enum Color1 {
                            RED, GREEN, BLUE;
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src2 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Color2").orElseThrow());
        assertThat(src2).contains("enum Color2");
        assertThat(src2).contains("RED");
        assertThat(src2).contains("GREEN");
        assertThat(src2).contains("BLUE");
        assertThat(src2).doesNotContain("@Permute");

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Color3").orElseThrow());
        assertThat(src3).contains("enum Color3");
    }

    @Test
    public void testEnumConstExpansion() {
        // @PermuteEnumConst on a sentinel constant expands it into multiple constants
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Priority1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteEnumConst;
                        @Permute(varName="i", from="2", to="3", className="Priority${i}")
                        public enum Priority1 {
                            LOW,
                            MED,
                            @PermuteEnumConst(varName="k", from="3", to="${i}", name="LEVEL${k}")
                            HIGH_PLACEHOLDER;
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // Priority2: from=3 to=2 → empty range → sentinel removed, no extra constants
        String src2 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Priority2").orElseThrow());
        assertThat(src2).contains("LOW");
        assertThat(src2).contains("MED");
        assertThat(src2).doesNotContain("HIGH_PLACEHOLDER");
        assertThat(src2).doesNotContain("LEVEL");

        // Priority3: from=3 to=3 → LEVEL3
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Priority3").orElseThrow());
        assertThat(src3).contains("LOW");
        assertThat(src3).contains("MED");
        assertThat(src3).contains("LEVEL3");
        assertThat(src3).doesNotContain("HIGH_PLACEHOLDER");
        assertThat(src3).doesNotContain("@PermuteEnumConst");
    }

    @Test
    public void testEnumConstExpansionWithArgs() {
        // Constants can have constructor arguments
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Status1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteEnumConst;
                        @Permute(varName="i", from="2", to="2", className="Status${i}")
                        public enum Status1 {
                            FIRST(1),
                            @PermuteEnumConst(varName="k", from="2", to="${i}", name="ITEM${k}", args="${k}")
                            PLACEHOLDER(99);
                            private final int code;
                            Status1(int code) { this.code = code; }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Status2").orElseThrow());
        assertThat(src).contains("FIRST(1)");
        assertThat(src).contains("ITEM2(2)");
        assertThat(src).doesNotContain("PLACEHOLDER");
    }
}
```

- [ ] **Step 2: Run to confirm all three tests fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=EnumGenerationTest -q 2>&1 | tail -10
```

Expected: compilation fails or generated source not found for `Color2` etc.

---

### Task 3: Implement `PermuteEnumConstTransformer`

**Files:**
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteEnumConstTransformer.java`

- [ ] **Step 1: Create the transformer**

```java
package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.List;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

/**
 * Handles {@code @PermuteEnumConst} on enum constant declarations.
 *
 * <p>Replaces the annotated sentinel constant with zero or more generated constants
 * produced by the inner loop [{@code from}, {@code to}]. The sentinel constant is
 * removed in all cases (including empty range).
 */
public class PermuteEnumConstTransformer {

    private static final String SIMPLE = "PermuteEnumConst";
    private static final String FQ     = "io.quarkiverse.permuplate.PermuteEnumConst";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        if (!(classDecl instanceof EnumDeclaration enumDecl))
            return;

        // Collect sentinel constants to process (avoid ConcurrentModificationException)
        List<EnumConstantDeclaration> sentinels = new ArrayList<>();
        for (EnumConstantDeclaration ec : enumDecl.getEntries()) {
            boolean hasAnn = ec.getAnnotations().stream().anyMatch(PermuteEnumConstTransformer::isPermuteEnumConst);
            if (hasAnn) sentinels.add(ec);
        }

        for (EnumConstantDeclaration sentinel : sentinels) {
            AnnotationExpr ann = sentinel.getAnnotations().stream()
                    .filter(PermuteEnumConstTransformer::isPermuteEnumConst)
                    .findFirst().orElseThrow();
            if (!(ann instanceof NormalAnnotationExpr normal))
                continue;

            String varName = null, from = null, to = null, nameTemplate = null, argsTemplate = "";
            for (MemberValuePair pair : normal.getPairs()) {
                String val = pair.getValue().asStringLiteralExpr().asString();
                switch (pair.getNameAsString()) {
                    case "varName" -> varName = val;
                    case "from"    -> from    = val;
                    case "to"      -> to      = val;
                    case "name"    -> nameTemplate = val;
                    case "args"    -> argsTemplate = val;
                }
            }
            if (varName == null || from == null || to == null || nameTemplate == null)
                continue;

            int sentinelIdx = enumDecl.getEntries().indexOf(sentinel);

            // Evaluate loop bounds
            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(from);
                toVal   = ctx.evaluateInt(to);
            } catch (Exception ignored) {
                enumDecl.getEntries().remove(sentinel);
                continue;
            }

            // Remove the sentinel
            enumDecl.getEntries().remove(sentinel);

            // Insert generated constants at the sentinel's position (in order)
            for (int k = fromVal; k <= toVal; k++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, k);
                String constName = innerCtx.evaluate(nameTemplate);
                EnumConstantDeclaration generated = new EnumConstantDeclaration(constName);

                String evaluatedArgs = argsTemplate.isEmpty() ? "" : innerCtx.evaluate(argsTemplate);
                if (!evaluatedArgs.isEmpty()) {
                    // Parse comma-separated args — wrap in a call to leverage StaticJavaParser
                    String callSrc = "__DUMMY__(" + evaluatedArgs + ")";
                    try {
                        NodeList<Expression> args = StaticJavaParser
                                .parseExpression(callSrc)
                                .asMethodCallExpr()
                                .getArguments();
                        args.forEach(generated::addArgument);
                    } catch (Exception ignored) {
                        // malformed args — skip
                    }
                }
                // Insert at the position where the sentinel was (pushed right each iteration)
                enumDecl.getEntries().add(sentinelIdx + (k - fromVal), generated);
            }
        }
    }

    static boolean isPermuteEnumConst(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(SIMPLE) || name.equals(FQ);
    }
}
```

- [ ] **Step 2: Build permuplate-core**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-core -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS

---

### Task 4: Add `enum` support to `PermuteProcessor` (APT)

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

The processor processes elements returned by `roundEnv.getElementsAnnotatedWith(Permute.class)`. Currently it checks the element kind to select `classDecl` vs `recordDecl`. Add `ENUM` handling.

- [ ] **Step 1: Find the element kind check**

```bash
grep -n "getKind\|ElementKind\|CLASS\|RECORD\|INTERFACE\|TypeElement" \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    | head -20
```

Locate the block that selects between `ClassOrInterfaceDeclaration` and `RecordDeclaration` based on `typeElement.getKind()`.

- [ ] **Step 2: Add `ElementKind.ENUM` handling**

In the kind-selection block, add an `ENUM` branch that:
1. Calls `cu.findFirst(EnumDeclaration.class, e -> e.getNameAsString().equals(simpleClassName))` to get the `EnumDeclaration` node
2. Renames it to `newClassName` (same as done for classes/records)
3. Strips all Permuplate annotations from it
4. Calls `PermuteEnumConstTransformer.transform(enumDecl, ctx)` to expand constants
5. Writes the generated CU

The exact code pattern should mirror the `RecordDeclaration` branch. Replace `RecordDeclaration` → `EnumDeclaration` and `getRecord...` → `getEnum...` in the appropriate API calls.

- [ ] **Step 3: Register `PermuteEnumConstTransformer` in the transform pipeline**

In the transform step (after `PermuteCaseTransformer.transform(...)` is called), add:

```java
io.quarkiverse.permuplate.core.PermuteEnumConstTransformer.transform(classDecl, ctx);
```

(The `classDecl` variable at this point is `TypeDeclaration<?>`, so the transformer's instanceof check for `EnumDeclaration` handles the dispatch cleanly.)

- [ ] **Step 4: Build the processor**

```bash
/opt/homebrew/bin/mvn install \
    -pl permuplate-annotations,permuplate-core,permuplate-processor -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Run the tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=EnumGenerationTest -q 2>&1 | tail -5
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 6: Run the full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add \
    permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteEnumConst.java \
    permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteEnumConstTransformer.java \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/EnumGenerationTest.java
git commit -m "feat: add enum template support and @PermuteEnumConst for constant expansion"
```

---

### Task 5: Add `enum` support to `InlineGenerator` (Maven plugin)

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

- [ ] **Step 1: Find where the template `TypeDeclaration` is processed**

```bash
grep -n "EnumDeclaration\|ClassOrInterfaceDeclaration\|RecordDeclaration\|instanceof\|getKind" \
    permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | head -20
```

- [ ] **Step 2: Add `EnumDeclaration` handling**

In `InlineGenerator.generate()`, after the `PermuteCaseTransformer.transform(classDecl, ctx)` call, add:

```java
PermuteEnumConstTransformer.transform(classDecl, ctx);
```

Also ensure `InlineGenerator` does not skip `EnumDeclaration` templates — check if there is a guard like `if (!(templateClassDecl instanceof ClassOrInterfaceDeclaration))` and remove/generalize it.

- [ ] **Step 3: Add the import**

```java
import io.quarkiverse.permuplate.core.PermuteEnumConstTransformer;
```

- [ ] **Step 4: Full build**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git commit -m "feat: register PermuteEnumConstTransformer in Maven plugin inline generation pipeline"
```

---

### Task 6: Add a working APT example

**Files:**
- Create: `permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/PriorityEnum1.java`

- [ ] **Step 1: Create the example**

```java
package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteEnumConst;

/**
 * Template: generates PriorityEnum2 and PriorityEnum3.
 *
 * <p>PriorityEnum2: LOW, MED (empty range from the @PermuteEnumConst)
 * <p>PriorityEnum3: LOW, MED, LEVEL3
 */
@Permute(varName = "i", from = "2", to = "3", className = "PriorityEnum${i}")
public enum PriorityEnum1 {
    LOW,
    MED,
    @PermuteEnumConst(varName = "k", from = "3", to = "${i}", name = "LEVEL${k}")
    HIGH_PLACEHOLDER;
}
```

- [ ] **Step 2: Full build including examples**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS (the example compiles and generates `PriorityEnum2`, `PriorityEnum3`).

- [ ] **Step 3: Commit**

```bash
git add permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/PriorityEnum1.java
git commit -m "example: PriorityEnum1 demonstrating enum template with @PermuteEnumConst"
```

---

### Task 7: Update README and CLAUDE.md

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add `@PermuteEnumConst` to the annotations table in README**

Add to the table:
```
| `@PermuteEnumConst` | enum constant (field) | Expand a sentinel enum constant into a sequence of constants per permutation |
```

- [ ] **Step 2: Add a `@PermuteEnumConst` section in README**

After the `@PermuteCase` section, add:

```markdown
### `@PermuteEnumConst`

Expands a sentinel enum constant into a sequence of constants per permutation. The sentinel constant is replaced by zero or more generated constants from the inner loop.

| Parameter | Meaning |
|---|---|
| `varName` | Inner loop variable name |
| `from` | Inner loop lower bound (JEXL expression) |
| `to` | Inner loop upper bound (JEXL expression); empty range removes the sentinel with no replacement |
| `name` | JEXL template for the generated constant name |
| `args` | JEXL template for constructor arguments (optional; empty = no-arg constants) |

```java
@PermuteEnumConst(varName="k", from="3", to="${i}", name="LEVEL${k}", args="${k}")
HIGH_PLACEHOLDER(99); // sentinel — removed; replaced by LEVEL3(3), LEVEL4(4), …
```
```

- [ ] **Step 3: Add enum support notes to CLAUDE.md key decisions table**

Add entry:
```
| `@Permute` on enum types | `EnumDeclaration` is handled the same as `RecordDeclaration` — it extends `TypeDeclaration<?>`, so all existing transformers work. `@PermuteMethod`, `@PermuteReturn`, and extends expansion are COID-only and already guarded. `PermuteEnumConstTransformer` dispatches on `instanceof EnumDeclaration` — a no-op on any other `TypeDeclaration` subtype. |
```

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: document @PermuteEnumConst and enum template support"
```
