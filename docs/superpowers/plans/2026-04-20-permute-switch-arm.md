# @PermuteSwitchArm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `@PermuteSwitchArm` — a new annotation that generates Java 21+ arrow-switch pattern arms (`case Type var -> body`) per permutation, analogous to `@PermuteCase` for colon-switch. Includes APT + Maven plugin pipeline registration, apt-example, IntelliJ rename propagation, and full documentation. Closes GitHub issue #74, part of epic #71.

**Architecture:** New annotation in `permuplate-annotations`. New `PermuteSwitchArmTransformer` in `permuplate-core` that finds the first `SwitchExpr` or `SwitchStmt` in the method body, evaluates `pattern`/`body`/`when` JEXL templates per inner-loop value, constructs each arm via a synthetic parse with language level JAVA_21, and inserts before the default arm. Registered in both APT and Maven plugin pipelines after `PermuteCaseTransformer`. IntelliJ plugin extended with rename propagation for the `pattern` attribute.

**Tech Stack:** Java 17 (templates), Java 21 (generated output), JavaParser 3.28.0, Apache Commons JEXL3, IntelliJ SDK (Gradle).

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Create | `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteSwitchArm.java` | Annotation definition |
| Create | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteSwitchArmTransformer.java` | AST transformation |
| Modify | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | Pipeline registration + strip set |
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | Pipeline registration |
| Create | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteSwitchArmTest.java` | Compilation tests |
| Create | `permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/ShapeDispatch2.java` | APT example |
| Modify | `permuplate-intellij-plugin/src/main/java/.../rename/AnnotationStringRenameProcessor.java` | Add to ALL_ANNOTATION_FQNS |
| Modify | `permuplate-intellij-plugin/src/main/java/.../navigation/PermuteMethodNavigator.java` | Add to ALL_ANNOTATION_FQNS |
| Modify | `permuplate-intellij-plugin/src/test/java/.../rename/AnnotationStringRenameProcessorTest.java` | Rename propagation test |
| Modify | `README.md` | New @PermuteSwitchArm section |
| Modify | `CLAUDE.md` | Annotation table + key decisions |

---

### Task 1: Define the annotation

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteSwitchArm.java`

- [ ] **Step 1: Create the annotation**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates Java 21+ arrow-switch pattern arms for the annotated method.
 *
 * <p>The annotated method must contain exactly one {@code switch} statement or
 * switch expression. For each value of the inner loop variable, one arm of the form
 * {@code case <pattern> [when <guard>] -> { <body> }} is inserted before the
 * {@code default} arm.
 *
 * <p>All attributes are JEXL expression strings evaluated in the inner loop context.
 * The outer permutation variable (e.g. {@code i}) is also available.
 *
 * <p>Example — dispatch over a generated sealed hierarchy:
 *
 * <pre>{@code
 * @PermuteSwitchArm(varName = "k", from = "1", to = "${i}",
 *                  pattern = "Shape${k} s",
 *                  body = "yield s.area();")
 * public double area(Shape shape) {
 *     return switch (shape) {
 *         case Circle c -> c.radius() * c.radius() * Math.PI;  // seed arm
 *         default -> throw new IllegalArgumentException(shape.toString());
 *     };
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteSwitchArm {

    /** Inner loop variable name (e.g. {@code "k"}). */
    String varName();

    /** Inclusive lower bound (JEXL expression, e.g. {@code "1"}). */
    String from();

    /**
     * Inclusive upper bound (JEXL expression, e.g. {@code "${i}"}).
     * When {@code from > to}, no arms are inserted (empty range).
     */
    String to();

    /**
     * JEXL template for the type pattern (e.g. {@code "Shape${k} s"}).
     * Produces the left side of {@code case <pattern> ->}.
     * May reference generated class names — rename propagation tracks this attribute.
     */
    String pattern();

    /**
     * JEXL template for the arm body — the right side of {@code ->}.
     * Use {@code yield expr;} for switch expressions, or plain statements for
     * switch statements. Block syntax ({@code { ... }}) is always valid.
     */
    String body();

    /**
     * Optional JEXL guard condition (e.g. {@code "${k} > 1"}).
     * When non-empty, generates {@code case <pattern> when <guard> -> <body>}.
     */
    String when() default "";
}
```

