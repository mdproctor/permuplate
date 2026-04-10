# @PermuteConst and Lambda @PermuteParam Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `@PermuteConst` for literal initializer substitution and extend `@PermuteParam` to work on typed lambda parameters, enabling generation of Consumer/Predicate/Function interface families.

**Architecture:** `@PermuteConst` is handled inside `PermuteDeclrTransformer` (same declaration-processing phase); lambda `@PermuteParam` is handled inside `PermuteParamTransformer` scoped to the lambda body. Both annotations coexist cleanly with `@PermuteDeclr` — they touch different aspects of the same declaration (name vs. value, and method params vs. lambda params).

**Tech Stack:** Java 17, JavaParser AST, Apache Commons JEXL3, Google compile-testing (JUnit 4)

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteConst.java` | **Create** | New `@PermuteConst` annotation |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java` | **Modify** | Add `transformConst()` handling fields + local vars |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java` | **Modify** | Add lambda param expansion |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteConstTest.java` | **Create** | Tests for `@PermuteConst` alone and mixed with `@PermuteDeclr` |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteParamTest.java` | **Modify** | Add lambda `@PermuteParam` tests |

---

## Task 1: `@PermuteConst` annotation

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteConst.java`

- [ ] **Step 1: Create the annotation**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the initializer of a field or local variable declaration with the
 * evaluated result of a JEXL expression, in the context of the current permutation.
 *
 * <p>
 * Place on a field or local variable that holds a numeric or string constant that
 * must match the current permutation index. The existing initializer value is used
 * only to make the template compile — it is replaced in every generated class.
 *
 * <p>
 * Integer expressions produce an integer literal; all others produce a string literal.
 *
 * <p>
 * May be combined with {@code @PermuteDeclr} on the same declaration: {@code @PermuteDeclr}
 * updates the type and name; {@code @PermuteConst} updates the initializer value.
 * The two annotations are independent and may appear in either order.
 *
 * <p>
 * Example — interface field (no rename):
 * <pre>{@code
 * @PermuteConst("${i}")
 * int ARITY = 2;
 *
 * default int getArity() { return ARITY; }
 * // Generated: int ARITY = 3;  (for i=3)
 * }</pre>
 *
 * <p>
 * Example — combined with {@code @PermuteDeclr} (rename + value update):
 * <pre>{@code
 * @PermuteDeclr(type = "int", name = "ARITY_${i}")
 * @PermuteConst("${i}")
 * int ARITY_2 = 2;
 *
 * public int getArity() { return ARITY_2; }
 * // Generated: int ARITY_3 = 3;  and  return ARITY_3;  (for i=3)
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE })
public @interface PermuteConst {
    /**
     * JEXL expression evaluated in the current permutation context.
     * E.g. {@code "${i}"}, {@code "${i * 2}"}, {@code "'prefix' + ${i}"}.
     */
    String value();
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn install -pl permuplate-annotations -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteConst.java
git commit -m "feat(annotations): add @PermuteConst for literal initializer substitution"
```

---

## Task 2: `@PermuteConst` transformer — fields

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java`

- [ ] **Step 1: Write the failing test first** (in `PermuteConstTest.java` — create the file)

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
 * Tests for @PermuteConst — literal initializer substitution on fields and local variables,
 * including combination with @PermuteDeclr.
 */
public class PermuteConstTest {

    // -------------------------------------------------------------------------
    // @PermuteConst on an interface field — integer expression
    // -------------------------------------------------------------------------

    @Test
    public void testIntegerFieldConst() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Consumer2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteConst;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        import io.quarkiverse.permuplate.PermuteParam;
                        @Permute(varName="i", from=3, to=4, className="Consumer${i}")
                        public interface Consumer2<A, @PermuteTypeParam(varName="j", from="2", to="${i}", name="${alpha(j)}") B> {
                            @PermuteConst("${i}") int ARITY = 2;
                            void accept(A a, @PermuteParam(varName="j", from=2, to="${i}", type="${alpha(j)}", name="${lower(j)}") B b);
                            default int getArity() { return ARITY; }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Consumer3").orElseThrow());
        assertThat(src3).contains("Consumer3<A, B, C>");
        assertThat(src3).contains("void accept(A a, B b, C c)");
        assertThat(src3).contains("int ARITY = 3");
        assertThat(src3).doesNotContain("int ARITY = 2");
        assertThat(src3).contains("return ARITY");

