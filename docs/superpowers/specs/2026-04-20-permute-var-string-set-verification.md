# @PermuteVar String-Set Axis — Verification and Documentation Design

**Status:** Feature already implemented; spec covers test and documentation gap only.

## Background

`@PermuteVar(values={"A","B","C"})` is fully wired in the codebase:
- `PermuteVar.java` has `String[] values() default {}` attribute
- `PermuteVarConfig` carries the values array
- `AnnotationReader.readExtraVars()` reads it from the JavaParser AST
- `PermuteConfig.buildAllCombinations()` cross-products string values correctly

What is missing: a happy-path compilation test and README documentation.

## What to Build

### Test

A new test in `StringSetIterationTest.java` verifying that `@PermuteVar(values={...})` produces a cross-product of classes:

```java
@Permute(varName="i", from="2", to="3",
         className="${T}Pair${i}",
         extraVars={@PermuteVar(varName="T", values={"Sync","Async"})})
public class SyncPair2 { }
```

Expected generated classes: `SyncPair2`, `SyncPair3`, `AsyncPair2`, `AsyncPair3` (4 total).

Test assertions:
- Compilation succeeds
- All four class names are present in generated source files
- No `@Permute` annotations remain in any generated class
- Template class `SyncPair2` is NOT generated (it's the template, different from `SyncPair${i}` range)

Also add a test verifying that `@PermuteVar(values={...})` with a string variable binding makes the variable available in annotation expressions:

```java
@Permute(varName="i", from="2", to="2",
         className="${capitalize(T)}Widget${i}",
         extraVars={@PermuteVar(varName="T", values={"fancy","plain"})})
public class WidgetTemplate { }
```

Expected: `FancyWidget2` and `PlainWidget2` (uses `capitalize()` from C4).

### Documentation

Update `README.md` `@PermuteVar` section to add a string-set example. The current README only shows the integer range example (`Matrix2x2`..`Matrix4x4`).

Add after the integer-range example:

```markdown
**String-set axis:** Use `values` instead of `from`/`to` to cross-product over named strings:

```java
@Permute(varName="i", from="2", to="3",
         className="${T}Pair${i}",
         extraVars={@PermuteVar(varName="T", values={"Sync","Async"})})
public class SyncPair2 { ... }
// Generates: SyncPair2, SyncPair3, AsyncPair2, AsyncPair3
```

String variables bind as `String` in JEXL — `capitalize(T)`, `decapitalize(T)`, and string comparison work as expected.
```

Update `CLAUDE.md` key decisions table to add:
```
| `@PermuteVar` string-set axis | Fully implemented — `values={"A","B"}` produces cross-product; variable binds as `String`. `from`/`to` must be omitted when `values` is used (validated in processor). Tested in `StringSetIterationTest`. |
```

## Files

| Action | Path |
|---|---|
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java` |
| Modify | `README.md` |
| Modify | `CLAUDE.md` |
