# G2 — Return Type Narrowing by Arity Design Spec

**Date:** 2026-04-02  
**Status:** Approved  
**Gap reference:** G2 in `docs/gap-analysis.md`

---

## Problem

Permuplate can generate a family of classes with growing type parameter lists (G1), but cannot express that a method in `Step${i}` returns `Step${i+1}<T1, ..., T${i+1}>`. The result: stateful builder chains that track accumulated type state cannot be type-safely generated.

**Real-world driver:** The Drools RuleBuilder DSL hand-writes `Join1First`, `Join2First`, ..., `Join5First` — each `.join()` call returns the next step type with one additional type parameter. `Join5First` has no `.join()` method at all: it is the leaf node, and there is no `Join6First` to return. G2 eliminates this boilerplate and reproduces the leaf-node behaviour automatically.

The problem has two coupled parts:
1. **Class name expression:** the return type is `Step${i+1}` — it varies with the outer permutation variable
2. **Growing type argument list:** the return type's type arguments are `<T1, T2, ..., T${i+1}>` — they grow by one per permutation

---

## Design Overview

G2 introduces two mechanisms and extends one existing annotation:

| Mechanism | Mode | Annotation required |
|---|---|---|
| Implicit return type inference | Inline (Maven plugin) only | None — zero annotation in common case |
| Explicit `@PermuteReturn` | Both APT and inline | `@PermuteReturn` on the method |
| `@PermuteDeclr` on method parameters | Both APT and inline | `@PermuteDeclr` on the parameter |

The implicit mechanism is the idiomatic path for inline templates. The explicit mechanism is required for APT mode (where templates must be valid, compilable Java) and available as an override in inline mode.

---

## Mechanism 1: Implicit Return Type Inference (inline mode only)

### How it works

When the Maven plugin processes a template file, it first scans **all** `@Permute` annotations in the file and builds the complete set of class names that will be generated. For example, `@Permute(varName="i", from=1, to=4, className="Step${i}")` produces `{Step1, Step2, Step3, Step4}`.

For every method in the template, the processor inspects the return type and applies implicit inference if **both** conditions hold:

**Condition 1 — the return type class is in the generated set:**
The return type's base class name (e.g., `Step2`) appears in the set of generated class names.

**Condition 2 — type arguments follow the `T${j}` naming convention:**
The return type's type arguments consist of:
- Type parameters declared on the enclosing class (e.g., `T1` declared on `Step1<T1>`) — these are fixed and pass through unchanged
- Followed by one or more type variables matching the `T${j}` naming pattern that are **not** declared on the enclosing class (e.g., `T2` in `Step1<T1>`) — these form the growing tip

When both conditions hold, the processor automatically:
1. Determines the offset expression — e.g., `Step2` in `Step1` → `Step${i+1}`
2. Expands the type argument list per permutation: fixed args pass through, growing tip extends by one
3. Omits the method when the evaluated class name is not in the generated set (see Leaf Node below)

### Template and generated output

```java
// src/main/permuplate/.../StepChain.java
@Permute(varName="i", from=1, to=4, className="Step${i}", inline=true, keepTemplate=true)
public class Step1<T1> {
    // Return type inferred — no @PermuteReturn needed.
    // @PermuteDeclr on the parameter handles the changing Source<T${i+1}> type.
    public Step2<T1, T2> join(
            @PermuteDeclr(type="Source<T${i+1}>") Source<T2> src) { ... }
    public void execute(Action1 action) { ... }
}
```

Generated output:

```java
public class Step1<T1>             { public Step2<T1, T2>           join(Source<T2> src) { ... } ... }
public class Step2<T1, T2>         { public Step3<T1, T2, T3>       join(Source<T3> src) { ... } ... }
public class Step3<T1, T2, T3>     { public Step4<T1, T2, T3, T4>  join(Source<T4> src) { ... } ... }
public class Step4<T1, T2, T3, T4> { /* join() omitted — Step5 not in generated set */            ... }
```

### When inference does NOT apply

If Condition 1 or Condition 2 is not met, inference is skipped and the return type is left unchanged. Use `@PermuteReturn` explicitly in those cases. A warning is emitted (see Validation, rule V4) when Condition 1 holds but Condition 2 does not — this is likely a user error.

---

## The Leaf Node: Automatic Boundary Omission

