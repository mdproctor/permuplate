# G1 — Generic Type Parameter Arity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `@PermuteTypeParam` annotation that expands a sentinel class type parameter into a sequence, enabling type-safe APIs like `Condition3<T1,T2,T3>` and `Consumer3<T1,T2,T3>` to be generated from a single template.

**Architecture:** A new `PermuteTypeParamTransformer` class in `permuplate-core` handles both explicit expansion (via `@PermuteTypeParam` on a type param) and implicit expansion (auto-detected when `@PermuteParam` uses `T${j}` type referencing a class type param). The transformer is injected into the existing pipeline in both `PermuteProcessor` (APT) and `InlineGenerator` (Maven plugin), called after class rename and before `PermuteDeclrTransformer`.

**Tech Stack:** JavaParser (AST manipulation), Google compile-testing (tests), JUnit 4, Apache Commons JEXL3 (already via EvaluationContext).

---

## File Map

| File | Change |
|---|---|
| `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteTypeParam.java` | **Create** — new annotation |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java` | **Create** — expansion logic (explicit + implicit + R1/R3/R4 validation) |
| `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | **Modify** — call transformer in `generatePermutation()` after rename |
| `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | **Modify** — call transformer in `generate()` after rename; add to `stripPermuteAnnotations` |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTypeParamTest.java` | **Create** — all G1 test cases |

---

## Task 1: Create `@PermuteTypeParam` annotation

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteTypeParam.java`

- [ ] **Step 1: Create the annotation**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Expands a sentinel class type parameter into a sequence of type parameters.
 *
 * <p>Place on a type parameter of a class or interface annotated with {@code @Permute}.
 * The sentinel type parameter is replaced by {@code (to - from + 1)} generated type
 * parameters, each named by evaluating {@code name} with the inner loop variable.
 *
 * <p><b>Bounds propagation:</b> the sentinel's declared bound (e.g.
 * {@code T1 extends Comparable<T1>}) is copied to each generated parameter with the
 * sentinel name substituted ({@code T2 extends Comparable<T2>}, etc.).
 *
 * <p><b>Not needed in the common case</b> — when {@link PermuteParam} expands a method
 * parameter whose type is {@code T${j}} and {@code T1} is a class type parameter, the
 * class type parameters are expanded automatically to match. Use {@code @PermuteTypeParam}
 * only for phantom types (type parameters with no corresponding {@link PermuteParam}).
 *
 * <p>Example — explicit phantom type:
 * <pre>{@code
 * @Permute(varName="i", from=2, to=5, className="Step${i}")
 * public class Step1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1> { }
 * // → Step2<T1, T2>, Step3<T1, T2, T3>, ...
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE_PARAMETER)
public @interface PermuteTypeParam {

    /** Inner loop variable name (e.g. {@code "j"}). */
    String varName();

    /** Inner lower bound — literal or expression (e.g. {@code "1"}). */
    String from();

    /**
     * Inner upper bound — expression evaluated against the outer context
     * (e.g. {@code "${i}"}).
     */
    String to();

    /** Generated type parameter name template (e.g. {@code "T${j}"}). */
    String name();
}
```

- [ ] **Step 2: Build to confirm the annotation compiles**

```bash
/opt/homebrew/bin/mvn clean install -DskipTests -pl permuplate-annotations -am
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteTypeParam.java
git commit -m "feat(g1): add @PermuteTypeParam annotation"
```

---

## Task 2: Write failing tests for explicit type parameter expansion

