# Better JEXL Error Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace silent exception swallowing in the Permuplate transformer pipeline with actionable compiler errors pointing at the specific annotation attribute that failed.

**Architecture:** Two improvements: (1) fix the `@PermuteStatements` body extraction to use `asStringLiteralExpr().asString()` (same root cause as the `@PermuteCase` bug fixed on 2026-04-18); (2) audit `catch (Exception ignored)` blocks in `PermuteProcessor.java` and replace JEXL-related silent failures with `messager.printMessage(ERROR, ...)` pointing to the failing element.

**Tech Stack:** Java 17, JavaParser 3.28.0, Apache Commons JEXL3, APT Messager API.

---

## Background

`PermuteStatementsTransformer.buildInsertList()` extracts the `body` attribute via `stripQuotes(pair.getValue().toString())`, which returns the JavaParser-serialised form with escape sequences (e.g. `return \"hello\";`). `StaticJavaParser.parseBlock()` rejects this — the exception is silently swallowed and the insertion is skipped. This is the same root cause as the `@PermuteCase` bug fixed on 2026-04-18. The fix is the same: use `asStringLiteralExpr().asString()`.

`PermuteProcessor.java` has 17 `catch (Exception ignored)` sites. Some are legitimate fallbacks (e.g., trying a second parse strategy). Others are JEXL evaluation failures that should surface as compiler errors.

---

## File Structure

