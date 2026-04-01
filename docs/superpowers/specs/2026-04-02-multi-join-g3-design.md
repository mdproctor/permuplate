# G3 — Multi-Join Permutation Design Spec

**Date:** 2026-04-02  
**Status:** Approved  
**Gap reference:** G3 in `docs/gap-analysis.md`  
**Depends on:** G1, G2, N4

---

## Problem

G2 handles the case where each generated class has exactly **one** `join()` method returning the next arity class (`Step${i}` → `join()` → `Step${i+1}`). The Drools RuleBuilder DSL goes further: each `Join${i}Second` class has **N join overloads**, one per valid "step size" j, as long as the resulting arity `i+j` does not exceed the maximum.

From `From1First` (arity 1) in the Drools code:

```java
public <C>    Join2First<END,DS,B,C>     join(From1First<?,DS,C> fromC)           // +1 fact
public <C,D>  Join3First<END,DS,B,C,D>   join(Join2Second<Void,DS,C,D> fromCD)    // +2 facts
public <C,D,E>Join4First<END,DS,B,C,D,E> join(Join3First<Void,DS,C,D,E> fromCDE)  // +3 facts
```

The parameter type, its type arguments, and the return type all change with both `i` (the class arity) and `j` (the step size). Additionally, `Join${i}First extends Join${i}Second<T1..T${i}>` — the extends clause also carries growing type references that must be updated.

G2's tools (`@PermuteReturn`, `@PermuteDeclr`) handle one method per class. G3 handles **multiple overloads per class** with a **two-variable** (i, j) pattern, plus extends/implements clause expansion.

**Real-world driver:** The complete Drools join chain: `Join1Second`–`Join5Second` each with up to 4 overloads (1×`From1First`, 1×`Join2Second/First`, 1×`Join3First`, 1×`Join4First`), constrained by i+j ≤ 5.

---

## Design Overview

G3 introduces three new capabilities:

| Capability | Scope | Description |
|---|---|---|
| `@PermuteMethod` | New annotation | Generates multiple overloads of one sentinel method per class using an inner loop variable |
| `typeArgList(from, to, style)` | N4 extension | JEXL function generating a comma-separated type argument list for use in `@PermuteDeclr.type` strings |
| Extends/implements expansion | G2 extension | Implicit inference applied to `extends`/`implements` type references (same rules as G2 return type inference, different syntactic position) |

All three capabilities compose with G1 (`@PermuteTypeParam`), G2 (`@PermuteReturn`, `@PermuteDeclr`), and N4 (`alpha`, `lower`).

---

## Capability 1: `@PermuteMethod`

### Annotation definition

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteMethod {
    /**
     * Inner loop variable name (e.g., {@code "j"}).
     * Available in all expressions on this method: {@code @PermuteReturn},
     * {@code @PermuteDeclr} on parameters, and the {@code when} attribute.
     */
    String varName();

    /**
     * Inner loop lower bound — literal or expression (e.g., {@code "1"}).
     * Defaults to {@code "1"}.
     */
    String from() default "1";

    /**
     * Inner loop upper bound — expression evaluated against the outer context
     * (e.g., {@code "${max - i}"}). When {@code from > to} after evaluation,
     * no overloads are generated for this permutation (empty range = silent no-op).
     */
    String to();
}
```

### Behaviour

`@PermuteMethod` is placed on a **sentinel method** alongside `@PermuteReturn` and/or `@PermuteDeclr`. For each outer permutation value `i`, the processor evaluates the inner loop `j` from `from` to `to`. For each `(i, j)` pair it generates one overload of the method — the sentinel appears once in the template but produces multiple methods in the output.

- The inner variable `j` is added to the JEXL context and is available in `@PermuteReturn`, `@PermuteDeclr.type`/`name`, and the `@PermuteReturn.when` expression.
- When `from > to` after evaluation (e.g., i=5, max=5 → `to = 5-5 = 0`), **no overloads are generated** — this is the natural leaf-node handling for the multi-join case.
- `@PermuteReturn`'s boundary omission rule still applies independently: if the evaluated return type class is not in the generated set, that specific overload is omitted. Both mechanisms are complementary.

### Empty range = leaf node for multi-join

This is the two-variable equivalent of G2's single-variable boundary omission. With `@PermuteMethod(to="${max - i}")`:

| Class | i | to | j range | Overloads generated |
|---|---|---|---|---|
| `Join1Second` | 1 | 4 | 1–4 | 4 `join()` overloads |
| `Join2Second` | 2 | 3 | 1–3 | 3 `join()` overloads |
| `Join3Second` | 3 | 2 | 1–2 | 2 `join()` overloads |
| `Join4Second` | 4 | 1 | 1–1 | 1 `join()` overload |
| `Join5Second` | 5 | 0 | empty | 0 overloads — leaf |

`Join5Second` has no `join()` method at all — exactly the Drools `Join5Second` pattern, reproduced automatically.

**Document this prominently.** Users will not expect the leaf class to have no join methods unless told explicitly. The reason (empty range when `i = max`) must be stated clearly in Javadoc and the user guide.

### Composition with `@PermuteReturn` and `@PermuteDeclr`

```java
@PermuteMethod(varName="j", from="1", to="${max - i}")
@PermuteReturn(className="Join${i+j}First",
               typeArgVarName="k", typeArgFrom="1", typeArgTo="${i+j}", typeArgName="T${k}")