        String src4 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Consumer4").orElseThrow());
        assertThat(src4).contains("Consumer4<A, B, C, D>");
        assertThat(src4).contains("int ARITY = 4");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteConstTest#testIntegerFieldConst -q 2>&1 | tail -20
```
Expected: FAIL — compilation error or wrong output (ARITY not substituted)

- [ ] **Step 3: Add `@PermuteConst` constants to `PermuteDeclrTransformer`**

Add these constants near the top of `PermuteDeclrTransformer`:

```java
private static final String CONST_ANNOTATION_SIMPLE = "PermuteConst";
private static final String CONST_ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteConst";
```

- [ ] **Step 4: Add `transformConstFields()` and call it from `transform()`**

In `PermuteDeclrTransformer.transform()`, add a call after `transformFields()`:

```java
public static void transform(ClassOrInterfaceDeclaration classDecl,
        EvaluationContext ctx,
        Messager messager) {
    transformFields(classDecl, ctx, messager);
    transformConstFields(classDecl, ctx);       // NEW
    transformConstLocals(classDecl, ctx);       // NEW — added in Task 3
    transformConstructorParams(classDecl, ctx, messager);
    transformForEachVars(classDecl, ctx, messager);
    transformMethodParams(classDecl, ctx, messager);
}
```

Add the new method:

```java
// -------------------------------------------------------------------------
// @PermuteConst — field initializer substitution
// -------------------------------------------------------------------------

private static void transformConstFields(ClassOrInterfaceDeclaration classDecl,
        EvaluationContext ctx) {
    classDecl.getFields().forEach(field -> {
        field.getAnnotations().stream()
                .filter(PermuteDeclrTransformer::isPermuteConst)
                .findFirst()
                .ifPresent(ann -> {
                    String expr = extractConstExpr(ann);
                    String evaluated = ctx.evaluate(expr);
                    Expression newInit = toExpression(evaluated);
                    // Update initializer of the first (and only) declarator
                    field.getVariable(0).setInitializer(newInit);
                    // Remove the annotation
                    field.getAnnotations().remove(ann);
                });
    });
}

private static boolean isPermuteConst(AnnotationExpr ann) {
    String name = ann.getNameAsString();
    return name.equals(CONST_ANNOTATION_SIMPLE) || name.equals(CONST_ANNOTATION_FQ);
}

private static String extractConstExpr(AnnotationExpr ann) {
    // @PermuteConst("${i}") — single string value
    if (ann instanceof SingleMemberAnnotationExpr single) {
        return stripQuotes(single.getMemberValue().toString());
    }
    if (ann instanceof NormalAnnotationExpr normal) {
        for (MemberValuePair pair : normal.getPairs()) {
            if (pair.getNameAsString().equals("value")) {
                return stripQuotes(pair.getValue().toString());
            }
        }
    }
    throw new IllegalStateException("@PermuteConst missing value");
}

/**
 * Converts an evaluated JEXL string to a JavaParser Expression.
 * Integers become IntegerLiteralExpr; everything else becomes StringLiteralExpr.
 */
static Expression toExpression(String evaluated) {
    try {
        Integer.parseInt(evaluated);
        return new IntegerLiteralExpr(evaluated);
    } catch (NumberFormatException e) {
        return new StringLiteralExpr(evaluated);
    }
}
```

Add the required imports to `PermuteDeclrTransformer`:
```java
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteConstTest#testIntegerFieldConst -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteConstTest.java
git commit -m "feat(core): @PermuteConst replaces field initializer literal with evaluated expression"
```

---

## Task 3: `@PermuteConst` transformer — local variables

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java`
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteConstTest.java`

- [ ] **Step 1: Write the failing test**

Add to `PermuteConstTest`:

```java
@Test
public void testIntegerLocalVariableConst() {
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.Tracker2",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteConst;
                    @Permute(varName="i", from=3, to=3, className="Tracker${i}")
                    public class Tracker2 {
                        public int getArity() {
                            @PermuteConst("${i}") int n = 2;
                            return n;
                        }
                    }
                    """);

    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);

    assertThat(compilation).succeeded();

    String src = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.Tracker3").orElseThrow());
    assertThat(src).contains("int n = 3");
    assertThat(src).doesNotContain("int n = 2");
    assertThat(src).contains("return n");
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteConstTest#testIntegerLocalVariableConst -q 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 3: Add `transformConstLocals()` to `PermuteDeclrTransformer`**

Add after `transformConstFields()`:

```java
// -------------------------------------------------------------------------
// @PermuteConst — local variable initializer substitution
// -------------------------------------------------------------------------

private static void transformConstLocals(ClassOrInterfaceDeclaration classDecl,
        EvaluationContext ctx) {
    // Walk all method and constructor bodies looking for annotated local variable declarations
    classDecl.walk(com.github.javaparser.ast.expr.VariableDeclarationExpr.class, varDeclExpr -> {
        // Skip for-each variables (handled by transformForEachVars)
        if (varDeclExpr.getParentNode().map(p -> p instanceof ForEachStmt).orElse(false))
            return;

        varDeclExpr.getAnnotations().stream()
                .filter(PermuteDeclrTransformer::isPermuteConst)
                .findFirst()
                .ifPresent(ann -> {
                    String expr = extractConstExpr(ann);
                    String evaluated = ctx.evaluate(expr);
                    Expression newInit = toExpression(evaluated);
                    varDeclExpr.getVariables().get(0).setInitializer(newInit);
                    varDeclExpr.getAnnotations().remove(ann);
                });
    });
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteConstTest#testIntegerLocalVariableConst -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Run all const tests so far**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteConstTest -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteConstTest.java
git commit -m "feat(core): @PermuteConst also replaces local variable initializer literals"
```

---

## Task 4: `@PermuteConst` + `@PermuteDeclr` combined

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteConstTest.java`

This is the combination the user specifically requested — both annotations on the same field.

- [ ] **Step 1: Write the failing test**

Add to `PermuteConstTest`:

```java
// -------------------------------------------------------------------------
// @PermuteConst + @PermuteDeclr combined on the same field
// -------------------------------------------------------------------------

@Test
public void testConstAndDeclrCombined() {
    // @PermuteDeclr renames ARITY_2 → ARITY_3 (type+name) and propagates to return stmt
    // @PermuteConst updates initializer 2 → 3
    // Result: int ARITY_3 = 3;  and  return ARITY_3;
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.Audit2",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteConst;
                    import io.quarkiverse.permuplate.PermuteDeclr;
                    @Permute(varName="i", from=3, to=4, className="Audit${i}")
                    public class Audit2 {
                        @PermuteDeclr(type="int", name="ARITY_${i}")
                        @PermuteConst("${i}")
                        int ARITY_2 = 2;

                        public int getArity() {
                            return ARITY_2;
                        }
                    }
                    """);

    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);