**Files:**
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTypeParamTest.java`

These tests will fail until Task 3 is complete.

- [ ] **Step 1: Create the test class**

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

/**
 * Tests for @PermuteTypeParam — explicit and implicit type parameter expansion.
 *
 * <p>All templates are compiled with the APT processor via Google compile-testing.
 * Assertions check the generated source text.
 */
public class PermuteTypeParamTest {

    // -------------------------------------------------------------------------
    // Explicit @PermuteTypeParam — no bounds
    // -------------------------------------------------------------------------

    @Test
    public void testExplicitExpansionNoBounds() {
        // @PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") on T1
        // For i=3 → Condition3<T1, T2, T3>
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Condition1",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteTypeParam;
                @Permute(varName="i", from=3, to=3, className="Condition${i}")
                public interface Condition1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1> {
                    boolean test(T1 fact);
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Condition3")
                .orElseThrow());
        assertThat(src).contains("Condition3<T1, T2, T3>");
        assertThat(src).doesNotContain("@PermuteTypeParam");
        assertThat(src).doesNotContain("@Permute");
    }

    // -------------------------------------------------------------------------
    // Explicit @PermuteTypeParam — with bounds
    // -------------------------------------------------------------------------

    @Test
    public void testExplicitExpansionWithBounds() {
        // T1 extends Comparable<T1> → T1 extends Comparable<T1>, T2 extends Comparable<T2>, ...
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.SortedCondition1",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteTypeParam;
                @Permute(varName="i", from=3, to=3, className="SortedCondition${i}")
                public interface SortedCondition1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1 extends Comparable<T1>> {
                    boolean test(T1 fact);
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.SortedCondition3")
                .orElseThrow());
        assertThat(src).contains("T1 extends Comparable<T1>");
        assertThat(src).contains("T2 extends Comparable<T2>");
        assertThat(src).contains("T3 extends Comparable<T3>");
    }

    // -------------------------------------------------------------------------
    // Explicit @PermuteTypeParam — range of generated classes
    // -------------------------------------------------------------------------

    @Test
    public void testExplicitExpansionRange() {
        // from=2 to=4 generates Condition2<T1,T2>, Condition3<T1,T2,T3>, Condition4<T1,T2,T3,T4>
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.RangeCondition1",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteTypeParam;
                @Permute(varName="i", from=2, to=4, className="RangeCondition${i}")
                public interface RangeCondition1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1> {
                    boolean test(T1 fact);
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.RangeCondition2")
                .orElseThrow()))
                .contains("RangeCondition2<T1, T2>");
        assertThat(sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.RangeCondition4")
                .orElseThrow()))
                .contains("RangeCondition4<T1, T2, T3, T4>");
    }

    // -------------------------------------------------------------------------
    // Implicit expansion — @PermuteParam type="T${j}" triggers class type param expansion
    // -------------------------------------------------------------------------

    @Test
    public void testImplicitExpansionNoBounds() {
        // No @PermuteTypeParam — triggered by @PermuteParam with type="T${j}"
        // For i=3 → Consumer3<T1, T2, T3> with accept(T1 arg1, T2 arg2, T3 arg3)
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Consumer1",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteParam;
                @Permute(varName="i", from=3, to=3, className="Consumer${i}")
                public interface Consumer1<T1> {
                    void accept(
                        @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="arg${j}") T1 arg1);
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Consumer3")
                .orElseThrow());
        assertThat(src).contains("Consumer3<T1, T2, T3>");
        assertThat(src).contains("void accept(T1 arg1, T2 arg2, T3 arg3)");
        assertThat(src).doesNotContain("@PermuteTypeParam");
        assertThat(src).doesNotContain("@PermuteParam");
    }

    @Test
    public void testImplicitExpansionWithBounds() {
        // T1 extends Comparable<T1> with implicit expansion
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.BoundedConsumer1",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteParam;
                @Permute(varName="i", from=3, to=3, className="BoundedConsumer${i}")
                public interface BoundedConsumer1<T1 extends Comparable<T1>> {
                    void accept(
                        @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="arg${j}") T1 arg1);
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.BoundedConsumer3")
                .orElseThrow());
        assertThat(src).contains("T1 extends Comparable<T1>");
        assertThat(src).contains("T2 extends Comparable<T2>");
        assertThat(src).contains("T3 extends Comparable<T3>");
        assertThat(src).contains("void accept(T1 arg1, T2 arg2, T3 arg3)");
    }

    @Test
    public void testImplicitExpansionFixedTypeParamSurvives() {
        // Fixed type param R (return type) must pass through unchanged
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Transformer1",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteParam;
                @Permute(varName="i", from=3, to=3, className="Transformer${i}")
                public interface Transformer1<T1, R> {
                    R apply(
                        @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="input${j}") T1 input1);
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Transformer3")
                .orElseThrow());
        // T1, T2, T3 expanded; R stays fixed
        assertThat(src).contains("Transformer3<T1, T2, T3, R>");
        assertThat(src).contains("R apply(T1 input1, T2 input2, T3 input3)");
    }

    // -------------------------------------------------------------------------
    // Validation: R1 — return type must not reference expanding type param
    // -------------------------------------------------------------------------

    @Test
    public void testR1ReturnTypeReferencesExpandingParam() {
        // Mapper1: apply() returns T1 (the expanding param) — R1 error
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Mapper1",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteParam;
                @Permute(varName="i", from=3, to=3, className="Mapper${i}")
                public interface Mapper1<T1> {
                    T1 apply(
                        @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="input${j}") T1 input1);
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("return type");
        assertThat(compilation).hadErrorContaining("expanding type parameter");
    }

    // -------------------------------------------------------------------------
    // Validation: R3 — @PermuteTypeParam name prefix must match sentinel
    // -------------------------------------------------------------------------

    @Test
    public void testR3NamePrefixMismatch() {
        // name="X${j}" but sentinel is T1 — leading literal "X" not prefix of "T1"
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.BadPrefix1",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteTypeParam;
                @Permute(varName="i", from=3, to=3, className="BadPrefix${i}")
                public interface BadPrefix1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="X${j}") T1> {
                    boolean test(T1 fact);
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("prefix");
    }

    // -------------------------------------------------------------------------
    // Validation: R4 — from > to is invalid
    // -------------------------------------------------------------------------

    @Test
    public void testR4FromGreaterThanTo() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.BadRange1",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteTypeParam;
                @Permute(varName="i", from=3, to=3, className="BadRange${i}")
                public interface BadRange1<@PermuteTypeParam(varName="j", from="5", to="2", name="T${j}") T1> {
                    boolean test(T1 fact);
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("invalid range");
    }
}
```