public Join2First<T1, T2> join(
        @PermuteDeclr(type="Join${j}First<${typeArgList(i+1, i+j, 'T')}>")
        Join1First<T2> fromJ) { ... }
```

For `Join1Second` (i=1), max=5:
- j=1: `join(Join1First<T2>)` → `Join2First<T1, T2>`
- j=2: `join(Join2First<T2, T3>)` → `Join3First<T1, T2, T3>`
- j=3: `join(Join3First<T2, T3, T4>)` → `Join4First<T1, T2, T3, T4>`
- j=4: `join(Join4First<T2, T3, T4, T5>)` → `Join5First<T1, T2, T3, T4, T5>`

For `Join4Second` (i=4), max=5:
- j=1 only: `join(Join1First<T5>)` → `Join5First<T1, T2, T3, T4, T5>`

For `Join5Second` (i=5), max=5: no overloads (empty range).

### `max` variable

The upper bound `max` must be available in the JEXL context. Declare it via the `strings` attribute on `@Permute`:

```java
@Permute(varName="i", from=1, to=5, className="Join${i}Second",
         strings={"max=5"}, inline=true, keepTemplate=true)
```

The value of `max` must match `to`. This is a **user responsibility** — Permuplate does not validate that `strings.max == @Permute.to`. Document this constraint clearly.

---

## Capability 2: `typeArgList(from, to, style)` JEXL Function (N4 Extension)

### Purpose

`@PermuteDeclr.type` is a plain string template. When the parameter type is a generated class with a growing type argument list (e.g., `Join2First<T2, T3>`), a plain string cannot express the variable-length list without a helper function.

### Function definition

```java
typeArgList(int from, int to, String style) → String
```

Generates a comma-separated list of type argument names from `from` to `to` (inclusive), using the naming style specified:

| style | Output example (from=2, to=4) |
|---|---|
| `"T"` | `"T2, T3, T4"` |
| `"alpha"` | `"B, C, D"` |
| `"lower"` | `"b, c, d"` |

When `from > to`, returns an empty string `""` (no type args — useful for the arity-1 case).

### Implementation

Added to `PermuplateStringFunctions` in `EvaluationContext`:

```java
public static String typeArgList(int from, int to, String style) {
    if (from > to) return "";
    StringBuilder sb = new StringBuilder();
    for (int k = from; k <= to; k++) {
        if (k > from) sb.append(", ");
        switch (style) {
            case "T":     sb.append("T").append(k); break;
            case "alpha": sb.append((char)('A' + k - 1)); break;
            case "lower": sb.append((char)('a' + k - 1)); break;
            default: throw new IllegalArgumentException(
                "typeArgList: unknown style \"" + style + "\" — use \"T\", \"alpha\", or \"lower\"");
        }
    }
    return sb.toString();
}
```

### Usage in `@PermuteDeclr.type`

```java
@PermuteDeclr(type="Join${j}First<${typeArgList(i+1, i+j, 'T')}>")
Join1First<T2> fromJ
```

For i=1, j=1: `Join1First<T2>` — `typeArgList(2, 2, 'T')` → `"T2"` → `Join1First<T2>`  
For i=1, j=3: `Join3First<T2, T3, T4>` — `typeArgList(2, 4, 'T')` → `"T2, T3, T4"` → `Join3First<T2, T3, T4>`  
For i=1, j=1 with alpha: `Join1First<B>` — `typeArgList(2, 2, 'alpha')` → `"B"` → `Join1First<B>`

The `typeArgList` function is also available in `@PermuteReturn.typeArgName` and any other string attribute — though `@PermuteReturn` already has its own loop mechanism and typically doesn't need it.

---

## Capability 3: Extends/Implements Clause Expansion (G2 Extension)

### The gap in G2

G2 specified implicit return type inference for **method return types**. The Drools code also has:

```java
public class Join2First<END, DS, B, C> extends Join2Second<END, DS, B, C> { ... }
```

When `Join1First<T1> extends Join1Second<T1>` is the template, the extends clause must also be updated per permutation:

- `Join1First<T1> extends Join1Second<T1>` (i=1)
- `Join2First<T1, T2> extends Join2Second<T1, T2>` (i=2)
- `Join3First<T1, T2, T3> extends Join3Second<T1, T2, T3>` (i=3)

The same implicit inference rules from G2 apply — the `extends` type reference `Join1Second<T1>` is treated identically to a return type:

**Condition 1 — the referenced class is in the generated set:** `Join1Second` is in `{Join1Second, Join2Second, ...}` → yes.

**Condition 2 — type arguments follow the `T${j}` convention:** `T1` is declared on the template class, no growing tip here (the extends clause has exactly the same args as the class) → the type arg list is fully inferred from the class's own type params.

Actually, for the extends clause the type args mirror the class's own type params exactly. So the inference rule simplifies: **if the referenced class is in the generated set AND its type arguments are exactly the enclosing class's declared type parameters in order**, expand to match the generated class's full type parameter list.

### Implicit inference for extends/implements

The processor applies extends/implements expansion using the same mechanism as G2 return type inference, with one addition: after G1 expands the class's type parameter list, the same expansion is applied to extends/implements clauses that reference other generated classes.

**When both conditions hold:**
1. The base class in `extends X<T1..T${n}>` is a generated class
2. The type arguments exactly match the template class's declared type params

→ Automatically update the base class name to `X${i}` and expand the type arg list to match the generated class's full type params.

**Explicit `@PermuteExtends`:** For cases where implicit inference doesn't apply (non-standard naming, different type arg subset), a new annotation `@PermuteExtends` placed on the class declaration provides explicit control — mirroring `@PermuteReturn` for return types:

```java
@PermuteExtends(className="Join${i}Second",
                typeArgVarName="k", typeArgFrom="1", typeArgTo="${i}", typeArgName="T${k}")