**This is the most important behaviour to understand and document.** The last generated class in the range automatically has the inferred method omitted.

For `@Permute(from=1, to=4)`, the generated set is `{Step1, Step2, Step3, Step4}`:
- `Step1`, `Step2`, `Step3`: `join()` is generated, returning the next step
- `Step4` (the leaf): `join()` would return `Step5`, which is **not in the generated set** → `join()` is silently omitted

This mirrors the Drools hand-written pattern exactly: `Join5First` has no `.join()` method because there is no `Join6First`. Permuplate reproduces this automatically with no annotation.

**Without boundary omission,** the generated `Step4.java` would reference `Step5` as a return type and fail to compile. The inference engine prevents this by treating "return type class not in generated set" as a signal to omit the method entirely.

**Boundary omission applies to both implicit inference and explicit `@PermuteReturn`** (see `when` attribute below). If you need the last class to reference an external, hand-written class, use `@PermuteReturn` with an explicit `when` expression to override the omission.

---

## Mechanism 2: `@PermuteReturn` (explicit annotation)

### Annotation definition

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteReturn {
    /**
     * Template expression for the return type class name (e.g., {@code "Step${i+1}"}).
     * Evaluated against the outer permutation context for each generated class.
     */
    String className();

    /**
     * Variable name for the type argument expansion loop (e.g., {@code "j"}).
     * If empty, no type arguments are generated (raw return type).
     */
    String typeArgVarName() default "";

    /** Lower bound of the type argument loop (e.g., {@code "1"}). */
    String typeArgFrom() default "1";

    /** Upper bound of the type argument loop (e.g., {@code "${i+1}"}). */
    String typeArgTo() default "";

    /** Type argument name template (e.g., {@code "T${j}"}). */
    String typeArgName() default "";

    /**
     * Optional JEXL guard expression controlling whether this method is generated
     * for a given permutation. When the expression evaluates to {@code false}, the
     * method is omitted entirely from that generated class.
     *
     * <p>When empty (the default), the standard boundary inference rule applies:
     * the method is omitted when the evaluated {@code className} is not in the set
     * of class names generated by {@code @Permute} annotations in the current
     * template file.
     *
     * <p>Use an explicit {@code when} expression to override boundary inference —
     * for example, to force generation of a method pointing to a hand-written
     * external class that is not in the generated set:
     * <pre>{@code
     * @PermuteReturn(className="TerminalStep", ..., when="true")
     * }</pre>
     */
    String when() default "";
}
```

### When to use `@PermuteReturn`

Use `@PermuteReturn` in these situations — each one should be documented clearly in Javadoc and the user guide:

| Situation | Why explicit is needed |
|---|---|
| **APT mode** | Template must be compilable Java; `Step2` as return type doesn't compile before `Step2` is generated. Use `Object` as the sentinel return type. |
| **Type args don't follow `T${j}` convention** | Inference Condition 2 fails; processor cannot determine the growing series. |
| **Return type is a non-linear offset** | E.g., `Step${i+2}` (skipping one) — inference always assumes `i+1`. |
| **Leaf class must reference an external class** | `when="true"` overrides boundary omission to force generation. |
| **No type arguments needed** | Raw return type — omit `typeArgVarName`. |

### APT mode template

In APT mode the template must be valid Java. Use `Object` as the sentinel return type:

```java
// src/main/java/.../StepChain.java  (APT mode — must compile)
@Permute(varName="i", from=1, to=4, className="Step${i}")
public class Step1<T1> {
    @PermuteReturn(className="Step${i+1}",
                   typeArgVarName="j", typeArgFrom="1", typeArgTo="${i+1}", typeArgName="T${j}")
    public Object join(@PermuteDeclr(type="Source<T${i+1}>") Object src) { ... }
}
```

The processor reads the source text via `getCharContent` (JavaParser), sees `@PermuteReturn`, and replaces `Object` with the fully typed return type in each generated class. `Step4.join()` is still omitted (boundary rule applies).

---

## Mechanism 3: `@PermuteDeclr` on Method Parameters

### Extension

`@PermuteDeclr` currently applies to fields (`FIELD`) and for-each variables (`LOCAL_VARIABLE`). G2 adds `PARAMETER` to its `@Target`.

The `name` attribute is made **optional**, defaulting to `""` (keep original parameter name):

```java
@Target({ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.PARAMETER})
public @interface PermuteDeclr {
    String type();
    String name() default "";  // "" = keep original name
}
```

When placed on a method parameter:
- `type` is evaluated and replaces the parameter's declared type
- `name` = `""`: only the type changes, no rename propagation needed
- `name` = non-empty: type AND name are replaced, rename is propagated through the method body

### Usage

```java
public Step2<T1, T2> join(
        @PermuteDeclr(type="Source<T${i+1}>") Source<T2> src) { ... }
