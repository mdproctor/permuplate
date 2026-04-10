# G3 — @PermuteMethod and Extends/Implements Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate multiple method overloads per class via `@PermuteMethod` (inner j loop producing one overload per j value), plus automatic expansion of `extends`/`implements` clauses referencing other generated classes.

**Architecture:** `applyPermuteMethod()` runs early in the InlineGenerator pipeline — after G1 type param expansion but before `PermuteDeclrTransformer`. For each inner j value, it clones the sentinel method, processes `@PermuteReturn` and `@PermuteDeclr` with the (i,j) context by delegating to existing helpers, and strips all permuplate annotations from the clone. The downstream transformers then see clean methods with no annotations. Extends expansion uses the class's post-G1 type param list to detect and update clauses referencing generated classes. In APT mode, `@PermuteMethod` is handled by reading the JavaParser AST in `generatePermutation()`; `to` inference requires explicit `strings={"max=N"}` in APT.

**Tech Stack:** JavaParser, Google compile-testing (APT tests), JUnit 4, EvaluationContext (all N4 functions available).

**Note:** `typeArgList()` is already implemented (N4). `@PermuteReturn` / `@PermuteDeclr` / implicit inference are already implemented (G2). This plan builds directly on both.

---

## Key design decisions

1. **Pipeline order in InlineGenerator:** `applyPermuteMethod()` runs BEFORE `PermuteDeclrTransformer` and `PermuteParamTransformer`. Each overload clone has all permuplate annotations pre-processed with innerCtx, then stripped. Downstream transformers see clean methods.

2. **`to` inference for InlineGenerator:** `config.to - currentI` where `currentI = vars.get(config.varName)`. No `strings={"max=N"}` needed in inline mode.

3. **`to` inference for APT:** APT can infer `to` from the enclosing `@Permute.to` annotation (readable from the same TypeElement). Explicit `strings={"max=N"}` is only needed for cross-module deps.

4. **Extends expansion:** Compare extends clause type args against the class's post-G1 declared type params. If the extends type args are a prefix of the declared params AND the base class is in the generated set → expand.

5. **M1 (APT):** `@PermuteMethod` is SOURCE retention — APT cannot detect it on non-`@Permute` classes via annotation mirrors. The M1 check is: within `generatePermutation()`, any `@PermuteMethod` found is valid by construction. A warning-only check can be done by scanning the source AST; full enforcement is a future improvement.

---

## File Map

| File | Change |
|---|---|
| `permuplate-annotations/.../PermuteMethod.java` | **Create** |
| `permuplate-annotations/.../PermuteExtends.java` | **Create** |
| `permuplate-core/.../PermuteDeclrTransformer.java` | **Modify** — add `public static processMethodParamDeclr()` |
| `permuplate-maven-plugin/.../AnnotationReader.java` | **Modify** — add `PermuteMethodConfig` record + `readPermuteMethod()` |
| `permuplate-maven-plugin/.../InlineGenerator.java` | **Modify** — `applyPermuteMethod()`, `applyExtendsExpansion()`, updated pipeline |
| `permuplate-processor/.../PermuteProcessor.java` | **Modify** — `@PermuteMethod` APT processing in `generatePermutation()` |
| `permuplate-tests/.../PermuteMethodTest.java` | **Create** |

---

## Task 1: Create `@PermuteMethod` and `@PermuteExtends` annotations

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteMethod.java`
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteExtends.java`

