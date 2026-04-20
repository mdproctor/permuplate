# @PermuteCase Arrow-Switch Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `PermuteCaseTransformer` to detect arrow-switch form and generate arrow-style entries (`case N -> body`) instead of colon-style (`case N: body; break;`), and to handle `SwitchExpr` (switch expressions) in addition to `SwitchStmt`. Closes GitHub issue #76, part of epic #75.

**Architecture:** `PermuteCaseTransformer` gains three new private helpers: `findSwitchEntries()` (handles both SwitchStmt and SwitchExpr), `isArrowSwitch()` (detects form from existing entries), and `buildArrowEntry()` (constructs a JAVA_21 arm via synthetic parse). The `findDefaultCaseIndex` and main `transform` loop are updated to use the new entry-list-based API. All existing colon-switch templates are unaffected.

**Tech Stack:** Java 17, JavaParser 3.28.0 (JAVA_21 parser level, already global), Google compile-testing.

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Modify | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java` | Add arrow-switch detection and generation |
| Create | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseArrowTest.java` | 8 TDD tests covering all branches |
| Create | `permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/ArrowDispatch2.java` | APT example |
| Modify | `README.md` | Add arrow-switch note to @PermuteCase section |
| Modify | `CLAUDE.md` | Key decision entry |

---

### Task 1: Write all failing tests (TDD first)