    assertThat(compilation).succeeded();

    String src3 = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.Audit3").orElseThrow());
    // @PermuteDeclr renamed the field and propagated usages
    assertThat(src3).contains("int ARITY_3");
    assertThat(src3).contains("return ARITY_3");
    assertThat(src3).doesNotContain("ARITY_2");
    // @PermuteConst updated the initializer
    assertThat(src3).contains("ARITY_3 = 3");
    assertThat(src3).doesNotContain("ARITY_3 = 2");

    String src4 = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.Audit4").orElseThrow());
    assertThat(src4).contains("int ARITY_4");
    assertThat(src4).contains("ARITY_4 = 4");
    assertThat(src4).contains("return ARITY_4");
}

@Test
public void testConstWithoutDeclrFieldNameUnchanged() {
    // When @PermuteConst is used alone (no @PermuteDeclr), the field name stays the same
    // across all generated classes — only the value changes.
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.Store2",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteConst;
                    @Permute(varName="i", from=3, to=3, className="Store${i}")
                    public interface Store2 {
                        @PermuteConst("${i}") int ARITY = 2;
                        default int getArity() { return ARITY; }
                    }
                    """);

    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);

    assertThat(compilation).succeeded();

    String src = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.Store3").orElseThrow());
    // Name unchanged, value updated
    assertThat(src).contains("int ARITY = 3");
    assertThat(src).doesNotContain("int ARITY = 2");
    assertThat(src).contains("return ARITY");
}
```

- [ ] **Step 2: Run the tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteConstTest -q 2>&1 | tail -10
```

Note: `testConstAndDeclrCombined` may fail if `@PermuteDeclr` runs before `@PermuteConst`, but the field lookup in `transformConstFields` finds the declaration by `@PermuteConst` annotation on the (already-renamed) field. Check: `transformFields()` removes `@PermuteDeclr` from the field — `transformConstFields()` then processes `@PermuteConst`. Since both run in the same `transform()` call, the field object (already renamed by `transformFields`) is found correctly because `@PermuteConst` is still present on it. No ordering issue.

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteConstTest.java
git commit -m "test(core): @PermuteConst + @PermuteDeclr combination coverage"
```

---

## Task 5: Lambda `@PermuteParam` expansion

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java`
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteParamTest.java`

- [ ] **Step 1: Write the failing test**

Add to `PermuteParamTest.java`:

```java
// -------------------------------------------------------------------------
// @PermuteParam on a typed lambda parameter
// -------------------------------------------------------------------------

@Test
public void testLambdaParamExpansion() {
    // negate()-style: lambda param expands; call site inside lambda body expands too.
    // The return type uses @PermuteReturn so it also becomes Predicate${i}.
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.Pred2",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteTypeParam;
                    import io.quarkiverse.permuplate.PermuteParam;
                    import io.quarkiverse.permuplate.PermuteReturn;
                    @Permute(varName="i", from=3, to=4, className="Pred${i}")
                    public interface Pred2<A, @PermuteTypeParam(varName="j", from="2", to="${i}", name="${alpha(j)}") B> {
                        boolean test(A a, @PermuteParam(varName="j", from=2, to="${i}", type="${alpha(j)}", name="${lower(j)}") B b);

                        @PermuteReturn(className="Pred${i}", typeArgVarName="j", typeArgFrom="1", typeArgTo="${i}", typeArgName="${alpha(j)}")
                        default Pred2<A, B> negate() {
                            return (A a, @PermuteParam(varName="j", from=2, to="${i}", type="${alpha(j)}", name="${lower(j)}") B b) -> !test(a, b);
                        }
                    }
                    """);

    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);

    assertThat(compilation).succeeded();

    String src3 = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.Pred3").orElseThrow());
    assertThat(src3).contains("Pred3<A, B, C>");
    assertThat(src3).contains("boolean test(A a, B b, C c)");
    // Lambda params expanded
    assertThat(src3).contains("(A a, B b, C c) -> !test(a, b, c)");
    // Return type updated by @PermuteReturn
    assertThat(src3).contains("default Pred3<A, B, C> negate()");

    String src4 = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.Pred4").orElseThrow());
    assertThat(src4).contains("(A a, B b, C c, D d) -> !test(a, b, c, d)");
    assertThat(src4).contains("default Pred4<A, B, C, D> negate()");
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteParamTest#testLambdaParamExpansion -q 2>&1 | tail -20
```
Expected: FAIL (lambda params not yet expanded)

- [ ] **Step 3: Implement lambda param expansion in `PermuteParamTransformer`**

In `PermuteParamTransformer.transform()`, add a lambda pass AFTER the method-level pass:

```java
public static void transform(ClassOrInterfaceDeclaration classDecl,
        EvaluationContext ctx,
        Messager messager) {
    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
        // Method-level sentinels (existing behaviour)
        Parameter sentinel;
        while ((sentinel = findNextSentinel(method)) != null) {
            transformMethod(method, sentinel, ctx, messager);
        }
        // Lambda-level sentinels (new)
        method.findAll(LambdaExpr.class).forEach(lambda -> {
            Parameter lambdaSentinel;
            while ((lambdaSentinel = findNextLambdaSentinel(lambda)) != null) {
                transformLambda(lambda, lambdaSentinel, ctx, messager);
            }
        });
    });
}
```

Add the new methods:

```java
private static Parameter findNextLambdaSentinel(LambdaExpr lambda) {
    return lambda.getParameters().stream()
            .filter(p -> hasPermuteParam(p.getAnnotations()))
            .findFirst()
            .orElse(null);
}