//      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^  type changes; name "src" stays the same
```

For `i=1` → `Source<T2> src`; for `i=2` → `Source<T3> src`; for `i=3` → `Source<T4> src`.

---

## Mode Comparison

| Feature | APT | Maven plugin (inline) |
|---|---|---|
| Implicit return type inference | No — template must compile with `Object` sentinel | Yes — template references generated class directly |
| `@PermuteReturn` explicit | Yes | Yes (overrides inference) |
| `@PermuteDeclr` on parameters | Yes (with `Object` sentinel) | Yes (with typed sentinel) |
| Boundary omission | Yes (same inference rule) | Yes |
| Template sentinel return type | `Object` | Actual next class (`Step2<T1, T2>`) |

---

## Validation Rules

| Rule | Condition | Severity | Message |
|---|---|---|---|
| **V1** | `@PermuteReturn` on a method outside a `@Permute`-annotated type | Error | `@PermuteReturn found outside a @Permute-annotated type — it can only appear on methods of classes or interfaces annotated with @Permute.` |
| **V2** | `@PermuteReturn` has `typeArgVarName` set but `typeArgTo` or `typeArgName` is empty | Error | `@PermuteReturn: typeArgVarName requires both typeArgTo and typeArgName to be specified.` |
| **V3** | `@PermuteReturn` `typeArgFrom` evaluates greater than `typeArgTo` | Error | `@PermuteReturn has invalid type argument range: from=N is greater than to=M` |
| **V4** | Implicit inference Condition 1 holds (return type in generated set) but Condition 2 fails (type args don't follow `T${j}` convention) | Warning | `Return type "Step2" is in the generated class set but its type arguments don't follow the T${j} naming convention — inference skipped. Use @PermuteReturn explicitly to control the return type expansion.` |
| **V5** | `@PermuteReturn` is present on a method whose sentinel return type is also a generated class (both explicit and inference would apply) | Warning | `@PermuteReturn is present — implicit return type inference is suppressed for this method. The sentinel return type is ignored.` |

All errors follow the project's error reporting standard: attribute-level where possible, element-level minimum.

---

## Examples

### Drools-style join chain (implicit inference, inline mode)

Inference requires the `T${j}` naming convention (Condition 2). Permuplate templates use `T1, T2, T3, ...` as fact type variables — the Drools hand-written code used `B, C, D, ...` but that naming does not trigger inference and would require explicit `@PermuteReturn` instead.

```java
// src/main/permuplate/.../RuleBuilder.java
public class RuleBuilder<CTX> {

    @Permute(varName="i", from=1, to=5, className="Join${i}First",
             inline=true, keepTemplate=true)
    public class Join1First<T1> {

        // Return type inferred: Join2First<T1, T2> → Join${i+1}First<T1..T${i+1}>
        // Omitted automatically on Join5First (the leaf node — no Join6First exists)
        public Join2First<T1, T2> join(
                @PermuteDeclr(type="Source<T${i+1}>") Source<T2> source) { ... }

        public void execute(Action1<CTX, T1> action) { ... }

        public boolean filter(Condition1<CTX, T1> condition) { ... }
    }
}
```

`Join5First<T1, T2, T3, T4, T5>` is generated with `execute()` and `filter()` but no `join()` — exactly the hand-written Drools pattern, reproduced automatically.

### With explicit `@PermuteReturn` (APT mode)

```java
// src/main/java/.../StepChain.java
@Permute(varName="i", from=1, to=4, className="Step${i}")
public class Step1<T1> {
    @PermuteReturn(className="Step${i+1}",
                   typeArgVarName="j", typeArgFrom="1", typeArgTo="${i+1}", typeArgName="T${j}")
    public Object join(@PermuteDeclr(type="Source<T${i+1}>") Object src) { ... }
}
```