- [ ] **Step 2: Run to confirm all tests fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am --no-transfer-progress 2>&1 | grep -E "FAIL|ERROR|Tests run|BUILD" | tail -5
```

Expected: BUILD FAILURE — `PermuteTypeParam` not found on classpath (annotation doesn't exist in compiled code yet for these tests), OR tests compile but fail because the processor doesn't expand type params.

- [ ] **Step 3: Commit the failing tests**

```bash
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTypeParamTest.java
git commit -m "test(g1): add failing PermuteTypeParamTest (TDD)"
```

---

## Task 3: Implement `PermuteTypeParamTransformer`

**Files:**
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java`

- [ ] **Step 1: Create the transformer**

```java
package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;

/**
 * Expands class type parameters annotated with {@code @PermuteTypeParam}, or implicitly
 * when {@code @PermuteParam} references a class type parameter via the {@code T${j}} naming
 * convention.
 *
 * <p>Transformation is done in-place on the cloned {@link ClassOrInterfaceDeclaration}
 * that is already undergoing permutation. This transformer runs <em>before</em>
 * {@link PermuteDeclrTransformer} and {@link PermuteParamTransformer} in the pipeline.
 *
 * <p><b>Pipeline position:</b>
 * <ol>
 * <li>Clone + rename class</li>
 * <li><b>PermuteTypeParamTransformer.transform()</b> ← this class</li>
 * <li>PermuteDeclrTransformer.transform()</li>
 * <li>PermuteParamTransformer.transform()</li>
 * <li>Strip annotations</li>
 * </ol>
 */
public class PermuteTypeParamTransformer {

    private static final String ANNOTATION_SIMPLE = "PermuteTypeParam";
    private static final String ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteTypeParam";
    private static final String PARAM_ANNOTATION_SIMPLE = "PermuteParam";
    private static final String PARAM_ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteParam";

    /**
     * Expands class type parameters and validates constraints.
     *
     * @param classDecl the cloned class declaration being transformed
     * @param ctx       the outer permutation context (contains {@code i}, string vars, etc.)
     * @param messager  for error reporting; may be {@code null} in Maven plugin mode
     * @param element   the annotated element for error location; may be {@code null}
     * @return names of the sentinel type parameters that were expanded (used for R1 check)
     */
    public static Set<String> transform(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager,
            Element element) {

        // Step 1: detect which type params will be expanded (for R1 pre-check)
        Set<String> willExpand = detectExpansions(classDecl, ctx);

        // Step 2: R1 — validate return type does not reference expanding type params
        if (messager != null && !willExpand.isEmpty()) {
            validateR1(classDecl, willExpand, messager, element);
        }

        // Step 3: expand explicit @PermuteTypeParam annotations
        Set<String> expanded = new HashSet<>();
        expanded.addAll(expandExplicit(classDecl, ctx, messager, element));

        // Step 4: expand implicit (from @PermuteParam with T${j} type)
        expanded.addAll(expandImplicit(classDecl, ctx, expanded));

        return expanded;
    }

    // -------------------------------------------------------------------------
    // Detection (dry-run to find which type params will expand — for R1 check)
    // -------------------------------------------------------------------------

    private static Set<String> detectExpansions(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx) {
        Set<String> sentinels = new HashSet<>();

        // Explicit: @PermuteTypeParam annotations
        for (TypeParameter tp : classDecl.getTypeParameters()) {
            if (hasAnnotation(tp.getAnnotations(), ANNOTATION_SIMPLE)) {
                sentinels.add(tp.getNameAsString());
            }
        }

        // Implicit: @PermuteParam with type="T${j}" where T(j=from) is a class type param
        Set<String> classTypeParamNames = typeParamNames(classDecl);
        for (MethodDeclaration method : classDecl.getMethods()) {
            for (Parameter param : method.getParameters()) {
                Optional<NormalAnnotationExpr> ann = findParamAnnotation(param);
                if (ann.isEmpty()) continue;
                String typeTemplate = getAttr(ann.get(), "type");
                String varName = getAttr(ann.get(), "varName");
                String fromStr = getAttr(ann.get(), "from");
                if (typeTemplate == null || varName == null || fromStr == null) continue;
                try {
                    int fromVal = ctx.evaluateInt(fromStr);
                    String typeAtFrom = ctx.withVariable(varName, fromVal).evaluate(typeTemplate);
                    if (classTypeParamNames.contains(typeAtFrom) && !sentinels.contains(typeAtFrom)) {
                        sentinels.add(typeAtFrom);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return sentinels;
    }

    // -------------------------------------------------------------------------
    // R1 validation — return type must not reference expanding type params
    // -------------------------------------------------------------------------

    private static void validateR1(ClassOrInterfaceDeclaration classDecl,
            Set<String> expandingSentinels,
            Messager messager,
            Element element) {
        for (MethodDeclaration method : classDecl.getMethods()) {
            String returnType = method.getTypeAsString();
            for (String sentinel : expandingSentinels) {
                // Check if return type contains the sentinel name as a word boundary
                // Use simple contains — false positives are rare and the error message is clear
                if (containsTypeRef(returnType, sentinel)) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@PermuteParam implicit type expansion: return type \"" + returnType +
                                    "\" references an expanding type parameter — ambiguous across permutations." +
                                    " Use Object or a fixed container type.",
                            element);
                }
            }
        }
    }

    /** Returns true if {@code text} contains {@code typeName} as a standalone identifier. */
    private static boolean containsTypeRef(String text, String typeName) {
        int idx = text.indexOf(typeName);
        while (idx >= 0) {
            boolean before = idx == 0 || !Character.isJavaIdentifierPart(text.charAt(idx - 1));
            int end = idx + typeName.length();
            boolean after = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
            if (before && after) return true;
            idx = text.indexOf(typeName, idx + 1);
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Explicit expansion — @PermuteTypeParam on type parameter
    // -------------------------------------------------------------------------

    private static Set<String> expandExplicit(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager,
            Element element) {
        Set<String> expanded = new HashSet<>();
        NodeList<TypeParameter> current = classDecl.getTypeParameters();
        NodeList<TypeParameter> result = new NodeList<>();

        for (TypeParameter tp : current) {
            Optional<NormalAnnotationExpr> ann = findTypeParamAnnotation(tp);
            if (ann.isEmpty()) {
                result.add(tp);
                continue;
            }

            NormalAnnotationExpr normal = ann.get();
            String varName = getAttr(normal, "varName");
            String fromStr = getAttr(normal, "from");
            String toStr = getAttr(normal, "to");
            String nameTemplate = getAttr(normal, "name");
            String sentinelName = tp.getNameAsString();

            // R4: from > to
            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(fromStr);
                toVal = ctx.evaluateInt(toStr);
            } catch (Exception e) {
                if (messager != null) messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteTypeParam: cannot evaluate range: " + e.getMessage(), element);
                result.add(tp);
                continue;
            }
            if (fromVal > toVal) {
                if (messager != null) messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteTypeParam has invalid range: from=" + fromVal +
                                " is greater than to=" + toVal, element);
                result.add(tp);
                continue;
            }

            // R3: name leading literal must be a prefix of sentinel name
            String leadingLiteral = nameTemplate.contains("${")
                    ? nameTemplate.substring(0, nameTemplate.indexOf("${"))
                    : nameTemplate;
            if (!leadingLiteral.isEmpty() && !sentinelName.startsWith(leadingLiteral)) {
                if (messager != null) messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteTypeParam name literal part \"" + leadingLiteral +
                                "\" is not a prefix of type parameter \"" + sentinelName + "\"",
                        element);
                result.add(tp);
                continue;
            }

            // Expand
            expanded.add(sentinelName);
            for (int j = fromVal; j <= toVal; j++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, j);
                String newName = innerCtx.evaluate(nameTemplate);
                result.add(buildTypeParam(newName, tp, sentinelName));
            }
        }

        classDecl.setTypeParameters(result);
        return expanded;
    }

    // -------------------------------------------------------------------------
    // Implicit expansion — triggered by @PermuteParam with type="T${j}"
    // -------------------------------------------------------------------------

    private static Set<String> expandImplicit(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Set<String> alreadyExpanded) {
        Set<String> expanded = new HashSet<>();
        Set<String> classTypeParamNames = typeParamNames(classDecl);

        for (MethodDeclaration method : classDecl.getMethods()) {
            for (Parameter param : method.getParameters()) {
                Optional<NormalAnnotationExpr> ann = findParamAnnotation(param);
                if (ann.isEmpty()) continue;

                NormalAnnotationExpr normal = ann.get();
                String typeTemplate = getAttr(normal, "type");
                String varName = getAttr(normal, "varName");
                String fromStr = getAttr(normal, "from");
                String toStr = getAttr(normal, "to");
                if (typeTemplate == null || varName == null || fromStr == null || toStr == null) continue;

                int fromVal;
                try {
                    fromVal = ctx.evaluateInt(fromStr);
                } catch (Exception ignored) { continue; }

                String typeAtFrom;
                try {
                    typeAtFrom = ctx.withVariable(varName, fromVal).evaluate(typeTemplate);
                } catch (Exception ignored) { continue; }

                // Is typeAtFrom a class type param not already expanded?
                if (!classTypeParamNames.contains(typeAtFrom) || alreadyExpanded.contains(typeAtFrom)
                        || expanded.contains(typeAtFrom)) continue;

                // Find the sentinel TypeParameter
                Optional<TypeParameter> sentinelTp = classDecl.getTypeParameters().stream()
                        .filter(tp -> tp.getNameAsString().equals(typeAtFrom))
                        .findFirst();
                if (sentinelTp.isEmpty()) continue;

                int toVal;
                try {
                    toVal = ctx.evaluateInt(toStr);
                } catch (Exception ignored) { continue; }

                // Expand the sentinel using the same j loop
                String sentinelName = typeAtFrom;
                expanded.add(sentinelName);

                NodeList<TypeParameter> current = classDecl.getTypeParameters();
                NodeList<TypeParameter> result = new NodeList<>();
                for (TypeParameter tp : current) {
                    if (!tp.getNameAsString().equals(sentinelName)) {
                        result.add(tp);
                        continue;
                    }
                    for (int j = fromVal; j <= toVal; j++) {
                        EvaluationContext innerCtx = ctx.withVariable(varName, j);
                        String newName = innerCtx.evaluate(typeTemplate); // e.g. "T${j}" → "T1"
                        result.add(buildTypeParam(newName, tp, sentinelName));
                    }
                }
                classDecl.setTypeParameters(result);
                classTypeParamNames = typeParamNames(classDecl); // refresh after expansion
            }
        }
        return expanded;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a new TypeParameter with the given name, copying and substituting bounds
     * from the sentinel.
     */
    private static TypeParameter buildTypeParam(String newName, TypeParameter sentinel,
            String sentinelName) {
        TypeParameter newTp = new TypeParameter(newName);
        NodeList<ClassOrInterfaceType> newBounds = new NodeList<>();
        for (ClassOrInterfaceType bound : sentinel.getTypeBound()) {
            // Substitute all occurrences of sentinelName in the bound text with newName.
            // toString() gives the full bound as a string, e.g. "Comparable<T1>".
            String boundStr = bound.toString().replace(sentinelName, newName);
            newBounds.add(StaticJavaParser.parseClassOrInterfaceType(boundStr));
        }
        newTp.setTypeBound(newBounds);
        return newTp;
    }

    private static Set<String> typeParamNames(ClassOrInterfaceDeclaration classDecl) {
        Set<String> names = new HashSet<>();
        for (TypeParameter tp : classDecl.getTypeParameters()) {
            names.add(tp.getNameAsString());
        }
        return names;
    }

    private static boolean hasAnnotation(NodeList<AnnotationExpr> annotations, String simpleName) {
        for (AnnotationExpr ann : annotations) {
            String n = ann.getNameAsString();
            if (n.equals(simpleName) || n.equals("io.quarkiverse.permuplate." + simpleName)) return true;
        }
        return false;
    }

    private static Optional<NormalAnnotationExpr> findTypeParamAnnotation(TypeParameter tp) {
        for (AnnotationExpr ann : tp.getAnnotations()) {
            String n = ann.getNameAsString();
            if ((n.equals(ANNOTATION_SIMPLE) || n.equals(ANNOTATION_FQ))
                    && ann instanceof NormalAnnotationExpr) {
                return Optional.of((NormalAnnotationExpr) ann);
            }
        }
        return Optional.empty();
    }

    private static Optional<NormalAnnotationExpr> findParamAnnotation(Parameter param) {
        for (AnnotationExpr ann : param.getAnnotations()) {
            String n = ann.getNameAsString();
            if ((n.equals(PARAM_ANNOTATION_SIMPLE) || n.equals(PARAM_ANNOTATION_FQ))
                    && ann instanceof NormalAnnotationExpr) {
                return Optional.of((NormalAnnotationExpr) ann);
            }
        }
        return Optional.empty();
    }

    private static String getAttr(NormalAnnotationExpr ann, String attrName) {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(attrName)) {
                return PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            }
        }
        return null;
    }
}
```