private static void transformLambda(LambdaExpr lambda,
        Parameter sentinel,
        EvaluationContext ctx,
        Messager messager) {
    AnnotationExpr ann = getPermuteParam(sentinel.getAnnotations());
    PermuteParamValues values = extractValues(ann, messager);
    if (values == null)
        return;

    String anchorName = sentinel.getNameAsString();

    int fromVal = ctx.evaluateInt(values.from);
    int toVal = ctx.evaluateInt(values.to);

    // Build expanded parameter list for the lambda
    NodeList<Parameter> newParams = new NodeList<>();
    List<String> generatedArgNames = new ArrayList<>();
    for (int j = fromVal; j <= toVal; j++) {
        EvaluationContext innerCtx = ctx.withVariable(values.varName, j);
        String paramType = innerCtx.evaluate(values.type);
        String paramName = innerCtx.evaluate(values.name);
        newParams.add(new Parameter(new ClassOrInterfaceType(null, paramType), paramName));
        generatedArgNames.add(paramName);
    }

    // Rebuild lambda parameter list: params before sentinel + expanded + params after
    NodeList<Parameter> origParams = lambda.getParameters();
    int sentinelIdx = origParams.indexOf(sentinel);

    NodeList<Parameter> allParams = new NodeList<>();
    for (int k = 0; k < sentinelIdx; k++) {
        allParams.add(origParams.get(k).clone());
    }
    allParams.addAll(newParams);
    for (int k = sentinelIdx + 1; k < origParams.size(); k++) {
        allParams.add(origParams.get(k).clone());
    }
    lambda.setParameters(allParams);

    // Expand anchor call sites WITHIN THE LAMBDA BODY ONLY
    expandAnchorInStatement(lambda.getBody(), anchorName, generatedArgNames);
}