- [ ] **Step 2: Build annotations module**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

---

### Task 2: Write the failing tests (TDD)

**Files:**
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteSwitchArmTest.java`

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

public class PermuteSwitchArmTest {

    @Test
    public void testArmsInsertedBeforeDefault() {
        // Happy path: 2 arms (k=1..2) inserted before default; seed arm preserved.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Dispatcher2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSwitchArm;
                        @Permute(varName="i", from="3", to="3", className="Dispatcher${i}")
                        public class Dispatcher2 {
                            @PermuteSwitchArm(varName="k", from="1", to="${i-1}",
                                             pattern="Number n${k}",
                                             body="yield n${k}.intValue();")
                            public int dispatch(Number n) {
                                return switch (n) {
                                    case Integer x -> x;
                                    default -> throw new IllegalArgumentException();
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Dispatcher3").orElseThrow());

        // Seed arm preserved
        assertThat(src).contains("case Integer x");
        // Generated arms present
        assertThat(src).contains("case Number n1");
        assertThat(src).contains("case Number n2");
        assertThat(src).contains("yield n1.intValue()");
        assertThat(src).contains("yield n2.intValue()");
        // Annotation removed
        assertThat(src).doesNotContain("@PermuteSwitchArm");
    }

    @Test
    public void testEmptyRangeInsertsNoArms() {
        // from > to after evaluation → no arms, default preserved
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Solo2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSwitchArm;
                        @Permute(varName="i", from="2", to="2", className="Solo${i}")
                        public class Solo2 {
                            @PermuteSwitchArm(varName="k", from="3", to="${i}",
                                             pattern="Object o${k}",
                                             body="yield 0;")
                            public int get(Object o) {
                                return switch (o) {
                                    case String s -> s.length();
                                    default -> -1;
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Solo2").orElseThrow());

        assertThat(src).contains("case String s");
        assertThat(src).doesNotContain("case Object o");
        assertThat(src).doesNotContain("@PermuteSwitchArm");
    }

    @Test
    public void testGuardConditionGeneratedCorrectly() {
        // when="${k} > 1" produces a `when` guard on each arm
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Guarded2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSwitchArm;
                        @Permute(varName="i", from="3", to="3", className="Guarded${i}")
                        public class Guarded2 {
                            @PermuteSwitchArm(varName="k", from="1", to="${i-1}",
                                             pattern="Integer n${k}",
                                             when="${k} > 1",
                                             body="yield n${k} * 2;")
                            public int doublePositive(Object o) {
                                return switch (o) {
                                    case Long l -> (int)(long)l;
                                    default -> 0;
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Guarded3").orElseThrow());

        // k=1: guard "1 > 1" = false? No — guard is emitted literally for the source, not pre-evaluated
        // The guard expression is included as-is in the generated source
        assertThat(src).contains("when 1 > 1");
        assertThat(src).contains("when 2 > 1");
        assertThat(src).doesNotContain("@PermuteSwitchArm");
    }

    @Test
    public void testBodyAsBlock() {
        // body = "{ System.out.println(s); yield s.length(); }" — block body
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Logger2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSwitchArm;
                        @Permute(varName="i", from="3", to="3", className="Logger${i}")
                        public class Logger2 {
                            @PermuteSwitchArm(varName="k", from="1", to="${i-1}",
                                             pattern="String s${k}",
                                             body="{ System.out.println(s${k}); yield s${k}.length(); }")
                            public int process(Object o) {
                                return switch (o) {
                                    case Integer n -> n;
                                    default -> -1;
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Logger3").orElseThrow());
        assertThat(src).contains("case String s1");
        assertThat(src).contains("System.out.println(s1)");
        assertThat(src).contains("yield s1.length()");
    }
}
```