- [ ] **Step 2: Build core module to confirm it compiles**

```bash
/opt/homebrew/bin/mvn clean install -DskipTests -pl permuplate-core -am
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit the transformer**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java
git commit -m "feat(g1): implement PermuteTypeParamTransformer (explicit + implicit expansion)"
```

---

## Task 4: Integrate transformer into the pipeline

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

### PermuteProcessor changes

- [ ] **Step 1: Add import to PermuteProcessor**

Find the imports block in `PermuteProcessor.java` and add:
```java
import io.quarkiverse.permuplate.core.PermuteTypeParamTransformer;
```

- [ ] **Step 2: Call the transformer in `generatePermutation()`**

In `generatePermutation()`, find this comment and the two lines after it:
```java
        // 2, 3 & 4. @PermuteDeclr — fields, constructor params, then for-each vars
        PermuteDeclrTransformer.transform(classDecl, ctx, processingEnv.getMessager());
```

Replace with:
```java
        // 1b. Expand class type parameters (@PermuteTypeParam — explicit and implicit)
        PermuteTypeParamTransformer.transform(classDecl, ctx,
                processingEnv.getMessager(), typeElement);

        // 2, 3 & 4. @PermuteDeclr — fields, constructor params, then for-each vars
        PermuteDeclrTransformer.transform(classDecl, ctx, processingEnv.getMessager());
```