### Overriding boundary omission (`when`)

```java
// Step4 must reference a hand-written Terminal class
@PermuteReturn(className="Step${i+1}",
               typeArgVarName="j", typeArgFrom="1", typeArgTo="${i+1}", typeArgName="T${j}",
               when="true")
public Object join(...) { ... }
```

---

## Documentation Requirements

Documentation of the implicit inference and the leaf-node boundary behaviour is **load-bearing** — users encountering a missing method on the last generated class will be confused unless the reason is stated explicitly and prominently.

### `@PermuteReturn` Javadoc
- State that this annotation is **not needed in the common case** — implicit inference handles it when the return type is a generated class following the `T${j}` convention
- List all four situations where explicit annotation IS required (table from "When to use" section above)
- State clearly: boundary omission applies by default; use `when="true"` to override

### `@PermuteDeclr` Javadoc
- Add section: new `PARAMETER` target and optional `name` attribute
- Note that `name = ""` (default) changes only the type, not the parameter name

### README / user guide
- New section: **Return type narrowing** with the full `Step1`–`Step4` example showing generated output
- **Prominent callout box:** "The leaf class: why the last generated class has no `join()` method" — explain the boundary inference rule and why it exists. Without this, users will think Permuplate has a bug.
- Comparison table: implicit inference (inline mode) vs. explicit `@PermuteReturn` (APT or override)
- Note the `when` attribute escape hatch for hand-written leaf classes

### CLAUDE.md non-obvious decisions table
- Add: implicit return type inference conditions (both must hold)
- Add: boundary omission rule (applies to both implicit and explicit `@PermuteReturn`)
- Add: leaf node pattern — last class in range has inferred methods omitted; mirrors Drools hand-written pattern

---

## Testing

New test class `PermuteReturnTest`:

- **Implicit / basic:** `Step1<T1>` template → `Step1`–`Step4` generated, `Step4` has no `join()`
- **Implicit / with bounds:** `Step1<T1 extends Comparable<T1>>` → bounds propagate through return type
- **Implicit / fixed type params survive:** `Step1<T1, R>` where `R` is fixed → `R` passes through in return type, does not expand
- **Implicit / with `@PermuteDeclr` on parameter:** `Source<T2>` → `Source<T${i+1}>` in each generated class
- **Explicit `@PermuteReturn` / APT mode:** `Object` sentinel → correct typed return in each generated class
- **Explicit `@PermuteReturn` / `when` override:** boundary omission suppressed; last class has method pointing to external type
- **Drools-style full chain:** `Join1First`–`Join5First` with `join()`, `execute()`, `filter()`; `Join5First` has no `join()`
- **Degenerate V1:** `@PermuteReturn` outside `@Permute` type → error
- **Degenerate V2:** `typeArgVarName` without `typeArgTo` → error
- **Degenerate V3:** `typeArgFrom > typeArgTo` → error
- **Degenerate V4:** return type in generated set but type args don't follow convention → warning
- **Degenerate V5:** `@PermuteReturn` present AND sentinel return type is a generated class → warning; `@PermuteReturn` takes precedence

---

## Files Created or Modified

| File | Change |
|---|---|
| `permuplate-annotations/src/main/java/.../PermuteReturn.java` | New annotation |
| `permuplate-annotations/src/main/java/.../PermuteDeclr.java` | Add `PARAMETER` to `@Target`; make `name` default `""` |
| `permuplate-core/src/main/java/.../PermuteDeclrTransformer.java` | Handle `Parameter` nodes (type-only update when `name` is empty) |
| `permuplate-maven-plugin/src/main/java/.../InlineGenerator.java` | Implicit return type inference; `@PermuteReturn` expansion; boundary omission |
| `permuplate-maven-plugin/src/main/java/.../AnnotationReader.java` | Read `@PermuteReturn` from JavaParser AST |
| `permuplate-processor/src/main/java/.../PermuteProcessor.java` | `@PermuteReturn` handling for APT mode; V1/V5 validation |
| `permuplate-tests/src/test/java/.../PermuteReturnTest.java` | New test class |
| `permuplate-tests/src/test/java/.../example/StepChain.java` | New example template |
| `README.md`, `CLAUDE.md` | Documentation updates |