**Files:**
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseArrowTest.java`

- [ ] **Step 1: Create the test file with 8 tests**

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
 * TDD tests for @PermuteCase arrow-switch support.
 *
 * Tests: unit (arrow detection, block body, auto-semicolon, string literal),
 * integration (SwitchExpr, SwitchStmt), correctness (empty range, colon regression),
 * happy path (E2E generation).
 */
public class PermuteCaseArrowTest {

    // -------------------------------------------------------------------------
    // Unit: SwitchStmt arrow form
    // -------------------------------------------------------------------------

    @Test
    public void testArrowSwitchStatementInsertsArrowEntries() {
        // Unit: SwitchStmt with arrow arms → generated arms use arrow form.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Stmt2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="Stmt${i}")
                        public class Stmt2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="doWork(${k});")
                            public void dispatch(int x) {
                                switch (x) {
                                    case 0 -> doWork(0);
                                    default -> {}
                                }
                            }
                            private void doWork(int n) {}
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Stmt3").orElseThrow());

        // Arrow form generated
        assertThat(src).contains("case 1 ->");
        assertThat(src).contains("case 2 ->");
        // Colon form NOT present for inserted arms
        assertThat(src).doesNotContain("case 1:");
        assertThat(src).doesNotContain("case 2:");
        // Seed arm preserved
        assertThat(src).contains("case 0 ->");
        assertThat(src).doesNotContain("@PermuteCase");
    }

    // -------------------------------------------------------------------------
    // Integration: SwitchExpr (return switch)
    // -------------------------------------------------------------------------

    @Test
    public void testArrowSwitchExpressionInsertsArrowEntries() {
        // Integration: method uses "return switch (x) { ... }" (SwitchExpr).
        // @PermuteCase must find and modify the SwitchExpr, not just SwitchStmt.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Expr2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="Expr${i}")
                        public class Expr2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="yield ${k};")
                            public int select(int x) {
                                return switch (x) {
                                    case 0 -> 0;
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
                .generatedSourceFile("io.permuplate.example.Expr3").orElseThrow());

        assertThat(src).contains("case 1 ->");
        assertThat(src).contains("case 2 ->");
        assertThat(src).contains("yield 1");
        assertThat(src).contains("yield 2");
        assertThat(src).doesNotContain("@PermuteCase");
    }

    // -------------------------------------------------------------------------
    // Correctness: empty range on arrow-switch
    // -------------------------------------------------------------------------

    @Test
    public void testEmptyRangeArrowSwitchInsertsNoArms() {
        // Correctness: from > to after evaluation — no arms inserted, annotation removed.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.EmptyArrow2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="2", to="2", className="EmptyArrow${i}")
                        public class EmptyArrow2 {
                            @PermuteCase(varName="k", from="3", to="${i}",
                                         index="${k}", body="yield ${k};")
                            public int get(int x) {
                                return switch (x) {
                                    case 0 -> 0;
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
                .generatedSourceFile("io.permuplate.example.EmptyArrow2").orElseThrow());

        assertThat(src).contains("case 0 ->");
        assertThat(src).doesNotContain("case 3");
        assertThat(src).doesNotContain("@PermuteCase");
    }

    // -------------------------------------------------------------------------
    // Unit: auto-semicolon for expression bodies
    // -------------------------------------------------------------------------

    @Test
    public void testArrowBodyWithoutSemicolonAutoAppended() {
        // Unit: body "yield ${k}" (no semicolon) must parse successfully.
        // Auto-append ';' before wrapping in { }.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.AutoSemi2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="AutoSemi${i}")
                        public class AutoSemi2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="yield ${k}")
                            public int compute(int x) {
                                return switch (x) {
                                    case 0 -> 0;
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
                .generatedSourceFile("io.permuplate.example.AutoSemi3").orElseThrow());
        assertThat(src).contains("yield 1");
        assertThat(src).contains("yield 2");
    }

    // -------------------------------------------------------------------------
    // Unit: block body on arrow-switch
    // -------------------------------------------------------------------------

    @Test
    public void testArrowBodyAsBlock() {
        // Unit: body "{ ... yield ...; }" — block form passed through verbatim.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.BlockArm2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="BlockArm${i}")
                        public class BlockArm2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}",
                                         body="{ System.out.println(${k}); yield ${k}; }")
                            public int handle(int x) {
                                return switch (x) {
                                    case 0 -> { System.out.println(0); yield 0; }
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
                .generatedSourceFile("io.permuplate.example.BlockArm3").orElseThrow());
        assertThat(src).contains("System.out.println(1)");
        assertThat(src).contains("System.out.println(2)");
        assertThat(src).contains("yield 1");
        assertThat(src).contains("yield 2");
    }

    // -------------------------------------------------------------------------
    // Unit: string literal in arrow body
    // -------------------------------------------------------------------------

    @Test
    public void testArrowBodyWithStringLiteral() {
        // Unit: body containing a Java string literal must not produce ParseProblemException.
        // Uses asStringLiteralExpr().asString() extraction (same fix as colon-switch).
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.StringArrow2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="StringArrow${i}")
                        public class StringArrow2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="yield \\"item-${k}\\";")
                            public String label(int x) {
                                return switch (x) {
                                    case 0 -> "item-0";
                                    default -> "unknown";
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.StringArrow3").orElseThrow());
        assertThat(src).contains("\"item-1\"");
        assertThat(src).contains("\"item-2\"");
    }

    // -------------------------------------------------------------------------
    // Correctness: colon-switch regression guard
    // -------------------------------------------------------------------------

    @Test
    public void testColonSwitchUnchanged() {
        // Correctness (regression): existing colon-switch templates must still produce
        // STATEMENT_GROUP entries, not arrow entries. Detection must not fire.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.ColonReg2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="ColonReg${i}")
                        public class ColonReg2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="return ${k};")
                            public int dispatch(int x) {
                                switch (x) {
                                    case 0: return 0;
                                    default: throw new IllegalArgumentException();
                                }
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.ColonReg3").orElseThrow());

        // Colon form in output (not arrow)
        assertThat(src).contains("case 1:");
        assertThat(src).contains("case 2:");
        assertThat(src).doesNotContain("case 1 ->");
        assertThat(src).doesNotContain("case 2 ->");
    }

    // -------------------------------------------------------------------------
    // Happy path / E2E: multiple arities with arrow-switch expression
    // -------------------------------------------------------------------------

    @Test
    public void testArrowSwitchExpressionEndToEnd() {
        // Happy path E2E: template from=3, to=5 generates 3 classes.
        // Each has the correct number of arrow arms injected.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.ArrowE2E2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="5", className="ArrowE2E${i}")
                        public class ArrowE2E2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="yield ${k} * 10;")
                            public int compute(int x) {
                                return switch (x) {
                                    case 0 -> 0;
                                    default -> -1;
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // ArrowE2E3: arms for k=1,2
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.ArrowE2E3").orElseThrow());
        assertThat(src3).contains("case 1 ->");
        assertThat(src3).contains("case 2 ->");
        assertThat(src3).doesNotContain("case 3 ->");

        // ArrowE2E4: arms for k=1,2,3
        String src4 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.ArrowE2E4").orElseThrow());
        assertThat(src4).contains("case 1 ->");
        assertThat(src4).contains("case 2 ->");
        assertThat(src4).contains("case 3 ->");

        // ArrowE2E5: arms for k=1,2,3,4
        String src5 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.ArrowE2E5").orElseThrow());
        assertThat(src5).contains("case 4 ->");
        assertThat(src5).contains("yield 40");
    }
}
```