public class Join1First<T1> extends Join1Second<T1> { ... }
```

`@PermuteExtends` can also target `implements` clauses; the `interfaceIndex` attribute (default 0) selects which implemented interface to override when there are multiple.

---

## Complete Template Example: Drools Join Chain

```java
// src/main/permuplate/.../JoinChain.java

// ── Join${i}Second family ──────────────────────────────────────────────────
@Permute(varName="i", from=1, to=5, className="Join${i}Second",
         strings={"max=5"}, inline=true, keepTemplate=true)
public class Join1Second<@PermuteTypeParam(varName="k", from="1", to="${i}", name="T${k}") T1> {

    // Generates one join() overload per valid j value.
    // Empty range when i=max → Join5Second has no join() methods (the leaf).
    @PermuteMethod(varName="j", from="1", to="${max - i}")
    @PermuteReturn(className="Join${i+j}First",
                   typeArgVarName="k", typeArgFrom="1", typeArgTo="${i+j}", typeArgName="T${k}")
    public Join2First<T1, T2> join(
            @PermuteDeclr(type="Join${j}First<${typeArgList(i+1, i+j, 'T')}>")
            Join1First<T2> fromJ) { ... }

    public void execute(Consumer1<T1> action) { ... }
}

// ── Join${i}First family (extends Join${i}Second) ─────────────────────────
// extends clause is expanded automatically by implicit inference (G2 extension)
@Permute(varName="i", from=1, to=5, className="Join${i}First",
         strings={"max=5"}, inline=true, keepTemplate=true)
