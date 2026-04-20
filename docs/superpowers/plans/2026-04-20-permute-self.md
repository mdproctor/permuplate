# @PermuteSelf Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** New `@Target(METHOD) @Retention(SOURCE)` annotation that marks a method as returning the current generated class with all its type parameters. Eliminates repetitive `@PermuteReturn(className="...", typeArgs="...", alwaysEmit=true)` on fluent self-returning methods.

**Architecture:** `PermuteSelfTransformer` scans methods annotated with `@PermuteSelf`, reads the current generated class name and its type parameters (already expanded by `PermuteTypeParamTransformer`), builds a `ClassOrInterfaceType` return type string, parses it with `StaticJavaParser`, sets the method return type, removes annotation. Registered in both APT and Maven plugin pipelines after `PermuteTypeParamTransformer`.

**Epic:** #79

**Tech Stack:** Java 17, JavaParser 3.28.0, Google compile-testing.

---

## File Structure

| Action | Path | What changes |
|---|---|---|
| Create | `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteSelf.java` | New annotation |
| Create | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteSelfTransformer.java` | New transformer |
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | Register after PermuteTypeParamTransformer |
| Modify | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | Register in COID+enum branches; add to stripPermuteAnnotations |
| Create | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteSelfTest.java` | Tests |
| Modify | `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java` | Apply @PermuteSelf |
| Modify | `CLAUDE.md` | Document transformer ordering and PermuteSelf entry |

---

### Task 1: Annotation

- [ ] **Step 1: Create `PermuteSelf.java`**

Create `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteSelf.java`:

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as returning the current generated class with all its type parameters.
 *
 * <p>The annotated method's return type (typically {@code Object}) is replaced with
 * the generated class name plus its full type parameter list. No attributes needed —
 * the transformer reads the class name and type parameters directly from the AST after
 * {@code @PermuteTypeParam} expansion.
 *
 * <p>Example:
 * <pre>{@code
 * @Permute(varName="i", from="2", to="4", className="Builder${i}")
 * public class Builder2<T1,
 *         @PermuteTypeParam(varName="k", from="2", to="${i}", name="T${k}") T2> {
 *
 *     @PermuteSelf
 *     public Object withValue(T1 v) { return this; }
 * }
 * // generates: public Builder3<T1,T2,T3> withValue(T1 v) { return this; }
 * }</pre>
 *
 * <p>Pipeline position: runs after {@code PermuteTypeParamTransformer} so type parameters
 * are already expanded, and before {@code PermuteParamTransformer}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteSelf {
}
```

---

### Task 2: Tests (TDD — write before implementing)

- [ ] **Step 1: Create `PermuteSelfTest.java`**

Create `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteSelfTest.java`.

Find the package declaration pattern used in existing tests:
```bash
head -5 /Users/mdproctor/claude/permuplate/permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseTest.java
```

Write the test class:

```java
package io.quarkiverse.permuplate;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static io.quarkiverse.permuplate.TestHelper.sourceOf;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import io.quarkiverse.permuplate.processor.PermuteProcessor;
import org.junit.jupiter.api.Test;

public class PermuteSelfTest {

    @Test
    public void testPermuteSelfSetsReturnTypeToCurrentClass() {
        // @PermuteSelf replaces Object return type with the generated class name.
        var source = JavaFileObjects.forSourceString("io.ex.Fluent2",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSelf;
                        @Permute(varName="i", from="2", to="3", className="Fluent${i}")
                        public class Fluent2 {
                            @PermuteSelf
                            public Object self() { return this; }
                        }
                        """);
        Compilation c = javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src2 = sourceOf(c.generatedSourceFile("io.ex.Fluent2").orElseThrow());
        assertThat(src2).contains("public Fluent2 self()");
        assertThat(src2).doesNotContain("@PermuteSelf");
        String src3 = sourceOf(c.generatedSourceFile("io.ex.Fluent3").orElseThrow());
        assertThat(src3).contains("public Fluent3 self()");
        assertThat(src3).doesNotContain("@PermuteSelf");
    }

    @Test
    public void testPermuteSelfWithTypeParameters() {
        // @PermuteSelf includes the expanded type parameter list in the return type.
        var source = JavaFileObjects.forSourceString("io.ex.Builder2",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="3", className="Builder${i}")
                        public class Builder2<T1,
                                @PermuteTypeParam(varName="k", from="2", to="${i}", name="T${k}") T2> {
                            @PermuteSelf
                            public Object withValue() { return this; }
                        }
                        """);
        Compilation c = javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        // Builder3 should have return type Builder3<T1, T2, T3>
        String src3 = sourceOf(c.generatedSourceFile("io.ex.Builder3").orElseThrow());
        assertThat(src3).contains("Builder3<T1, T2, T3> withValue()");
    }

    @Test
    public void testPermuteSelfOnMultipleMethods() {
        // Multiple @PermuteSelf methods in the same class are all updated.
        var source = JavaFileObjects.forSourceString("io.ex.Chain2",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="2", className="Chain${i}")
                        public class Chain2 {
                            @PermuteSelf public Object step1() { return this; }
                            @PermuteSelf public Object step2() { return this; }
                        }
                        """);
        Compilation c = javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.Chain2").orElseThrow());
        assertThat(src).contains("public Chain2 step1()");
        assertThat(src).contains("public Chain2 step2()");
    }

    @Test
    public void testMethodsWithoutPermuteSelfAreUnchanged() {
        // Methods without @PermuteSelf keep their return type.
        var source = JavaFileObjects.forSourceString("io.ex.NoSelf2",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="2", className="NoSelf${i}")
                        public class NoSelf2 {
                            public String name() { return "hello"; }
                        }
                        """);
        Compilation c = javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.NoSelf2").orElseThrow());
        assertThat(src).contains("public String name()");
    }
}
```

- [ ] **Step 2: Run to confirm tests fail (annotation missing)**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteSelfTest -q 2>&1 | tail -10
```

