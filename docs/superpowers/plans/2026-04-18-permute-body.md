# @PermuteBody Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `@PermuteBody` — an annotation that replaces an entire method body with a JEXL-evaluated template per permutation, as a more powerful alternative to `@PermuteStatements` (which only inserts at first/last).

**Architecture:** New annotation in `permuplate-annotations`, new transformer in `permuplate-core`, registered in both `PermuteProcessor` (APT) and `InlineGenerator` (Maven plugin) after `@PermuteStatements`. Uses the same `asStringLiteralExpr().asString()` extraction pattern established for `@PermuteCase` and `@PermuteStatements`.

**Tech Stack:** Java 17, JavaParser 3.28.0, Apache Commons JEXL3.

---

## Design

```java
// Template: replace the entire method body per arity
@Permute(varName="i", from="2", to="4", className="Tuple${i}")
public class Tuple2 {
    @PermuteBody(body = "{ return new Object[${i}]; }")
    public Object[] toArray() {
        return new Object[2]; // template placeholder — replaced entirely
    }
}
// Tuple3 gets:  public Object[] toArray() { return new Object[3]; }
// Tuple4 gets:  public Object[] toArray() { return new Object[4]; }
```

The `body` is a JEXL template for the complete method body INCLUDING the surrounding `{ }`. `StaticJavaParser.parseBlock()` parses it directly. Constructors are also supported.

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Create | `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteBody.java` | Annotation definition |
| Create | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteBodyTransformer.java` | Transform: replace method body |
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | Register in APT pipeline after PermuteStatements |
| Modify | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | Register in Maven pipeline after PermuteStatements |
| Create | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteBodyTest.java` | Tests |

---

### Task 1: Define the annotation

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteBody.java`

- [ ] **Step 1: Write the annotation**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the annotated method or constructor body entirely with the evaluated {@code body}
 * template for each permutation.
 *
 * <p>The {@code body} must be a valid Java block statement including surrounding braces,
 * e.g. {@code "{ return ${i}; }"}. JEXL placeholders ({@code ${...}}) are evaluated
 * before reparsing.
 *
 * <p>Applied after {@code @PermuteStatements} in the transform pipeline.
 */
@Target({ ElementType.METHOD, ElementType.CONSTRUCTOR })
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteBody {
    /** JEXL template for the complete method body including braces. */
    String body();
}
```

- [ ] **Step 2: Build annotations module**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS

---

### Task 2: Write the failing test

**Files:**
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteBodyTest.java`

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

public class PermuteBodyTest {

    @Test
    public void testBodyReplacesEntireMethod() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Holder1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteBody;
                        @Permute(varName="i", from="2", to="3", className="Holder${i}")
                        public class Holder1 {
                            @PermuteBody(body = "{ return ${i}; }")
                            public int arity() {
                                return 1; // template placeholder
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src2 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Holder2").orElseThrow());
        assertThat(src2).contains("return 2");
        assertThat(src2).doesNotContain("return 1");
        assertThat(src2).doesNotContain("@PermuteBody");

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Holder3").orElseThrow());
        assertThat(src3).contains("return 3");
        assertThat(src3).doesNotContain("return 1");
    }

    @Test
    public void testBodyWithStringLiteral() {
        // Body can contain Java string literals — must use asString() extraction
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Namer1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteBody;
                        @Permute(varName="i", from="2", to="2", className="Namer${i}")
                        public class Namer1 {
                            @PermuteBody(body = "{ return \\"arity-${i}\\"; }")
                            public String name() {
                                return "arity-1";
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Namer2").orElseThrow());
        assertThat(src).contains("return \"arity-2\"");
    }

    @Test
    public void testBodyOnConstructor() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Box1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteBody;
                        @Permute(varName="i", from="2", to="2", className="Box${i}")
                        public class Box1 {
                            private final int size;
                            @PermuteBody(body = "{ this.size = ${i}; }")
                            public Box1() {
                                this.size = 1;
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Box2").orElseThrow());
        assertThat(src).contains("this.size = 2");
        assertThat(src).doesNotContain("this.size = 1");
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteBodyTest -q 2>&1 | tail -10
```

Expected: compilation error or generated source missing `return 2` — test fails.

---

### Task 3: Implement `PermuteBodyTransformer`

**Files:**
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteBodyTransformer.java`

- [ ] **Step 1: Create the transformer**

```java
package io.quarkiverse.permuplate.core;

import java.util.Optional;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;

/**
 * Handles {@code @PermuteBody} on method and constructor declarations.
 *
 * <p>Replaces the entire method or constructor body with a JEXL-evaluated template.
 * The {@code body} attribute must include surrounding braces, e.g. {@code "{ return ${i}; }"}.
 *
 * <p>Applied AFTER {@link PermuteStatementsTransformer} so that any @PermuteStatements
 * insertions are overridden if both annotations are present (unusual but allowed).
 */
