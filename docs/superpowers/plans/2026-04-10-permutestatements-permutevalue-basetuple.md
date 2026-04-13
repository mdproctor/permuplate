# @PermuteStatements, @PermuteValue, and BaseTuple Template Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `@PermuteStatements` (inserts accumulated statements into a method body) and `@PermuteValue` (replaces the RHS of an assignment or field initializer with a JEXL expression), then use them to template `BaseTuple` in droolsvol2, eliminating the last 400 lines of hand-written numbered code.

**Architecture:** Two new transformers — `PermuteValueTransformer` (step 5f, runs before statement insertion so indices are stable) and `PermuteStatementsTransformer` (step 5g). `@PermuteValue` on a METHOD replaces the RHS of the statement at `index` in the original body; on FIELD/LOCAL_VARIABLE it replaces the initializer (superset of `@PermuteConst`). `@PermuteStatements` inserts one or more evaluated statements at `position = "first"` or `"last"`; with `varName/from/to` it loops like `@PermuteCase`. Both are wired into APT (`PermuteProcessor`) and Maven plugin (`InlineGenerator`) at the same positions.

**Tech Stack:** Java 17, JavaParser 3.25.9, JEXL3, Google compile-testing (JUnit 4)

---

## File Map

| File | Action |
|---|---|
| `permuplate-annotations/…/PermuteStatements.java` | **Create** |
| `permuplate-annotations/…/PermuteValue.java` | **Create** |
| `permuplate-core/…/PermuteValueTransformer.java` | **Create** |
| `permuplate-core/…/PermuteStatementsTransformer.java` | **Create** |
| `permuplate-processor/…/PermuteProcessor.java` | **Modify** — add steps 5f, 5g |
| `permuplate-maven-plugin/…/InlineGenerator.java` | **Modify** — add after PermuteCaseTransformer |
| `permuplate-tests/…/PermuteStatementsTest.java` | **Create** |
| `permuplate-tests/…/PermuteValueTest.java` | **Create** |
| `droolsvol2/src/main/permuplate/…/function/BaseTuple.java` | **Create** |
| `droolsvol2/src/main/java/…/function/BaseTuple.java` | **Modify** — remove Tuple2..6, refactor Tuple1 |

---

## Task 1: Annotations

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteStatements.java`
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteValue.java`