Expected: compile failure — `PermuteSelf` does not exist yet.

---

### Task 3: Implement `PermuteSelfTransformer`

- [ ] **Step 1: Create `PermuteSelfTransformer.java`**

Create `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteSelfTransformer.java`:

```java
package io.quarkiverse.permuplate.core;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.stream.Collectors;

/**
 * Processes {@code @PermuteSelf} annotations on methods.
 *
 * <p>For each method annotated with {@code @PermuteSelf}, replaces the method's return type
 * with the current generated class name including all its type parameters. The annotation
 * is then removed from the method.
 *
 * <p>Must run AFTER {@code PermuteTypeParamTransformer} so that type parameters are already
 * expanded to their final form.
 */
public class PermuteSelfTransformer {

    private static final String SIMPLE = "PermuteSelf";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteSelf";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        String className = classDecl.getNameAsString();

        // Build the full return type from the class's current type parameters.
        // Type params are already expanded by PermuteTypeParamTransformer at this point.
        String typeParams = classDecl.getTypeParameters().stream()
                .map(tp -> tp.getNameAsString())
                .collect(Collectors.joining(", "));
        String returnTypeSrc = typeParams.isEmpty()
                ? className
                : className + "<" + typeParams + ">";
        com.github.javaparser.ast.type.Type returnType =
                StaticJavaParser.parseType(returnTypeSrc);

        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            boolean hasAnnotation = method.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals(SIMPLE)
                                || a.getNameAsString().equals(FQ));
            if (!hasAnnotation) return;
            method.getAnnotations().removeIf(a -> a.getNameAsString().equals(SIMPLE)
                                               || a.getNameAsString().equals(FQ));
            method.setType(returnType.clone());
        });
    }
}
```

---

### Task 4: Register in pipelines

- [ ] **Step 1: Build annotations + core modules**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-core -am -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Register in `PermuteProcessor.java`**

In `PermuteProcessor.java`, find the block that calls `PermuteTypeParamTransformer`. After that call, insert:

```java
// @PermuteSelf — set return type to current generated class (with type params)
io.quarkiverse.permuplate.core.PermuteSelfTransformer.transform(classDecl, ctx);
```

To find the exact location:
```bash
grep -n "PermuteTypeParamTransformer" \
    /Users/mdproctor/claude/permuplate/permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
```

- [ ] **Step 3: Register in `InlineGenerator.java` (COID branch)**

In `InlineGenerator.java`, find the COID branch that calls `PermuteTypeParamTransformer`. After that call, insert:

```java
// @PermuteSelf — set return type to current generated class (with type params)
PermuteSelfTransformer.transform(generated, ctx);
```

Add import: `import io.quarkiverse.permuplate.core.PermuteSelfTransformer;`

To find the exact location:
```bash
grep -n "PermuteTypeParamTransformer\|PermuteSelf\|stripPermuteAnnotations" \
    /Users/mdproctor/claude/permuplate/permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | head -20
```

- [ ] **Step 4: Add to `stripPermuteAnnotations` in `InlineGenerator.java`**

Find the `stripPermuteAnnotations` method (or equivalent set of annotation names to strip). Add `"PermuteSelf"` and `"io.quarkiverse.permuplate.PermuteSelf"` to the list.

- [ ] **Step 5: Build processor + plugin**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor,permuplate-maven-plugin -am -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Run tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteSelfTest -q 2>&1 | tail -5
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: Full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

Expected: all tests pass.

---

### Task 5: Apply to Drools DSL `JoinBuilder.java`

- [ ] **Step 1: Read the self-returning methods in `Join0First`**

```bash
grep -n "@PermuteReturn\|return this\|filter\|index\|\.var\b" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    | head -40
```

Identify all methods in `Join0First` that have both `@PermuteReturn` and `return this;` (or `return cast(this);`).

- [ ] **Step 2: Replace `@PermuteReturn` with `@PermuteSelf` on self-returning methods**

For each such method, replace:
```java
@PermuteReturn(className = "Join${i}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i, 'alpha')",
               alwaysEmit = true)
public Object filter(...) { ... return this; }
```
with:
```java
@PermuteSelf
public Object filter(...) { ... return this; }
```

- [ ] **Step 3: Build the DSL example**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Verify `@PermuteSelf` is not present in generated output**

```bash
find /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/target \
    -name "JoinBuilder.java" | xargs grep "@PermuteSelf" 2>/dev/null | head
```

Expected: no output (annotation stripped).

---

### Task 6: Update CLAUDE.md and commit

- [ ] **Step 1: Update CLAUDE.md**

Add `@PermuteSelf` to the annotations table. Add entry to the key decisions table:

```
| `PermuteSelfTransformer` ordering | Runs after `PermuteTypeParamTransformer` so type params are already expanded. Reads `classDecl.getTypeParameters()` directly — no JEXL evaluation needed. Runs before `PermuteParamTransformer`. |
```

- [ ] **Step 2: Commit**

```bash
git add \
    permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteSelf.java \
    permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteSelfTransformer.java \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteSelfTest.java \
    permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    CLAUDE.md
git commit -m "feat: add @PermuteSelf annotation for self-return methods; apply to JoinBuilder (closes #80)"
```
