# @PermuteAnnotation and @PermuteThrows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `@PermuteAnnotation` (adds Java annotations to generated elements conditionally) and `@PermuteThrows` (adds exception types to method throws clauses conditionally), both with IntelliJ inspections for syntax validation.

**Architecture:** Each feature follows the established transformer pattern: new annotation files in `permuplate-annotations`, a new `TypeDeclaration<?>` transformer in `permuplate-core`, and a two-line integration in both `PermuteProcessor.generatePermutation()` (after line 479, after `PermuteStatementsTransformer`) and `InlineGenerator.generate()` (after `PermuteStatementsTransformer` in COID branch, after `PermuteValueTransformer` in record branch). Both transformers take `(TypeDeclaration<?>, EvaluationContext, Messager)` and null-check the Messager.

**Tech Stack:** Java 17, JavaParser, Apache Commons JEXL3, Google compile-testing (APT tests), JUnit 4, IntelliJ Platform SDK (inspections).

**GitHub:** Refs #31 (@PermuteAnnotation), #32 (@PermuteThrows), epic #30.

---

## File Map

| File | Change |
|---|---|
| `permuplate-annotations/…/PermuteAnnotation.java` | Create |
| `permuplate-annotations/…/PermuteAnnotations.java` | Create (container) |
| `permuplate-annotations/…/PermuteThrows.java` | Create |
| `permuplate-annotations/…/PermuteThrowsList.java` | Create (container) |
| `permuplate-core/…/PermuteAnnotationTransformer.java` | Create |
| `permuplate-core/…/PermuteThrowsTransformer.java` | Create |
| `permuplate-processor/…/PermuteProcessor.java` | Add two transformer calls after `PermuteStatementsTransformer` (line ~479) |
| `permuplate-maven-plugin/…/InlineGenerator.java` | Add two transformer calls in both COID and record branches |
| `permuplate-intellij-plugin/…/inspection/PermuteAnnotationValueInspection.java` | Create |
| `permuplate-intellij-plugin/…/inspection/PermuteThrowsTypeInspection.java` | Create |
| `permuplate-tests/…/PermuteAnnotationTransformerTest.java` | Create (5 tests) |
| `permuplate-tests/…/PermuteThrowsTransformerTest.java` | Create (3 tests) |

---

## Task 1: Annotation files + failing tests

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteAnnotation.java`
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteAnnotations.java`
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteThrows.java`
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteThrowsList.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteAnnotationTransformerTest.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteThrowsTransformerTest.java`

- [ ] **Step 1.1: Create PermuteAnnotation.java**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/**
 * Adds a Java annotation to the generated type, method, or field per permutation.
 * {@code value} is a JEXL-evaluated annotation literal (e.g. {@code "@Deprecated(since=\"${i}\")"}).
 * {@code when} is an optional JEXL boolean — empty string means always apply.
 *
 * <p>Example:
 * <pre>{@code
 * @Permute(varName="i", from="1", to="6", className="Callable${i}", strings={"max=6"})
 * @PermuteAnnotation(when="${i == 1}", value="@FunctionalInterface")
 * @PermuteAnnotation(when="${i == max}", value="@Deprecated(since=\"use higher arity\")")
 * public interface Callable1 { ... }
 * }</pre>
 */
@Repeatable(PermuteAnnotations.class)
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
public @interface PermuteAnnotation {
    /** JEXL-evaluated Java annotation literal to add. Must parse as a valid annotation. */
    String value();
    /** JEXL boolean condition. Empty string means always apply. */
    String when() default "";
}
```

- [ ] **Step 1.2: Create PermuteAnnotations.java**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/** Container annotation for repeatable {@link PermuteAnnotation}. */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
public @interface PermuteAnnotations {
    PermuteAnnotation[] value();
}
```

- [ ] **Step 1.3: Create PermuteThrows.java**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/**
 * Adds an exception type to a method's {@code throws} clause per permutation.
 * {@code value} is a JEXL-evaluated exception class name.
 * {@code when} is an optional JEXL boolean — empty string means always add.
 *
 * <p>Example:
 * <pre>{@code
 * @PermuteThrows(when="${i > 4}", value="TooManyArgsException")
 * public void join(...) throws SomeException { ... }
 * // Join5..Join10 get: throws SomeException, TooManyArgsException
 * }</pre>
 */