public class Join1First<@PermuteTypeParam(varName="k", from="1", to="${i}", name="T${k}") T1>
        extends Join1Second<T1> {

    // filter() self-returns — return type is THIS class, not the next; no @PermuteReturn needed
    public Join1First<T1> filter(Predicate1<T1> pred) { ... }
}
```

### Generated output (excerpt)

```java
// Join1Second: 4 join() overloads
public class Join1Second<T1> {
    public Join2First<T1, T2>          join(Join1First<T2> fromJ) { ... }
    public Join3First<T1, T2, T3>      join(Join2First<T2, T3> fromJ) { ... }
    public Join4First<T1, T2, T3, T4>  join(Join3First<T2, T3, T4> fromJ) { ... }
    public Join5First<T1,T2,T3,T4,T5>  join(Join4First<T2, T3, T4, T5> fromJ) { ... }
    public void execute(Consumer1<T1> action) { ... }
}

// Join3Second: 2 join() overloads
public class Join3Second<T1, T2, T3> {
    public Join4First<T1,T2,T3,T4>         join(Join1First<T4> fromJ) { ... }
    public Join5First<T1,T2,T3,T4,T5>      join(Join2First<T4, T5> fromJ) { ... }
    public void execute(Consumer3<T1,T2,T3> action) { ... }
}

// Join5Second: no join() methods — the leaf
public class Join5Second<T1, T2, T3, T4, T5> {
    public void execute(Consumer5<T1,T2,T3,T4,T5> action) { ... }
}

// Join2First extends Join2Second (extends clause expanded automatically)
public class Join2First<T1, T2> extends Join2Second<T1, T2> {
    public Join2First<T1, T2> filter(Predicate2<T1, T2> pred) { ... }
}
```

### With `alpha(j)` naming (explicit `@PermuteReturn` required — N4)

```java
@Permute(varName="i", from=1, to=5, className="Join${i}Second",
         strings={"max=5"}, inline=true, keepTemplate=true)
public class Join1Second<@PermuteTypeParam(varName="k", from="1", to="${i}", name="${alpha(k)}") A> {