- [ ] **Step 3: Strip @PermuteTypeParam in the annotation-removal block**

In `generatePermutation()`, find:
```java
        // 6. Remove @Permute from the class
        classDecl.getAnnotations().removeIf(a -> {
            String name = a.getNameAsString();
            return name.equals("Permute") || name.equals("io.quarkiverse.permuplate.Permute");
        });
```

This removes `@Permute` from the CLASS annotation list. `@PermuteTypeParam` is on TYPE PARAMETERS, not on the class. After expansion, the type parameters are replaced entirely — the sentinel with `@PermuteTypeParam` is gone, and the generated TypeParameters have no annotations. So no extra removal needed in the processor.

However, verify that TypeParameter annotations don't leak by checking `PermuteTypeParamTransformer.buildTypeParam()` — it creates a new `TypeParameter` with only the bounds, no annotations (correct by construction).

### InlineGenerator changes

- [ ] **Step 4: Add import to InlineGenerator**

Find the imports block in `InlineGenerator.java` and add:
```java
import io.quarkiverse.permuplate.core.PermuteTypeParamTransformer;
```

- [ ] **Step 5: Call the transformer in `generate()`**

In `InlineGenerator.generate()`, find:
```java
            // Apply transformations (null messager — Maven plugin has no Messager)
            PermuteDeclrTransformer.transform(generated, ctx, null);
```