@Repeatable(PermuteThrowsList.class)
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteThrows {
    /** JEXL-evaluated exception class name to add to the throws clause. */
    String value();
    /** JEXL boolean condition. Empty string means always add. */
    String when() default "";
}
```

- [ ] **Step 1.4: Create PermuteThrowsList.java**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/** Container annotation for repeatable {@link PermuteThrows}. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteThrowsList {
    PermuteThrows[] value();
}
```

- [ ] **Step 1.5: Create PermuteAnnotationTransformerTest.java with 5 failing tests**

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.quarkiverse.permuplate.processor.PermuteProcessor;
import org.junit.Test;

public class PermuteAnnotationTransformerTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    /** @PermuteAnnotation with no when= applies to every generated class. */
    @Test
    public void testAlwaysApplyOnClass() {
        Compilation c = compile("io.example.Callable1",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Callable${i}\")\n" +
                "@PermuteAnnotation(value=\"@SuppressWarnings(\\\"unchecked\\\")\")\n" +
                "public interface Callable1 {}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable2").get()))
                .contains("@SuppressWarnings(\"unchecked\")");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable3").get()))
                .contains("@SuppressWarnings(\"unchecked\")");
    }

    /** @PermuteAnnotation(when=...) applies only when the JEXL condition is true. */
    @Test
    public void testConditionalOnClass() {
        Compilation c = compile("io.example.Callable1",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Callable${i}\")\n" +
                "@PermuteAnnotation(when=\"${i == 2}\", value=\"@FunctionalInterface\")\n" +
                "public interface Callable1 { void call(); }");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable2").get()))
                .contains("@FunctionalInterface");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable3").get()))
                .doesNotContain("@FunctionalInterface");
    }

    /** JEXL expressions in value= are evaluated per permutation. */
    @Test
    public void testJexlInValue() {
        Compilation c = compile("io.example.Callable1",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Callable${i}\")\n" +
                "@PermuteAnnotation(value=\"@SuppressWarnings(\\\"arity${i}\\\")\")\n" +
                "public interface Callable1 {}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable2").get()))
                .contains("@SuppressWarnings(\"arity2\")");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable3").get()))
                .contains("@SuppressWarnings(\"arity3\")");
    }

    /** @PermuteAnnotation on a method adds the annotation to that method in each generated class. */
    @Test
    public void testMethodLevel() {
        Compilation c = compile("io.example.Join1",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Join${i}\")\n" +
                "public class Join1 {\n" +
                "    @PermuteAnnotation(when=\"${i > 2}\", value=\"@Deprecated\")\n" +
                "    public void join() {}\n" +
                "}");
        assertThat(c).succeeded();
        String join2 = sourceOf(c.generatedSourceFile("io.example.Join2").get());
        String join3 = sourceOf(c.generatedSourceFile("io.example.Join3").get());
        assertThat(join2).doesNotContain("@Deprecated");
        assertThat(join3).contains("@Deprecated");
    }

    /** @PermuteAnnotation on a field adds the annotation to that field in each generated class. */
    @Test
    public void testFieldLevel() {
        Compilation c = compile("io.example.Join1",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Join${i}\")\n" +
                "public class Join1 {\n" +
                "    @PermuteAnnotation(when=\"${i == 3}\", value=\"@Deprecated\")\n" +
                "    public int arity = 1;\n" +
                "}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join2").get()))
                .doesNotContain("@Deprecated");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join3").get()))
                .contains("@Deprecated");
    }
}
```

- [ ] **Step 1.6: Create PermuteThrowsTransformerTest.java with 3 failing tests**

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.quarkiverse.permuplate.processor.PermuteProcessor;
import org.junit.Test;

public class PermuteThrowsTransformerTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    /** @PermuteThrows with no when= adds the exception to every generated method. */
    @Test
    public void testAlwaysAddThrows() {
        Compilation c = compile("io.example.Join1",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Join${i}\")\n" +
                "public class Join1 {\n" +
                "    @PermuteThrows(value=\"java.io.IOException\")\n" +
                "    public void call() {}\n" +
                "}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join2").get()))
                .contains("throws java.io.IOException");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join3").get()))
                .contains("throws java.io.IOException");
    }

    /** @PermuteThrows(when=...) adds the exception only when the condition is true. */
    @Test
    public void testConditionalThrows() {
        Compilation c = compile("io.example.Join1",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Join${i}\")\n" +
                "public class Join1 {\n" +
                "    @PermuteThrows(when=\"${i > 2}\", value=\"java.io.IOException\")\n" +
                "    public void call() throws RuntimeException {}\n" +
                "}");
        assertThat(c).succeeded();
        String join2 = sourceOf(c.generatedSourceFile("io.example.Join2").get());
        String join3 = sourceOf(c.generatedSourceFile("io.example.Join3").get());
        assertThat(join2).doesNotContain("IOException");
        assertThat(join3).contains("java.io.IOException");
        assertThat(join3).contains("RuntimeException");
    }

    /** Multiple @PermuteThrows on the same method: all applicable entries are added. */
    @Test
    public void testMultipleThrows() {
        Compilation c = compile("io.example.Join1",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Join${i}\")\n" +
                "public class Join1 {\n" +
                "    @PermuteThrows(value=\"java.io.IOException\")\n" +
                "    @PermuteThrows(when=\"${i > 2}\", value=\"java.sql.SQLException\")\n" +
                "    public void call() {}\n" +
                "}");
        assertThat(c).succeeded();
        String join2 = sourceOf(c.generatedSourceFile("io.example.Join2").get());
        String join3 = sourceOf(c.generatedSourceFile("io.example.Join3").get());
        assertThat(join2).contains("java.io.IOException");
        assertThat(join2).doesNotContain("SQLException");
        assertThat(join3).contains("java.io.IOException");
        assertThat(join3).contains("java.sql.SQLException");
    }
}
```