- [ ] **Step 1: Create @PermuteStatements**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inserts one or more statements into the annotated method body per permutation.
 *
 * <p>
 * When {@code varName}, {@code from}, and {@code to} are provided, a statement is
 * inserted for each value of the inner variable in [{@code from}, {@code to}] —
 * the same inner-loop pattern as {@link PermuteCase}. When they are omitted, a
 * single statement is inserted using only the outer permutation context.
 *
 * <p>
 * Example — accumulate field assignments at the start of a constructor:
 * <pre>{@code
 * @PermuteStatements(varName = "k", from = "1", to = "${i-1}",
 *                    position = "first",
 *                    body = "this.${lower(k)} = ${lower(k)};")
 * public Tuple1(A a) { this.a = a; this.size = 1; }
 * // Tuple3: this.a=a; this.b=b; [template body: this.c=c; this.size=3;]
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteStatements {
    /** Inner loop variable name (e.g. {@code "k"}). Omit for single-statement insertion. */
    String varName() default "";

    /** Inclusive lower bound for the inner loop (JEXL expression). */
    String from() default "";

    /** Inclusive upper bound for the inner loop (JEXL expression). */
    String to() default "";

    /**
     * Where to insert: {@code "first"} inserts before all existing statements;
     * {@code "last"} appends after all existing statements.
     */
    String position();

    /**
     * JEXL template for the statement(s) to insert. Multiple statements allowed
     * (parsed as a block). Evaluated per inner-loop value or once if no loop.
     */
    String body();
}
```

- [ ] **Step 2: Create @PermuteValue**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the right-hand side of an assignment or the initializer of a field /
 * local variable with a JEXL-evaluated expression per permutation.
 *
 * <p>
 * On a <b>field or local variable declaration</b>: replaces the initializer.
 * No {@code index} needed — the declaration itself is the target. This is a
 * superset of {@link PermuteConst}.
 *
 * <p>
 * On a <b>method</b>: replaces the RHS of the assignment statement at position
 * {@code index} in the original template method body (0-based, before any
 * {@link PermuteStatements} insertions).
 *
 * <p>
 * Example — on a field:
 * <pre>{@code
 * @PermuteValue("${i}") int ARITY = 2;
 * // Generated at i=4: int ARITY = 4;
 * }</pre>
 *
 * <p>
 * Example — on a method, targeting statement 1:
 * <pre>{@code
 * @PermuteValue(index = 1, value = "${i}")
 * public void init() {
 *     this.name = "x";  // statement 0 — untouched
 *     this.size = 1;    // statement 1 — RHS "1" becomes ${i}
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.METHOD })
public @interface PermuteValue {
    /**
     * JEXL expression for the replacement value (e.g. {@code "${i}"},
     * {@code "${i * 2}"}). Integer results become {@code IntegerLiteralExpr};
     * all others become {@code StringLiteralExpr}.
     */
    String value() default "";

    /**
     * 0-based index of the statement in the method body whose RHS to replace.
     * Only used when the annotation is placed on a method. Ignored on field
     * and local variable declarations.
     */
    int index() default -1;
}
```

- [ ] **Step 3: Build annotations module**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn install -pl permuplate-annotations -DskipTests -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteStatements.java
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteValue.java
git commit -m "feat(annotations): add @PermuteStatements and @PermuteValue"
```

---

## Task 2: Transformers + APT integration (TDD)

**Files:**
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteValueTransformer.java`
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java`
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteValueTest.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteStatementsTest.java`

- [ ] **Step 1: Write failing tests**

Create `PermuteValueTest.java`:

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteValueTest {

    @Test
    public void testFieldInitializerReplaced() {
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Sized2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteValue;
                        @Permute(varName="i", from="3", to="3", className="Sized${i}")
                        public class Sized2 {
                            @PermuteValue("${i}") static int ARITY = 2;
                        }
                        """);
        var compilation = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.permuplate.example.Sized3").orElseThrow());
        assertThat(src).contains("ARITY = 3");
        assertThat(src).doesNotContain("ARITY = 2");
        assertThat(src).doesNotContain("@PermuteValue");
    }

    @Test
    public void testMethodStatementRhsReplaced() {
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Counted2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteValue;
                        @Permute(varName="i", from="3", to="3", className="Counted${i}")
                        public class Counted2 {
                            int size;
                            @PermuteValue(index = 1, value = "${i}")
                            public void init() {
                                this.size = 99;   // statement 0 — untouched
                                this.size = 2;    // statement 1 — RHS replaced
                            }
                        }
                        """);
        var compilation = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.permuplate.example.Counted3").orElseThrow());
        assertThat(src).contains("this.size = 99");   // statement 0 preserved
        assertThat(src).contains("this.size = 3");    // statement 1 replaced
        assertThat(src).doesNotContain("this.size = 2");
        assertThat(src).doesNotContain("@PermuteValue");
    }
}
```

Create `PermuteStatementsTest.java`:

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteStatementsTest {

    @Test
    public void testStatementsInsertedFirst() {
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Accum2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteStatements;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        import io.quarkiverse.permuplate.PermuteDeclr;
                        @Permute(varName="i", from="2", to="3", className="Accum${i}")
                        public class Accum1<@PermuteTypeParam(varName="j", from="2", to="${i}", name="${alpha(j)}") A> {
                            @PermuteDeclr(type="${alpha(i)}", name="${lower(i)}") protected A a;
                            @PermuteStatements(varName="k", from="1", to="${i-1}",
                                               position="first", body="this.${lower(k)} = ${lower(k)};")
                            public void init(A a) {
                                this.a = a;
                            }
                        }
                        """);
        var compilation = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(compilation).succeeded();

        // Accum2: no inserts (from=1, to=0 → empty), just this.b = b from renamed template
        String src2 = sourceOf(compilation.generatedSourceFile("io.permuplate.example.Accum2").orElseThrow());
        assertThat(src2).doesNotContain("@PermuteStatements");

        // Accum3: inserts this.a = a before template body, template body becomes this.c = c
        String src3 = sourceOf(compilation.generatedSourceFile("io.permuplate.example.Accum3").orElseThrow());
        assertThat(src3).contains("this.a = a");
        assertThat(src3).contains("this.c = c");
        assertThat(src3).doesNotContain("@PermuteStatements");
    }

    @Test
    public void testSingleStatementInsertedLast() {
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Tail2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteStatements;
                        @Permute(varName="i", from="3", to="3", className="Tail${i}")
                        public class Tail2 {
                            int x;
                            @PermuteStatements(position="last", body="this.x = ${i};")
                            public void setup() {
                                this.x = 0;
                            }
                        }
                        """);
        var compilation = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.permuplate.example.Tail3").orElseThrow());
        assertThat(src).contains("this.x = 0");   // original preserved
        assertThat(src).contains("this.x = 3");   // appended
        assertThat(src).doesNotContain("@PermuteStatements");
    }
}
```