public class PermuteBodyTransformer {

    private static final String SIMPLE = "PermuteBody";
    private static final String FQ     = "io.quarkiverse.permuplate.PermuteBody";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<AnnotationExpr> annOpt = method.getAnnotations().stream()
                    .filter(PermuteBodyTransformer::isPermuteBody)
                    .findFirst();
            if (annOpt.isEmpty())
                return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr normal))
                return;

            String bodyTemplate = extractBody(normal);
            if (bodyTemplate == null)
                return;

            String evaluated = ctx.evaluate(bodyTemplate);
            BlockStmt newBody = StaticJavaParser.parseBlock(evaluated);
            method.setBody(newBody);
            method.getAnnotations().remove(ann);
        });

        classDecl.findAll(ConstructorDeclaration.class).forEach(constructor -> {
            Optional<AnnotationExpr> annOpt = constructor.getAnnotations().stream()
                    .filter(PermuteBodyTransformer::isPermuteBody)
                    .findFirst();
            if (annOpt.isEmpty())
                return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr normal))
                return;

            String bodyTemplate = extractBody(normal);
            if (bodyTemplate == null)
                return;

            String evaluated = ctx.evaluate(bodyTemplate);
            BlockStmt newBody = StaticJavaParser.parseBlock(evaluated);
            constructor.setBody(newBody);
            constructor.getAnnotations().remove(ann);
        });
    }

    private static String extractBody(NormalAnnotationExpr normal) {
        for (MemberValuePair pair : normal.getPairs()) {
            if ("body".equals(pair.getNameAsString())) {
                return pair.getValue().asStringLiteralExpr().asString();
            }
        }
        return null;
    }

    static boolean isPermuteBody(AnnotationExpr ann) {
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

### Task 4: Register in APT pipeline (`PermuteProcessor`)

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

- [ ] **Step 1: Find where `PermuteStatementsTransformer.transform()` is called**

```bash
grep -n "PermuteStatementsTransformer\|PermuteAnnotationTransformer" \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    | head -10
```

- [ ] **Step 2: Add `PermuteBodyTransformer.transform()` call immediately after `PermuteStatementsTransformer`**

Locate the line:
```java
PermuteStatementsTransformer.transform(classDecl, ctx);
```

Add directly below it:
```java
io.quarkiverse.permuplate.core.PermuteBodyTransformer.transform(classDecl, ctx);
```

- [ ] **Step 3: Build the processor**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-core,permuplate-processor -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Run all three tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteBodyTest -q 2>&1 | tail -5
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Run full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

Expected: all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add \
    permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteBody.java \
    permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteBodyTransformer.java \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteBodyTest.java
git commit -m "feat: add @PermuteBody annotation for full method body replacement per permutation"
```

---

### Task 5: Register in Maven plugin pipeline (`InlineGenerator`)

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

- [ ] **Step 1: Find where `PermuteStatementsTransformer.transform()` is called in InlineGenerator**

```bash
grep -n "PermuteStatementsTransformer\|PermuteAnnotationTransformer" \
    permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | head -10
```

- [ ] **Step 2: Add `PermuteBodyTransformer.transform()` immediately after `PermuteStatementsTransformer`**

```java
PermuteBodyTransformer.transform(classDecl, ctx);
```

- [ ] **Step 3: Add the import at the top of InlineGenerator.java**

```java
import io.quarkiverse.permuplate.core.PermuteBodyTransformer;
```

- [ ] **Step 4: Full build**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git commit -m "feat: register PermuteBodyTransformer in Maven plugin inline generation pipeline"
```

---

### Task 6: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add `@PermuteBody` section after the `@PermuteStatements` section**

Find the `### @PermuteStatements` section and add after it:

```markdown
### `@PermuteBody`

Replaces the entire annotated method or constructor body with a JEXL-evaluated template per permutation. The `body` attribute must include surrounding braces.

| Parameter | Meaning |
|---|---|
| `body` | JEXL template for the complete method body including `{ }` |

```java
@PermuteBody(body = "{ return ${i}; }")
public int arity() {
    return 1; // template placeholder — replaced entirely in generated classes
}
```

Use `@PermuteBody` when you need to replace the full body, not just insert at the beginning or end. Use `@PermuteStatements` when you want to keep existing statements and insert around them.
```

- [ ] **Step 2: Update the annotation table** in the annotations section — add `@PermuteBody` to the table listing all annotations with purpose column: "Replace entire method/constructor body per permutation".

- [ ] **Step 3: Full build to verify**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: document @PermuteBody in README"
```