- [ ] **Step 1.7: Run failing tests to confirm they fail because annotations don't exist yet**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest="PermuteAnnotationTransformerTest,PermuteThrowsTransformerTest" 2>&1 | tail -10
```

Expected: FAIL — import not found for `PermuteAnnotation`/`PermuteThrows`.

- [ ] **Step 1.8: Build annotations module — expect SUCCESS**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations -q 2>&1 | tail -5
```

- [ ] **Step 1.9: Stage and commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteAnnotation.java \
        permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteAnnotations.java \
        permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteThrows.java \
        permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteThrowsList.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteAnnotationTransformerTest.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteThrowsTransformerTest.java
git commit -m "feat(annotations): add @PermuteAnnotation and @PermuteThrows with failing tests

Refs #31, #32, #30.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: PermuteAnnotationTransformer + pipeline integration

**Files:**
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteAnnotationTransformer.java`
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

- [ ] **Step 2.1: Create PermuteAnnotationTransformer.java**

```java
package io.quarkiverse.permuplate.core;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import io.quarkiverse.permuplate.core.EvaluationContext;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds Java annotations to generated types, methods, and fields based on
 * {@code @PermuteAnnotation} / {@code @PermuteAnnotations} on the template element.
 *
 * <p>Run LAST in the transform pipeline — after all other transformers — so that
 * {@code when} expressions see the final permutation state.
 */
public class PermuteAnnotationTransformer {

    private static final String SIMPLE     = "PermuteAnnotation";
    private static final String FQ         = "io.quarkiverse.permuplate.PermuteAnnotation";
    private static final String SIMPLE_CTR = "PermuteAnnotations";
    private static final String FQ_CTR     = "io.quarkiverse.permuplate.PermuteAnnotations";

    public static void transform(TypeDeclaration<?> classDecl,
                                  EvaluationContext ctx,
                                  Messager messager) {
        processElement(classDecl, ctx, messager);
        classDecl.getMethods().forEach(m -> processElement(m, ctx, messager));
        classDecl.getFields().forEach(f -> processElement(f, ctx, messager));
    }