- [ ] **Step 2: Run to verify FAIL**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-ide-support,permuplate-core,permuplate-processor -DskipTests -q
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="PermuteValueTest,PermuteStatementsTest" 2>&1 | tail -10
```
Expected: FAIL — annotations not processed.

- [ ] **Step 3: Create PermuteValueTransformer**

Create `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteValueTransformer.java`:

```java
package io.quarkiverse.permuplate.core;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.Optional;

/**
 * Handles {@code @PermuteValue} on fields, local variables, and methods.
 *
 * <p>
 * On a <b>field or local variable</b>: replaces the initializer with the JEXL-evaluated
 * {@code value} expression — identical behaviour to {@code @PermuteConst}.
 *
 * <p>
 * On a <b>method</b>: replaces the right-hand side of the assignment statement at
 * position {@code index} in the original template body (0-based, evaluated before
 * any {@code @PermuteStatements} insertions).
 */
public class PermuteValueTransformer {

    private static final String SIMPLE = "PermuteValue";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteValue";

    public static void transform(ClassOrInterfaceDeclaration classDecl, EvaluationContext ctx) {
        // --- Fields ---
        classDecl.getFields().forEach(field -> field.getAnnotations().stream()
                .filter(PermuteValueTransformer::isPermuteValue)
                .findFirst()
                .ifPresent(ann -> {
                    extractValue(ann).ifPresent(expr -> {
                        String evaluated = ctx.evaluate(expr);
                        field.getVariable(0).setInitializer(PermuteDeclrTransformer.toExpression(evaluated));
                    });
                    field.getAnnotations().remove(ann);
                }));

        // --- Local variables ---
        classDecl.walk(com.github.javaparser.ast.expr.VariableDeclarationExpr.class, vde -> {
            if (vde.getParentNode().map(p -> p instanceof com.github.javaparser.ast.stmt.ForEachStmt).orElse(false))
                return;
            vde.getAnnotations().stream()
                    .filter(PermuteValueTransformer::isPermuteValue)
                    .findFirst()
                    .ifPresent(ann -> {
                        extractValue(ann).ifPresent(expr -> {
                            String evaluated = ctx.evaluate(expr);
                            vde.getVariables().get(0).setInitializer(PermuteDeclrTransformer.toExpression(evaluated));
                        });
                        vde.getAnnotations().remove(ann);
                    });
        });

        // --- Methods (statement RHS replacement) ---
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            method.getAnnotations().stream()
                    .filter(PermuteValueTransformer::isPermuteValue)
                    .findFirst()
                    .ifPresent(ann -> {
                        int idx = extractIndex(ann);
                        String valueExpr = extractValue(ann).orElse(null);
                        if (idx >= 0 && valueExpr != null) {
                            method.getBody().ifPresent(body -> {
                                if (idx < body.getStatements().size()) {
                                    Statement stmt = body.getStatements().get(idx);
                                    replaceAssignmentRhs(stmt, ctx.evaluate(valueExpr));
                                }
                            });
                        }
                        method.getAnnotations().remove(ann);
                    });
        });
    }

    private static void replaceAssignmentRhs(Statement stmt, String evaluated) {
        if (!(stmt instanceof ExpressionStmt es)) return;
        if (!(es.getExpression() instanceof AssignExpr assign)) return;
        try {
            assign.setValue(PermuteDeclrTransformer.toExpression(evaluated));
        } catch (Exception ignored) {}
    }

    private static boolean isPermuteValue(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(SIMPLE) || name.equals(FQ);
    }

    private static Optional<String> extractValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr s)
            return Optional.of(PermuteDeclrTransformer.stripQuotes(s.getMemberValue().toString()));
        if (ann instanceof NormalAnnotationExpr n)
            return n.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value"))
                    .findFirst()
                    .map(p -> PermuteDeclrTransformer.stripQuotes(p.getValue().toString()));
        return Optional.empty();
    }

    private static int extractIndex(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr n)
            return n.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("index"))
                    .findFirst()
                    .map(p -> { try { return Integer.parseInt(p.getValue().toString()); } catch (Exception e) { return -1; } })
                    .orElse(-1);
        return -1;
    }
}
```

- [ ] **Step 4: Create PermuteStatementsTransformer**

Create `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java`:

```java
package io.quarkiverse.permuplate.core;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles {@code @PermuteStatements} on method declarations.
 *
 * <p>
 * Inserts one or more statements into the method body at {@code position = "first"}
 * (before all existing statements) or {@code position = "last"} (after all existing
 * statements). When {@code varName/from/to} are provided, one statement is inserted
 * per inner-loop value; otherwise the body is inserted once using the outer context.
 *
 * <p>
 * Applied AFTER {@link PermuteValueTransformer} so that {@code @PermuteValue} index
 * positions refer to the original template body (before any insertions shift indices).
 */