Replace with:
```java
            // Apply transformations (null messager — Maven plugin has no Messager)
            PermuteTypeParamTransformer.transform(generated, ctx, null, null);
            PermuteDeclrTransformer.transform(generated, ctx, null);
```

- [ ] **Step 6: Add @PermuteTypeParam to stripPermuteAnnotations**

In `InlineGenerator.stripPermuteAnnotations()`, find:
```java
        Set<String> PERMUPLATE_ANNOTATIONS = Set.of(
                "Permute", "io.quarkiverse.permuplate.Permute",
                "PermuteDeclr", "io.quarkiverse.permuplate.PermuteDeclr",
                "PermuteParam", "io.quarkiverse.permuplate.PermuteParam");
```

Replace with:
```java
        Set<String> PERMUPLATE_ANNOTATIONS = Set.of(
                "Permute", "io.quarkiverse.permuplate.Permute",
                "PermuteDeclr", "io.quarkiverse.permuplate.PermuteDeclr",
                "PermuteParam", "io.quarkiverse.permuplate.PermuteParam",
                "PermuteTypeParam", "io.quarkiverse.permuplate.PermuteTypeParam");
```

- [ ] **Step 7: Run the full test suite — all Task 2 tests should now pass**

```bash
/opt/homebrew/bin/mvn clean install --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD|FAIL" | tail -5
```

