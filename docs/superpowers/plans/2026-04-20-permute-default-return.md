# @PermuteDefaultReturn Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** New class-level `@Target(TYPE) @Retention(SOURCE)` annotation that sets a default return type for all `Object`-returning methods without explicit `@PermuteReturn`. Individual methods can override with their own `@PermuteReturn`. Eliminates the need to repeat the same `@PermuteReturn` on a dozen methods when they all share the same return type expression.

**Architecture:** Read `@PermuteDefaultReturn` from the generated class in `applyPermuteReturn()`. After processing all explicit `@PermuteReturn` methods (existing logic), apply the default to remaining `Object`-returning methods. Implemented in both APT (`PermuteProcessor`) and Maven plugin (`InlineGenerator`) paths. The annotation is stripped from the generated class output.

**Epic:** #79

**Tech Stack:** Java 17, JavaParser 3.28.0, APT + Maven plugin, Google compile-testing.

---

## File Structure

| Action | Path | What changes |
|---|---|---|
| Create | `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDefaultReturn.java` | New annotation |
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | `applyDefaultPermuteReturn()` helper; call after explicit loop |
| Modify | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | `applyDefaultPermuteReturn()` helper; register; add to stripPermuteAnnotations |
| Create | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDefaultReturnTest.java` | Tests |

---

### Task 1: Annotation

- [ ] **Step 1: Create `PermuteDefaultReturn.java`**

Create `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDefaultReturn.java`:

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level annotation that sets a default return type for all {@code Object}-returning
 * methods that do not have an explicit {@code @PermuteReturn}.
 *
 * <p>Eliminates repetition when many methods in a template share the same return-type
 * expression. Individual methods can override with {@code @PermuteReturn}.
 *
 * <p>Example:
 * <pre>{@code
 * @Permute(varName="i", from="2", to="5", className="Builder${i}")
 * @PermuteDefaultReturn(className="Builder${i}", typeArgs="")
 * public class Builder2 {
 *     public Object withName(String name) { return this; }  // becomes Builder2 withName(...)
 *     public Object withValue(int v)  { return this; }      // becomes Builder2 withValue(...)
 *
 *     @PermuteReturn(className="String", typeArgs="", alwaysEmit=true)
 *     public Object build() { ... }                         // explicit override: returns String
 * }
 * }</pre>
 *
 * <p>Processing: runs after explicit {@code @PermuteReturn} methods are processed.
 * Stripped from generated output.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteDefaultReturn {

    /**
     * JEXL expression for the default return class name.
     * Same semantics as {@code @PermuteReturn.className}.
     */
    String className();

    /**
     * JEXL expression for the type arguments, or empty string for no type args.
     * Same semantics as {@code @PermuteReturn.typeArgs}.
     */
    String typeArgs() default "";

    /**
     * When {@code true}, methods are always generated even if {@code className}
     * evaluates to a class not in the generated set. Defaults to {@code true}
     * because the default return is typically the current class (always in the set).
     */
    boolean alwaysEmit() default true;
}
```

- [ ] **Step 2: Build annotations module**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

---

### Task 2: Tests (TDD — write before implementing)

- [ ] **Step 1: Create `PermuteDefaultReturnTest.java`**

Find the pattern from an existing test file:
```bash
head -10 /Users/mdproctor/claude/permuplate/permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteReturnTest.java
```

Create `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDefaultReturnTest.java`:

```java
package io.quarkiverse.permuplate;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static io.quarkiverse.permuplate.TestHelper.sourceOf;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import io.quarkiverse.permuplate.processor.PermuteProcessor;
import org.junit.jupiter.api.Test;

public class PermuteDefaultReturnTest {

    @Test
    public void testDefaultReturnAppliedToAllObjectMethods() {
        // @PermuteDefaultReturn applies to all Object-returning methods without explicit
        // @PermuteReturn. Explicit @PermuteReturn takes precedence.
        var source = JavaFileObjects.forSourceString("io.ex.R2",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="3", className="R${i}")
                        @PermuteDefaultReturn(className="R${i}", typeArgs="")
                        public class R2 {
                            public Object step1() { return this; }
                            public Object step2() { return this; }
                            @PermuteReturn(className="String", typeArgs="", alwaysEmit=true)
                            public Object name() { return "hello"; }
                        }
                        """);
        Compilation c = javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src2 = sourceOf(c.generatedSourceFile("io.ex.R2").orElseThrow());
        assertThat(src2).contains("public R2 step1()");
        assertThat(src2).contains("public R2 step2()");
        assertThat(src2).contains("public String name()");
        assertThat(src2).doesNotContain("@PermuteDefaultReturn");
        String src3 = sourceOf(c.generatedSourceFile("io.ex.R3").orElseThrow());
        assertThat(src3).contains("public R3 step1()");
        assertThat(src3).contains("public R3 step2()");
    }

    @Test
    public void testDefaultReturnAnnotationStrippedFromOutput() {
        // @PermuteDefaultReturn must not appear in generated source.
        var source = JavaFileObjects.forSourceString("io.ex.F2",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="2", className="F${i}")
                        @PermuteDefaultReturn(className="F${i}", typeArgs="")
                        public class F2 {
                            public Object run() { return this; }
                        }
                        """);
        Compilation c = javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.F2").orElseThrow());
        assertThat(src).doesNotContain("@PermuteDefaultReturn");
        assertThat(src).contains("public F2 run()");
    }

    @Test
    public void testMethodsWithNonObjectReturnTypeAreUntouched() {
        // Methods with a non-Object return type (String, int, etc.) are not modified.
        var source = JavaFileObjects.forSourceString("io.ex.G2",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="2", className="G${i}")
                        @PermuteDefaultReturn(className="G${i}", typeArgs="")
                        public class G2 {
                            public Object fluent() { return this; }
                            public String label() { return "g"; }
                            public int count() { return 0; }
                        }
                        """);
        Compilation c = javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.G2").orElseThrow());
        assertThat(src).contains("public G2 fluent()");
        assertThat(src).contains("public String label()");
        assertThat(src).contains("public int count()");
    }

    @Test
    public void testDefaultReturnWithTypeArgs() {
        // typeArgs="" is not required; a non-empty typeArgs applies the full generic form.
        var source = JavaFileObjects.forSourceString("io.ex.H2",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="2", className="H${i}")
                        @PermuteDefaultReturn(className="java.util.List", typeArgs="\"String\"")
                        public class H2 {
                            public Object getAll() { return null; }
                        }
                        """);
        Compilation c = javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.H2").orElseThrow());
        assertThat(src).contains("List<String> getAll()");
    }
}
```