public class PermuteStatementsTransformer {

    private static final String SIMPLE = "PermuteStatements";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteStatements";

    public static void transform(ClassOrInterfaceDeclaration classDecl, EvaluationContext ctx) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            method.getAnnotations().stream()
                    .filter(PermuteStatementsTransformer::isPermuteStatements)
                    .findFirst()
                    .ifPresent(ann -> {
                        if (!(ann instanceof NormalAnnotationExpr normal)) return;

                        String varName = null, from = null, to = null, position = null, body = null;
                        for (MemberValuePair pair : normal.getPairs()) {
                            String val = PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
                            switch (pair.getNameAsString()) {
                                case "varName"   -> varName = val;
                                case "from"      -> from = val;
                                case "to"        -> to = val;
                                case "position"  -> position = val;
                                case "body"      -> body = val;
                            }
                        }
                        if (position == null || body == null) return;

                        List<Statement> toInsert = new ArrayList<>();
                        boolean hasLoop = varName != null && !varName.isEmpty()
                                && from != null && !from.isEmpty()
                                && to != null && !to.isEmpty();

                        if (hasLoop) {
                            int fromVal, toVal;
                            try {
                                fromVal = ctx.evaluateInt(from);
                                toVal = ctx.evaluateInt(to);
                            } catch (Exception ignored) { return; }

                            for (int k = fromVal; k <= toVal; k++) {
                                EvaluationContext innerCtx = ctx.withVariable(varName, k);
                                String evaluated = innerCtx.evaluate(body);
                                parseStatements(evaluated).forEach(toInsert::add);
                            }
                        } else {
                            // Single insertion — no inner loop
                            String evaluated = ctx.evaluate(body);
                            parseStatements(evaluated).forEach(toInsert::add);
                        }

                        method.getBody().ifPresent(methodBody -> {
                            if ("first".equals(position)) {
                                for (int i = toInsert.size() - 1; i >= 0; i--) {
                                    methodBody.getStatements().add(0, toInsert.get(i).clone());
                                }
                            } else { // "last"
                                toInsert.forEach(s -> methodBody.getStatements().add(s.clone()));
                            }
                        });

                        method.getAnnotations().remove(ann);
                    });
        });
    }

    private static List<Statement> parseStatements(String bodyStr) {
        try {
            BlockStmt block = StaticJavaParser.parseBlock("{" + bodyStr + "}");
            return new ArrayList<>(block.getStatements());
        } catch (Exception e) {
            return List.of();
        }
    }

    static boolean isPermuteStatements(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(SIMPLE) || name.equals(FQ);
    }
}
```

- [ ] **Step 5: Wire into PermuteProcessor**

In `PermuteProcessor.generatePermutation()`, after step 5e (PermuteCaseTransformer), add:

```java
        // 5f. @PermuteValue — replace field initializers and method statement RHS values
        //     Must run BEFORE @PermuteStatements so index positions refer to the original body.
        io.quarkiverse.permuplate.core.PermuteValueTransformer.transform(classDecl, ctx);

        // 5g. @PermuteStatements — insert accumulated statements into method bodies
        io.quarkiverse.permuplate.core.PermuteStatementsTransformer.transform(classDecl, ctx);
