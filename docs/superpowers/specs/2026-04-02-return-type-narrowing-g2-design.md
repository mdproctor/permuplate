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

G2 introduces two mechanisms and extends one existing annotation.

> **Note:** G2 as specified covers method return types. Extends/implements clause expansion (e.g., `Join1First<T1> extends Join1Second<T1>` → `Join2First<T1,T2> extends Join2Second<T1,T2>`) uses the same implicit inference rules but is fully specified and implemented in **G3**, which depends on G2.

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
4. **Parameter type inference:** For each method parameter whose declared type references a type variable that is NOT declared on the enclosing class (i.e., appears in the return type's growing tip), the processor automatically substitutes the inferred expansion into the parameter type. No `@PermuteDeclr` annotation is required on the parameter in the common case.

### Parameter Type Inference

**The key insight:** Undeclared type variables in parameter types are the same type variables as in the return type's growing tip. When return type inference fires, the processor already knows which type variables form the growing tip (e.g., `T2` in `Step1<T1>` — declared is `T1`, so growing tip is `T2`). Any parameter type that references those same undeclared variables is automatically updated with the same substitution.

This means the common case template is annotation-free beyond `@Permute` — no `@PermuteDeclr` on parameters needed:

```java
// T2 in Source<T2> is the same undeclared type var as in Step2<T1, T2>
public Step2<T1, T2> join(Source<T2> src) { ... }
// → Source<T2> src, Source<T3> src, Source<T4> src per permutation
```

Parameter type inference is **inline mode only** — in APT mode the template must be valid compilable Java, so undeclared type variables in parameter types cause a compile error. Use `@PermuteDeclr` explicitly in APT mode.

### Template and generated output

```java
// src/main/permuplate/.../StepChain.java
@Permute(varName="i", from=1, to=4, className="Step${i}", inline=true, keepTemplate=true)
public class Step1<T1> {
    // Return type inferred — no @PermuteReturn needed.
    // Parameter type also inferred — no @PermuteDeclr needed.
    // T2 in Source<T2> is undeclared on Step1<T1>; the processor maps it to T${i+1} automatically.
    public Step2<T1, T2> join(Source<T2> src) { ... }
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

If Condition 1 or Condition 2 is not met, return type inference is skipped and the return type is left unchanged. Use `@PermuteReturn` explicitly in those cases. A warning is emitted (see Validation, rule V4) when Condition 1 holds but Condition 2 does not — this is likely a user error.

Parameter type inference similarly does not apply when the type convention is not `T${j}` (e.g., `alpha(j)` naming), or when the parameter type references a mix of declared and undeclared variables that the processor cannot unambiguously resolve. Use `@PermuteDeclr` explicitly in those cases.

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
     * Template expression for the return type. Typically a class name
     * (e.g., {@code "Step${i+1}"}), but may also be a type variable name
     * (e.g., {@code "${alpha(i)}"} to return the i-th type parameter).
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
     * Full type argument list template — a JEXL expression producing the complete
     * comma-separated type argument list. Use when type args are a mix of fixed values
     * and growing series (e.g., {@code "DS, ${typeArgList(1, i, 'T')}"}).
     * Overrides {@code typeArgVarName}/{@code typeArgFrom}/{@code typeArgTo}/{@code typeArgName}
     * when set. Mutually exclusive with {@code typeArgVarName} (see V6).
     * See <strong>G4</strong> for the full design and examples.
     */
    String typeArgs() default "";

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
| **Custom type variable naming (e.g., `alpha(j)`)** | Inference Condition 2 requires the `T${j}` convention. Using `alpha(j)` (→ `A, B, C`) or any other naming requires explicit `@PermuteReturn`. This is the canonical Drools use case — see example below. |
| **Type args don't follow `T${j}` convention** | Inference Condition 2 fails; processor cannot determine the growing series. |
| **Return type is a non-linear offset** | E.g., `Step${i+2}` (skipping one) — inference always assumes `i+1`. |
| **Leaf class must reference an external class** | `when="true"` overrides boundary omission to force generation. |
| **No type arguments needed** | Raw return type — omit `typeArgVarName`. |
| **Mixed fixed + growing type args** | The `typeArgVarName` loop produces only a uniform series. When some type args are fixed (e.g., `DS`) and others grow (e.g., `T1,...,T${i}`), use `typeArgs="DS, ${typeArgList(1, i, 'T')}"` instead. See G4 for full design. |
| **Return type is a type variable** | `className="${alpha(i)}"` returns the i-th type parameter (e.g., for typed getters like `getB()` returning `B`). `typeArgVarName` is not applicable in this case. |

### APT mode template

In APT mode the template must be valid Java. Use `Object` as the sentinel return type:

```java
// src/main/java/.../StepChain.java  (APT mode — must compile)
@Permute(varName="i", from=1, to=4, className="Step${i}")
public class Step1<T1> {
    @PermuteReturn(className="Step${i+1}",
                   typeArgVarName="j", typeArgFrom="1", typeArgTo="${i+1}", typeArgName="T${j}")
    public Object join(@PermuteDeclr(type="Source<T${i+1}>") Object src) { ... }
    // APT mode: Object sentinels required so the template compiles.
    // Parameter type inference is not available — @PermuteDeclr must be explicit.
}
```

The processor reads the source text via `getCharContent` (JavaParser), sees `@PermuteReturn`, and replaces `Object` with the fully typed return type in each generated class. `Step4.join()` is still omitted (boundary rule applies).

---

## Mechanism 3: `@PermuteDeclr` on Method Parameters (Explicit Override)

In inline mode, parameter type inference (Mechanism 1, step 4) handles the common case automatically. `@PermuteDeclr` on a parameter is required when inference does not apply:
- **APT mode** — template must compile; undeclared type variables not allowed
- **Non-`T${j}` naming** — e.g., `alpha(j)` naming; inference Condition 2 fails
- **Partial substitution** — only some type arguments in the parameter type are undeclared

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

### Usage (APT mode — inference not available)

```java
// APT mode: Object is the sentinel type (compiles); @PermuteDeclr provides the actual type
public Object join(
        @PermuteDeclr(type="Source<T${i+1}>") Object src) { ... }
//      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^  type changes; name "src" stays the same
```

For `i=1` → `Source<T2> src`; for `i=2` → `Source<T3> src`; for `i=3` → `Source<T4> src`.

---

## Mode Comparison

| Feature | APT | Maven plugin (inline) |
|---|---|---|
| Implicit return type inference | No — template must compile with `Object` sentinel | Yes — template references generated class directly |
| Implicit parameter type inference | No — template must compile; no undeclared type vars | Yes — inferred from return type's growing tip |
| `@PermuteReturn` explicit | Yes | Yes (overrides inference) |
| `@PermuteDeclr` on parameters (explicit override) | Yes (required; use `Object` sentinel) | Yes (overrides inference; needed for `alpha(j)` naming) |
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
| **V6** | `@PermuteReturn` has both `typeArgs` and `typeArgVarName` set | Error | `@PermuteReturn: typeArgs and typeArgVarName are mutually exclusive — use one or the other` |

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
        // Parameter type also inferred: T2 in Source<T2> is undeclared → Source<T${i+1}>
        // Omitted automatically on Join5First (the leaf node — no Join6First exists)
        public Join2First<T1, T2> join(Source<T2> source) { ... }

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

### Drools-style single-letter type parameters (explicit `@PermuteReturn` + `alpha(j)`)

This is the canonical example for why `@PermuteReturn` exists. The Drools RuleBuilder DSL uses `A, B, C, D, E` as fact type parameter names rather than `T1, T2, T3`. The `alpha(j)` built-in expression function (N4) converts an integer to an uppercase letter.

Implicit inference does **not** apply here — Condition 2 requires the `T${j}` naming convention, and `alpha(j)` produces `A, B, C` which the processor cannot reverse-engineer into a range. Explicit `@PermuteReturn` is required.

```java
// src/main/permuplate/.../JoinChain.java
@Permute(varName="i", from=1, to=5, className="Join${i}First", inline=true, keepTemplate=true)
public class Join1First<@PermuteTypeParam(varName="j", from="1", to="${i}", name="${alpha(j)}") A> {

    // @PermuteReturn required: alpha(j) naming does not trigger implicit inference
    @PermuteReturn(className="Join${i+1}First",
                   typeArgVarName="j", typeArgFrom="1", typeArgTo="${i+1}", typeArgName="${alpha(j)}")
    public Join2First<A, B> join(
            @PermuteDeclr(type="Source<${alpha(i+1)}>") Source<B> source) { ... }

    public void execute(Action1<A> action) { ... }
}
```

Generated output:

```java
public class Join1First<A>             { public Join2First<A, B>          join(Source<B> source) { ... } ... }
public class Join2First<A, B>          { public Join3First<A, B, C>       join(Source<C> source) { ... } ... }
public class Join3First<A, B, C>       { public Join4First<A, B, C, D>    join(Source<D> source) { ... } ... }
public class Join4First<A, B, C, D>    { public Join5First<A, B, C, D, E> join(Source<E> source) { ... } ... }
public class Join5First<A, B, C, D, E> { /* join() omitted — Join6First not in generated set */              ... }
```

Boundary omission still applies — `Join5First` has no `join()`, exactly matching the hand-written Drools pattern.

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
- Explain **why** the `T${j}` convention is required: the processor identifies the growing tip by finding type variables that are NOT declared on the enclosing class AND match a `T+number` pattern. Single-letter names like `A`, `B`, `C` have no numeric pattern — the processor cannot determine that `B` is the second in a growing series, so inference does not fire.
- List all situations where explicit annotation IS required (table from "When to use" section above)
- State clearly: boundary omission applies by default; use `when="true"` to override
- Note that `alpha(j)` from N4 can be used in `typeArgName` to produce `A, B, C` naming when explicit `@PermuteReturn` is used

### `@PermuteDeclr` Javadoc
- Add section: new `PARAMETER` target and optional `name` attribute
- Note that `name = ""` (default) changes only the type, not the parameter name
- Note that parameter type inference (no annotation needed) applies in inline mode when the type follows `T${j}` convention — `@PermuteDeclr` on a parameter is the explicit override for APT mode or non-`T${j}` naming

### README / user guide
- New section: **Return type narrowing** with the full `Step1`–`Step4` example showing generated output
- **Prominent callout box:** "The leaf class: why the last generated class has no `join()` method" — explain the boundary inference rule and why it exists. Without this, users will think Permuplate has a bug.
- **"Choosing your approach" table** — this is load-bearing user guidance; must appear prominently:

  | Goal | Mode | Type param naming | Annotation burden |
  |---|---|---|---|
  | Minimum annotations | Inline (Maven plugin) | `T1, T2, T3` (`T${j}`) | Zero — inference handles return type and parameters |
  | Custom naming (`A, B, C`) | Inline (Maven plugin) | `alpha(j)` via N4 | Explicit `@PermuteReturn` + `@PermuteDeclr` on each affected method |
  | APT mode (template must compile) | APT | Any | Explicit `@PermuteReturn` + `@PermuteDeclr`, `Object` sentinels required |

- Explain **why** `T${j}` naming enables inference but `A, B, C` does not: the processor pattern-matches on the numeric suffix to identify the growing tip. There is no such pattern in single-letter names.
- Note that `alpha(j)` produces `A, B, C` output and works fully with explicit `@PermuteReturn` — it only disables *implicit* inference, not the feature itself.
- Note the `when` attribute escape hatch for hand-written leaf classes

### CLAUDE.md non-obvious decisions table
- Add: implicit return type inference conditions (both must hold)
- Add: boundary omission rule (applies to both implicit and explicit `@PermuteReturn`)
- Add: leaf node pattern — last class in range has inferred methods omitted; mirrors Drools hand-written pattern
- Add: `T${j}` naming enables zero-annotation inference; `alpha(j)` or APT mode requires explicit `@PermuteReturn` + `@PermuteDeclr` — the processor needs a numeric pattern to identify the growing tip

---

## Testing

New test class `PermuteReturnTest`:

- **Implicit / basic:** `Step1<T1>` template → `Step1`–`Step4` generated, `Step4` has no `join()`
- **Implicit / with bounds:** `Step1<T1 extends Comparable<T1>>` → bounds propagate through return type
- **Implicit / fixed type params survive:** `Step1<T1, R>` where `R` is fixed → `R` passes through in return type, does not expand
- **Implicit / parameter type inferred (no `@PermuteDeclr`):** `Source<T2>` in `Step1<T1>` → `Source<T2>` in `Step1`, `Source<T3>` in `Step2`, `Source<T4>` in `Step3` — no annotation on parameter
- **Implicit / parameter with non-growing type (fixed):** parameter type `String` (no undeclared type vars) → left unchanged across all permutations
- **Explicit `@PermuteReturn` / APT mode:** `Object` sentinels for both return and parameter → correct typed output in each generated class
- **Explicit `@PermuteReturn` / `when` override:** boundary omission suppressed; last class has method pointing to external type
- **Drools-style `T${j}` chain:** `Join1First<T1>`–`Join5First<T1..T5>` with implicit inference; `Join5First` has no `join()`
- **Drools-style `alpha(j)` chain:** `Join1First<A>`–`Join5First<A..E>` with explicit `@PermuteReturn`; `Join5First` has no `join()` — requires N4 expression functions
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
