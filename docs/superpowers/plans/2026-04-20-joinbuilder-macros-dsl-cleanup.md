# JoinBuilder macros= DSL Application and Final Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development

**Goal:** Apply macros={"alpha=typeArgList(1,i,'alpha')"} to Join0Second to eliminate 4 repetitions of typeArgList(1,i,'alpha'). Also apply any remaining cleanup after Plans B, C, D are merged. Closes #90.

**Prerequisite:** Plans B (alpha inference, #87), C (@PermuteMethod macros=, #88), and D (replaceLastTypeArgWith, #89) must be merged first.

**Architecture:** Pure DSL template change. No engine modifications.

**GitHub:** Epic #86, Issue #90

---

## Tasks

### Task 1: Confirm prerequisites are merged

Before touching JoinBuilder.java, verify:

1. `inferAlphaTypeArgs()` exists in InlineGenerator (Plan B)
2. `macros=` is accepted on `@PermuteMethod` (Plan C)
3. `replaceLastTypeArgWith=` is accepted on `@PermuteReturn` (Plan D)

If any are missing, stop and wait.

### Task 2: Add macros= to @Permute on Join0Second

File: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`

Find the `@Permute` on `Join0Second`:
```java
@Permute(varName = "i", from = "1", to = "6", className = "Join${i}Second",
         inline = true, keepTemplate = false)
```

Add `macros={"alpha=typeArgList(1,i,'alpha')"}`:
```java
@Permute(varName = "i", from = "1", to = "6", className = "Join${i}Second",
         inline = true, keepTemplate = false,
         macros = {"alpha=typeArgList(1,i,'alpha')"})
```

Note: `macros=` on `@Permute` is evaluated once per generated class (outer loop), so `alpha` is available in all inner JEXL expressions for that class. This is distinct from `@PermuteMethod macros=` (Plan C) which is evaluated per inner loop iteration.

### Task 3: Replace typeArgList(1,i,'alpha') with alpha in 4 places

All four occurrences are in Join0Second and its inner template methods.

**3a. not()/exists() typeArgs**

Find:
```java
typeArgs = "'Join' + i + 'Second<END, DS, ' + typeArgList(1, i, 'alpha') + '>, DS'",
```
Replace with:
```java
typeArgs = "'Join' + i + 'Second<END, DS, ' + alpha + '>, DS'",
```

**3b. extensionPoint() typeArgs**

Find:
```java
typeArgs = "'DS, ' + typeArgList(1, i, 'alpha')",
```
Replace with:
```java
typeArgs = "'DS, ' + alpha",
```

**3c. fn() @PermuteDeclr Consumer type**

Find:
```java
@PermuteDeclr(type = "Consumer${i+1}<DS, ${typeArgList(1, i, 'alpha')}>")
```
Replace with:
```java
@PermuteDeclr(type = "Consumer${i+1}<DS, ${alpha}>")
```

**3d. filter() @PermuteDeclr Predicate type**

Find:
```java
@PermuteDeclr(type = "Predicate${i+1}<DS, ${typeArgList(1, i, 'alpha')}>")
```
Replace with:
```java
@PermuteDeclr(type = "Predicate${i+1}<DS, ${alpha}>")
```

### Task 4: Verify path method macros use alpha (from Plan C)

Check that the `path${n}` @PermuteMethod (applied in Plan C) has its typeArgs using `alpha` rather than `typeArgList(1,i,'alpha')`. The macros on the @PermuteMethod cover `tail` and `prev` (inner-n-dependent); the outer `alpha` comes from the class-level `@Permute macros=` added in Task 2.

Expected final state of path method typeArgs:
```java
typeArgs = "'Join'+(i+1)+'First<END, DS, '+alpha+', BaseTuple.Tuple'+n+'<'+tail+'>>, BaseTuple.Tuple'+(n-1)+'<'+prev+'>, '+tail"
```

If Plan C was written with `typeArgList(1,i,'alpha')` inline instead of `alpha`, update it now.

### Task 5: Verify join() uses alpha inference (from Plan B)

Check that `join()` has no `typeArgs=` attribute (Plan B removes it). Expected state:
```java
@PermuteReturn(className = "Join${i+1}First")  // typeArgs inferred from @PermuteTypeParam
```

### Task 6: Verify type() uses replaceLastTypeArgWith (from Plan D)

Check that `type()` uses:
```java
@PermuteReturn(className = "Join${i}First", alwaysEmit = true, replaceLastTypeArgWith = "T")
```

### Task 7: Full build and test

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

All DSL tests in `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/` must pass.

If any test fails, diagnose — do not skip.

### Task 8: Update CLAUDE.md and commit (closes #90)

Add to the "Key non-obvious decisions" table in `CLAUDE.md`:

```
| JoinBuilder macros=alpha | macros={"alpha=typeArgList(1,i,'alpha')"} on Join0Second @Permute. Used in Consumer/Predicate @PermuteDeclr, not()/exists() typeArgs, extensionPoint() typeArgs, and path${n} @PermuteMethod typeArgs. Eliminates 4 repetitions of the typeArgList expression. Outer-class macros= evaluated once per generated class; @PermuteMethod macros= evaluated per inner loop iteration. |
```

Stage only the files changed for this issue. Stop for review before committing.