- [ ] **Step 2: Run all tests to confirm they fail**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-core,permuplate-processor -am -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteCaseArrowTest -q 2>&1 | tail -10
```

Expected: most tests fail (generated source still uses colon form or SwitchExpr not found).

---

### Task 2: Implement arrow-switch support in `PermuteCaseTransformer`

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java`

- [ ] **Step 1: Add SwitchExpr import**

At the top of `PermuteCaseTransformer.java`, add alongside existing imports:

```java
import com.github.javaparser.ast.expr.SwitchExpr;
```

- [ ] **Step 2: Replace the full transformer with the updated version**

Replace the entire content of `PermuteCaseTransformer.java` with:

```java
package io.quarkiverse.permuplate.core;

import java.util.Optional;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;

/**
 * Handles {@code @PermuteCase} on method declarations.
 *
 * <p>For each generated class at arity {@code i}, inserts new {@link SwitchEntry} nodes
 * into the method's switch statement or expression for each value {@code k} in
 * [{@code from}, {@code to}]. The seed cases and {@code default} case are preserved.
 *
 * <p>Supports both colon-switch ({@code case N: body; break;}) and arrow-switch
 * ({@code case N -> body}). The form is detected from the existing entries in the switch:
 * if any entry uses arrow form ({@code EXPRESSION}, {@code BLOCK}, or
 * {@code THROWS_STATEMENT} type), new arms are generated in arrow form; otherwise colon
 * form is used (existing behaviour).
 *
 * <p>Both {@link SwitchStmt} (standalone switch) and {@link SwitchExpr}
 * ({@code return switch (x) \{...\}}) are supported.
 */
public class PermuteCaseTransformer {

    private static final String ANNOTATION_SIMPLE = "PermuteCase";
    private static final String ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteCase";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<AnnotationExpr> annOpt = method.getAnnotations().stream()
                    .filter(PermuteCaseTransformer::isPermuteCase)
                    .findFirst();
            if (annOpt.isEmpty())
                return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr normal))
                return;

            String varName = null, from = null, to = null, indexExpr = null, bodyExpr = null;
            for (MemberValuePair pair : normal.getPairs()) {
                String val = pair.getValue().asStringLiteralExpr().asString();
                switch (pair.getNameAsString()) {
                    case "varName" -> varName    = val;
                    case "from"    -> from       = val;
                    case "to"      -> to         = val;
                    case "index"   -> indexExpr  = val;
                    case "body"    -> bodyExpr   = val;
                }
            }
            if (varName == null || from == null || to == null
                    || indexExpr == null || bodyExpr == null)
                return;

            NodeList<SwitchEntry> entries = findSwitchEntries(method);
            if (entries == null)
                return;

            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(from);
                toVal   = ctx.evaluateInt(to);
            } catch (Exception ignored) {
                return;
            }

            // Remove annotation before potentially returning (empty range is still valid)
            method.getAnnotations().remove(ann);

            if (fromVal > toVal)
                return;

            boolean arrowForm = isArrowSwitch(entries);
            int defaultIdx = findDefaultCaseIndex(entries);

            for (int k = fromVal; k <= toVal; k++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, k);

                String caseLabelStr = innerCtx.evaluate(indexExpr);
                String caseBodyStr  = innerCtx.evaluate(bodyExpr);

                int caseLabel;
                try {
                    caseLabel = Integer.parseInt(caseLabelStr.trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }

                SwitchEntry entry = arrowForm
                        ? buildArrowEntry(caseLabel, caseBodyStr)
                        : buildColonEntry(caseLabel, caseBodyStr);
                entries.add(defaultIdx, entry);
                defaultIdx++;
            }
        });
    }

    /**
     * Finds the entries of the first switch (statement or expression) in the method body.
     * Returns {@code null} if no switch is found.
     */
    private static NodeList<SwitchEntry> findSwitchEntries(MethodDeclaration method) {
        Optional<SwitchStmt> stmtOpt = method.getBody()
                .flatMap(body -> body.findFirst(SwitchStmt.class));
        if (stmtOpt.isPresent())
            return stmtOpt.get().getEntries();

        Optional<SwitchExpr> exprOpt = method.getBody()
                .flatMap(body -> body.findFirst(SwitchExpr.class));
        if (exprOpt.isPresent())
            return exprOpt.get().getEntries();

        return null;
    }

    /**
     * Returns {@code true} if the existing switch entries use arrow form
     * ({@code EXPRESSION}, {@code BLOCK}, or {@code THROWS_STATEMENT}).
     */
    private static boolean isArrowSwitch(NodeList<SwitchEntry> entries) {
        for (SwitchEntry e : entries) {
            SwitchEntry.Type t = e.getType();
            if (t == SwitchEntry.Type.EXPRESSION
                    || t == SwitchEntry.Type.BLOCK
                    || t == SwitchEntry.Type.THROWS_STATEMENT) {
                return true;
            }
        }
        return false;
    }

    private static int findDefaultCaseIndex(NodeList<SwitchEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getLabels().isEmpty())
                return i;
        }
        return entries.size();
    }

    /**
     * Builds an arrow-switch arm: {@code case <label> -> { <body> }}.
     * Auto-appends ';' to non-block bodies that lack it.
     * Parsed via synthetic switch with JAVA_21 language level (global since PermuteSwitchArm).
     */
    private static SwitchEntry buildArrowEntry(int label, String bodyStr) {
        String normalized = bodyStr.trim();
        if (!normalized.startsWith("{") && !normalized.endsWith(";"))
            normalized = normalized + ";";
        String blockBody = normalized.startsWith("{") ? normalized : "{ " + normalized + " }";
        String synthetic = "switch (__x__) { case " + label + " -> " + blockBody
                + " default -> { throw new UnsupportedOperationException(); } }";
        SwitchStmt temp = StaticJavaParser.parseStatement(synthetic).asSwitchStmt();
        return temp.getEntries().get(0).clone();
    }

    /**
     * Builds a colon-switch entry: {@code case <label>: <statements>}.
     * This is the original behaviour, preserved for all colon-switch templates.
     */
    private static SwitchEntry buildColonEntry(int label, String bodyStr) {
        BlockStmt block = StaticJavaParser.parseBlock("{" + bodyStr + "}");
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

- [ ] **Step 3: Build and run all 8 tests**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-core -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteCaseArrowTest -q 2>&1 | tail -5
```

Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 4: Run the existing colon-switch tests (regression guard)**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteCaseTest -q 2>&1 | tail -5
```

Expected: all existing tests still pass (colon form unchanged).

- [ ] **Step 5: Run the full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

Expected: all tests pass.

- [ ] **Step 6: Stage and commit**

```bash
git add \
    permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseArrowTest.java