```

Also add both to the Permuplate annotation strip list in the import-removal section (they already have SOURCE retention so imports are already stripped, but the class-level annotation removal via the existing removeIf should handle them automatically since they're SOURCE annotations).

- [ ] **Step 6: Run tests**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-ide-support,permuplate-core,permuplate-processor -DskipTests -q
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="PermuteValueTest,PermuteStatementsTest" 2>&1 | tail -10
```
Expected: 4/4 pass

Full suite:
```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | tail -5
```
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteValueTransformer.java
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteValueTest.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteStatementsTest.java
git commit -m "feat(core): @PermuteValue and @PermuteStatements transformers in APT"
```

---

## Task 3: Maven plugin integration

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

- [ ] **Step 1: Wire into InlineGenerator**

After `PermuteCaseTransformer.transform(generated, ctx);`, add:

```java
            // @PermuteValue — replace field initializers and method statement RHS
            io.quarkiverse.permuplate.core.PermuteValueTransformer.transform(generated, ctx);

            // @PermuteStatements — insert accumulated statements into method bodies
            io.quarkiverse.permuplate.core.PermuteStatementsTransformer.transform(generated, ctx);
```

Add `"PermuteValue"`, `"io.quarkiverse.permuplate.PermuteValue"`, `"PermuteStatements"`, `"io.quarkiverse.permuplate.PermuteStatements"` to `PERMUPLATE_ANNOTATIONS` in `stripPermuteAnnotations()`.

- [ ] **Step 2: Full install**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | grep "Tests run:.*Skipped: 0$\|BUILD" | tail -4
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git commit -m "feat(plugin): wire @PermuteValue and @PermuteStatements into InlineGenerator"
```

---

## Task 4: BaseTuple template in droolsvol2

**Files:**
- Create: `droolsvol2/src/main/permuplate/org/drools/core/function/BaseTuple.java`
- Modify: `droolsvol2/src/main/java/org/drools/core/function/BaseTuple.java`

Read both files before modifying.

The template has `Tuple1` generating `Tuple2..Tuple10`. Three annotations work together per generated class at arity `i`:
- `@PermuteStatements(k=1..i-1, first)` — inserts `this.a = a; this.b = b; ...` before the new field assignment
- `@PermuteValue(index=1)` — replaces `this.size = 1` → `this.size = ${i}`
- `@PermuteCase` — accumulates switch cases in `get()`/`set()`

- [ ] **Step 1: Create permuplate template**