    @PermuteMethod(varName="j", from="1", to="${max - i}")
    @PermuteReturn(className="Join${i+j}First",
                   typeArgVarName="k", typeArgFrom="1", typeArgTo="${i+j}", typeArgName="${alpha(k)}")
    public Join2First<A, B> join(
            @PermuteDeclr(type="Join${j}First<${typeArgList(i+1, i+j, 'alpha')}>")
            Join1First<B> fromJ) { ... }
}
```

---

## Relationship Between G1, G2, G3

| Capability | G1 | G2 | G3 |
|---|---|---|---|
| Class type parameter expansion | ✓ | — | — |
| Single `join()` return type narrowing | — | ✓ | — |
| Extends/implements type reference expansion | — | ✓ (extension) | Depends on |
| Multiple `join()` overloads per class | — | — | ✓ |
| Parameter type = generated class with growing type args | — | — | ✓ |
| Two-variable boundary (i+j ≤ max) | — | — | ✓ |
| Expression functions (`alpha`, `lower`, `typeArgList`) | — | — | ✓ (N4) |

G3 builds directly on G1 (for type parameter expansion on the class declaration) and G2 (for `@PermuteReturn` and `@PermuteDeclr`). It cannot be implemented before G1 and G2.

---

## Validation Rules

| Rule | Condition | Severity | Message |
|---|---|---|---|
| **M1** | `@PermuteMethod` on a method outside a `@Permute`-annotated type | Error | `@PermuteMethod found outside a @Permute-annotated type` |
| **M2** | `@PermuteMethod` present but no `@PermuteReturn` — all generated overloads would have identical signatures | Warning | `@PermuteMethod without @PermuteReturn: all generated overloads have the same signature — this will produce a compile error` |
| **M3** | `typeArgList` called with unknown style string | Error (at generation time) | `typeArgList: unknown style "X" — use "T", "alpha", or "lower"` |
| **M4** | `@PermuteExtends` on a class that is not `@Permute`-annotated | Error | `@PermuteExtends found outside a @Permute-annotated type` |
| **M5** | `strings` declares `max` with a value that doesn't match `@Permute.to` | Warning | `strings key "max" value N does not match @Permute to=M — boundary expression "${max - i}" will be incorrect` |

---

## Documentation Requirements

### `@PermuteMethod` Javadoc
- Explain that it generates **overloads** (multiple methods per class), not variants of the class
- State clearly that an empty range (from > to) silently generates **no methods** — this is the leaf-node mechanism
- Document the two-variable context (outer `i`, inner `j`) and that both are available in `@PermuteReturn`, `@PermuteDeclr`, and `when`
- Explain the `max` pattern: declare via `strings={"max=N"}` matching `@Permute.to`

### `typeArgList` Javadoc
- Document all three styles (`"T"`, `"alpha"`, `"lower"`) with examples
- Document the `from > to` → empty string behaviour
- Show in combination with `@PermuteDeclr.type` for growing parameter type args

### `@PermuteExtends` Javadoc
- Explain that implicit inference handles the common case (same T${j} convention, same type args as enclosing class)
- State when explicit is required (non-standard naming, subset of type args in extends clause)

### README / user guide
- New section: **Multi-join permutation** — the full Drools join chain as the worked example
- **Callout box:** "The leaf class in multi-join: why `Join5Second` has no `join()` methods" — the empty range rule is subtle
- Explain the G1 → G2 → G3 progression: class type params, single return narrowing, multi-overload generation
- The `strings={"max=N"}` pattern for expressing the maximum arity as a JEXL variable

### CLAUDE.md non-obvious decisions table
- Add: `@PermuteMethod` empty range = no-op (silent, not an error) — leaf-node mechanism for multi-join
- Add: `typeArgList(from, to, style)` returns `""` when `from > to` (arity-1 parameter types)
- Add: `strings={"max=N"}` must match `@Permute.to` — not validated automatically

---

## Testing

New test class `PermuteMethodTest`:

- **Basic 2×2:** `@PermuteMethod(to="${max-i}")` with max=3 → `Join1Second` gets 2 overloads, `Join2Second` gets 1, `Join3Second` gets 0 (leaf)
- **Full Drools chain `T${j}` naming:** `Join1Second`–`Join5Second` with correct overloads; `Join5Second` has no `join()`; `Join1First extends Join1Second` extends clause expanded correctly
- **Full Drools chain `alpha(j)` naming:** same but with `alpha`/`typeArgList(..., 'alpha')`; requires explicit `@PermuteReturn` and `@PermuteExtends`
- **`typeArgList` unit:** `typeArgList(2, 4, "T")` → `"T2, T3, T4"`, `typeArgList(2, 2, "alpha")` → `"B"`, `typeArgList(3, 2, "T")` → `""` (empty range)
- **Extends clause implicit expansion:** `Join1First<T1> extends Join1Second<T1>` → `Join3First<T1,T2,T3> extends Join3Second<T1,T2,T3>`
- **`@PermuteExtends` explicit:** non-standard naming; confirms correct extends clause in each generated class
- **Degenerate M1:** `@PermuteMethod` outside `@Permute` type → error
- **Degenerate M2:** `@PermuteMethod` without `@PermuteReturn` → warning (identical signatures)
- **Degenerate M5:** `strings={"max=3"}` with `@Permute(to=5)` → warning

---

## Files Created or Modified

| File | Change |
|---|---|
| `permuplate-annotations/src/main/java/.../PermuteMethod.java` | New annotation |
| `permuplate-annotations/src/main/java/.../PermuteExtends.java` | New annotation |
| `permuplate-core/src/main/java/.../EvaluationContext.java` | Add `typeArgList` to `PermuplateStringFunctions` |
| `permuplate-core/src/main/java/.../PermuteDeclrTransformer.java` | Handle extends/implements clause expansion |
| `permuplate-maven-plugin/src/main/java/.../InlineGenerator.java` | `@PermuteMethod` inner loop; extends/implements expansion; `@PermuteExtends` handling |
| `permuplate-maven-plugin/src/main/java/.../AnnotationReader.java` | Read `@PermuteMethod`, `@PermuteExtends` from JavaParser AST |
| `permuplate-processor/src/main/java/.../PermuteProcessor.java` | M1/M4 validation; `@PermuteMethod` APT path |
| `permuplate-tests/src/test/java/.../PermuteMethodTest.java` | New test class |
| `permuplate-tests/src/test/java/.../ExpressionFunctionsTest.java` | Add `typeArgList` tests |
| `README.md`, `CLAUDE.md` | Documentation updates |
