# @PermuteAnnotation and @PermuteThrows — Design Spec

**Date:** 2026-04-16  
**Status:** Approved  
**Scope:** `permuplate-annotations`, `permuplate-core`, `permuplate-processor`, `permuplate-maven-plugin`, `permuplate-intellij-plugin`

---

## Overview

Two new Tier 2 annotation language features:

- **`@PermuteAnnotation`** — adds Java annotations to generated types, methods, or fields, with an optional JEXL condition controlling when each annotation is applied.
- **`@PermuteThrows`** — adds exception types to a method's `throws` clause per permutation, with an optional JEXL condition.

Both follow the established Permuplate annotation pattern: `SOURCE` retention, repeatable via a container annotation, JEXL-evaluated attributes, stripped from generated output.

---

## `@PermuteAnnotation`

### Annotation definition

```java
@Repeatable(PermuteAnnotations.class)
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
public @interface PermuteAnnotation {
    /**
     * A JEXL-evaluated Java annotation literal to add to the element.
     * E.g. {@code "@Deprecated(since=\"${i}\")"} or {@code "@FunctionalInterface"}.
     * The evaluated string must parse as a valid Java annotation expression.
     */
    String value();

    /**
     * JEXL boolean condition. The annotation is added only when this evaluates to
     * {@code true}. Empty string (the default) means always apply.
     */
    String when() default "";
}
```

`PermuteAnnotations` is the standard container (`PermuteAnnotation[] value()`), same `@Target` and `@Retention`.

### Usage

```java
@Permute(varName="i", from="1", to="6", className="Callable${i}",
         strings={"max=6"})
@PermuteAnnotation(when="${i == 1}", value="@FunctionalInterface")
@PermuteAnnotation(when="${i == max}", value="@Deprecated(since=\"use higher arity\")")
public interface Callable1 { ... }

// Callable1 gets @FunctionalInterface
// Callable6 gets @Deprecated(since="use higher arity")
// Others get neither
```

Also works on methods and fields within the template:

```java
@PermuteAnnotation(when="${i > 4}", value="@SuppressWarnings(\"unchecked\")")
public void heavyMethod() { ... }
```

### Processing (`PermuteAnnotationTransformer`)

New transformer in `permuplate-core`. Pipeline position: **last** — runs after all other transformers so `when` expressions see the final permutation state.

For each element (class, method, field) that carries `@PermuteAnnotation`/`@PermuteAnnotations`:

1. For each annotation: evaluate `when` with the current `EvaluationContext`. If `false`, skip. If empty or `true`, proceed.
2. Evaluate `value` as a JEXL string template (e.g. `"${i}"` resolves to `"3"`).
3. Parse the result with `StaticJavaParser.parseAnnotation(evaluatedValue)` to get a proper `AnnotationExpr`.
4. Add the `AnnotationExpr` to the element's annotation list.
5. Strip the `@PermuteAnnotation`/`@PermuteAnnotations` annotation from the output.

**Errors:**
- `when` doesn't evaluate to boolean → `WARNING`, skip this annotation (same as `@PermuteFilter` error handling)
- `value` doesn't parse as a valid annotation → `ERROR` with element-level location

### IntelliJ inspection

New `PermuteAnnotationValueInspection` in the plugin:

- Triggered on any `@PermuteAnnotation` in a Permuplate template
- Stubs out `${...}` JEXL expressions (replaces with `X`)
- Attempts to parse the stub with `StaticJavaParser.parseAnnotation()`
- Warns if parsing fails: `"@PermuteAnnotation value is not a valid annotation expression"`

---

## `@PermuteThrows`

### Annotation definition

```java
@Repeatable(PermuteThrowsList.class)
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteThrows {
    /**
     * A JEXL-evaluated exception class name to add to the method's throws clause.
     * E.g. {@code "TooManyArgsException"} or {@code "${exceptionName}"}.
     */
    String value();

    /**
     * JEXL boolean condition. The exception is added only when this evaluates to
     * {@code true}. Empty string (the default) means always add.
     */
    String when() default "";
}
```