Create `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/permuplate/org/drools/core/function/BaseTuple.java`:

```java
package org.drools.core.function;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteCase;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteStatements;
import io.quarkiverse.permuplate.PermuteTypeParam;
import io.quarkiverse.permuplate.PermuteValue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseTuple implements Tuple {
    protected int size;

    private static Map<String, Constructor<?>> constructors = new ConcurrentHashMap<>();

    public abstract <T> T get(int index);
    public abstract <T> void set(int index, T t);

    private <T> Constructor<T> getConstructor(Class<T> cls) {
        Constructor<?> con = constructors.computeIfAbsent(cls.getName(), (k) -> {
            for (Constructor<?> c : cls.getDeclaredConstructors())
                if (c.getParameterCount() == size) return c;
            throw new IllegalStateException("Unable to resolve constructor for " + cls.getCanonicalName());
        });
        return (Constructor<T>) con;
    }

    public <T> T as(T... v) {
        Class cls = v.getClass().getComponentType();
        Constructor<T> con = getConstructor(cls);
        try {
            switch (size) {
                case 1: { Tuple1<?> t = (Tuple1<?>) this; return con.newInstance(t.a); }
                case 2: { Tuple2<?,?> t = (Tuple2<?,?>) this; return con.newInstance(t.a, t.b); }
                case 3: { Tuple3<?,?,?> t = (Tuple3<?,?,?>) this; return con.newInstance(t.a, t.b, t.c); }
                case 4: { Tuple4<?,?,?,?> t = (Tuple4<?,?,?,?>) this; return con.newInstance(t.a, t.b, t.c, t.d); }
                case 5: { Tuple5<?,?,?,?,?> t = (Tuple5<?,?,?,?,?>) this; return con.newInstance(t.a, t.b, t.c, t.d, t.e); }
                case 6: { Tuple6<?,?,?,?,?,?> t = (Tuple6<?,?,?,?,?,?>) this; return con.newInstance(t.a, t.b, t.c, t.d, t.e, t.f); }
            }
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        throw new IllegalStateException("Unable to instantiate target class");
    }

    public int size() { return size; }

    public static class Tuple0 extends BaseTuple {
        public static Tuple0 INSTANCE = new Tuple0();
        public Tuple0() { this.size = 0; }
        @Override public <T> T get(int index) { throw new UnsupportedOperationException(); }
        @Override public <T> void set(int index, T t) { throw new UnsupportedOperationException(); }
    }

    // Tuple1 is the template: generates Tuple2..Tuple10
    @Permute(varName = "i", from = "2", to = "10", className = "Tuple${i}",
             inline = true, keepTemplate = true)
    public static class Tuple1<@PermuteTypeParam(varName = "j", from = "2", to = "${i}", name = "${alpha(j)}") A>
            extends BaseTuple {

        // Sentinel field — renamed to b, c, d... by @PermuteDeclr; previous fields inherited
        @PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
        protected A a;

        public Tuple1() { super(); }

        // Constructor:
        // @PermuteStatements inserts "this.a = a; this.b = b; ..." for all previous fields
        // @PermuteValue replaces "this.size = 1" with "this.size = ${i}" — zero extra operations
        @PermuteStatements(varName = "k", from = "1", to = "${i-1}",
                           position = "first", body = "this.${lower(k)} = ${lower(k)};")
        @PermuteValue(index = 1, value = "${i}")
        public Tuple1(A a) {
            this.a = a;      // statement 0 — renamed to this.b=b, this.c=c etc. by @PermuteDeclr
            this.size = 1;   // statement 1 — RHS replaced by @PermuteValue
        }

        public A getA() { return a; }
        public void setA(A a) { this.a = a; }

        // get(int) — @PermuteCase accumulates cases for all previous fields
        @PermuteCase(varName = "k", from = "1", to = "${i-1}",
                     index = "${k}", body = "return (T) ${lower(k+1)};")
        @Override
        public <T> T get(int index) {
            switch (index) {
                case 0: return (T) a;
                default: throw new IndexOutOfBoundsException(index);
            }
        }

        // set(int, T) — @PermuteCase accumulates cases for all previous fields
        @PermuteCase(varName = "k", from = "1", to = "${i-1}",
                     index = "${k}", body = "this.${lower(k+1)} = (${alpha(k+1)}) t; break;")
        @Override
        public <T> void set(int index, T t) {
            switch (index) {
                case 0: { this.a = (A) t; break; }
                default: throw new IndexOutOfBoundsException(index);
            }
        }
    }
}
```

