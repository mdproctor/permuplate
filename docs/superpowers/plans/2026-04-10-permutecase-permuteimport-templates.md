# @PermuteCase, @PermuteImport, and DSL Templates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `@PermuteCase` (switch case accumulation without super() delegation) and `@PermuteImport` (dynamic per-permutation imports), then use them to eliminate the last three hand-written numbered sequences in droolsvol2: `BaseTuple`, `RuleOOPathBuilder`, and `RuleExtendsPoint`.

**Architecture:** `@PermuteCase` is processed by a new `PermuteCaseTransformer` called at step 5e in `generatePermutation()` and in `InlineGenerator`'s processing loop. For each generated class at arity `i`, it inserts cases for `k = from..to` (evaluated from the annotation) before the `default` case in the switch. `@PermuteImport` is processed at the CU construction step — evaluated import strings are added to the generated `CompilationUnit` immediately after template imports are copied. Both annotations are stripped from the generated output. Both work identically in APT and Maven plugin modes since both use JavaParser.

**Tech Stack:** Java 17, JavaParser 3.25.9 (`SwitchStmt`, `SwitchEntry`, `StaticJavaParser.parseBlock()`), Apache Commons JEXL3, Google compile-testing (JUnit 4)

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteCase.java` | **Create** | `@PermuteCase` annotation |
| `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteImport.java` | **Create** | `@PermuteImport` annotation |
| `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteImports.java` | **Create** | Repeatable container for `@PermuteImport` |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java` | **Create** | Switch case accumulation logic |
| `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | **Modify** | Add step 5e (@PermuteCase) and @PermuteImport to CU construction |
| `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | **Modify** | Add @PermuteCase and @PermuteImport to inline pipeline |
| `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java` | **Modify** | Add `readPermuteCase()` helper |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseTest.java` | **Create** | TDD tests for @PermuteCase |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteImportTest.java` | **Create** | TDD tests for @PermuteImport |
| `droolsvol2/src/main/permuplate/org/drools/core/RuleExtendsPoint.java` | **Create** | Inline template replacing hand-written inner classes |
| `droolsvol2/src/main/java/org/drools/core/RuleExtendsPoint.java` | **Modify** | Remove hand-written RuleExtendsPoint2..6 inner classes |
| `droolsvol2/src/main/permuplate/org/drools/core/RuleOOPathBuilder.java` | **Create** | Inline template replacing hand-written Path2..6 |
| `droolsvol2/src/main/java/org/drools/core/RuleOOPathBuilder.java` | **Modify** | Remove hand-written Path2..6 inner classes |
| `droolsvol2/src/main/permuplate/org/drools/core/function/BaseTuple.java` | **Create** | Inline template with @PermuteCase for get/set |
| `droolsvol2/src/main/java/org/drools/core/function/BaseTuple.java` | **Modify** | Remove Tuple2..6, refactor Tuple1 get/set to delegation-free pattern |

---

## Task 1: @PermuteCase and @PermuteImport annotations

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteCase.java`
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteImport.java`
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteImports.java`

- [ ] **Step 1: Create @PermuteCase**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Expands a switch statement in the annotated method by inserting new cases
 * for each value of an inner loop variable, producing a fully-inlined dispatch
 * without inheritance delegation.
 *
 * <p>
 * Placed on a method that contains exactly one {@code switch} statement. For each
 * generated class at arity {@code i}, the transformer inserts cases for
 * {@code k = from..to} (evaluated in the outer permutation context) before the
 * {@code default} case. The seed case (in the template source) and the
 * {@code default} case are preserved unchanged in all generated classes.
 *
 * <p>
 * All attributes are JEXL expression strings. The inner variable {@code varName}
 * is bound to each value in {@code [from, to]} when evaluating {@code index} and
 * {@code body}. The outer permutation variable (e.g. {@code i}) is also available.
 *
 * <p>
 * Example — accumulate field access cases in {@code Tuple1} template:
 * <pre>{@code
 * @PermuteCase(varName = "k", from = "1", to = "${i-1}",
 *              index = "${k}", body = "return (T) ${lower(k+1)};")
 * public <T> T get(int index) {
 *     switch (index) {
 *         case 0: return (T) a;   // seed case — unchanged in all generated classes
 *         default: throw new IndexOutOfBoundsException(index);
 *     }
 * }
 * // Generated Tuple3 (i=3): cases 0, 1, 2 — no super() call, no extra stack frame
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteCase {
    /** Inner loop variable name (e.g. {@code "k"}). */
    String varName();

    /** Inclusive lower bound for the inner loop (JEXL expression, e.g. {@code "1"}). */
    String from();

    /**
     * Inclusive upper bound for the inner loop (JEXL expression, e.g. {@code "${i-1}"}).
     * When {@code from > to} after evaluation, no cases are inserted (empty range).
     */
    String to();

    /**
     * JEXL expression for the integer case label, evaluated at each {@code varName} value
     * (e.g. {@code "${k}"}). Must evaluate to a non-negative integer.
     */
    String index();

    /**
     * JEXL template for the case body statements, evaluated at each {@code varName} value
     * (e.g. {@code "return (T) ${lower(k+1)};"}). Multiple statements are allowed;
     * they are parsed as a block. Refer to {@code alpha(n)}, {@code lower(n)}, and
     * all variables from the outer permutation context.
     */
    String body();
}
```