    @SuppressWarnings("unchecked")
    private static <N extends com.github.javaparser.ast.Node & NodeWithAnnotations<N>>
    void processElement(NodeWithAnnotations<N> element, EvaluationContext ctx, Messager messager) {
        List<AnnotationExpr> toRemove = new ArrayList<>();
        List<AnnotationExpr> toAdd    = new ArrayList<>();

        for (AnnotationExpr ann : element.getAnnotations()) {
            String name = ann.getNameAsString();
            if (FQ.equals(name) || SIMPLE.equals(name)) {
                toRemove.add(ann);
                applyOne(ann, ctx, messager, toAdd);
            } else if (FQ_CTR.equals(name) || SIMPLE_CTR.equals(name)) {
                toRemove.add(ann);
                // Container — unwrap the inner @PermuteAnnotation array
                if (ann instanceof NormalAnnotationExpr normal) {
                    for (MemberValuePair pair : normal.getPairs()) {
                        if ("value".equals(pair.getNameAsString())
                                && pair.getValue() instanceof ArrayInitializerExpr arr) {
                            for (Expression inner : arr.getValues()) {
                                applyOne(inner, ctx, messager, toAdd);
                            }
                        }
                    }
                }
            }
        }

        toRemove.forEach(element.getAnnotations()::remove);
        toAdd.forEach(element::addAnnotation);
    }

    private static void applyOne(Expression annExpr,
                                  EvaluationContext ctx,
                                  Messager messager,
                                  List<AnnotationExpr> toAdd) {
        String when  = extractAttr(annExpr, "when");
        String value = extractAttr(annExpr, "value");
        if (value == null) return;

        // Evaluate when condition (empty = always apply)
        if (when != null && !when.isEmpty()) {
            try {
                if (!ctx.evaluateBoolean(when)) return;
            } catch (Exception e) {
                if (messager != null) {
                    messager.printMessage(Diagnostic.Kind.WARNING,
                            "@PermuteAnnotation when expression error (annotation skipped): "
                                    + when + " — " + e.getMessage());
                }
                return;
            }
        }

        // Evaluate value, then parse as annotation
        String evaluated = ctx.evaluate(value);
        try {
            toAdd.add(StaticJavaParser.parseAnnotation(evaluated));
        } catch (Exception e) {
            if (messager != null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteAnnotation value is not a valid annotation: \"" + evaluated + "\"");
            }
        }
    }

    /** Extracts a string attribute value from a @PermuteAnnotation expression node. */
    private static String extractAttr(Expression annExpr, String attrName) {
        if (annExpr instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (attrName.equals(pair.getNameAsString())
                        && pair.getValue() instanceof StringLiteralExpr lit) {
                    return lit.asString();
                }
            }
        } else if (annExpr instanceof SingleMemberAnnotationExpr single
                && "value".equals(attrName)
                && single.getMemberValue() instanceof StringLiteralExpr lit) {
            return lit.asString();
        }
        return null;
    }
}
```

- [ ] **Step 2.2: Integrate into PermuteProcessor**

Open `PermuteProcessor.java`. Find the call to `PermuteStatementsTransformer.transform(classDecl, ctx)` (around line 479). Add two lines immediately after it:

```java
        PermuteStatementsTransformer.transform(classDecl, ctx);

        // Conditional annotation and throws additions — run last
        io.quarkiverse.permuplate.core.PermuteAnnotationTransformer.transform(
                classDecl, ctx, processingEnv.getMessager());
```

Also add the import at the top of the file (or use fully-qualified reference as shown).

- [ ] **Step 2.3: Integrate into InlineGenerator**

Open `InlineGenerator.java`. Find both pipeline branches:

**In the COID branch** — after `PermuteStatementsTransformer.transform(generated, ctx)` (line ~164):

```java
                PermuteStatementsTransformer.transform(generated, ctx);
                io.quarkiverse.permuplate.core.PermuteAnnotationTransformer.transform(
                        generated, ctx, null);