- [ ] **Step 2: Run to confirm tests fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteDefaultReturnTest -q 2>&1 | tail -10
```

Expected: compile failure — `PermuteDefaultReturn` does not exist or tests fail because the feature is not implemented.

---

### Task 3: Implement in `PermuteProcessor` (APT path)

- [ ] **Step 1: Read the existing `applyPermuteReturn` in `PermuteProcessor`**

```bash
grep -n "applyPermuteReturn\|PermuteReturn\|generatedSet\|getAnnAttr\|shouldGenerate" \
    /Users/mdproctor/claude/permuplate/permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    | head -30
```

Find the method boundary and read its full implementation.

- [ ] **Step 2: Add `applyDefaultPermuteReturn()` helper to `PermuteProcessor`**

Add this private method to `PermuteProcessor`:

```java
/**
 * Applies {@code @PermuteDefaultReturn} to all Object-returning methods that do not
 * have an explicit {@code @PermuteReturn} annotation. Called after the explicit
 * {@code @PermuteReturn} processing loop so explicit annotations take precedence.
 */
private void applyDefaultPermuteReturn(ClassOrInterfaceDeclaration classDecl,
        EvaluationContext ctx, Set<String> generatedSet, javax.lang.model.element.Element element) {

    // Find @PermuteDefaultReturn on the class
    Optional<com.github.javaparser.ast.expr.NormalAnnotationExpr> defAnn =
            classDecl.getAnnotations().stream()
                    .filter(a -> a.getNameAsString().equals("PermuteDefaultReturn")
                              || a.getNameAsString().equals(
                                      "io.quarkiverse.permuplate.PermuteDefaultReturn"))
                    .filter(a -> a instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr)
                    .map(a -> (com.github.javaparser.ast.expr.NormalAnnotationExpr) a)
                    .findFirst();
    if (defAnn.isEmpty()) return;

    String classNameTemplate = getAnnAttr(defAnn.get(), "className");
    String typeArgsExpr = getAnnAttr(defAnn.get(), "typeArgs");
    String alwaysEmitStr = getAnnAttr(defAnn.get(), "alwaysEmit");
    boolean alwaysEmit = !"false".equals(alwaysEmitStr); // default true

    if (classNameTemplate == null || classNameTemplate.isEmpty()) return;

    String evaluatedClass;
    try { evaluatedClass = ctx.evaluate(classNameTemplate); }
    catch (Exception e) { return; }

    boolean shouldGenerate = alwaysEmit || generatedSet.contains(evaluatedClass);

    classDecl.getMethods().stream()
            .filter(m -> m.getType().asString().equals("Object"))
            .filter(m -> m.getAnnotations().stream().noneMatch(
                    a -> a.getNameAsString().equals("PermuteReturn")
                      || a.getNameAsString().equals(
                              "io.quarkiverse.permuplate.PermuteReturn")))
            .forEach(m -> {
                if (!shouldGenerate) {
                    m.remove();
                    return;
                }
                String typeArgs = (typeArgsExpr == null || typeArgsExpr.isEmpty()) ? ""
                        : ctx.evaluate(typeArgsExpr);
                String typeSrc = typeArgs.isEmpty()
                        ? evaluatedClass
                        : evaluatedClass + "<" + typeArgs + ">";
                m.setType(StaticJavaParser.parseType(typeSrc));
            });

    // Strip the class-level annotation from generated output
    classDecl.getAnnotations().removeIf(a ->
            a.getNameAsString().equals("PermuteDefaultReturn")
         || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteDefaultReturn"));
}
```

- [ ] **Step 3: Call `applyDefaultPermuteReturn()` at the end of the explicit `@PermuteReturn` processing**

In the `applyPermuteReturn()` method (or wherever `@PermuteReturn` is processed per class), add after the existing loop:

```java
// Class-level default: apply to remaining Object-returning methods
applyDefaultPermuteReturn(classDecl, ctx, generatedSet, element);
```

---

### Task 4: Implement in `InlineGenerator` (Maven plugin path)

- [ ] **Step 1: Read the existing `@PermuteReturn` processing in `InlineGenerator`**

```bash
grep -n "applyPermuteReturn\|PermuteReturn\|allGeneratedNames\|shouldGenerate" \
    /Users/mdproctor/claude/permuplate/permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | head -20
```

- [ ] **Step 2: Add `applyDefaultPermuteReturn()` to `InlineGenerator`**

Add this private method (adapted to InlineGenerator's pattern for reading annotation attributes and generated name sets):

```java
/**
 * Applies the class-level {@code @PermuteDefaultReturn} to all Object-returning methods
 * that do not have an explicit {@code @PermuteReturn}. Mirrors the APT path implementation.
 */
private static void applyDefaultPermuteReturn(TypeDeclaration<?> classDecl,
        EvaluationContext ctx, Set<String> allGeneratedNames) {

    classDecl.getAnnotations().stream()
            .filter(a -> a.getNameAsString().equals("PermuteDefaultReturn")
                      || a.getNameAsString().equals(
                              "io.quarkiverse.permuplate.PermuteDefaultReturn"))
            .filter(a -> a instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr)
            .map(a -> (com.github.javaparser.ast.expr.NormalAnnotationExpr) a)
            .findFirst()
            .ifPresent(defAnn -> {
                String classNameTemplate = getAnnAttrInline(defAnn, "className");
                String typeArgsExpr = getAnnAttrInline(defAnn, "typeArgs");
                String alwaysEmitStr = getAnnAttrInline(defAnn, "alwaysEmit");
                boolean alwaysEmit = !"false".equals(alwaysEmitStr);

                if (classNameTemplate == null || classNameTemplate.isEmpty()) return;

                String evaluatedClass;
                try { evaluatedClass = ctx.evaluate(classNameTemplate); }
                catch (Exception e) { return; }

                boolean shouldGenerate = alwaysEmit || allGeneratedNames.contains(evaluatedClass);

                classDecl.getMethods().stream()
                        .filter(m -> m.getType().asString().equals("Object"))
                        .filter(m -> m.getAnnotations().stream().noneMatch(
                                a -> a.getNameAsString().equals("PermuteReturn")
                                  || a.getNameAsString().equals(
                                          "io.quarkiverse.permuplate.PermuteReturn")))
                        .forEach(m -> {
                            if (!shouldGenerate) { m.remove(); return; }
                            String typeArgs = (typeArgsExpr == null || typeArgsExpr.isEmpty()) ? ""
                                    : ctx.evaluate(typeArgsExpr);
                            String typeSrc = typeArgs.isEmpty()
                                    ? evaluatedClass
                                    : evaluatedClass + "<" + typeArgs + ">";
                            m.setType(StaticJavaParser.parseType(typeSrc));
                        });

                // Strip the annotation from generated output
                classDecl.getAnnotations().removeIf(a ->
                        a.getNameAsString().equals("PermuteDefaultReturn")
                     || a.getNameAsString().equals(
                             "io.quarkiverse.permuplate.PermuteDefaultReturn"));
            });
}
```

Note: The helper `getAnnAttrInline` may need to be added or use the existing `getAnnAttr` name — read InlineGenerator to confirm the correct helper name:
```bash
grep -n "getAnnAttr\|private static String get" \
    /Users/mdproctor/claude/permuplate/permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | head -10
```

- [ ] **Step 3: Register in `generate()` after `@PermuteReturn` processing**

Find where `@PermuteReturn` processing happens in `InlineGenerator.generate()` and add after it:

```java
// Class-level default return type
applyDefaultPermuteReturn(generated, ctx, allGeneratedNames);
```

- [ ] **Step 4: Add to `stripPermuteAnnotations`**

Add `"PermuteDefaultReturn"` and `"io.quarkiverse.permuplate.PermuteDefaultReturn"` to the annotation strip list. (The transformer already strips it, but the strip list is a safety net for template-level stripping.)

---

### Task 5: Build and verify

- [ ] **Step 1: Build all modules**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-processor,permuplate-maven-plugin -am -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run the new tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteDefaultReturnTest -q 2>&1 | tail -5
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 3: Full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

Expected: all tests pass.

- [ ] **Step 4: Full build including DSL examples**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

---

### Task 6: Commit

- [ ] **Step 1: Commit**

```bash
git add \
    permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDefaultReturn.java \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDefaultReturnTest.java
git commit -m "feat: @PermuteDefaultReturn class-level default return type annotation (closes #82)"
```