- [ ] **Step 2: Run to confirm all 4 tests fail**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-core,permuplate-processor -am -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteSwitchArmTest -q 2>&1 | tail -10
```

Expected: compilation error (annotation not found) or test failures.

---

### Task 3: Implement `PermuteSwitchArmTransformer`

**Files:**
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteSwitchArmTransformer.java`

- [ ] **Step 1: Create the transformer**

```java
package io.quarkiverse.permuplate.core;

import java.util.Optional;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;

/**
 * Handles {@code @PermuteSwitchArm} on method declarations.
 *
 * <p>For each generated class at arity {@code i}, inserts new {@link SwitchEntry}
 * arrow-switch arms into the method's switch statement or expression for each value
 * {@code k} in [{@code from}, {@code to}]. The seed arms and {@code default} arm are
 * preserved unchanged.
 *
 * <p>Arms are parsed using language level JAVA_21 to support type patterns
 * ({@code case Shape s ->}). The parser language level is restored after parsing.
 */
public class PermuteSwitchArmTransformer {

    private static final String ANNOTATION_SIMPLE = "PermuteSwitchArm";
    private static final String ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteSwitchArm";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<AnnotationExpr> annOpt = method.getAnnotations().stream()
                    .filter(PermuteSwitchArmTransformer::isPermuteSwitchArm)
                    .findFirst();
            if (annOpt.isEmpty())
                return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr normal))
                return;

            String varName = null, from = null, to = null,
                   patternExpr = null, bodyExpr = null, whenExpr = "";
            for (MemberValuePair pair : normal.getPairs()) {
                String val = pair.getValue().asStringLiteralExpr().asString();
                switch (pair.getNameAsString()) {
                    case "varName"  -> varName     = val;
                    case "from"     -> from        = val;
                    case "to"       -> to          = val;
                    case "pattern"  -> patternExpr = val;
                    case "body"     -> bodyExpr    = val;
                    case "when"     -> whenExpr    = val;
                }
            }
            if (varName == null || from == null || to == null
                    || patternExpr == null || bodyExpr == null)
                return;

            // Find the switch (statement or expression) in the method body.
            // @PermuteSwitchArm requires arrow-switch; we look for SwitchStmt
            // (covers both switch statements and the desugared form of switch expressions
            // as parsed by JavaParser when the language level is >= JAVA_14).
            Optional<SwitchStmt> switchOpt = method.getBody()
                    .flatMap(body -> body.findFirst(SwitchStmt.class));
            if (switchOpt.isEmpty())
                return;

            SwitchStmt sw = switchOpt.get();

            // Evaluate loop bounds
            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(from);
                toVal   = ctx.evaluateInt(to);
            } catch (Exception ignored) {
                return;
            }

            // Remove annotation before potentially returning (empty range is valid)
            method.getAnnotations().remove(ann);

            if (fromVal > toVal)
                return;

            int defaultIdx = findDefaultArmIndex(sw);

            for (int k = fromVal; k <= toVal; k++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, k);
                String pattern = innerCtx.evaluate(patternExpr);
                String body    = innerCtx.evaluate(bodyExpr);
                String guard   = whenExpr.isEmpty() ? "" : innerCtx.evaluate(whenExpr);

                SwitchEntry arm = buildArm(pattern, guard, body);
                sw.getEntries().add(defaultIdx, arm);
                defaultIdx++;
            }
        });
    }

    /**
     * Finds the index of the default arm (no labels) for insertion-before-default.
     * Returns entries.size() if no default exists.
     */
    private static int findDefaultArmIndex(SwitchStmt sw) {
        NodeList<SwitchEntry> entries = sw.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getLabels().isEmpty())
                return i;
        }
        return entries.size();
    }

    /**
     * Builds a single arrow-switch arm by parsing a synthetic switch statement
     * with language level JAVA_21 and extracting the first entry.
     *
     * <p>Synthetic form: {@code switch (__x__) { case <pattern> [when <guard>] -> { <body> } default -> null; }}
     *
     * <p>Using a block body ({@code { body }}) for all arms is safe: JavaParser
     * produces a BLOCK-type entry, which is valid for both switch statements and
     * switch expressions (with {@code yield} in the body for expressions).
     */
    private static SwitchEntry buildArm(String pattern, String guard, String body) {
        String guardPart = guard.isEmpty() ? "" : " when " + guard;
        // Wrap body in { } if not already a block
        String blockBody = body.trim().startsWith("{") ? body.trim() : "{ " + body + " }";
        String synthetic = "switch (__x__) { case " + pattern + guardPart
                + " -> " + blockBody + " default -> { throw new UnsupportedOperationException(); } }";

        ParserConfiguration cfg = StaticJavaParser.getParserConfiguration();
        ParserConfiguration.LanguageLevel prev = cfg.getLanguageLevel();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        try {
            SwitchStmt temp = StaticJavaParser.parseStatement(synthetic).asSwitchStmt();
            return temp.getEntries().get(0).clone();
        } finally {
            cfg.setLanguageLevel(prev);
        }
    }

    static boolean isPermuteSwitchArm(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(ANNOTATION_SIMPLE) || name.equals(ANNOTATION_FQ);
    }
}
```