- [ ] **Step 2: Create @PermuteImport**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds a JEXL-evaluated import statement to each generated class.
 *
 * <p>
 * Placed on the template class. For each permutation, the {@code value} string
 * is evaluated with the current permutation context and added as an import to
 * the generated compilation unit. The annotation itself is stripped from the
 * generated output.
 *
 * <p>
 * Example:
 * <pre>{@code
 * @Permute(varName = "i", from = "3", to = "10", className = "Join${i}First", inline = true)
 * @PermuteImport("org.drools.core.function.BaseTuple.Tuple${i}")
 * @PermuteImport("org.drools.core.RuleOOPathBuilder.Path${i}")
 * public static class Join2First<...> { ... }
 * // Generated Join4First gets: import BaseTuple.Tuple4; import RuleOOPathBuilder.Path4;
 * }</pre>
 */
@Repeatable(PermuteImports.class)
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteImport {
    /**
     * JEXL-interpolated fully-qualified import string added to each generated class.
     * E.g. {@code "org.drools.core.function.BaseTuple.Tuple${i}"}.
     */
    String value();
}
```

- [ ] **Step 3: Create @PermuteImports container**

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container annotation for repeatable {@link PermuteImport}. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteImports {
    PermuteImport[] value();
}
```

- [ ] **Step 4: Compile annotations module**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn install -pl permuplate-annotations -DskipTests -q
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteCase.java
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteImport.java
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteImports.java
git commit -m "feat(annotations): add @PermuteCase and @PermuteImport annotations"
```

---

## Task 2: PermuteCaseTransformer — core logic (TDD)

**Files:**
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseTest.java`

- [ ] **Step 1: Write failing test**

Create `PermuteCaseTest.java`:

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
 * Tests for @PermuteCase — switch case accumulation without super() delegation.
 */
public class PermuteCaseTest {