| Action | Path | What changes |
|---|---|---|
| Modify | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java` | Fix body/position extraction to use `asString()` |
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | Replace key `ignored` catches with error reporting |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteStatementsTest.java` | Add regression test for string-literal body |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java` | Add tests for previously-silent JEXL failures |

---

### Task 1: Fix `PermuteStatementsTransformer` body extraction

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java`
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteStatementsTest.java`

- [ ] **Step 1: Write the failing test**

Add to `PermuteStatementsTest.java`:

```java
@Test
public void testBodyWithStringLiteral() {
    // Regression: body with a Java string literal was silently dropped
    // because stripQuotes(toString()) left raw escape sequences.
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.Greeter1",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteStatements;
                    @Permute(varName="i", from="2", to="2", className="Greeter${i}")
                    public class Greeter1 {
                        private String greeting;
                        @PermuteStatements(position="first", body="this.greeting = \\"hello\\";")
                        public void init() {
                        }
                    }
                    """);
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);
    assertThat(compilation).succeeded();
    String src = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.Greeter2").orElseThrow());
    assertThat(src).contains("this.greeting = \"hello\"");
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteStatementsTest#testBodyWithStringLiteral -q 2>&1 | tail -10
```

Expected: FAIL — the `this.greeting = "hello"` line is absent from the generated source.

- [ ] **Step 3: Fix `buildInsertList` to use `asString()`**

In `PermuteStatementsTransformer.java`, replace `buildInsertList`:

```java
private static List<Statement> buildInsertList(NormalAnnotationExpr normal, EvaluationContext ctx) {
    String varName = null, from = null, to = null, body = null;
    for (MemberValuePair pair : normal.getPairs()) {
        String val = pair.getValue().asStringLiteralExpr().asString();
        switch (pair.getNameAsString()) {
            case "varName" -> varName = val;
            case "from"    -> from    = val;
            case "to"      -> to      = val;
            case "body"    -> body    = val;
        }
    }
    if (body == null)
        return null;

    final String finalBody    = body;
    final String finalVarName = varName;
    final String finalFrom    = from;
    final String finalTo      = to;

    boolean hasLoop = finalVarName != null && !finalVarName.isEmpty()
            && finalFrom != null && !finalFrom.isEmpty()
            && finalTo   != null && !finalTo.isEmpty();

    List<Statement> toInsert = new ArrayList<>();
    if (hasLoop) {
        int fromVal, toVal;
        try {
            fromVal = ctx.evaluateInt(finalFrom);
            toVal   = ctx.evaluateInt(finalTo);
        } catch (Exception ignored) {
            return null;
        }
        for (int k = fromVal; k <= toVal; k++) {
            EvaluationContext innerCtx = ctx.withVariable(finalVarName, k);
            String evaluated = innerCtx.evaluate(finalBody);
            parseStatements(evaluated).forEach(toInsert::add);
        }
    } else {
        String evaluated = ctx.evaluate(finalBody);
        parseStatements(evaluated).forEach(toInsert::add);
    }
    return toInsert;
}
```

Also fix `extractAttr` (used for `position`):

```java
private static String extractAttr(NormalAnnotationExpr normal, String attrName) {
    for (MemberValuePair pair : normal.getPairs()) {
        if (pair.getNameAsString().equals(attrName)) {
            return pair.getValue().asStringLiteralExpr().asString();
        }
    }
    return null;
}
```

- [ ] **Step 4: Run the new test to confirm it passes**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-core -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteStatementsTest -q 2>&1 | tail -5
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteStatementsTest.java
git commit -m "fix: @PermuteStatements body with string literals silently dropped — use asString() like @PermuteCase"
```

---

### Task 2: Audit and improve error reporting in `PermuteProcessor`

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java`

The target `ignored` sites that should produce errors (verified by reading the surrounding code):

**Site A — line ~391:** `evaluate(permute.className())` failure inside the name-uniqueness check. Currently ignored, which means a bad `className` expression is not flagged during validation. However, this same expression is already caught and reported at line ~424 during the actual generation pass. Leave this one as `ignored` — the later catch handles it.

**Site B — line ~539:** `generatedCu.addImport(ctx.evaluate(importStr))` for `@PermuteImport`. Currently ignored — a broken `@PermuteImport` expression silently adds no import. Should report an error.

**Site C — line ~977, ~989, ~994, ~995:** `PermuteParam`/`PermuteStatements` from/to evaluation. Currently ignored. Should report errors since a bad bound means the annotation is silently skipped.

**Site D — line ~1048:** Inner loop class-name evaluation in `@PermuteMethod`. Currently ignored. Should report an error.

**Sites E — lines ~1205, ~1220, ~1263, ~1284:** Various inference / extends expansion sites. These are optional inference steps — `ignored` is correct here; log as debug if needed but don't error.

The strategy: for B, C, D — replace `ignored` with an `error(...)` call using the element and annotation mirror available in scope.

- [ ] **Step 1: Add a test for a bad `@PermuteImport` expression**

In `DegenerateInputTest.java`, add:

```java
@Test
public void testBadPermuteImportExpressionIsError() {
    var compilation = compile(Callable2.class, "ImportFail2",
            """
            package %s;
            import %s;
            import io.quarkiverse.permuplate.PermuteImport;
            @Permute(varName = "i", from = "2", to = "2", className = "ImportFail${i}")
            @PermuteImport("${undefinedVar}.SomeClass")
            public class ImportFail2 {}
            """.formatted(PKG, PERMUTE_FQN));
    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("@PermuteImport");
}
```

- [ ] **Step 2: Run to confirm it currently PASSES (i.e., no error is reported today — bad state)**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=DegenerateInputTest#testBadPermuteImportExpressionIsError -q 2>&1 | tail -10
```

Expected: FAIL because the compilation SUCCEEDS today (no error reported) but the test expects failure.

- [ ] **Step 3: Read the `@PermuteImport` handling in PermuteProcessor to find the exact line**

```bash
grep -n "addImport\|PermuteImport\|importStr" \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    | head -20
```

Locate the `catch (Exception ignored)` around `addImport(ctx.evaluate(importStr))`.

- [ ] **Step 4: Replace the `@PermuteImport` silent catch with an error**

Find the block in `PermuteProcessor.java` that looks like:
```java
try {
    generatedCu.addImport(ctx.evaluate(importStr));
} catch (Exception ignored) {
}
```

Replace with (using the `element` and annotation mirror variables in scope at that point):
```java
try {
    generatedCu.addImport(ctx.evaluate(importStr));
} catch (Exception e) {
    error("@PermuteImport expression failed to evaluate: " + e.getMessage(), typeElement);
}
```

(`error(String, Element)` is an existing helper in PermuteProcessor — check its signature and use the appropriate overload.)

- [ ] **Step 5: Run the new test to confirm it now passes**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=DegenerateInputTest#testBadPermuteImportExpressionIsError -q 2>&1 | tail -5
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 6: Run the full test suite to confirm no regressions**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java
git commit -m "fix: report compiler error for bad @PermuteImport JEXL expression instead of silently skipping"
```

---

### Task 3: Full build and verify

- [ ] **Step 1: Full clean build**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.