- [ ] **Step 2: Build permuplate-core**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-core -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

---

### Task 4: Register in APT pipeline (`PermuteProcessor`)

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

- [ ] **Step 1: Find where PermuteCaseTransformer is called**

```bash
grep -n "PermuteCaseTransformer\|PermuteSwitchArmTransformer" \
    /Users/mdproctor/claude/permuplate/permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    | head -5
```

- [ ] **Step 2: Add call immediately after PermuteCaseTransformer**

Add:
```java
// 5e2. @PermuteSwitchArm — generate Java 21+ arrow-switch pattern arms
io.quarkiverse.permuplate.core.PermuteSwitchArmTransformer.transform(classDecl, ctx);
```

- [ ] **Step 3: Build and run tests**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-core,permuplate-processor -am -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteSwitchArmTest -q 2>&1 | tail -5
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 4: Run full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

- [ ] **Step 5: Stage and commit**

```bash
git add \
    permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteSwitchArm.java \
    permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteSwitchArmTransformer.java \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteSwitchArmTest.java
git commit -m "feat: add @PermuteSwitchArm annotation and transformer for Java 21+ arrow-switch pattern arms"
```

---

### Task 5: Register in Maven plugin (`InlineGenerator`)

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

- [ ] **Step 1: Find PermuteCaseTransformer call in InlineGenerator**

```bash
grep -n "PermuteCaseTransformer\|PermuteBodyTransformer" \
    /Users/mdproctor/claude/permuplate/permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | head -10
```

- [ ] **Step 2: Add import and call in COID branch**

Add import at top of file:
```java
import io.quarkiverse.permuplate.core.PermuteSwitchArmTransformer;
```

In the COID branch (after `PermuteCaseTransformer.transform(generated, ctx)`):
```java
// @PermuteSwitchArm — generate Java 21+ arrow-switch pattern arms
PermuteSwitchArmTransformer.transform(generated, ctx);
```

- [ ] **Step 3: Add to enum branch**

In the enum branch (after `PermuteCaseTransformer.transform(generated, ctx)`):
```java
PermuteSwitchArmTransformer.transform(generated, ctx);
```

- [ ] **Step 4: Add to `stripPermuteAnnotations` set**

Find the `PERMUPLATE_ANNOTATIONS` set in `stripPermuteAnnotations()` (contains `"PermuteBody"`, `"PermuteEnumConst"`, etc.). Add:
```java
"PermuteSwitchArm", "io.quarkiverse.permuplate.PermuteSwitchArm",
```

- [ ] **Step 5: Full build**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

- [ ] **Step 6: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git commit -m "feat: register PermuteSwitchArmTransformer in Maven plugin pipeline"
```

---

### Task 6: APT example

**Files:**
- Create: `permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/ShapeDispatch2.java`

- [ ] **Step 1: Create the example**

This example generates `ShapeDispatch3` through `ShapeDispatch5`. Each generated class dispatches over all shapes added at that arity. The example also shows that the generated code compiles when the target Java version supports pattern matching.

```java
package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteSwitchArm;