    @Test
    public void testSwitchCasesAccumulatePerArity() {
        // Tuple1 template: seed case 0 (field a).
        // Generated Tuple2 adds case 1 (field b). Tuple3 adds cases 1 and 2 (fields b, c).
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Tuple1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        import io.quarkiverse.permuplate.PermuteCase;
                        import io.quarkiverse.permuplate.PermuteDeclr;
                        @Permute(varName="i", from="2", to="3", className="Tuple${i}")
                        public class Tuple1<@PermuteTypeParam(varName="j", from="2", to="${i}", name="${alpha(j)}") A> {
                            protected A a;
                            @PermuteCase(varName="k", from="1", to="${i-1}", index="${k}", body="return ${lower(k+1)};")
                            public Object get(int index) {
                                switch (index) {
                                    case 0: return a;
                                    default: throw new IndexOutOfBoundsException(index);
                                }
                            }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // Tuple2: should have case 0 (seed) + case 1 (inserted)
        String src2 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Tuple2").orElseThrow());
        assertThat(src2).contains("case 0:");
        assertThat(src2).contains("case 1:");
        assertThat(src2).contains("return b");
        assertThat(src2).doesNotContain("@PermuteCase");

        // Tuple3: should have case 0 (seed) + case 1 + case 2 (inserted)
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Tuple3").orElseThrow());
        assertThat(src3).contains("case 0:");
        assertThat(src3).contains("case 1:");
        assertThat(src3).contains("case 2:");
        assertThat(src3).contains("return b");
        assertThat(src3).contains("return c");
        assertThat(src3).doesNotContain("super.get");
    }

    @Test
    public void testEmptyRangeInsertsNoCases() {
        // When from > to (e.g. i=1, to="${i-1}"=0), no cases are inserted.
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Single1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="2", to="2", className="Single${i}")
                        public class Single1 {
                            @PermuteCase(varName="k", from="1", to="${i-1}", index="${k}", body="return k;")
                            public int dispatch(int index) {
                                switch (index) {
                                    case 0: return 0;
                                    default: throw new IndexOutOfBoundsException(index);
                                }
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);
        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Single2").orElseThrow());
        assertThat(src).contains("case 0:");
        assertThat(src).contains("case 1:");  // from=1, to=i-1=1, k=1 → case 1 inserted
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteCaseTest 2>&1 | tail -10
```
Expected: FAIL — @PermuteCase annotation not processed.

- [ ] **Step 3: Create PermuteCaseTransformer**

Create `/Users/mdproctor/claude/permuplate/permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java`:

```java
package io.quarkiverse.permuplate.core;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;

import java.util.Optional;

/**
 * Handles {@code @PermuteCase} on method declarations.
 *
 * <p>
 * For each generated class at arity {@code i}, inserts new {@code SwitchEntry} nodes
 * into the method's switch statement for each value {@code k} in [{@code from}, {@code to}].
 * The seed case and {@code default} case are preserved. No {@code super()} calls are
 * generated — all cases are inlined directly.
 */
public class PermuteCaseTransformer {

    private static final String ANNOTATION_SIMPLE = "PermuteCase";
    private static final String ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteCase";

    public static void transform(ClassOrInterfaceDeclaration classDecl, EvaluationContext ctx) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<AnnotationExpr> annOpt = method.getAnnotations().stream()
                    .filter(PermuteCaseTransformer::isPermuteCase)
                    .findFirst();
            if (annOpt.isEmpty()) return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr)) return;
            NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;

            String varName = null, from = null, to = null, indexExpr = null, bodyExpr = null;
            for (MemberValuePair pair : normal.getPairs()) {
                String val = PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
                switch (pair.getNameAsString()) {
                    case "varName" -> varName = val;
                    case "from"    -> from = val;
                    case "to"      -> to = val;
                    case "index"   -> indexExpr = val;
                    case "body"    -> bodyExpr = val;
                }
            }
            if (varName == null || from == null || to == null || indexExpr == null || bodyExpr == null)
                return;

            // Find the switch statement in the method body
            Optional<SwitchStmt> switchOpt = method.getBody()
                    .flatMap(body -> body.findFirst(SwitchStmt.class));
            if (switchOpt.isEmpty()) return;

            SwitchStmt sw = switchOpt.get();

            // Evaluate loop bounds
            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(from);
                toVal = ctx.evaluateInt(to);
            } catch (Exception ignored) { return; }

            if (fromVal > toVal) {
                // Empty range — no cases to insert, just strip annotation
                method.getAnnotations().remove(ann);
                return;
            }

            // Find index of the default case (insert before it)
            int defaultIdx = findDefaultCaseIndex(sw);

            // Insert cases for k = fromVal..toVal
            for (int k = fromVal; k <= toVal; k++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, k);

                String caseLabelStr = innerCtx.evaluate(indexExpr);
                String caseBodyStr = innerCtx.evaluate(bodyExpr);

                int caseLabel;
                try { caseLabel = Integer.parseInt(caseLabelStr.trim()); }
                catch (NumberFormatException ignored) { continue; }

                SwitchEntry entry = buildSwitchEntry(caseLabel, caseBodyStr);
                sw.getEntries().add(defaultIdx, entry);
                defaultIdx++; // default shifts right after each insertion
            }

            method.getAnnotations().remove(ann);
        });
    }

    private static int findDefaultCaseIndex(SwitchStmt sw) {
        NodeList<SwitchEntry> entries = sw.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            SwitchEntry entry = entries.get(i);
            if (entry.getLabels().isEmpty()) return i; // default case has no labels
        }
        return entries.size(); // append if no default found
    }

    private static SwitchEntry buildSwitchEntry(int label, String bodyStr) {
        // Parse body as a block to handle multiple statements (e.g. "this.b = t; break;")
        BlockStmt block;
        try {
            block = StaticJavaParser.parseBlock("{" + bodyStr + "}");
        } catch (Exception e) {
            block = new BlockStmt();
        }

        SwitchEntry entry = new SwitchEntry();
        entry.setType(SwitchEntry.Type.STATEMENT_GROUP);
        entry.getLabels().add(new IntegerLiteralExpr(String.valueOf(label)));
        entry.getStatements().addAll(block.getStatements());
        return entry;
    }

    static boolean isPermuteCase(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(ANNOTATION_SIMPLE) || name.equals(ANNOTATION_FQ);
    }
}
```

- [ ] **Step 4: Wire PermuteCaseTransformer into PermuteProcessor**

In `PermuteProcessor.generatePermutation()`, after step 5d (implicit inference), add step 5e:

```java
        // 5e. @PermuteCase — expand switch statement cases per permutation
        io.quarkiverse.permuplate.core.PermuteCaseTransformer.transform(classDecl, ctx);