```

**In the record branch** — after `PermuteValueTransformer.transform(generated, ctx)` (line ~203):

```java
                PermuteValueTransformer.transform(generated, ctx);
                io.quarkiverse.permuplate.core.PermuteAnnotationTransformer.transform(
                        generated, ctx, null);
```

- [ ] **Step 2.4: Run the 5 PermuteAnnotationTransformerTest tests — expect PASS**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn install -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="PermuteAnnotationTransformerTest" 2>&1 | tail -10
```

Expected: 5/5 PASS.

- [ ] **Step 2.5: Run full suite — expect BUILD SUCCESS**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

- [ ] **Step 2.6: Stage and commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteAnnotationTransformer.java \
        permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
        permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git commit -m "feat(core): PermuteAnnotationTransformer — conditional annotations on generated elements

Refs #31, #30.

Evaluates when= (JEXL boolean, empty=always) and value= (JEXL annotation literal),
parses with StaticJavaParser.parseAnnotation(), adds to TYPE/METHOD/FIELD.
Integrated last in pipeline in both PermuteProcessor and InlineGenerator.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: PermuteThrowsTransformer + pipeline integration

**Files:**
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteThrowsTransformer.java`
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

- [ ] **Step 3.1: Create PermuteThrowsTransformer.java**

```java
package io.quarkiverse.permuplate.core;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ReferenceType;
import io.quarkiverse.permuplate.core.EvaluationContext;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds exception types to method {@code throws} clauses based on
 * {@code @PermuteThrows} / {@code @PermuteThrowsList} on the template method.
 *
 * <p>Add-only: cannot remove existing exceptions. Run after PermuteAnnotationTransformer.
 */
public class PermuteThrowsTransformer {

    private static final String SIMPLE     = "PermuteThrows";
    private static final String FQ         = "io.quarkiverse.permuplate.PermuteThrows";
    private static final String SIMPLE_CTR = "PermuteThrowsList";
    private static final String FQ_CTR     = "io.quarkiverse.permuplate.PermuteThrowsList";

    public static void transform(TypeDeclaration<?> classDecl,
                                  EvaluationContext ctx,
                                  Messager messager) {
        classDecl.getMethods().forEach(method -> processMethod(method, ctx, messager));
    }

    private static void processMethod(MethodDeclaration method,
                                       EvaluationContext ctx,
                                       Messager messager) {
        List<AnnotationExpr> toRemove = new ArrayList<>();

        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            if (FQ.equals(name) || SIMPLE.equals(name)) {
                toRemove.add(ann);
                applyOne(ann, method, ctx, messager);
            } else if (FQ_CTR.equals(name) || SIMPLE_CTR.equals(name)) {
                toRemove.add(ann);
                if (ann instanceof NormalAnnotationExpr normal) {
                    for (MemberValuePair pair : normal.getPairs()) {
                        if ("value".equals(pair.getNameAsString())
                                && pair.getValue() instanceof ArrayInitializerExpr arr) {
                            arr.getValues().forEach(inner -> applyOne(inner, method, ctx, messager));
                        }
                    }
                }
            }
        }

        toRemove.forEach(method.getAnnotations()::remove);
    }

    private static void applyOne(Expression annExpr,
                                  MethodDeclaration method,
                                  EvaluationContext ctx,
                                  Messager messager) {
        String when  = extractAttr(annExpr, "when");
        String value = extractAttr(annExpr, "value");
        if (value == null) return;

        if (when != null && !when.isEmpty()) {
            try {
                if (!ctx.evaluateBoolean(when)) return;
            } catch (Exception e) {
                if (messager != null) {
                    messager.printMessage(Diagnostic.Kind.WARNING,
                            "@PermuteThrows when expression error (entry skipped): "
                                    + when + " — " + e.getMessage());
                }
                return;
            }
        }

        String evaluated = ctx.evaluate(value);
        try {
            ReferenceType type = StaticJavaParser.parseClassOrInterfaceType(evaluated);
            method.addThrownException(type);
        } catch (Exception e) {
            if (messager != null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteThrows value does not parse as a type: \"" + evaluated + "\"");
            }
        }
    }

    private static String extractAttr(Expression annExpr, String attrName) {
        if (annExpr instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (attrName.equals(pair.getNameAsString())
                        && pair.getValue() instanceof StringLiteralExpr lit) {
                    return lit.asString();
                }
            }
        } else if (annExpr instanceof SingleMemberAnnotationExpr single
                && "value".equals(attrName)
                && single.getMemberValue() instanceof StringLiteralExpr lit) {
            return lit.asString();
        }
        return null;
    }
}
```

- [ ] **Step 3.2: Integrate into PermuteProcessor**

After the `PermuteAnnotationTransformer` call added in Task 2, add:

```java
        io.quarkiverse.permuplate.core.PermuteAnnotationTransformer.transform(
                classDecl, ctx, processingEnv.getMessager());
        io.quarkiverse.permuplate.core.PermuteThrowsTransformer.transform(
                classDecl, ctx, processingEnv.getMessager());
