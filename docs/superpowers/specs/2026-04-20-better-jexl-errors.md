# Better JEXL Error Messages Design

## Problem

`PermuteProcessor` has ~17 `catch (Exception ignored)` sites. Most are legitimate silent fallbacks (optional inference steps). A subset are annotation-level evaluation failures that should surface as compiler errors pointing at the specific attribute — currently they silently skip the annotation, leaving the developer with no feedback.

We already fixed `@PermuteImport`. This spec covers the remaining high-value cases.

## Scope

**Fix these (silently skipping is wrong):**

1. **`@PermuteStatements` / `@PermuteParam` from/to evaluation** (~lines 977-1007): When the `from` or `to` expression on an inner-loop annotation fails, the annotation is silently skipped. No cases are inserted, no error is shown.

2. **`@PermuteMethod` name template evaluation** (~line 1048): When the `name` template fails, the method keeps its original name (or is silently dropped). The developer gets no indication the expression failed.

3. **`@PermuteImport` in APT path** (already done in a previous session; confirm it's covered).

**Leave these alone (silent is correct):**
- Lines 1205-1284: optional inference and extends-expansion steps — these try-and-fallback by design
- Line 141: annotation validation prefix check — already handled at a higher level
- Line 851: `NumberFormatException` in case label parsing — wrong input → skip is appropriate

## Design

### Helper method

Add a private helper to `PermuteProcessor`:

```java
/**
 * Evaluates {@code expression} using {@code ctx} and returns the result as a String.
 * On failure, reports a compiler error at the annotation attribute level and returns
 * {@code null}. The caller should skip the annotation if {@code null} is returned.
 */
@Nullable
private String evaluateOrError(EvaluationContext ctx, String expression,
        String annotationName, String attributeName, Element element) {
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

And an integer variant:

```java
@Nullable
private Integer evaluateIntOrError(EvaluationContext ctx, String expression,
        String annotationName, String attributeName, Element element) {
    try {
        return ctx.evaluateInt(expression);
    } catch (Exception e) {
        error("@" + annotationName + " '" + attributeName
                + "' failed to evaluate: \"" + expression + "\" — " + e.getMessage(),
                element);
        return null;
    }
}
```

### Apply to target sites

Replace each target `catch (Exception ignored)` with a call to the helper, then check for `null` before continuing:

**Site 1 — `@PermuteStatements`/`@PermuteParam` from/to:**
```java
// Before:
try {
    fromVal = ctx.evaluateInt(fromStr);
} catch (Exception ignored) {
    return;
}

// After:
Integer fromVal = evaluateIntOrError(ctx, fromStr, "PermuteStatements", "from", typeElement);
if (fromVal == null) return;
```

**Site 2 — `@PermuteMethod` name template:**
```java
// Before:
try {
    clone.setName(innerCtx.evaluate(nameTempl));
} catch (Exception ignored) {}

// After:
String evaluatedName = evaluateOrError(innerCtx, nameTempl, "PermuteMethod", "name", element);
if (evaluatedName != null) clone.setName(evaluatedName);
// (don't skip — clone is still added without name change if eval fails; the error is already reported)
```

### Error message format

```
@PermuteStatements 'from' failed to evaluate: "${undefinedVar}" — JEXL: undefined variable 'undefinedVar'
```

This gives the developer:
- Which annotation (`@PermuteStatements`)
- Which attribute (`from`)
- The expression that failed (`"${undefinedVar}"`)
- The JEXL error message

### Tests

Add to `DegenerateInputTest.java`:

1. Test that a bad `from` expression on `@PermuteStatements` surfaces a compiler error containing `"PermuteStatements"` and `"from"`.
2. Test that a bad `name` template on `@PermuteMethod` surfaces a compiler error containing `"PermuteMethod"` and `"name"`.

## Files

| Action | Path |
|---|---|
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java` |