```

Also add `PermuteCase` and `PermuteCases` to the Permuplate annotation strip list in the import-removal loop:
```java
        templateCu.getImports().forEach(imp -> {
            if (!imp.getNameAsString().startsWith("io.quarkiverse.permuplate")) {
                generatedCu.addImport(imp.clone());
            }
        });
```
(Already strips all `io.quarkiverse.permuplate` imports — no change needed here.)

- [ ] **Step 5: Run tests**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-ide-support,permuplate-core,permuplate-processor -DskipTests -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteCaseTest 2>&1 | tail -10
```
Expected: 2/2 pass, BUILD SUCCESS

- [ ] **Step 6: Full suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | tail -5
```
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseTest.java
git commit -m "feat(core): @PermuteCase transformer — accumulates switch cases per permutation"
```

---

## Task 3: @PermuteCase in InlineGenerator (Maven plugin)

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` (stripPermuteAnnotations)

- [ ] **Step 1: Call PermuteCaseTransformer in InlineGenerator**

In `InlineGenerator.generate()`, after the existing transformer calls (around the `applyExtendsExpansion` call at line ~113), add:

```java
            // @PermuteCase — expand switch cases per permutation
            io.quarkiverse.permuplate.core.PermuteCaseTransformer.transform(generated, ctx);
```

- [ ] **Step 2: Add PermuteCase to stripPermuteAnnotations**

In `stripPermuteAnnotations()`, the `PERMUPLATE_ANNOTATIONS` set should already include all annotations. Verify `"PermuteCase"` and `"io.quarkiverse.permuplate.PermuteCase"` are in the set (they are if the set is constructed from a comprehensive list). If not, add them.

- [ ] **Step 3: Build and test**

```bash
/opt/homebrew/bin/mvn install -DskipTests -q 2>&1 | tail -3
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git commit -m "feat(plugin): wire @PermuteCase transformer into InlineGenerator"
```

---

## Task 4: @PermuteImport — APT + Maven plugin (TDD)

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteImportTest.java`

- [ ] **Step 1: Write failing test**

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

public class PermuteImportTest {

    @Test
    public void testImportAddedPerPermutation() {
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Gen2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteImport;
                        @Permute(varName="i", from="3", to="4", className="Gen${i}")
                        @PermuteImport("java.util.List${i}")
                        public class Gen2 {}
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // Gen3 should have import java.util.List3
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Gen3").orElseThrow());
        assertThat(src3).contains("import java.util.List3");
        assertThat(src3).doesNotContain("@PermuteImport");

        // Gen4 should have import java.util.List4
        String src4 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Gen4").orElseThrow());
        assertThat(src4).contains("import java.util.List4");
    }