```

- [ ] **Step 3.3: Integrate into InlineGenerator**

In both the COID and record branches, after the `PermuteAnnotationTransformer` call added in Task 2, add:

```java
                io.quarkiverse.permuplate.core.PermuteThrowsTransformer.transform(
                        generated, ctx, null);
```

- [ ] **Step 3.4: Run the 3 PermuteThrowsTransformerTest tests — expect PASS**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn install -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="PermuteThrowsTransformerTest" 2>&1 | tail -10
```

Expected: 3/3 PASS.

- [ ] **Step 3.5: Run full suite — expect BUILD SUCCESS**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

Expected: 201 tests (193 existing + 8 new), 0 failures.

- [ ] **Step 3.6: Stage and commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteThrowsTransformer.java \
        permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
        permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git commit -m "feat(core): PermuteThrowsTransformer — conditional throws clause additions

Refs #32, #30.

Evaluates when= (JEXL boolean, empty=always) and value= (JEXL type name),
parses with StaticJavaParser.parseClassOrInterfaceType(), adds to method throws.
Repeatable via @PermuteThrowsList container. Integrated after PermuteAnnotationTransformer.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: IntelliJ inspections

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/inspection/PermuteAnnotationValueInspection.java`
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/inspection/PermuteThrowsTypeInspection.java`
- Modify: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

Read `AnnotationStringInspection.java` and `plugin.xml` in full before implementing — they show the exact inspection registration pattern.

- [ ] **Step 4.1: Create PermuteAnnotationValueInspection.java**

```java
package io.quarkiverse.permuplate.intellij.inspection;

import com.github.javaparser.StaticJavaParser;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * Validates that @PermuteAnnotation(value=...) contains a parseable Java annotation literal.
 * JEXL ${...} expressions are stubbed with "X" before validation.
 */
public class PermuteAnnotationValueInspection extends LocalInspectionTool {

    private static final String SHORT_NAME = "PermuteAnnotationValue";
    private static final Pattern JEXL = Pattern.compile("\\$\\{[^}]+}");

    @Override
    public @NotNull String getShortName() { return SHORT_NAME; }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                String fqn = annotation.getQualifiedName();
                if (fqn == null) return;
                if (!fqn.equals("io.quarkiverse.permuplate.PermuteAnnotation")
                        && !fqn.endsWith(".PermuteAnnotation")) return;

                PsiAnnotationMemberValue valueAttr = annotation.findAttributeValue("value");
                if (!(valueAttr instanceof PsiLiteralExpression lit)) return;
                if (!(lit.getValue() instanceof String raw) || raw.isEmpty()) return;

                // Stub JEXL expressions so the remainder can be parsed as Java
                String stubbed = JEXL.matcher(raw).replaceAll("X");
                try {
                    StaticJavaParser.parseAnnotation(stubbed);
                } catch (Exception e) {
                    holder.registerProblem(valueAttr,
                            "@PermuteAnnotation: value does not parse as a valid Java annotation",
                            ProblemHighlightType.WARNING);
                }
            }
        };
    }
}
```

- [ ] **Step 4.2: Create PermuteThrowsTypeInspection.java**