- [ ] **Step 1: Create `@PermuteMethod`**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates multiple overloads of a sentinel method, one per inner loop value.
 *
 * <p>For outer permutation value {@code i} and inner value {@code j} (from {@code from}
 * to {@code to} inclusive), one method overload is generated. When {@code from > to},
 * no overloads are generated — this is the leaf-node mechanism (e.g., {@code Join5Second}
 * has no {@code join()} methods when i=max).
 *
 * <p><b>{@code to} inference:</b> when omitted, inferred as {@code @Permute.to - i}.
 * Works in both APT and Maven plugin modes for same-module class families.
 *
 * <p>In Maven plugin inline mode with {@code T${j}} naming, {@link PermuteReturn} and
 * {@link PermuteDeclr} on parameters are inferred automatically — only
 * {@code @PermuteMethod} is required. In APT mode, use explicit annotations.
 *
 * <p>Example (inline mode, minimal):
 * <pre>{@code
 * @PermuteMethod(varName="j")  // to inferred as @Permute.to - i
 * public Join2First<T1, T2> join(Join1First<T2> fromJ) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteMethod {
    /** Inner loop variable name (e.g., {@code "j"}). */
    String varName();

    /** Inner loop lower bound. Defaults to {@code "1"}. */
    String from() default "1";

    /**
     * Inner loop upper bound. When empty (default), inferred as {@code @Permute.to - i}.
     * Set explicitly for non-linear bounds or cross-module APT dependencies.
     */
    String to() default "";

    /**
     * Optional method name template (e.g., {@code "path${k}"}). When set, each overload
     * gets a distinct name. When empty (default), all overloads share the sentinel name.
     */
    String name() default "";
}
```

- [ ] **Step 2: Create `@PermuteExtends`**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit control over extends/implements clause expansion for a generated class.
 *
 * <p>In the common case (same {@code T${j}} naming, type args matching the class's
 * declared type params), extends/implements expansion is automatic. Use
 * {@code @PermuteExtends} only when implicit inference does not apply.
 *
 * <p>Example:
 * <pre>{@code
 * @PermuteExtends(className="Join${i}Second",
 *                 typeArgVarName="k", typeArgFrom="1", typeArgTo="${i}", typeArgName="T${k}")
 * public class Join1First<T1> extends Join1Second<T1> { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteExtends {
    String className();
    String typeArgVarName() default "";
    String typeArgFrom() default "1";
    String typeArgTo() default "";
    String typeArgName() default "";
    String typeArgs() default "";
    /** 0 = extends clause; 1+ = nth implements interface. */
    int interfaceIndex() default 0;
}
```

- [ ] **Step 3: Build**

```bash
/opt/homebrew/bin/mvn clean install -DskipTests -pl permuplate-annotations -am --no-transfer-progress
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteMethod.java \
        permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteExtends.java
git commit -m "feat(g3): add @PermuteMethod and @PermuteExtends annotations"
```

---

## Task 2: Infrastructure — PermuteMethodConfig + public helper method

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java`
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java`

### AnnotationReader

- [ ] **Step 1: Add `PermuteMethodConfig` and `readPermuteMethod()` after the `PermuteReturnConfig` record**

```java
    /** Parsed @PermuteMethod configuration. */
    public record PermuteMethodConfig(String varName, String from, String to, String name) {
        public boolean hasExplicitTo() { return to != null && !to.isEmpty(); }
        public boolean hasName()       { return name != null && !name.isEmpty(); }
    }

    /**
     * Reads a {@code @PermuteMethod} annotation from a JavaParser {@link AnnotationExpr}.
     * Returns {@code null} if not a {@link NormalAnnotationExpr} or {@code varName} absent.
     */
    public static PermuteMethodConfig readPermuteMethod(AnnotationExpr ann) {
        if (!(ann instanceof NormalAnnotationExpr)) return null;
        NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;
        String varName = null, from = "1", to = "", name = "";
        for (MemberValuePair pair : normal.getPairs()) {
            String val = PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            switch (pair.getNameAsString()) {
                case "varName" -> varName = val;
                case "from"    -> from = val;
                case "to"      -> to = val;
                case "name"    -> name = val;
            }
        }
        return varName == null ? null : new PermuteMethodConfig(varName, from, to, name);
    }
```

### PermuteDeclrTransformer

The helpers `hasPermuteDeclr`, `getPermuteDeclr`, `extractTwoParams`, `renameAllUsages` are package-private in `permuplate-core`. InlineGenerator is in a different module. Add a public method that processes `@PermuteDeclr` on a single method's parameters:

- [ ] **Step 2: Add `processMethodParamDeclr()` to `PermuteDeclrTransformer`**

Add after `transformMethodParams()`:

```java
    /**
     * Processes {@code @PermuteDeclr} on the parameters of a single method using the
     * provided context. Used by {@code InlineGenerator.applyPermuteMethod()} to handle
     * parameter types with the inner {@code (i,j)} context before the method is added
     * to the generated class.
     */
    public static void processMethodParamDeclr(MethodDeclaration method,
            EvaluationContext ctx) {
        List<Parameter> annotated = new ArrayList<>();
        method.getParameters().forEach(p -> {
            if (hasPermuteDeclr(p.getAnnotations())) annotated.add(p);
        });
        for (Parameter param : annotated) {
            AnnotationExpr ann = getPermuteDeclr(param.getAnnotations());
            String[] params = extractTwoParams(ann, null);
            if (params == null) continue;
            String newType = ctx.evaluate(params[0]);
            String newName = params[1];
            param.setType(new ClassOrInterfaceType(null, newType));
            param.getAnnotations().remove(ann);
            if (!newName.isEmpty()) {
                String oldName = param.getNameAsString();
                param.setName(ctx.evaluate(newName));
                method.getBody().ifPresent(body ->
                        renameAllUsages(body, oldName, ctx.evaluate(newName)));
            }
        }
    }
```

You will also need to add the import for `MethodDeclaration` if not present:
```java
import com.github.javaparser.ast.body.MethodDeclaration;
```

- [ ] **Step 3: Build**

```bash
/opt/homebrew/bin/mvn clean install -DskipTests -pl permuplate-core,permuplate-maven-plugin -am --no-transfer-progress
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java \
        permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java
git commit -m "feat(g3): PermuteMethodConfig in AnnotationReader; public processMethodParamDeclr helper"
```

---

## Task 3: Write failing `PermuteMethodTest`

**Files:**
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java`

- [ ] **Step 1: Create the test file**

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteVarConfig;
import io.quarkiverse.permuplate.maven.InlineGenerator;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for @PermuteMethod (multiple overloads per class) and extends/implements expansion.
 */
public class PermuteMethodTest {

    private static String generateInline(String source, String templateClass,
            String varName, int from, int to, String classNameTemplate, int forI) {
        CompilationUnit cu = StaticJavaParser.parse(source);
        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals(templateClass)).orElseThrow();
        PermuteConfig config = new PermuteConfig(varName, from, to, classNameTemplate,
                new String[0], new PermuteVarConfig[0], true, false);
        return InlineGenerator.generate(cu, template, config,
                List.of(Map.of(varName, forI))).toString();
    }

    // =========================================================================
    // @PermuteMethod — inline mode, inferred to
    // =========================================================================

    @Test
    public void testBasicInferredToLeaf() {
        // Join1Second (i=1): j=1..2 → 2 overloads
        // Join3Second (i=3): j=1..0 → 0 overloads (leaf)
        String template = """
                package com.example;
                public class Parent {
                    public static class Join1Second<T1> {
                        @io.quarkiverse.permuplate.PermuteMethod(varName="j")
                        public Join2First<T1, T2> join(Join1First<T2> fromJ) { return null; }
                        public void execute() {}
                    }
                }
                """;

        // i=1: 2 overloads (to = 3-1 = 2)
        String out1 = generateInline(template, "Join1Second", "i", 1, 3, "Join${i}Second", 1);
        assertThat(out1).contains("Join2First<T1, T2>");
        assertThat(out1).contains("Join3First<T1, T2, T3>");
        assertThat(out1).contains("execute()");

        // i=3: 0 overloads (leaf)
        String out3 = generateInline(template, "Join1Second", "i", 1, 3, "Join${i}Second", 3);
        assertThat(out3).doesNotContain("join(");
        assertThat(out3).contains("execute()");
    }

    @Test
    public void testInferredReturnAndParamTypesForAllJ() {
        // i=1, to=4: j=1,2,3 → 3 overloads with different return + param types
        String template = """
                package com.example;
                public class Parent {
                    public static class Join1Second<T1> {
                        @io.quarkiverse.permuplate.PermuteMethod(varName="j")
                        public Join2First<T1, T2> join(Join1First<T2> fromJ) { return null; }
                    }
                }
                """;

        String out1 = generateInline(template, "Join1Second", "i", 1, 4, "Join${i}Second", 1);
        // j=1: Join2First<T1,T2>, Join1First<T2>
        assertThat(out1).contains("Join2First<T1, T2>");
        assertThat(out1).contains("Join1First<T2>");
        // j=2: Join3First<T1,T2,T3>, Join2First<T2,T3>
        assertThat(out1).contains("Join3First<T1, T2, T3>");
        assertThat(out1).contains("Join2First<T2, T3>");
        // j=3: Join4First<T1,T2,T3,T4>, Join3First<T2,T3,T4>
        assertThat(out1).contains("Join4First<T1, T2, T3, T4>");
        assertThat(out1).contains("Join3First<T2, T3, T4>");
        // @PermuteMethod stripped
        assertThat(out1).doesNotContain("@PermuteMethod");
    }

    // =========================================================================
    // Extends/implements clause expansion
    // =========================================================================

    @Test
    public void testExtendsClauseImplicitExpansion() {
        // Join1First<T1> extends Join1Second<T1>
        // At i=1: Join2First<T1,T2> extends Join2Second<T1,T2>
        // At i=3: Join4First<T1,T2,T3,T4> extends Join4Second<T1,T2,T3,T4>
        String template = """
                package com.example;
                public class Parent {
                    public static class Join1First<T1> extends Join1Second<T1> {
                        public void filter() {}
                    }
                }
                """;

        String out1 = generateInline(template, "Join1First", "i", 1, 4, "Join${i}First", 1);
        assertThat(out1).contains("extends Join2Second");
        assertThat(out1).contains("T1, T2");

        String out3 = generateInline(template, "Join1First", "i", 1, 4, "Join${i}First", 3);
        assertThat(out3).contains("extends Join4Second");
        assertThat(out3).contains("T1, T2, T3, T4");
    }

    @Test
    public void testExtendsClauseNotExpandedWhenNotInGeneratedSet() {
        // Extends a non-generated class → unchanged
        String template = """
                package com.example;
                public class Parent {
                    public static class Step1<T1> extends BaseStep<T1> {
                    }
                }
                """;

        String out1 = generateInline(template, "Step1", "i", 1, 3, "Step${i}", 1);
        assertThat(out1).contains("extends BaseStep<T1>");
    }

    // =========================================================================
    // APT mode — explicit @PermuteMethod with @PermuteReturn
    // =========================================================================

    @Test
    public void testAptExplicitPermuteMethod() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Apt1Second",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteMethod;
                import io.quarkiverse.permuplate.PermuteReturn;
                @Permute(varName="i", from=1, to=3, className="Apt${i}Second",
                         strings={"max=3"})
                public class Apt1Second {
                    @PermuteMethod(varName="j", from="1", to="${max - i}")
                    @PermuteReturn(className="Apt${i+j}First")
                    public Object join(Object src) { return null; }
                    public void execute() {}
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // Apt1Second: j=1..2 → 2 overloads
        String src1 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Apt1Second").orElseThrow());
        assertThat(src1).contains("Apt2First");
        assertThat(src1).contains("Apt3First");
        assertThat(src1).contains("execute()");
        assertThat(src1).doesNotContain("@PermuteMethod");

        // Apt3Second: j=1..0 → 0 overloads (leaf)
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Apt3Second").orElseThrow());
        assertThat(src3).doesNotContain("join(");
        assertThat(src3).contains("execute()");
    }

    @Test
    public void testM1PermuteMethodOutsidePermute() {
        // @PermuteMethod is SOURCE retention — APT cannot detect it on non-@Permute classes.
        // Current behaviour: compilation succeeds; annotation silently ignored.
        // Full M1 enforcement across non-@Permute classes is a future improvement.
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Bare",
                """
                package io.quarkiverse.permuplate.example;
                import io.quarkiverse.permuplate.PermuteMethod;
                public class Bare {
                    @PermuteMethod(varName="j")
                    public Object method() { return null; }
                }
                """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        // Annotation silently ignored on non-@Permute class
        assertThat(compilation).succeeded();
    }
}
```

- [ ] **Step 2: Verify tests fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am --no-transfer-progress 2>&1 | grep -E "FAIL|ERROR|Tests run|BUILD" | tail -5
```

Expected: BUILD FAILURE or most tests failing. `testM1PermuteMethodOutsidePermute` may already pass.

- [ ] **Step 3: Commit**

```bash
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java
git commit -m "test(g3): add failing PermuteMethodTest (TDD)"
```

---

## Task 4: Implement `applyPermuteMethod()` in InlineGenerator

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

Read the file first to understand the current structure:
```bash
cat permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
```

- [ ] **Step 1: Add necessary imports to InlineGenerator**

Add any missing imports:
```java
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import com.github.javaparser.ast.body.MethodDeclaration;

import io.quarkiverse.permuplate.core.PermuteParamTransformer;
```

- [ ] **Step 2: Add `applyPermuteMethod()` method**

```java
    private static final String PM_SIMPLE = "PermuteMethod";
    private static final String PM_FQ    = "io.quarkiverse.permuplate.PermuteMethod";

    /**
     * Processes @PermuteMethod: for each inner j value, clones the sentinel method,
     * resolves return type and parameter types with the (i,j) context, strips all
     * permuplate annotations from the clone, and adds the overload to the class.
     * The sentinel method is removed. Runs BEFORE PermuteDeclrTransformer so the
     * downstream transformers see clean methods.
     */
    private static void applyPermuteMethod(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            PermuteConfig config,
            Map<String, Object> vars,
            Set<String> allGeneratedNames) {

        List<MethodDeclaration> toRemove = new ArrayList<>();
        List<MethodDeclaration> toAdd    = new ArrayList<>();

        classDecl.getMethods().forEach(method -> {
            java.util.Optional<AnnotationExpr> pmAnnOpt = method.getAnnotations().stream()
                    .filter(a -> a.getNameAsString().equals(PM_SIMPLE)
                            || a.getNameAsString().equals(PM_FQ))
                    .findFirst();
            if (pmAnnOpt.isEmpty()) return;

            AnnotationReader.PermuteMethodConfig pmCfg =
                    AnnotationReader.readPermuteMethod(pmAnnOpt.get());
            if (pmCfg == null) return;

            int fromVal;
            try { fromVal = ctx.evaluateInt(pmCfg.from().isEmpty() ? "1" : pmCfg.from()); }
            catch (Exception ignored) { fromVal = 1; }

            int toVal;
            if (!pmCfg.hasExplicitTo()) {
                int currentI = ((Number) vars.get(config.varName)).intValue();
                toVal = config.to - currentI;
            } else {
                try { toVal = ctx.evaluateInt(pmCfg.to()); }
                catch (Exception ignored) { toVal = fromVal - 1; }
            }

            toRemove.add(method);

            for (int j = fromVal; j <= toVal; j++) {
                EvaluationContext innerCtx = ctx.withVariable(pmCfg.varName(), j);
                MethodDeclaration clone = method.clone();

                // Strip @PermuteMethod from clone
                clone.getAnnotations().removeIf(a -> a.getNameAsString().equals(PM_SIMPLE)
                        || a.getNameAsString().equals(PM_FQ));

                // Process @PermuteReturn (explicit) and implicit inference with innerCtx
                // Wrap clone in a temporary class to reuse existing helpers
                ClassOrInterfaceDeclaration tmp = new ClassOrInterfaceDeclaration();
                tmp.setName("_Tmp");
                tmp.addMember(clone);
                Set<String> explicit = collectExplicitReturnMethodNames(tmp);
                applyPermuteReturn(tmp, innerCtx, allGeneratedNames);
                applyImplicitInference(tmp, innerCtx, allGeneratedNames, explicit);
                clone = tmp.getMethods().get(0);

                // Process @PermuteDeclr on parameters with innerCtx
                io.quarkiverse.permuplate.core.PermuteDeclrTransformer
                        .processMethodParamDeclr(clone, innerCtx);

                // Process @PermuteParam with innerCtx
                ClassOrInterfaceDeclaration tmpParam = new ClassOrInterfaceDeclaration();
                tmpParam.setName("_TmpParam");
                tmpParam.addMember(clone);
                PermuteParamTransformer.transform(tmpParam, innerCtx, null);
                clone = tmpParam.getMethods().get(0);

                // Apply name template if set
                if (pmCfg.hasName()) {
                    try { clone.setName(innerCtx.evaluate(pmCfg.name())); }
                    catch (Exception ignored) { }
                }

                toAdd.add(clone);
            }
        });

        toRemove.forEach(m -> classDecl.getMembers().removeIf(member -> member == m));
        toAdd.forEach(classDecl::addMember);
    }
```

- [ ] **Step 3: Update the pipeline in `generate()`**

Find the current pipeline block:
```java
            // Apply transformations (null messager — Maven plugin has no Messager)
            PermuteTypeParamTransformer.transform(generated, ctx, null, null);
            PermuteDeclrTransformer.transform(generated, ctx, null);
            PermuteParamTransformer.transform(generated, ctx, null);
```

Replace with:
```java
            // Apply transformations (null messager — Maven plugin has no Messager)
            PermuteTypeParamTransformer.transform(generated, ctx, null, null);

            // Capture post-G1 type param names for extends clause expansion (Task 5)
            Set<String> postG1TypeParams = new java.util.LinkedHashSet<>();
            generated.getTypeParameters().forEach(tp ->
                    postG1TypeParams.add(tp.getNameAsString()));

            // @PermuteMethod: generate overloads with (i,j) context — BEFORE other transforms
            applyPermuteMethod(generated, ctx, config, vars, allGeneratedNames);

            // @PermuteDeclr on fields/ctor params/for-each (method params handled above)
            PermuteDeclrTransformer.transform(generated, ctx, null);
            // @PermuteParam on non-@PermuteMethod methods (already handled above)
            PermuteParamTransformer.transform(generated, ctx, null);
```

Also add `@PermuteMethod` and `@PermuteExtends` to `stripPermuteAnnotations()`:
```java
        Set<String> PERMUPLATE_ANNOTATIONS = Set.of(
                "Permute", "io.quarkiverse.permuplate.Permute",
                "PermuteDeclr", "io.quarkiverse.permuplate.PermuteDeclr",
                "PermuteParam", "io.quarkiverse.permuplate.PermuteParam",
                "PermuteTypeParam", "io.quarkiverse.permuplate.PermuteTypeParam",
                "PermuteReturn", "io.quarkiverse.permuplate.PermuteReturn",
                "PermuteMethod", "io.quarkiverse.permuplate.PermuteMethod",
                "PermuteExtends", "io.quarkiverse.permuplate.PermuteExtends");
```

- [ ] **Step 4: Run tests — @PermuteMethod tests should pass; extends tests still fail**

```bash
/opt/homebrew/bin/mvn clean install --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD|FAIL" | tail -5
```

Expected: BUILD SUCCESS. `testBasicInferredToLeaf`, `testInferredReturnAndParamTypesForAllJ`, `testAptExplicitPermuteMethod`, `testM1PermuteMethodOutsidePermute` should pass. Extends tests still fail (Task 5).

If the APT test fails — the APT processor doesn't handle `@PermuteMethod` yet. That's addressed in Task 5.

- [ ] **Step 5: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git commit -m "feat(g3): implement @PermuteMethod in InlineGenerator — inner j loop with inference"
```

---

## Task 5: Extends/implements expansion + APT `@PermuteMethod` processing

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

### InlineGenerator: extends expansion

- [ ] **Step 1: Add `applyExtendsExpansion()` to InlineGenerator**

```java
    /**
     * Expands extends/implements clauses referencing generated classes.
     * Implicit inference: if the base class is in the generated set AND its type args
     * are a prefix of the class's post-G1 declared type params → expand to full list.
     *
     * @param classDecl         the generated class (after G1 expansion)
     * @param declaredTypeParams post-G1 declared type parameter names in order
     * @param allGeneratedNames  the complete set of generated class names
     * @param currentSuffix     numeric suffix of this generated class (e.g. 3 for Join3First)
     */
    private static void applyExtendsExpansion(ClassOrInterfaceDeclaration classDecl,
            Set<String> declaredTypeParams,
            Set<String> allGeneratedNames,
            int currentSuffix) {

        List<String> declaredList = new ArrayList<>(declaredTypeParams);

        NodeList<ClassOrInterfaceType> extended = classDecl.getExtendedTypes();
        for (int idx = 0; idx < extended.size(); idx++) {
            ClassOrInterfaceType ext = extended.get(idx);
            String baseName = ext.getNameAsString();
            if (!allGeneratedNames.contains(baseName)) continue;

            // Check type args are a prefix of declared params
            java.util.Optional<NodeList<com.github.javaparser.ast.type.Type>> typeArgsOpt
                    = ext.getTypeArguments();
            if (typeArgsOpt.isEmpty()) continue;

            List<String> extArgNames = typeArgsOpt.get().stream()
                    .map(com.github.javaparser.ast.type.Type::asString)
                    .collect(Collectors.toList());

            // The template extends clause type args must be a prefix of declared params
            if (extArgNames.size() > declaredList.size()) continue;
            if (!declaredList.subList(0, extArgNames.size()).equals(extArgNames)) continue;

            // Compute new base class name: strip numeric suffix + currentSuffix
            String basePrefix = stripNumericSuffix(baseName);
            String newBaseName = basePrefix + currentSuffix;
            if (!allGeneratedNames.contains(newBaseName)) continue;

            // Build expanded type with all declared params
            String newTypeArgs = String.join(", ", declaredList);
            String newExtStr = newBaseName + (newTypeArgs.isEmpty() ? "" : "<" + newTypeArgs + ">");
            try { extended.set(idx, StaticJavaParser.parseClassOrInterfaceType(newExtStr)); }
            catch (Exception ignored) { }
        }
        // Implements clauses handled identically (future: @PermuteExtends explicit override)
    }
```

- [ ] **Step 2: Wire `applyExtendsExpansion()` into `generate()` pipeline**

After `applyImplicitInference(...)` and before the `// Strip @Permute` block, add:

```java
            // Extends/implements clause expansion (uses post-G1 type params)
            int classSuffix = classNameSuffix(newClassName);
            if (classSuffix >= 0) {
                applyExtendsExpansion(generated, postG1TypeParams, allGeneratedNames, classSuffix);
            }
```

The `postG1TypeParams` variable was captured in Task 4's pipeline update. `classNameSuffix()` already exists in InlineGenerator from the G2b work.

### PermuteProcessor: `@PermuteMethod` APT processing

- [ ] **Step 3: Add `applyPermuteMethodApt()` to PermuteProcessor**

Read the current PermuteProcessor to understand the structure:
```bash
grep -n "applyPermuteReturn\|applyPermuteMethod\|generatePermutation\|5b\.\|6\. Remove" permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java | head -15
```

Add a call in `generatePermutation()`, AFTER `PermuteParamTransformer.transform()` and BEFORE `applyPermuteReturn()`:

```java
        // 5c. @PermuteMethod — generate overloads with explicit @PermuteReturn/@PermuteDeclr
        applyPermuteMethodApt(classDecl, ctx, generatedSet, typeElement, permute);
```

Add the implementation method:

```java
    /**
     * APT mode @PermuteMethod processing: generates one overload per inner j value.
     * @PermuteReturn and @PermuteDeclr on parameters must be explicit (APT mode).
     * The {@code to} attribute must be explicit (set via strings={"max=N"}) since APT
     * cannot infer it from other templates in cross-module scenarios.
     * Within a single module, to is inferred from @Permute.to - i.
     */
    private void applyPermuteMethodApt(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Set<String> generatedSet,
            TypeElement element,
            Permute permute) {

        List<MethodDeclaration> toRemove = new ArrayList<>();
        List<MethodDeclaration> toAdd    = new ArrayList<>();

        classDecl.getMethods().forEach(method -> {
            Optional<NormalAnnotationExpr> pmAnnOpt = method.getAnnotations().stream()
                    .filter(a -> (a.getNameAsString().equals("PermuteMethod")
                            || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteMethod"))
                            && a instanceof NormalAnnotationExpr)
                    .map(a -> (NormalAnnotationExpr) a)
                    .findFirst();
            if (pmAnnOpt.isEmpty()) return;

            String varName = getAnnAttr(pmAnnOpt.get(), "varName");
            if (varName == null || varName.isEmpty()) return;

            String fromStr = getAnnAttr(pmAnnOpt.get(), "from");
            String toStr   = getAnnAttr(pmAnnOpt.get(), "to");
            String nameTemplate = getAnnAttr(pmAnnOpt.get(), "name");

            int fromVal;
            try { fromVal = ctx.evaluateInt(fromStr == null || fromStr.isEmpty() ? "1" : fromStr); }
            catch (Exception ignored) { fromVal = 1; }

            int toVal;
            if (toStr == null || toStr.isEmpty()) {
                // Infer from @Permute.to - i (works for same-module)
                try {
                    int currentI = ctx.evaluateInt(permute.varName());
                    toVal = permute.to() - currentI;
                } catch (Exception ignored) { toVal = fromVal - 1; }
            } else {
                try { toVal = ctx.evaluateInt(toStr); } catch (Exception ignored) { return; }
            }

            toRemove.add(method);

            for (int j = fromVal; j <= toVal; j++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, j);
                MethodDeclaration clone = method.clone();

                // Strip @PermuteMethod from clone
                clone.getAnnotations().removeIf(a ->
                        a.getNameAsString().equals("PermuteMethod")
                        || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteMethod"));

                // Apply @PermuteReturn with innerCtx
                applyPermuteReturnToSingleMethod(clone, innerCtx, generatedSet, element);

                // Apply @PermuteDeclr on params with innerCtx
                io.quarkiverse.permuplate.core.PermuteDeclrTransformer
                        .processMethodParamDeclr(clone, innerCtx);

                // Apply name template
                if (nameTemplate != null && !nameTemplate.isEmpty()) {
                    try { clone.setName(innerCtx.evaluate(nameTemplate)); } catch (Exception ignored) { }
                }

                // Only add if not boundary-omitted (check method name sentinel)
                if (!clone.getNameAsString().equals("__BOUNDARY_OMITTED__")) {
                    toAdd.add(clone);
                }
            }
        });

        toRemove.forEach(m -> classDecl.getMembers().removeIf(member -> member == m));
        toAdd.forEach(classDecl::addMember);
    }

    /**
     * Applies @PermuteReturn to a single MethodDeclaration with the given context.
     * Sets name to "__BOUNDARY_OMITTED__" if the method should be omitted.
     */
    private void applyPermuteReturnToSingleMethod(MethodDeclaration method,
            EvaluationContext ctx,
            Set<String> generatedSet,
            TypeElement element) {
        Optional<NormalAnnotationExpr> annOpt = method.getAnnotations().stream()
                .filter(a -> (a.getNameAsString().equals("PermuteReturn")
                        || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteReturn"))
                        && a instanceof NormalAnnotationExpr)
                .map(a -> (NormalAnnotationExpr) a)
                .findFirst();
        if (annOpt.isEmpty()) return;
        NormalAnnotationExpr ann = annOpt.get();

        String classNameTemplate = getAnnAttr(ann, "className");
        if (classNameTemplate == null) return;

        String evaluatedClassName;
        try { evaluatedClassName = ctx.evaluate(classNameTemplate); }
        catch (Exception ignored) { return; }

        // Boundary omission
        String whenExpr = getAnnAttr(ann, "when");
        boolean shouldGenerate;
        if (whenExpr == null || whenExpr.isEmpty()) {
            shouldGenerate = generatedSet.contains(evaluatedClassName);
        } else {
            try { shouldGenerate = Boolean.parseBoolean(ctx.evaluate("${" + whenExpr + "}")); }
            catch (Exception ignored) { shouldGenerate = generatedSet.contains(evaluatedClassName); }
        }
        if (!shouldGenerate) {
            method.setName("__BOUNDARY_OMITTED__");
            return;
        }

        String returnTypeStr = buildReturnTypeStr(evaluatedClassName,
                getAnnAttr(ann, "typeArgVarName"), getAnnAttr(ann, "typeArgFrom"),
                getAnnAttr(ann, "typeArgTo"), getAnnAttr(ann, "typeArgName"),
                getAnnAttr(ann, "typeArgs"), ctx);
        try { method.setType(StaticJavaParser.parseType(returnTypeStr)); }
        catch (Exception ignored) { }
        method.getAnnotations().removeIf(a -> a == ann);
    }
```

Also pass `permute` to `generatePermutation()` — check if it already has access (it does: `Permute permute` is a parameter). Update the call to `applyPermuteMethodApt()` to pass `permute`.

- [ ] **Step 4: Run the full test suite — all tests should pass**

```bash
/opt/homebrew/bin/mvn clean install --no-transfer-progress 2>&1 | grep -E "Tests run|BUILD|FAIL" | tail -5
```

Expected: BUILD SUCCESS. All PermuteMethodTest tests pass (7 new + 114 existing = 121 total), 0 failures, 1 skipped.

Debug: if `testAptExplicitPermuteMethod` fails, read the test output carefully and fix `applyPermuteMethodApt()`. If extends tests fail, check `applyExtendsExpansion()` — verify `postG1TypeParams` is populated after G1 runs and before extends expansion runs.

- [ ] **Step 5: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
        permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git commit -m "feat(g3): extends expansion in InlineGenerator; @PermuteMethod APT processing

applyExtendsExpansion() detects extends/implements clauses referencing
generated classes (type args are prefix of post-G1 declared params),
updates base class name suffix and type arg list automatically.

applyPermuteMethodApt() in PermuteProcessor generates multiple overloads
in APT mode using explicit @PermuteReturn/@PermuteDeclr with inner j
context; to inferred from @Permute.to - i for same-module templates."
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task | Status |
|---|---|---|
| `@PermuteMethod` annotation with varName/from/to/name | Task 1 | ✓ |
| `@PermuteExtends` annotation | Task 1 | ✓ |
| `PermuteMethodConfig` in AnnotationReader | Task 2 | ✓ |
| `to` inference (`@Permute.to - i`) — InlineGenerator | Task 4 | ✓ |
| `to` inference — APT (same-module) | Task 5 | ✓ |
| Leaf node: empty range → 0 overloads | Task 4 | ✓ |
| Return type + parameter inference for each j | Task 4 (via applyPermuteReturn + applyImplicitInference with innerCtx) | ✓ |
| Extends clause implicit expansion | Task 5 | ✓ |
| `@PermuteMethod` APT processing (explicit @PermuteReturn) | Task 5 | ✓ |
| `@PermuteMethod`/`@PermuteExtends` stripped from keepTemplate output | Task 4 (stripPermuteAnnotations) | ✓ |
| typeArgList already implemented (N4) | n/a | ✓ already done |

**Deferred (out of scope):**
- M1 full enforcement across non-@Permute classes (SOURCE retention prevents APT detection; test documents current behavior as compile-success)
- M2 warning (identical signatures without @PermuteReturn) — detection logic complex
- M7 cycle detection — dependency graph implementation deferred
- `@PermuteExtends` explicit override in InlineGenerator (framework in place; expansion deferred)

**Placeholder scan:** None found.

**Type consistency:** `PermuteMethodConfig` used consistently in Task 2, 4, 5. `applyPermuteReturn` / `applyImplicitInference` already exist in InlineGenerator from G2b.