`PermuteThrowsList` is the container annotation, `@Target(METHOD)` only.

### Usage

```java
@Permute(varName="i", from="1", to="10", className="Join${i}")
public class Join1 {

    @PermuteThrows(when="${i > 4}", value="TooManyArgsException")
    public void join(Object o1) throws SomeException { ... }
}
// Join1..Join4: throws SomeException
// Join5..Join10: throws SomeException, TooManyArgsException
```

### Processing (`PermuteThrowsTransformer`)

New transformer in `permuplate-core`. Pipeline position: after `PermuteValueTransformer` and `PermuteStatementsTransformer`, before output.

For each `MethodDeclaration` that carries `@PermuteThrows`/`@PermuteThrowsList`:

1. For each entry: evaluate `when`. If `false`, skip. If empty or `true`, proceed.
2. Evaluate `value` as a JEXL string template.
3. Parse with `StaticJavaParser.parseClassOrInterfaceType(evaluatedValue)` to get a `ReferenceType`.
4. Add the type to `method.getThrownExceptions()`.
5. Strip the `@PermuteThrows`/`@PermuteThrowsList` annotation from the output.

**Errors:**
- `when` doesn't evaluate to boolean → `WARNING`, skip this entry
- `value` doesn't parse as a valid type → `ERROR` with element-level location
- Adding to a method that has no existing `throws` clause is not an error — the clause is created

### IntelliJ inspection

New `PermuteThrowsTypeInspection` in the plugin:

- Triggered on any `@PermuteThrows` in a Permuplate template
- Stubs out `${...}` JEXL expressions (replaces with `Object` as a safe fallback)
- Attempts to resolve the stub as a type in the project scope
- Warns if resolution fails: `"@PermuteThrows value does not resolve to a known type"`

---

## File Map

| File | Change |
|---|---|
| `permuplate-annotations/…/PermuteAnnotation.java` | Create |
| `permuplate-annotations/…/PermuteAnnotations.java` | Create (container) |
| `permuplate-annotations/…/PermuteThrows.java` | Create |
| `permuplate-annotations/…/PermuteThrowsList.java` | Create (container) |
| `permuplate-core/…/PermuteAnnotationTransformer.java` | Create |
| `permuplate-core/…/PermuteThrowsTransformer.java` | Create |
| `permuplate-processor/…/PermuteProcessor.java` | Add both transformer calls in `generatePermutation()` |
| `permuplate-maven-plugin/…/InlineGenerator.java` | Add both transformer calls in `generate()` |
| `permuplate-intellij-plugin/…/inspection/PermuteAnnotationValueInspection.java` | Create |
| `permuplate-intellij-plugin/…/inspection/PermuteThrowsTypeInspection.java` | Create |
| `permuplate-tests/…/PermuteAnnotationTransformerTest.java` | Create |
| `permuplate-tests/…/PermuteThrowsTransformerTest.java` | Create |

---

## Testing (TDD order)

### `@PermuteAnnotation` (5 tests)

1. **Class-level always-apply** — `when=""`, `value="@FunctionalInterface"` — annotation present on all generated classes
2. **Class-level conditional** — `when="${i == 1}"`, `value="@FunctionalInterface"` — annotation only on arity 1
3. **JEXL in value** — `value="@SuppressWarnings(\"${i}\")"` — resolves correctly per arity
4. **Method-level** — `@PermuteAnnotation` on a method inside the template — annotation added to that method in generated classes
5. **Field-level** — same for a field

### `@PermuteThrows` (3 tests)

6. **Always-apply** — `when=""`, `value="IOException"` — exception on all generated methods
7. **Conditional** — `when="${i > 4}"`, `value="TooManyArgsException"` — exception only at high arities
8. **Multiple** — two `@PermuteThrows` on same method, both applied

---

## What Is Not In Scope

- Removing existing throws clause entries (add-only, decided)
- `@PermuteAnnotation` on constructor parameters or type parameters (have dedicated annotations)
- Rename propagation for class names referenced inside `value` strings — follows the existing annotation string opacity limitation
- IntelliJ index changes — both are SOURCE-retention and don't affect generated class names
