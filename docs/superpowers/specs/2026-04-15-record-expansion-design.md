# Record Template Support — Design Spec

**Date:** 2026-04-15  
**Status:** Approved  
**Scope:** `permuplate-core`, `permuplate-processor`, `permuplate-maven-plugin`, `permuplate-tests`

---

## Problem

Records cannot currently be used as `@Permute` templates. Two blockers exist:

1. **Parser language level** — `StaticJavaParser` defaults to Java 11, which predates records (Java 14+). Any record template throws `ParseProblemException`. Documented in `RecordExpansionTest.testBlocker1RecordParsingFails`.

2. **AST type** — All transformer methods in `permuplate-core` and both generator paths take `ClassOrInterfaceDeclaration`. Records are `RecordDeclaration` in JavaParser — a sibling type with common supertype `TypeDeclaration<?>`. Documented in `RecordExpansionTest.testBlocker2RecordDeclarationNotFoundByProcessor`.

---

## Goal

Full parity: all Permuplate annotations work on record templates to the same degree they work on class/interface templates, in both APT mode and Maven plugin inline mode.

Primary use case — the `Tuple${i}` family:

```java
@Permute(varName="i", from="2", to="4", className="Tuple${i}", inline=true)
public record Tuple2<@PermuteTypeParam(varName="k", from="1", to="${i}",
                                        name="${alpha(k)}") A>(
    @PermuteParam(varName="j", from="1", to="${i}",
                  type="${alpha(j)}", name="${lower(j)}")
    A a) {}
// Generates: Tuple2<A,B>(A a, B b), Tuple3<A,B,C>(A a, B b, C c), Tuple4<A,B,C,D>(A a, B b, C c, D d)
```

---

## Architecture

### Core approach: generalize to `TypeDeclaration<?>`

`ClassOrInterfaceDeclaration` and `RecordDeclaration` share the abstract supertype `TypeDeclaration<?>`. The APIs used by all transformers — `getFields()`, `getMethods()`, `getConstructors()`, `getAnnotations()`, `walk()`, `findAll()`, `getName()`, `setName()`, `getTypeParameters()` — are all on `TypeDeclaration<?>`.

Every transformer method signature in `permuplate-core` changes from:
```java
public static void transform(ClassOrInterfaceDeclaration classDecl, ...)
```
to:
```java
public static void transform(TypeDeclaration<?> classDecl, ...)
```

Casts to `ClassOrInterfaceDeclaration` or `RecordDeclaration` are added only where type-specific APIs are needed (four places, described below).

### Blocker 1 fix

One call in each generator's initialization:
```java
StaticJavaParser.getParserConfiguration()
    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
```

Added to `PermuteProcessor.init()` and as a static initializer in `InlineGenerator`.

### AST lookup

Both `PermuteProcessor` and `InlineGenerator` find the template class using `findFirst(ClassOrInterfaceDeclaration.class, predicate)`. Updated to try both:

```java
Optional<TypeDeclaration<?>> found = templateCu
    .findFirst(ClassOrInterfaceDeclaration.class, c -> c.getNameAsString().equals(name))
    .<TypeDeclaration<?>>map(c -> c)
    .or(() -> templateCu.findFirst(RecordDeclaration.class,
                                    r -> r.getNameAsString().equals(name)));
```

---

## Record-Specific Branches

Four places where records diverge from classes:

### 1. Skip extends expansion
Records cannot extend classes. `applyExtendsExpansion()` in both the processor and `InlineGenerator` is guarded:
```java
if (classDecl instanceof ClassOrInterfaceDeclaration coid) {
    applyExtendsExpansion(coid, ...);
}
```

### 2. Skip interface/abstract modifier stripping
Records have neither `interface` nor `abstract` modifiers. The calls `classDecl.setInterface(false)` and related are guarded the same way.

### 3. Static modifier stripping
When a nested record is promoted to a top-level file (APT mode), the `static` modifier is stripped — same as nested static classes. Records are implicitly static when nested; the stripping code runs for both.

### 4. Constructor rename after class rename
After renaming the record, any explicit `ConstructorDeclaration` nodes inside it (compact constructors, non-canonical constructors) are renamed to match. The canonical constructor is implicit — no AST node to rename.

---

## New capability: @PermuteParam on record components

Record components in JavaParser are `Parameter` nodes in `RecordDeclaration.getParameters()`. Currently `PermuteParamTransformer` only walks `MethodDeclaration` and `ConstructorDeclaration` parameter lists.

For records, it additionally walks `RecordDeclaration.getParameters()` directly:

```java
if (classDecl instanceof RecordDeclaration rec) {
    transformRecordComponents(rec, ctx, messager, element);
}
```

`transformRecordComponents()` applies the same `@PermuteParam` expansion logic:
1. Find sentinel component annotated with `@PermuteParam`
2. Expand into the generated sequence (inserting before/after the sentinel)
3. Expand anchor usages in the record body (compact constructor body, method bodies)

This is what makes `record Tuple2<A>(A a)` expand to `record Tuple3<A,B,C>(A a, B b, C c)`.

---

## File Map

| File | Change |
|---|---|
| `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | Language level init; `findFirst` handles `RecordDeclaration`; `generatePermutation` signature → `TypeDeclaration<?>`; four record-specific branches |
| `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | Language level init; same structural changes |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java` | Signature: `ClassOrInterfaceDeclaration` → `TypeDeclaration<?>` |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java` | Signature change + `transformRecordComponents()` |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java` | Signature change |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java` | Signature change |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteValueTransformer.java` | Signature change |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java` | Signature change |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteReturnTransformer.java` | Signature change |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteMethodTransformer.java` | Signature change |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/RecordExpansionTest.java` | Replace blocker-documenting tests with working tests |

---

## Testing (TDD order)

All in `RecordExpansionTest.java`. APT mode first, then Maven plugin.

| # | Test | What it verifies |
|---|---|---|
| 1 | `testBasicRecordPermutation` | `@Permute` on a record with fixed components generates correctly named records with components preserved |
| 2 | `testRecordWithPermuteDeclrOnComponent` | `@PermuteDeclr(type="${T}")` on a record component renames its type per permutation |
| 3 | `testRecordWithPermuteParam` | `@PermuteParam` on a record component expands the component list — the Tuple pattern |
| 4 | `testRecordWithPermuteTypeParam` | Type parameters on the record expand correctly |
| 5 | `testRecordInlineModeWithPermuteParam` | Same as (3) but `inline=true` via Maven plugin — Tuple family generated as nested siblings |

All 181 existing tests must continue passing — backward compatibility for class/interface templates is non-negotiable.

---

## What Is Not In Scope

- **`@PermuteExtends` on records** — records cannot extend classes; this annotation is skipped for records (same as existing boundary omission for `@PermuteReturn`).
- **Compact constructor `@PermuteStatements`** — compact constructors have no explicit parameter list and their body semantics differ from regular constructors. `@PermuteStatements` on a compact constructor is deferred.
- **IntelliJ plugin updates** — the plugin's `PermuteTemplateIndex` and `PermuteGeneratedIndex` use PSI (IntelliJ's own AST), not JavaParser. PSI already supports Java 17 records. Index updates for records are a separate task.