```java
package io.quarkiverse.permuplate.intellij.inspection;

import com.github.javaparser.StaticJavaParser;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * Validates that @PermuteThrows(value=...) looks like a valid Java type name.
 * JEXL ${...} expressions are stubbed with "Object" before validation.
 */
public class PermuteThrowsTypeInspection extends LocalInspectionTool {

    private static final String SHORT_NAME = "PermuteThrowsType";
    private static final Pattern JEXL = Pattern.compile("\\$\\{[^}]+}");

    @Override
    public @NotNull String getShortName() { return SHORT_NAME; }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                String fqn = annotation.getQualifiedName();
                if (fqn == null) return;
                if (!fqn.equals("io.quarkiverse.permuplate.PermuteThrows")
                        && !fqn.endsWith(".PermuteThrows")) return;

                PsiAnnotationMemberValue valueAttr = annotation.findAttributeValue("value");
                if (!(valueAttr instanceof PsiLiteralExpression lit)) return;
                if (!(lit.getValue() instanceof String raw) || raw.isEmpty()) return;

                String stubbed = JEXL.matcher(raw).replaceAll("Object");
                try {
                    StaticJavaParser.parseClassOrInterfaceType(stubbed);
                } catch (Exception e) {
                    holder.registerProblem(valueAttr,
                            "@PermuteThrows: value does not look like a valid Java type name",
                            ProblemHighlightType.WARNING);
                }
            }
        };
    }
}
```

- [ ] **Step 4.3: Register both inspections in plugin.xml**

Open `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`. Find the `<extensions>` section where existing inspections are registered (look for `AnnotationStringInspection` registration). Add alongside it:

```xml
<localInspection language="JAVA"
                 displayName="@PermuteAnnotation value syntax"
                 groupName="Permuplate"
                 enabledByDefault="true"
                 level="WARNING"
                 implementationClass="io.quarkiverse.permuplate.intellij.inspection.PermuteAnnotationValueInspection"/>

<localInspection language="JAVA"
                 displayName="@PermuteThrows value type"
                 groupName="Permuplate"
                 enabledByDefault="true"
                 level="WARNING"
                 implementationClass="io.quarkiverse.permuplate.intellij.inspection.PermuteThrowsTypeInspection"/>
```

- [ ] **Step 4.4: Run IntelliJ plugin tests**

```bash
cd /Users/mdproctor/claude/permuplate/permuplate-intellij-plugin
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test 2>&1 | grep -E "BUILD|tests completed|FAILED" | head -5
```

Expected: BUILD SUCCESSFUL (existing tests still pass; no new plugin tests required for these inspections — they are visual/IDE features).

- [ ] **Step 4.5: Stage and commit**

```bash
cd /Users/mdproctor/claude/permuplate
git add permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/inspection/PermuteAnnotationValueInspection.java \
        permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/inspection/PermuteThrowsTypeInspection.java \
        permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml
git commit -m "feat(plugin): add @PermuteAnnotation and @PermuteThrows IntelliJ inspections

Refs #31, #32, #30.

PermuteAnnotationValueInspection: validates value= parses as a Java annotation
  (JEXL ${...} stubbed with X before parsing).
PermuteThrowsTypeInspection: validates value= looks like a valid type name
  (JEXL stubbed with Object before parsing).
Both registered in plugin.xml with WARNING level.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Documentation + CLAUDE.md + close issues

**Files:**
- Modify: `CLAUDE.md`
- Modify: `OVERVIEW.md`
- Modify: `docs/annotation-ideas.md`

- [ ] **Step 5.1: Update the annotation roster in CLAUDE.md**

Find the annotation roster table. Add two new rows after `@PermuteFilter`:

```
| `@PermuteAnnotation` | class, interface, method, field | Add a Java annotation to the generated element per permutation; optional JEXL condition (repeatable) |
| `@PermuteThrows` | method | Add an exception to a method's throws clause per permutation; optional JEXL condition (repeatable) |
```

- [ ] **Step 5.2: Add non-obvious decisions entries to CLAUDE.md**

In the "Key non-obvious decisions and past bugs" table, add:

```
| `@PermuteAnnotation` pipeline position | Runs LAST — after all other transformers — so `when` expressions see the final permutation state (correct field names, type params already expanded, etc.). |
| `@PermuteThrows` on records | `@PermuteThrows` targets METHOD only; records can have instance methods so it applies. It is placed in both the COID and record branches of InlineGenerator. |
```

- [ ] **Step 5.3: Add to OVERVIEW.md Annotation API Detail section**

After the `@PermuteFilter` section, add:

```markdown
### `@PermuteAnnotation`