git commit -m "feat: @PermuteCase supports arrow-switch form and SwitchExpr (closes #76)"
```

---

### Task 3: APT example

**Files:**
- Create: `permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/ArrowDispatch2.java`

- [ ] **Step 1: Create the example**

```java
package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteCase;

/**
 * Demonstrates {@code @PermuteCase} with Java 21+ arrow-switch expressions.
 *
 * <p>Template {@code ArrowDispatch2} generates {@code ArrowDispatch3..5}.
 * Each generated class adds integer case arms in arrow form to the switch expression.
 * The seed arm ({@code case 0 -> 0}) is preserved unchanged.
 *
 * <p>Note: generated source uses Java 21 switch expression syntax.
 * The consuming project must compile with {@code --release 21} or later.
 */
@Permute(varName = "i", from = "3", to = "5", className = "ArrowDispatch${i}")
public class ArrowDispatch2 {

    /**
     * Returns {@code k * 10} for case {@code k}, or 0 for case 0, or -1 for all other inputs.
     * Generates arities 3–5 with one more case arm each.
     */
    @PermuteCase(varName = "k", from = "1", to = "${i-1}",
                 index = "${k}", body = "yield ${k} * 10;")
    public int dispatch(int n) {
        return switch (n) {
            case 0 -> 0;
            default -> -1;
        };
    }
}
```

- [ ] **Step 2: Build apt-examples**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-apt-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS. Generated ArrowDispatch3.java, ArrowDispatch4.java, ArrowDispatch5.java in target/.

- [ ] **Step 3: Verify generated arms are arrow form**

```bash
find /Users/mdproctor/claude/permuplate/permuplate-apt-examples/target \
    -name "ArrowDispatch3.java" 2>/dev/null | xargs grep "case 1 ->" 2>/dev/null | head -3