/**
 * Demonstrates {@code @PermuteSwitchArm} — Java 21+ arrow-switch pattern arm generation.
 *
 * <p>Template class {@code ShapeDispatch2} generates {@code ShapeDispatch3..5}.
 * Each generated class adds a {@code Number} arm to the dispatch method for each arity.
 *
 * <p>Note: the generated source uses Java 21 pattern matching syntax ({@code case Type var ->}).
 * The APT-generated {@code .java} files compile cleanly when the consuming project targets Java 21+.
 */
@Permute(varName = "i", from = "3", to = "5", className = "ShapeDispatch${i}")
public class ShapeDispatch2 {

    /**
     * Dispatches on the type of {@code obj}, with one arm per arity index.
     * The seed arm handles {@code String}; generated arms handle {@code Number} subtypes.
     */
    @PermuteSwitchArm(varName = "k", from = "1", to = "${i-1}",
                      pattern = "Number n${k}",
                      body = "yield n${k}.intValue();")
    public int dispatch(Object obj) {
        return switch (obj) {
            case String s -> s.length();
            default -> -1;
        };
    }
}
```

- [ ] **Step 2: Build apt-examples**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-apt-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS (generated source files for ShapeDispatch3..5 appear in target/).

- [ ] **Step 3: Verify generated source contains pattern arms**

```bash
find /Users/mdproctor/claude/permuplate/permuplate-apt-examples/target \
    -name "ShapeDispatch3.java" | xargs grep "case Number n" 2>/dev/null | head -5
```

Expected: `case Number n1`, `case Number n2` in `ShapeDispatch3`.

- [ ] **Step 4: Commit**

```bash
git add permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/ShapeDispatch2.java
git commit -m "example: ShapeDispatch demonstrates @PermuteSwitchArm Java 21+ pattern arm generation"
```

---

### Task 7: IntelliJ plugin — rename propagation

**Files:**
- Modify: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java`
- Modify: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/navigation/PermuteMethodNavigator.java`

- [ ] **Step 1: Add to `AnnotationStringRenameProcessor.ALL_ANNOTATION_FQNS`**

Find the `Set.of(...)` at line ~48 in `AnnotationStringRenameProcessor.java`. Add:
```java
"io.quarkiverse.permuplate.PermuteSwitchArm",
```

- [ ] **Step 2: Add to `PermuteMethodNavigator.ALL_ANNOTATION_FQNS`**

Find the equivalent set in `PermuteMethodNavigator.java`. Add:
```java
"io.quarkiverse.permuplate.PermuteSwitchArm",
```

- [ ] **Step 3: Build the IntelliJ plugin**

From `permuplate-intellij-plugin/`:
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew build -x test 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

---

### Task 8: IntelliJ plugin — rename propagation test

**Files:**
- Modify: `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessorTest.java`

- [ ] **Step 1: Add the test**

Add at the end of `AnnotationStringRenameProcessorTest`:

```java
public void testClassRenameUpdatesPermuteSwitchArmPattern() {
    myFixture.configureByText("Dispatch2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.*;\n" +
            "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Dispatch${i}\")\n" +
            "public class Dispatch2 {\n" +
            "    @PermuteSwitchArm(varName=\"k\", from=\"1\", to=\"${i-1}\",\n" +
            "                     pattern=\"Shape<caret>2 s\",\n" +
            "                     body=\"yield s.area();\")\n" +
            "    public double area(Object o) {\n" +
            "        return switch (o) { default -> 0.0; };\n" +
            "    }\n" +
            "}");

    // Renaming the class "Shape2" → "Geom2" should update the pattern string
    PsiClass shape2 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Shape2",
                    GlobalSearchScope.allScope(getProject()));
    if (shape2 == null) {
        // Shape2 not in index — add it as a fixture
        myFixture.addFileToProject("Shape2.java",
                "package io.example; public class Shape2 {}");
        shape2 = JavaPsiFacade.getInstance(getProject())
                .findClass("io.example.Shape2",
                        GlobalSearchScope.allScope(getProject()));
    }
    myFixture.renameElement(shape2, "Geom2");

    myFixture.checkResult(
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.*;\n" +
            "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Dispatch${i}\")\n" +
            "public class Dispatch2 {\n" +
            "    @PermuteSwitchArm(varName=\"k\", from=\"1\", to=\"${i-1}\",\n" +
            "                     pattern=\"Geom${i} s\",\n" +
            "                     body=\"yield s.area();\")\n" +
            "    public double area(Object o) {\n" +
            "        return switch (o) { default -> 0.0; };\n" +
            "    }\n" +
            "}");
}
```

- [ ] **Step 2: Run IntelliJ plugin tests**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test 2>&1 | tail -10
```