/**
 * Expands anchor call sites within any {@link Statement} node (method body or lambda body).
 * Replaces occurrences of {@code anchorName} in argument lists of all MethodCallExpr nodes.
 */
private static void expandAnchorInStatement(Statement body,
        String anchorName,
        List<String> generatedArgNames) {
    body.accept(new ModifierVisitor<Void>() {
        @Override
        public Visitable visit(MethodCallExpr call, Void arg) {
            super.visit(call, arg);
            NodeList<Expression> args = call.getArguments();
            int anchorIdx = indexOfAnchor(args, anchorName);
            if (anchorIdx < 0)
                return call;

            NodeList<Expression> newArgs = new NodeList<>();
            for (int i = 0; i < anchorIdx; i++) {
                newArgs.add(args.get(i).clone());
            }
            for (String name : generatedArgNames) {
                newArgs.add(new NameExpr(name));
            }
            for (int i = anchorIdx + 1; i < args.size(); i++) {
                newArgs.add(args.get(i).clone());
            }
            call.setArguments(newArgs);
            return call;
        }
    }, null);
}
```

Also refactor `expandAnchorAtCallSites` to reuse `expandAnchorInStatement`:

```java
private static void expandAnchorAtCallSites(MethodDeclaration method,
        String anchorName,
        List<String> generatedArgNames) {
    method.getBody().ifPresent(body -> expandAnchorInStatement(body, anchorName, generatedArgNames));
}
```

Add the required import:
```java
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.Statement;
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteParamTest#testLambdaParamExpansion -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Run full PermuteParamTest suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteParamTest -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS` — existing tests still pass

- [ ] **Step 6: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteParamTest.java
git commit -m "feat(core): extend @PermuteParam to expand typed lambda parameters"
```

---

## Task 6: Additional lambda tests — edge cases

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteParamTest.java`