Adds a Java annotation literal to a generated type, method, or field. `value` is JEXL-evaluated. `when` is an optional JEXL boolean (empty = always apply). Repeatable via `@PermuteAnnotations`.

### `@PermuteThrows`

Adds an exception type to a method's `throws` clause. `value` is JEXL-evaluated. `when` is an optional JEXL boolean (empty = always add). Add-only — cannot remove existing exceptions. Repeatable via `@PermuteThrowsList`.
```

- [ ] **Step 5.4: Update annotation-ideas.md priority table**

Change the statuses for both:
```
| `@PermuteAnnotation` | Medium | Medium | **Done** (#31) |
| `@PermuteThrows` | Low | Low | **Done** (#32) |
```

- [ ] **Step 5.5: Run final full suite**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 201+ tests, 0 failures.

- [ ] **Step 5.6: Commit and close issues**

```bash
git add CLAUDE.md OVERVIEW.md docs/annotation-ideas.md
git commit -m "docs: add @PermuteAnnotation and @PermuteThrows to CLAUDE.md, OVERVIEW.md, roadmap

Closes #31, #32. Refs #30.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

```bash
gh issue close 31 --repo mdproctor/permuplate --comment "Implemented. @PermuteAnnotation + @PermuteAnnotations added. PermuteAnnotationTransformer runs last in pipeline. IntelliJ inspection validates value syntax. 5 tests passing."
gh issue close 32 --repo mdproctor/permuplate --comment "Implemented. @PermuteThrows + @PermuteThrowsList added. PermuteThrowsTransformer add-only. IntelliJ inspection validates type name. 3 tests passing."
gh issue edit 30 --repo mdproctor/permuplate --body "$(cat <<'BODY'
## Overview
Two new annotation language features for conditional generation.

## Scope
- [x] #31 — @PermuteAnnotation implementation
- [x] #32 — @PermuteThrows implementation

## Definition of Done
Both annotations work in APT and Maven plugin inline mode. IntelliJ inspections validate value syntax. CLAUDE.md and OVERVIEW.md updated. 8 new tests passing.
BODY
)"
gh issue close 30 --repo mdproctor/permuplate --comment "Both children complete: #31 (@PermuteAnnotation) and #32 (@PermuteThrows)."
```

---

## Self-Review

**Spec coverage:**
- ✅ `@PermuteAnnotation` + `@PermuteAnnotations` — Task 1
- ✅ `@PermuteThrows` + `@PermuteThrowsList` — Task 1
- ✅ `PermuteAnnotationTransformer` — Task 2
- ✅ `PermuteThrowsTransformer` — Task 3
- ✅ Both integrated in PermuteProcessor and InlineGenerator (COID + record) — Tasks 2+3
- ✅ `when` evaluation error → WARNING; `value` parse error → ERROR — in both transformers
- ✅ 5 @PermuteAnnotation tests (class always, class conditional, JEXL in value, method, field) — Task 1
- ✅ 3 @PermuteThrows tests (always, conditional, multiple) — Task 1
- ✅ IntelliJ inspections — Task 4
- ✅ CLAUDE.md + OVERVIEW.md + roadmap updated — Task 5

**No placeholders found.**

**Type consistency:** `PermuteAnnotationTransformer.transform(TypeDeclaration<?>, EvaluationContext, Messager)` and `PermuteThrowsTransformer.transform(TypeDeclaration<?>, EvaluationContext, Messager)` — used consistently throughout Tasks 2 and 3.