Expected: all tests pass (including the new one).

- [ ] **Step 3: Commit**

```bash
git add \
    permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java \
    permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/navigation/PermuteMethodNavigator.java \
    permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessorTest.java
git commit -m "feat: extend IntelliJ rename propagation and navigation to @PermuteSwitchArm"
```

---

### Task 9: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add `### @PermuteSwitchArm` section**

Find the `### @PermuteCase` section. Add immediately after it:

```markdown
### `@PermuteSwitchArm`

Generates Java 21+ arrow-switch pattern arms (`case Type var -> body`) for the annotated method. The method must contain exactly one switch statement or switch expression. Each generated arm is inserted before the `default` arm.

| Parameter | Meaning |
|---|---|
| `varName` | Inner loop variable name |
| `from` | Inner loop lower bound (JEXL expression) |
| `to` | Inner loop upper bound; empty range inserts no arms |
| `pattern` | JEXL template for the type pattern (e.g. `"Shape${k} s"`) |
| `body` | JEXL template for the arm body (expression or `{ block }`) |
| `when` | Optional JEXL guard condition (e.g. `"${k} > 1"`) |

```java
@PermuteSwitchArm(varName="k", from="1", to="${i}",
                  pattern="Shape${k} s",
                  body="yield s.area();")
public double area(Shape shape) {
    return switch (shape) {
        case Circle c -> c.radius() * c.radius() * Math.PI;  // seed arm — preserved
        default -> throw new IllegalArgumentException();
    };
}
```

The `pattern` attribute may contain generated class names — the IntelliJ plugin propagates renames atomically. Use `@PermuteCase` for classic colon-switch; use `@PermuteSwitchArm` for Java 21+ arrow-switch.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document @PermuteSwitchArm in README"
```

---

### Task 10: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add to annotations table**

In the annotations table, add after `@PermuteCase`:
```
| `@PermuteSwitchArm` | method | Generate Java 21+ arrow-switch pattern arms per permutation; `pattern`/`body`/`when` are JEXL templates; IntelliJ rename propagation covers `pattern` |
```

- [ ] **Step 2: Add key decisions entry**

```
| `@PermuteSwitchArm` body parsing | Constructs a synthetic `switch (__x__) { case <pattern> [when <guard>] -> { <body> } default -> { throw ...; } }` with language level JAVA_21, extracts the first `SwitchEntry`, and restores the previous language level. Block body wrapping (`{ }`) is always applied. This avoids direct `TypePatternExpr` AST construction — simpler and more robust as Java 21 AST evolves. |
| `@PermuteSwitchArm` vs `@PermuteCase` | `@PermuteCase` targets colon-switch (`case N:`); `@PermuteSwitchArm` targets arrow-switch (`case Type var ->`). Java forbids mixing both in one switch, so they are intentionally separate annotations. `@PermuteSwitchArm` is registered in the pipeline immediately after `@PermuteCase`. |
```

- [ ] **Step 3: Full clean build**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document @PermuteSwitchArm in CLAUDE.md annotations table and key decisions"
```
