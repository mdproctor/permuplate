# Better JEXL Error Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace silent `catch (Exception ignored)` blocks in `PermuteProcessor` for `@PermuteStatements`/`@PermuteParam` from/to bounds and `@PermuteMethod` name templates with compiler errors that point at the failing expression and explain the JEXL failure.

**Architecture:** Add two private helper methods (`evaluateIntOrError`, `evaluateOrError`) to `PermuteProcessor`. Apply to the 2-3 highest-value silent catch sites. Intentional silent fallbacks (inference steps) are left unchanged. Closes GitHub issue #73, part of epic #71.

**Tech Stack:** Java 17, APT Messager API, Apache Commons JEXL3.

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | Add helpers; apply to target catch sites |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java` | Failing tests for bad expressions |

---

### Task 1: Failing tests first

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java`

- [ ] **Step 1: Write the @PermuteMethod bad name test**

Add to `DegenerateInputTest.java` (following the existing `PKG` / `compile(Class, String, String)` pattern):

```java
// -------------------------------------------------------------------------
// JEXL expression evaluation failures
// -------------------------------------------------------------------------

@Test
public void testBadPermuteMethodNameExpressionIsError() {
    // A broken JEXL expression in @PermuteMethod name= currently silently uses
    // the original method name. After the fix it must surface as a compiler error.
    var compilation = compile(Callable2.class, "BadMethodName2",
            """
            package %s;
            import %s;
            import io.quarkiverse.permuplate.PermuteMethod;
            @Permute(varName = "i", from = "1", to = "1", className = "BadMethodName${i}")
            public class BadMethodName2 {
                @PermuteMethod(varName = "j", from = "1", to = "2",
                               name = "${undefinedNameVar}Method${j}")
                public void templateMethod() {}
            }
            """.formatted(PKG, PERMUTE_FQN));
    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("PermuteMethod");
    assertThat(compilation).hadErrorContaining("name");
}
```

- [ ] **Step 2: Write the @PermuteStatements bad from test**

```java
@Test
public void testBadPermuteStatementsBoundExpressionIsError() {
    // A broken JEXL expression in @PermuteStatements from= currently silently
    // skips the annotation. After the fix it must surface as a compiler error.
    var compilation = compile(Callable2.class, "BadStmts2",
            """
            package %s;
            import %s;
            import io.quarkiverse.permuplate.PermuteStatements;
            @Permute(varName = "i", from = "2", to = "2", className = "BadStmts${i}")
            public class BadStmts2 {
                @PermuteStatements(varName = "k", from = "${undefinedBoundVar}",
                                   to = "${i}", position = "first",
                                   body = "System.out.println(${k});")
                public void init() {}
            }
            """.formatted(PKG, PERMUTE_FQN));
    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("PermuteStatements");
    assertThat(compilation).hadErrorContaining("from");
}
```

- [ ] **Step 3: Run to confirm both currently FAIL the assertion (compilation succeeds today)**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=DegenerateInputTest#testBadPermuteMethodNameExpressionIsError+testBadPermuteStatementsBoundExpressionIsError \
    -q 2>&1 | tail -10
```

Expected: 2 test FAILURES — compilation currently succeeds silently rather than failing.

---

### Task 2: Add helper methods to `PermuteProcessor`

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

- [ ] **Step 1: Find a good insertion point for the helpers**

```bash
grep -n "private.*error\|private.*getAnnAttr\|private.*evaluate" \
    /Users/mdproctor/claude/permuplate/permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    | head -10
```

Insert the two helper methods near the existing `error(...)` helper methods.

- [ ] **Step 2: Add the helpers**

Add immediately after the existing `error()` overloads:

```java
/**
 * Evaluates {@code expression} as an integer using {@code ctx} and returns the result.
 * On failure, reports a compiler error at {@code element} with the annotation name,
 * attribute name, failing expression, and JEXL error message. Returns {@code null}
 * on failure so the caller can skip processing.
 */
@javax.annotation.Nullable
private Integer evaluateIntOrError(EvaluationContext ctx, String expression,
        String annotationName, String attributeName, javax.lang.model.element.Element element) {
    try {
        return ctx.evaluateInt(expression);
    } catch (Exception e) {
        error("@" + annotationName + " '" + attributeName
                + "' failed to evaluate: \"" + expression + "\" — " + e.getMessage(),
                element);
        return null;
    }
}

/**
 * Evaluates {@code expression} as a String using {@code ctx} and returns the result.
 * On failure, reports a compiler error and returns {@code null}.
 */
@javax.annotation.Nullable
private String evaluateOrError(EvaluationContext ctx, String expression,
        String annotationName, String attributeName, javax.lang.model.element.Element element) {
    try {
        return ctx.evaluate(expression);
    } catch (Exception e) {
        error("@" + annotationName + " '" + attributeName
                + "' failed to evaluate: \"" + expression + "\" — " + e.getMessage(),
                element);
        return null;
    }
}
```

Note: `@javax.annotation.Nullable` — use the fully-qualified form or add `import javax.annotation.Nullable` if that import is available. If not available, just omit the annotation (the null return is documented in the Javadoc).

---

### Task 3: Apply helpers to `@PermuteMethod` name evaluation

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

- [ ] **Step 1: Find the @PermuteMethod name template evaluation site**

```bash
grep -n "nameTempl\|clone.setName\|evaluate(nameTempl)" \
    /Users/mdproctor/claude/permuplate/permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    | head -10