- [ ] **Step 1: Write tests for block-body lambda and no-return-type-change lambda**

Add to `PermuteParamTest.java`:

```java
@Test
public void testLambdaParamExpansionBlockBody() {
    // Block-body lambda: { return !test(a, b); } — same expansion, different lambda form
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.BlockPred2",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteTypeParam;
                    import io.quarkiverse.permuplate.PermuteParam;
                    @Permute(varName="i", from=3, to=3, className="BlockPred${i}")
                    public interface BlockPred2<A, @PermuteTypeParam(varName="j", from="2", to="${i}", name="${alpha(j)}") B> {
                        boolean test(A a, @PermuteParam(varName="j", from=2, to="${i}", type="${alpha(j)}", name="${lower(j)}") B b);

                        default Object negate() {
                            return (A a, @PermuteParam(varName="j", from=2, to="${i}", type="${alpha(j)}", name="${lower(j)}") B b) -> {
                                return !test(a, b);
                            };
                        }
                    }
                    """);

    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);

    assertThat(compilation).succeeded();

    String src = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.BlockPred3").orElseThrow());
    assertThat(src).contains("(A a, B b, C c)");
    assertThat(src).contains("!test(a, b, c)");
}

@Test
public void testMethodAndLambdaParamInSameMethod() {
    // A method that has @PermuteParam on its own parameter AND a lambda inside
    // with @PermuteParam — both must expand independently.
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.DualExpander2",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteTypeParam;
                    import io.quarkiverse.permuplate.PermuteParam;
                    @Permute(varName="i", from=3, to=3, className="DualExpander${i}")
                    public class DualExpander2<A, @PermuteTypeParam(varName="j", from="2", to="${i}", name="${alpha(j)}") B> {
                        public void consume(A a, @PermuteParam(varName="j", from=2, to="${i}", type="${alpha(j)}", name="${lower(j)}") B b) {
                            Runnable r = () -> {
                                Object fn = (A a2, @PermuteParam(varName="j", from=2, to="${i}", type="${alpha(j)}", name="${lower(j)}") B b2) -> doSomething(a2, b2);
                            };
                        }
                        void doSomething(A a, B b) {}
                    }
                    """);

    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);

    assertThat(compilation).succeeded();

    String src = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.DualExpander3").orElseThrow());
    // Method param expanded
    assertThat(src).contains("void consume(A a, B b, C c)");
    // Lambda param expanded separately
    assertThat(src).contains("(A a2, B b2, C c2)");
    assertThat(src).contains("doSomething(a2, b2, c2)");
}
```

- [ ] **Step 2: Run the new tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteParamTest -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteParamTest.java
git commit -m "test(core): add edge-case tests for lambda @PermuteParam (block body, dual expansion)"
```

---

## Task 7: Full build verification

**Files:** None — verification only

- [ ] **Step 1: Run the full test suite**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn clean install 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`, 137+ tests passing

- [ ] **Step 2: If all green, commit any remaining changes and tag the feature complete**

```bash
git log --oneline -6
```

---

## Self-Review

**Spec coverage:**
- ✅ `@PermuteConst` annotation created with FIELD + LOCAL_VARIABLE targets
- ✅ Field literal substitution (integer and string)
- ✅ Local variable literal substitution
- ✅ `@PermuteConst` + `@PermuteDeclr` combination (user's explicit request) — Task 4
- ✅ Lambda `@PermuteParam` on typed lambda parameters
- ✅ Call-site expansion scoped to lambda body
- ✅ Lambda + `@PermuteReturn` (negate() pattern) — Task 5
- ✅ Block-body lambda edge case — Task 6
- ✅ Method param + lambda param in same method — Task 6

**Placeholder scan:** None found — all steps have concrete code.

**Type consistency:**
- `PermuteParamValues` inner class reused as-is from existing transformer
- `expandAnchorInStatement(Statement, String, List<String>)` is a new shared method used by both method and lambda paths — consistent signatures
- `toExpression(String)` is a new static utility in `PermuteDeclrTransformer` — only called from `transformConstFields` and `transformConstLocals`