- [ ] **Step 2: Refactor src/main/java BaseTuple**

Read the existing file. Remove `Tuple2..Tuple6` inner classes entirely. Keep:
- `BaseTuple` outer class (unchanged)
- `Tuple0` (unchanged)
- `Tuple1` — refactored to set fields directly (no super chain), using delegation-free switch:

```java
public static class Tuple1<A> extends BaseTuple {
    protected A a;

    public Tuple1() { super(); }

    public Tuple1(A a) {
        this.a = a;
        this.size = 1;
    }

    public A getA() { return a; }
    public void setA(A a) { this.a = a; }

    @Override
    public <T> T get(int index) {
        switch (index) {
            case 0: return (T) a;
            default: throw new IndexOutOfBoundsException(index);
        }
    }

    @Override
    public <T> void set(int index, T t) {
        switch (index) {
            case 0: { this.a = (A) t; break; }
            default: throw new IndexOutOfBoundsException(index);
        }
    }
}
```

- [ ] **Step 3: Test**

```bash
cd /Users/mdproctor/dev/droolsoct2025/droolsvol2
/opt/homebrew/bin/mvn clean test 2>&1 | grep "Tests run:\|BUILD\|ERROR" | tail -5
```
Expected: 65 tests, BUILD SUCCESS

Spot-check a generated file:
```bash
grep "case 2\|case 3\|this.size = 3\|this.b = b" \
  target/generated-sources/permuplate/org/drools/core/function/BaseTuple.java | head -10
```
Expected: all patterns present, confirming accumulation worked.

- [ ] **Step 4: Stage**

```bash
git add src/main/permuplate/org/drools/core/function/BaseTuple.java
git add src/main/java/org/drools/core/function/BaseTuple.java
git status --short | head -10
```

---

## Task 5: Full build verification

- [ ] **Step 1: Full Permuplate install**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn clean install 2>&1 | grep "Tests run:.*Skipped: 0$\|BUILD" | tail -4
```
Expected: BUILD SUCCESS

- [ ] **Step 2: droolsvol2 clean test**

```bash
cd /Users/mdproctor/dev/droolsoct2025/droolsvol2
/opt/homebrew/bin/mvn clean test 2>&1 | grep "Tests run:\|BUILD" | tail -3
```
Expected: 65 tests, BUILD SUCCESS

---

## Self-Review

**Spec coverage:**
- ✅ @PermuteStatements annotation with varName loop and single-body mode — Task 1 & 2
- ✅ @PermuteValue annotation on fields, local vars, methods (by index) — Task 1 & 2
- ✅ @PermuteValue runs BEFORE @PermuteStatements (step 5f vs 5g) — stable index references
- ✅ Maven plugin integration — Task 3
- ✅ BaseTuple template with zero extra runtime operations — Task 4
- ✅ Full build — Task 5

**Placeholder scan:** None found.

**Type consistency:**
- `PermuteValueTransformer.transform(ClassOrInterfaceDeclaration, EvaluationContext)` — defined Task 2, called Tasks 2 & 3
- `PermuteStatementsTransformer.transform(ClassOrInterfaceDeclaration, EvaluationContext)` — same
- `PermuteDeclrTransformer.toExpression(String)` — existing public method, used in PermuteValueTransformer ✓
- `PermuteDeclrTransformer.stripQuotes(String)` — existing public method ✓