```

Expected: around line 1064-1068:
```java
if (nameTempl != null && !nameTempl.isEmpty()) {
    try {
        clone.setName(innerCtx.evaluate(nameTempl));
    } catch (Exception ignored) {
    }
}
```

- [ ] **Step 2: Replace with `evaluateOrError`**

Replace the block with:

```java
if (nameTempl != null && !nameTempl.isEmpty()) {
    String evaluatedName = evaluateOrError(innerCtx, nameTempl,
            "PermuteMethod", "name", element);
    if (evaluatedName != null)
        clone.setName(evaluatedName);
    // If null: error was already reported; clone keeps original name but is still added
    // so the caller sees a meaningful error, not a confusing "method not found".
}
```

---

### Task 4: Apply helpers to `@PermuteStatements`/`@PermuteParam` from/to evaluation

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

- [ ] **Step 1: Find the from/to silent catch for inner-loop annotations**

```bash
grep -n "fromVal\|toVal\|catch.*ignored\|evaluateInt" \
    /Users/mdproctor/claude/permuplate/permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    | head -30
```

Look for the section around lines 982-1006 where `@PermuteMethod`'s `from`/`to` is evaluated. These lines currently:
- Silently default `fromVal = 1` on `from` parse error
- Return silently on `to` parse error

**Note:** The `from`/`to` of `@PermuteMethod` itself (the outer range) has different semantics: `from` defaults to 1 on error (was a design choice for inference). We should only fix the `to` silent-return case, not the from-defaults-to-1 case.

- [ ] **Step 2: Find the @PermuteStatements from/to evaluation**

The test we wrote targets `@PermuteStatements`. Search for where `@PermuteStatements` inner-loop bounds are evaluated in `PermuteStatementsTransformer` (this is in `permuplate-core`, not `PermuteProcessor`):

```bash
grep -n "evaluateInt\|fromVal\|catch.*ignored" \
    /Users/mdproctor/claude/permuplate/permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java \
    | head -10
```

The transformer doesn't have access to `Messager`. The error must be reported at the processor level. Find where `PermuteStatementsTransformer.transform()` is called in `PermuteProcessor.java`:

```bash
grep -n "PermuteStatementsTransformer\|PermuteBodyTransformer\|PermuteCaseTransformer" \
    /Users/mdproctor/claude/permuplate/permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    | head -10
```

The transformers are called as a batch. Since the transformers don't have Messager access, there's no direct way to report per-annotation errors from within them. The most practical fix for `@PermuteStatements` is:

**Option A (recommended):** Add a pre-flight validation step in `PermuteProcessor` before calling the transformers: scan for `@PermuteStatements` annotations, evaluate their from/to with the current ctx, and report errors if evaluation fails. This lets the transformer still run (it will silently skip the broken annotation), but the user sees a clear error.

Add a private method `validatePermuteStatementsExpressions(TypeDeclaration<?> classDecl, EvaluationContext ctx, Element element)`:

```java
private void validatePermuteStatementsExpressions(
        TypeDeclaration<?> classDecl, EvaluationContext ctx,
        javax.lang.model.element.Element element) {
    classDecl.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).forEach(method ->
        method.getAnnotations().stream()
            .filter(a -> {
                String n = a.getNameAsString();
                return n.equals("PermuteStatements")
                    || n.equals("io.quarkiverse.permuplate.PermuteStatements");
            })
            .filter(a -> a instanceof NormalAnnotationExpr)
            .map(a -> (NormalAnnotationExpr) a)
            .forEach(ann -> {
                String from = io.quarkiverse.permuplate.core.PermuteDeclrTransformer.stripQuotes(
                        getAnnAttrRaw(ann, "from"));
                String to   = io.quarkiverse.permuplate.core.PermuteDeclrTransformer.stripQuotes(
                        getAnnAttrRaw(ann, "to"));
                if (from != null && !from.isEmpty())
                    evaluateIntOrError(ctx, from, "PermuteStatements", "from", element);
                if (to != null && !to.isEmpty())
                    evaluateIntOrError(ctx, to, "PermuteStatements", "to", element);
            }));
}
```

Where `getAnnAttrRaw(ann, name)` is the existing `getAnnAttr` helper (check the actual method name in the file). Note: `asStringLiteralExpr().asString()` is not needed here since we only evaluate, not parse.

Call this method immediately before the transformer pipeline:
```java
validatePermuteStatementsExpressions(classDecl, ctx, typeElement);
// 5e. @PermuteCase — expand switch statement cases
PermuteCaseTransformer.transform(classDecl, ctx);
// 5g. @PermuteStatements ...
```

- [ ] **Step 3: Build**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor -am -q 2>&1 | tail -5
```

---

### Task 5: Verify tests now pass

- [ ] **Step 1: Run both new tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=DegenerateInputTest#testBadPermuteMethodNameExpressionIsError+testBadPermuteStatementsBoundExpressionIsError \
    -q 2>&1 | tail -5
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 2: Run full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

Expected: all tests pass.

- [ ] **Step 3: Stage and commit**

```bash
git add \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java
git commit -m "fix: report compiler errors for bad @PermuteMethod name and @PermuteStatements bound expressions (closes #73)"
```

---

### Task 6: Update CLAUDE.md

- [ ] **Step 1: Add entries**

In CLAUDE.md, add to the error reporting standard section:

```
**`evaluateIntOrError` / `evaluateOrError` helpers** — private methods in `PermuteProcessor` that wrap JEXL evaluation and call `error()` with the annotation name, attribute name, failing expression, and JEXL message when evaluation fails. Apply to every new catch site that processes user-facing JEXL expressions. Leave intentional silent-fallback sites (inference, optional expansion) unchanged.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document evaluateIntOrError/evaluateOrError error helpers in CLAUDE.md"
```