Expected: BUILD SUCCESS. `PermuteTypeParamTest` tests all pass. Total should be 93 (existing) + 8 (new) = 101 tests, 0 failures, 1 skipped.

If the count is off, check which test failed:
```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am --no-transfer-progress 2>&1 | grep -E "FAILED|ERROR" | head -10
```

- [ ] **Step 8: Commit**

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
        permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git commit -m "feat(g1): integrate PermuteTypeParamTransformer into APT and Maven plugin pipelines"
```

---

## Task 5: Update `@SupportedAnnotationTypes` and strip `@PermuteTypeParam` from generated output

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

- [ ] **Step 1: Check that @PermuteTypeParam doesn't appear in generated source**

Run a quick sanity check on the generated test output. The test `testExplicitExpansionNoBounds` generates `Condition3`. Check its source doesn't contain `@PermuteTypeParam`:

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am --no-transfer-progress \
  -Dtest=PermuteTypeParamTest#testExplicitExpansionNoBounds 2>&1 | grep -E "PASS|FAIL|@PermuteTypeParam"
```

Expected: PASS (the assertion `assertThat(src).doesNotContain("@PermuteTypeParam")` already covers this).

The transformer replaces the sentinel TypeParameter (which had `@PermuteTypeParam`) with newly constructed TypeParameters (which have no annotations). So the annotation disappears naturally from the generated source.