```

Expected: `case 1 ->` present (arrow form).

- [ ] **Step 4: Stage and commit**

```bash
git add permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/ArrowDispatch2.java
git commit -m "example: ArrowDispatch demonstrates @PermuteCase with Java 21 arrow-switch"
```

---

### Task 4: Update README and CLAUDE.md

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add arrow-switch note to README `@PermuteCase` section**

Find `### @PermuteCase` in README.md. After the existing parameter table and example, add:

```markdown
**Arrow-switch support:** When the template switch uses arrow form (`case N -> body`), `@PermuteCase` detects this automatically and generates arrow-style arms. Both switch statements and switch expressions (`return switch (x) { ... }`) are supported:

```java
@PermuteCase(varName="k", from="1", to="${i-1}", index="${k}", body="yield ${k};")
public int select(int x) {
    return switch (x) {
        case 0 -> 0;   // seed arm — preserved
        default -> -1;
    };
}
// Generated Expr3: case 1 -> { yield 1; } case 2 -> { yield 2; }
```

Use `yield` in the body for switch expressions. Arrow bodies without a trailing `;` have one appended automatically.
```

- [ ] **Step 2: Add CLAUDE.md key decision entry**

In the key decisions table, add:

```
| `@PermuteCase` arrow-switch detection | `PermuteCaseTransformer` checks existing `SwitchEntry.Type`: if any entry is `EXPRESSION`, `BLOCK`, or `THROWS_STATEMENT`, arrow form is used for new arms; otherwise colon form (existing behaviour). Handles both `SwitchStmt` and `SwitchExpr` via `findSwitchEntries()`. Arrow arm built via synthetic switch parse (same JAVA_21 level as `@PermuteSwitchArm`). Auto-appends `;` to non-block bodies. |
```

- [ ] **Step 3: Full clean build**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Stage and commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: @PermuteCase arrow-switch support documented in README and CLAUDE.md"
```