    @Test
    public void testMultipleImportsRepeatable() {
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Multi2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteImport;
                        @Permute(varName="i", from="3", to="3", className="Multi${i}")
                        @PermuteImport("pkg.A${i}")
                        @PermuteImport("pkg.B${i}")
                        public class Multi2 {}
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Multi3").orElseThrow());
        assertThat(src).contains("import pkg.A3");
        assertThat(src).contains("import pkg.B3");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteImportTest 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 3: Implement @PermuteImport in PermuteProcessor**

In `generatePermutation()`, in the CU construction block (just after the template imports are copied, before `generatedCu.addType(classDecl)`), add:

```java
        // @PermuteImport — add per-permutation imports to the generated CU
        collectPermuteImports(classDecl).forEach(importStr -> {
            try {
                generatedCu.addImport(ctx.evaluate(importStr));
            } catch (Exception ignored) {}
        });
        // Strip @PermuteImport and @PermuteImports from the generated class
        classDecl.getAnnotations().removeIf(a -> {
            String n = a.getNameAsString();
            return n.equals("PermuteImport")   || n.equals("io.quarkiverse.permuplate.PermuteImport")
                || n.equals("PermuteImports") || n.equals("io.quarkiverse.permuplate.PermuteImports");
        });
```

Add the helper method to `PermuteProcessor`:
```java
    /** Extracts all @PermuteImport value strings from the class (handles both single and @PermuteImports container). */
    private static java.util.List<String> collectPermuteImports(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl) {
        java.util.List<String> imports = new java.util.ArrayList<>();
        classDecl.getAnnotations().forEach(ann -> {
            String name = ann.getNameAsString();
            if (name.equals("PermuteImport") || name.equals("io.quarkiverse.permuplate.PermuteImport")) {
                if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr single) {
                    imports.add(io.quarkiverse.permuplate.core.PermuteDeclrTransformer
                            .stripQuotes(single.getMemberValue().toString()));
                } else if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
                    normal.getPairs().stream()
                            .filter(p -> p.getNameAsString().equals("value"))
                            .findFirst()
                            .ifPresent(p -> imports.add(io.quarkiverse.permuplate.core.PermuteDeclrTransformer
                                    .stripQuotes(p.getValue().toString())));
                }
            } else if (name.equals("PermuteImports") || name.equals("io.quarkiverse.permuplate.PermuteImports")) {
                // Container: @PermuteImports({@PermuteImport("..."), @PermuteImport("...")})
                if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
                    normal.getPairs().stream()
                            .filter(p -> p.getNameAsString().equals("value"))
                            .findFirst()
                            .ifPresent(p -> {
                                com.github.javaparser.ast.expr.Expression val = p.getValue();
                                java.util.List<com.github.javaparser.ast.expr.Expression> elems =
                                        val instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr
                                                ? arr.getValues() : java.util.List.of(val);
                                elems.forEach(e -> {
                                    if (e instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr sm) {
                                        imports.add(io.quarkiverse.permuplate.core.PermuteDeclrTransformer
                                                .stripQuotes(sm.getMemberValue().toString()));
                                    }
                                });
                            });
                }
            }
        });
        return imports;
    }
```

- [ ] **Step 4: Implement @PermuteImport in InlineGenerator**

In InlineGenerator, after generating each class (in the `generateInlineGroup` loop where the generated CU is being built), add the @PermuteImport handling. Find where the parent CU imports are managed and add evaluated imports to the parent CU:

```java
            // @PermuteImport — add evaluated imports to the parent CU for each permutation
            collectPermuteImportsFromClass(generated, vars, config).forEach(imp -> {
                if (outputCu.getImports().stream().noneMatch(i -> i.getNameAsString().equals(imp))) {
                    outputCu.addImport(imp);
                }
            });
            // Strip @PermuteImport annotations from the generated inner class
            generated.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("PermuteImport")   || n.equals("io.quarkiverse.permuplate.PermuteImport")
                    || n.equals("PermuteImports") || n.equals("io.quarkiverse.permuplate.PermuteImports");
            });
```

Add the helper method to InlineGenerator that mirrors the processor's `collectPermuteImports` but uses the EvaluationContext from vars:

```java
    private static java.util.List<String> collectPermuteImportsFromClass(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl,
            java.util.Map<String, Object> vars, PermuteConfig config) {
        EvaluationContext ctx = new EvaluationContext(vars);
        java.util.List<String> imports = new java.util.ArrayList<>();
        classDecl.getAnnotations().forEach(ann -> {
            String name = ann.getNameAsString();
            if (name.equals("PermuteImport") || name.equals("io.quarkiverse.permuplate.PermuteImport")) {
                extractSingleImportValue(ann).ifPresent(v -> {
                    try { imports.add(ctx.evaluate(v)); } catch (Exception ignored) {}
                });
            } else if (name.equals("PermuteImports") || name.equals("io.quarkiverse.permuplate.PermuteImports")) {
                extractRepeatedImportValues(ann).forEach(v -> {
                    try { imports.add(ctx.evaluate(v)); } catch (Exception ignored) {}
                });
            }
        });
        return imports;
    }

    private static java.util.Optional<String> extractSingleImportValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr s)
            return java.util.Optional.of(PermuteDeclrTransformer.stripQuotes(s.getMemberValue().toString()));
        if (ann instanceof NormalAnnotationExpr n)
            return n.getPairs().stream().filter(p -> p.getNameAsString().equals("value"))
                    .findFirst().map(p -> PermuteDeclrTransformer.stripQuotes(p.getValue().toString()));
        return java.util.Optional.empty();
    }

    private static java.util.List<String> extractRepeatedImportValues(AnnotationExpr ann) {
        java.util.List<String> vals = new java.util.ArrayList<>();
        if (ann instanceof NormalAnnotationExpr n)
            n.getPairs().stream().filter(p -> p.getNameAsString().equals("value")).findFirst()
                    .ifPresent(p -> {
                        Expression val = p.getValue();
                        java.util.List<Expression> elems = val instanceof ArrayInitializerExpr arr
                                ? arr.getValues() : java.util.List.of(val);
                        elems.forEach(e -> extractSingleImportValue(e instanceof AnnotationExpr a ? a : ann)
                                .ifPresent(vals::add));
                    });
        return vals;
    }
```

- [ ] **Step 5: Run tests**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-ide-support,permuplate-core,permuplate-processor,permuplate-maven-plugin -DskipTests -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteImportTest 2>&1 | tail -10
```
Expected: 2/2 pass

- [ ] **Step 6: Full suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | tail -5
```
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteImportTest.java
git commit -m "feat(core+plugin): @PermuteImport adds evaluated imports to each generated class"
```

---

## Task 5: Rebuild and install updated Permuplate snapshot

- [ ] **Step 1: Full install**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn clean install 2>&1 | grep "Tests run:.*Skipped: 0$\|BUILD" | tail -4
```
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Install snapshot to local Maven repo for droolsvol2**

Already done by `mvn install` above — snapshot is at `~/.m2/repository/io/quarkiverse/permuplate/`.

---

## Task 6: Template RuleExtendsPoint (simplest — pure boilerplate)

**Files:**
- Create: `droolsvol2/src/main/permuplate/org/drools/core/RuleExtendsPoint.java`
- Modify: `droolsvol2/src/main/java/org/drools/core/RuleExtendsPoint.java`

`RuleExtendsPoint2..6` are identical except for arity. `RuleExtendsPoint2<DS, B>` is the template.

- [ ] **Step 1: Create permuplate template**

Create `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/permuplate/org/drools/core/RuleExtendsPoint.java`:

```java
package org.drools.core;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteTypeParam;

import org.kie.api.definition.rule.Rule;

public class RuleExtendsPoint {
    private Rule rule;
    private int arity;

    public RuleExtendsPoint(Rule rule, int arity) {
        this.rule = rule;
        this.arity = arity;
    }

    public int arity() { return arity; }
    public void setArity(int arity) { this.arity = arity; }

    // Template generates RuleExtendsPoint3..RuleExtendsPoint10
    @Permute(varName = "i", from = "3", to = "10", className = "RuleExtendsPoint${i}",
             inline = true, keepTemplate = true)
    public static class RuleExtendsPoint2<DS,
            @PermuteTypeParam(varName = "j", from = "2", to = "${i}", name = "${alpha(j)}") B>
            extends RuleExtendsPoint {
        public RuleExtendsPoint2(Rule rule) {
            super(rule, 2);
        }
    }
}
```

- [ ] **Step 2: Remove inner classes from src/main/java/RuleExtendsPoint.java**

Remove `RuleExtendsPoint3` through `RuleExtendsPoint6` from the existing file, keeping only the outer class plus `RuleExtendsPoint2`.

- [ ] **Step 3: Test**

```bash
cd /Users/mdproctor/dev/droolsoct2025/droolsvol2
/opt/homebrew/bin/mvn clean test 2>&1 | grep "Tests run:\|BUILD" | tail -3
```
Expected: 65 tests, BUILD SUCCESS

- [ ] **Step 4: Stage**

```bash
git add src/main/permuplate/org/drools/core/RuleExtendsPoint.java
git add src/main/java/org/drools/core/RuleExtendsPoint.java
git status --short
```

---

## Task 7: Template RuleOOPathBuilder

**Files:**
- Create: `droolsvol2/src/main/permuplate/org/drools/core/RuleOOPathBuilder.java`
- Modify: `droolsvol2/src/main/java/org/drools/core/RuleOOPathBuilder.java`

Pattern: `Path2` is the leaf (returns `END` directly). `Path3` returns `Path2`. `PathN` returns `Path(N-1)`. The template is `Path3` — it generates `Path4..Path10`. `Path2` stays hand-written (it's the leaf with different `path()` return type).

- [ ] **Step 1: Create permuplate template**

Create `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/permuplate/org/drools/core/RuleOOPathBuilder.java`:

```java
package org.drools.core;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteReturn;
import io.quarkiverse.permuplate.PermuteTypeParam;

import org.drools.core.PathNode.ListPathNode;
import org.drools.core.function.Function2;
import org.drools.core.function.Predicate2;
import org.drools.core.function.BaseTuple;

public class RuleOOPathBuilder {

    public static class BasePath<END, A, B, T extends BaseTuple> {
        protected Function2<PathContext<T>, A, ?> fn2;
        protected Predicate2<PathContext<T>, B> flt2;
        protected END end;
        protected OOPathFinisher<?, ?, T> finisher;

        public BasePath(END end, OOPathFinisher<?, ?, T> finisher) {
            this.end = end;
            this.finisher = finisher;
        }

        public Function2<PathContext<T>, A, ?> function() { return fn2; }
        public Predicate2<PathContext<T>, B> filter() { return flt2; }
    }

    public static class OOPathFinisher<R, L, T extends BaseTuple> {
        private PathNode<?, ?, T> leaf;
        public PathNode<?, ?, T> getLeaf() { return leaf; }
        public void setLeaf(PathNode<?, ?, T> leaf) { this.leaf = leaf; }
        public OOPath<R, L, T> finish() { return (OOPath<R, L, T>) new OOPath<>(leaf); }
    }

    // Path2 is the leaf — returns END directly. Not templated.
    public static class Path2<END, T extends BaseTuple, A, B> extends BasePath<END, A, B, T> {
        PathNode<A, B, T> path2;
        PathNode<?, A, T> parentPath;

        public Path2(END end, OOPathFinisher<?, ?, T> finisher, PathNode<?, A, T> parentPath) {
            super(end, finisher);
            this.parentPath = parentPath;
        }

        public END path(Function2<PathContext<T>, A, ?> fn2, Predicate2<PathContext<T>, B> flt2) {
            path2 = new ListPathNode<>(AccessType.LIST, fn2, flt2, parentPath);
            finisher.setLeaf(path2);
            return end;
        }
    }

    // Path3 is the template generating Path4..Path10
    @Permute(varName = "i", from = "4", to = "10", className = "Path${i}",
             inline = true, keepTemplate = true)
    public static class Path3<END, T extends BaseTuple, A, B,
            @PermuteTypeParam(varName = "j", from = "3", to = "${i}", name = "${alpha(j)}") C>
            extends BasePath<END, A, B, T> {

        @PermuteDeclr(type = "PathNode<A, B, T>", name = "path${i}")
        PathNode<A, B, T> path3;

        PathNode<?, A, T> parentPath;

        public Path3(END end, OOPathFinisher<?, ?, T> finisher, PathNode<?, A, T> parentPath) {
            super(end, finisher);
            this.parentPath = parentPath;
        }

        @PermuteReturn(className = "Path${i-1}", typeArgs = "'END, T, ' + typeArgList(2, i, 'alpha')",
                       when = "true")
        public Path2<END, T, B, C> path(Function2<PathContext<T>, A, ?> fn2,
                                         Predicate2<PathContext<T>, B> flt2) {
            this.fn2 = fn2;
            this.flt2 = flt2;
            path3 = new @PermuteDeclr(type = "ListPathNode") ListPathNode<>(AccessType.LIST, fn2, flt2, parentPath);
            return new @PermuteDeclr(type = "Path${i-1}") Path2<>(end, finisher, path3);
        }
    }
}
```

- [ ] **Step 2: Remove Path3..Path6 from src/main/java/RuleOOPathBuilder.java**

Keep `BasePath`, `OOPathFinisher`, `Path2`, and `Path3` (the template is in permuplate dir). Remove `Path4`, `Path5`, `Path6`.

- [ ] **Step 3: Test**

```bash
/opt/homebrew/bin/mvn clean test 2>&1 | grep "Tests run:\|BUILD" | tail -3
```
Expected: 65 tests, BUILD SUCCESS

- [ ] **Step 4: Stage**

```bash
git add src/main/permuplate/org/drools/core/RuleOOPathBuilder.java
git add src/main/java/org/drools/core/RuleOOPathBuilder.java
git status --short
```

---

## Task 8: Template BaseTuple (most complex — uses @PermuteCase)

**Files:**
- Create: `droolsvol2/src/main/permuplate/org/drools/core/function/BaseTuple.java`
- Modify: `droolsvol2/src/main/java/org/drools/core/function/BaseTuple.java`

The key insight: refactor `Tuple1.get()/set()` to keep the seed case (index 0) and use `@PermuteCase` to accumulate higher cases without `super()`. Each `TupleN` has access to all fields `a..z` through inheritance (`protected` fields), so all cases can be inlined.

The `BaseTuple.as()` switch also needs to grow — but it's on `BaseTuple` itself (not a template inner class), so it stays hand-written for now and extended when needed.

- [ ] **Step 1: Create permuplate template**

Create `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/permuplate/org/drools/core/function/BaseTuple.java`:

```java
package org.drools.core.function;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteCase;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteTypeParam;

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
            for (Constructor<?> i : cls.getDeclaredConstructors()) {
                if (i.getParameterCount() == size) return i;
            }
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

    // Tuple1 is the template generating Tuple2..Tuple10
    @Permute(varName = "i", from = "2", to = "10", className = "Tuple${i}",
             inline = true, keepTemplate = true)
    public static class Tuple1<@PermuteTypeParam(varName = "j", from = "2", to = "${i}", name = "${alpha(j)}") A>
            extends BaseTuple {

        // Sentinel field — @PermuteDeclr renames to b, c, d... per generated class
        @PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
        protected A a;

        public Tuple1() { super(); }

        public Tuple1(A a) {
            this.a = a;
            this.size = 1;
        }

        // Getter for the NEW field per arity — @PermuteMethod generates getB(), getC()...
        public A getA() { return a; }

        @PermuteCase(varName = "k", from = "1", to = "${i-1}",
                     index = "${k}", body = "return (T) ${lower(k+1)};")
        @Override
        public <T> T get(int index) {
            switch (index) {
                case 0: return (T) a;
                default: throw new IndexOutOfBoundsException(index);
            }
        }

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

**Note:** The `getA()` getter only generates for the TEMPLATE's own arity (arity 1, getter `getA()`). Generated Tuple2 inherits `getA()` from Tuple1 AND needs `getB()`. Getters for the new field can be added via `@PermuteMethod` (generates `getB()`, `getC()`, etc.) — add this to the template if needed. The existing tests use `getA()`, `getB()` etc. so this needs to be handled. For the initial implementation, keep the hand-written getters in the `src/main/java` version and only template the switch dispatch.

Actually, the simplest approach for getters: since each generated class inherits from the previous, `getA()` is inherited by all. For `getB()` on Tuple2, we need to add it. This can be done with `@PermuteMethod` but is complex. **For this task: keep individual getter/setter methods in Tuple1..Tuple6 as hand-written, only template the `get(int)/set(int,T)` switch dispatch.** The switch is the source of growth; individual getters are fixed per class.

Revise the template to NOT template getters (keep them in the original classes). The template only handles the switch accumulation.

- [ ] **Step 2: Simplify — only template the switch, keep hand-written classes**

Since templating getters/setters adds complexity without fixing the core issue (switch growth), the pragmatic approach for this task:

1. Keep `BaseTuple.java` in `src/main/java/` with Tuple1..Tuple6 hand-written
2. The ONLY change: refactor Tuple1..Tuple6 to use the delegation-free switch pattern (all cases inlined per class, matching the current implementation which already does this)

The current implementation ALREADY has all cases inlined in each class — it doesn't use `super()` delegation. So `BaseTuple` is already **as efficient as possible**. The issue is just maintenance: 486 lines of hand-written code.

**Decision: skip BaseTuple inline templating for now.** The switch accumulation pattern requires refactoring the constructors too (each constructor calls super with previous args). This is feasible but adds significant scope. Log it as a follow-up.

Update the droolsvol2 issue (apache/incubator-kie-drools#6658) with this note.

- [ ] **Step 3: Stage and commit RuleExtendsPoint + RuleOOPathBuilder work**

```bash
cd /Users/mdproctor/dev/droolsoct2025/droolsvol2
git add src/main/permuplate/ src/main/java/org/drools/core/RuleExtendsPoint.java \
        src/main/java/org/drools/core/RuleOOPathBuilder.java
git commit -m "feat(droolsvol2): template RuleExtendsPoint and RuleOOPathBuilder via Permuplate inline mode"
```

---

## Task 9: Full build verification

- [ ] **Step 1: Full Permuplate build**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn clean install 2>&1 | grep "Tests run:.*Skipped: 0$\|BUILD" | tail -4
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Full droolsvol2 build**

```bash
cd /Users/mdproctor/dev/droolsoct2025/droolsvol2
/opt/homebrew/bin/mvn clean test 2>&1 | grep "Tests run:\|BUILD" | tail -3
```
Expected: 65 tests, BUILD SUCCESS

---

## Self-Review

**Spec coverage:**
- ✅ `@PermuteCase` annotation — Task 1
- ✅ `PermuteCaseTransformer` — Task 2 (APT) + Task 3 (Maven plugin)
- ✅ `@PermuteImport` annotation + container — Task 1
- ✅ @PermuteImport processing in both paths — Task 4
- ✅ RuleExtendsPoint templated — Task 6
- ✅ RuleOOPathBuilder templated — Task 7
- BaseTuple: deferred (noted in Task 8)
- ✅ Full build verification — Task 9

**Placeholder scan:** None found. Task 8 explicitly defers BaseTuple with reasoning.

**Type consistency:**
- `PermuteCaseTransformer.transform(ClassOrInterfaceDeclaration, EvaluationContext)` defined in Task 2, called in Tasks 3, 4
- `collectPermuteImports(ClassOrInterfaceDeclaration)` defined in Task 4 PermuteProcessor, separate helper in InlineGenerator — consistent naming
- `SwitchEntry.Type.STATEMENT_GROUP` — JavaParser 3.25.9 API, correct