- [ ] **Step 2: Verify no permuplate annotation leaks in any test**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD" | tail -3
```

Expected: BUILD SUCCESS, 101 tests, 0 failures.

- [ ] **Step 3: Commit a note if no code change needed**

If step 1 confirms no leakage, add a comment in `PermuteProcessor.generatePermutation()` near the annotation-strip block:

Find:
```java
        // 6. Remove @Permute from the class
        classDecl.getAnnotations().removeIf(a -> {
```

Add a comment before it:
```java
        // Note: @PermuteTypeParam is on TypeParameters, not on the class.
        // PermuteTypeParamTransformer already replaced the sentinel TypeParameter
        // (which carried @PermuteTypeParam) with new TypeParameters that have no annotations.
        // No additional cleanup needed here for @PermuteTypeParam.

        // 6. Remove @Permute from the class
        classDecl.getAnnotations().removeIf(a -> {
```

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git commit -m "docs(g1): clarify @PermuteTypeParam cleanup in processor pipeline comment"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task | Status |
|---|---|---|
| `@PermuteTypeParam` annotation with `varName`, `from`, `to`, `name` | Task 1 | ✓ |
| Explicit expansion: sentinel replaced by j=from..to type params | Task 3 | ✓ |
| Bounds propagation: sentinel name substituted in bound text | Task 3 `buildTypeParam()` | ✓ |
| Implicit expansion: `@PermuteParam(type="T${j}")` triggers class type param expansion | Task 3 `expandImplicit()` | ✓ |
| Implicit: fixed type params (e.g., `R`) pass through unchanged | Task 3 — non-sentinel TPs kept as-is | ✓ |
| R1: return type references expanding type param → error | Task 3 `validateR1()` | ✓ |
| R3: `name` leading literal not a prefix of sentinel name → error | Task 3 `expandExplicit()` | ✓ |
| R4: `from > to` → error | Task 3 `expandExplicit()` | ✓ |
| APT integration | Task 4 | ✓ |
| Maven plugin (InlineGenerator) integration | Task 4 | ✓ |
| `@PermuteTypeParam` stripped from generated output | Task 3 (by construction) | ✓ |

**Deferred (out of scope for this plan):**
- R2: explicit + implicit conflict on same type param → deferred (rare edge case)
- R5: `@PermuteTypeParam` outside `@Permute` type → deferred (hard in APT)
- Method-level `@PermuteTypeParam` (G4 feature)
- `alpha(j)` naming for type params (uses N4 functions — already implemented, works automatically)

**Placeholder scan:** None found — all steps have complete code.

**Type consistency:** `PermuteTypeParamTransformer.transform()` signature used consistently across Task 3 and Task 4 integration calls.
