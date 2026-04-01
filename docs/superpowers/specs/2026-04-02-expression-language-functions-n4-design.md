# N4 — Expression Language Functions Design Spec

**Date:** 2026-04-02  
**Status:** Approved  
**Gap reference:** N4 in `docs/gap-analysis.md`

---

## Problem

Permuplate's string attributes are evaluated by Apache Commons JEXL3, but only integer arithmetic and string concatenation are available out of the box. Users who want to generate names using conventions other than `T${j}` integers — such as single uppercase letters `A, B, C, D` as used in the Drools RuleBuilder DSL — have no way to convert an integer loop variable into a letter.

This forces users into the `T${j}` naming convention even when their domain convention differs, and is the reason why the Drools-style `Join1First<A>`, `Join2First<A, B>`, etc. pattern requires explicit `@PermuteReturn` rather than implicit inference. Without expression functions there is no way to express `${alpha(j)}` → `A`, `B`, `C`, ...

**Real-world driver:** The Drools DSL uses single uppercase letters as fact type parameter names (e.g., `Join3First<A, B, C>`). N4 makes this pattern fully expressible in Permuplate templates.

---

## Design

Two built-in functions are registered in `EvaluationContext` and available in **every JEXL expression** throughout Permuplate:

| Function | Description | Range | Examples |
|---|---|---|---|
| `alpha(n)` | Integer → uppercase letter, 1-indexed | 1–26 | `alpha(1)` → `"A"`, `alpha(2)` → `"B"`, `alpha(26)` → `"Z"` |
| `lower(n)` | Integer → lowercase letter, 1-indexed | 1–26 | `lower(1)` → `"a"`, `lower(2)` → `"b"`, `lower(26)` → `"z"` |

Functions are available as top-level names (no namespace prefix). Values outside 1–26 throw `IllegalArgumentException` at generation time with a clear message: `alpha(n): n must be between 1 and 26, got N`.

### Availability

Available in all attributes evaluated by `EvaluationContext.evaluate()` and `EvaluationContext.evaluateInt()`:

- `@Permute.className`, `@Permute.strings` values
- `@PermuteDeclr.type`, `@PermuteDeclr.name`
- `@PermuteParam.type`, `@PermuteParam.name`, `@PermuteParam.from`, `@PermuteParam.to`
- `@PermuteTypeParam.name`, `@PermuteTypeParam.from`, `@PermuteTypeParam.to` (G1)
- `@PermuteReturn.className`, `@PermuteReturn.typeArgName`, `@PermuteReturn.typeArgFrom`, `@PermuteReturn.typeArgTo` (G2)

### Implementation

A nested static class in `EvaluationContext` provides the two functions:

```java
public static final class PermuplateStringFunctions {
    public static String alpha(int n) {
        if (n < 1 || n > 26)
            throw new IllegalArgumentException(
                "alpha(n): n must be between 1 and 26, got " + n);
        return String.valueOf((char) ('A' + n - 1));
    }

    public static String lower(int n) {
        if (n < 1 || n > 26)
            throw new IllegalArgumentException(
                "lower(n): n must be between 1 and 26, got " + n);
        return String.valueOf((char) ('a' + n - 1));
    }
}
```

Registered in the JEXL engine at `EvaluationContext` construction time via `JexlBuilder.namespaces()` with the empty string as the key, making the functions available without a namespace prefix:

```java
JexlEngine jexl = new JexlBuilder()
    .namespaces(Map.of("", PermuplateStringFunctions.class))
    .create();
```

---

## Primary Use Case: Drools-style single-letter type parameter names

This feature enables generating Drools-style `A, B, C, D, E` type parameter names in combination with G1 (`@PermuteTypeParam`) and G2 (`@PermuteReturn`).

Explicit `@PermuteReturn` is required — implicit inference only triggers on the `T${j}` naming convention. The Drools example is the canonical case documenting *why* explicit `@PermuteReturn` exists.

```java
// src/main/permuplate/.../JoinChain.java
@Permute(varName="i", from=1, to=5, className="Join${i}First", inline=true, keepTemplate=true)
public class Join1First<@PermuteTypeParam(varName="j", from="1", to="${i}", name="${alpha(j)}") A> {

    // Explicit @PermuteReturn required: alpha(j) naming does not trigger implicit inference
    // (implicit inference requires the T${j} naming convention — see G2 spec)
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

---

## Additional Examples

### `alpha` in `@Permute.className`

```java
@Permute(varName="i", from=1, to=5, className="Step${alpha(i)}")
public class StepA { ... }
// Generates: StepA, StepB, StepC, StepD, StepE
```

### `lower` in `@PermuteParam.name`

```java
@PermuteParam(varName="j", from="1", to="${i}", type="Object", name="fact${lower(j)}")
Object facta;
// i=3 → facta, factb, factc
```

### `alpha` in `@PermuteTypeParam.name` (standalone, no `@PermuteReturn`)

```java
@Permute(varName="i", from=1, to=4, className="Tuple${i}")
public class Tuple1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="${alpha(j)}") A> {
    // A, B, C, D — no join chain, just type-safe tuples
}
// Generates: Tuple1<A>, Tuple2<A, B>, Tuple3<A, B, C>, Tuple4<A, B, C, D>
```

---

## Testing

New test class `ExpressionFunctionsTest`:

- **`alpha` correctness:** `alpha(1)` → `"A"`, `alpha(13)` → `"M"`, `alpha(26)` → `"Z"`
- **`lower` correctness:** `lower(1)` → `"a"`, `lower(13)` → `"m"`, `lower(26)` → `"z"`
- **Out of range:** `alpha(0)` → error; `alpha(27)` → error; `lower(0)` → error; `lower(27)` → error
- **`alpha` in `className`:** `@Permute(className="Step${alpha(i)}")` → `StepA`, `StepB`, ..., `StepE`
- **`lower` in `@PermuteParam.name`:** `name="fact${lower(j)}"` → `facta`, `factb`, `factc`
- **`alpha` in `@PermuteTypeParam.name`:** `Tuple1<A>` → `Tuple4<A, B, C, D>`
- **`alpha` in `@PermuteReturn.typeArgName`:** return type type args `A, B, C` from `${alpha(j)}`
- **Full Drools chain:** `Join1First<A>` → `Join5First<A, B, C, D, E>`; `Join5First` has no `join()`

---

## Documentation

### `EvaluationContext` Javadoc
- Document `PermuplateStringFunctions` with both functions: signature, parameter range, examples, and error condition
- State that both functions are available in all string attributes throughout Permuplate

### README
- New subsection in the expression language / advanced section: **Built-in expression functions**
- Table: `alpha(n)`, `lower(n)` — description and examples
- Reference to the G2 Drools example as the canonical use case

### CLAUDE.md non-obvious decisions table
- Add: `alpha(n)` and `lower(n)` are top-level JEXL functions (registered via empty-string namespace key); range 1–26; out-of-range throws at generation time, not validation time

---

## Files Created or Modified

| File | Change |
|---|---|
| `permuplate-core/src/main/java/.../EvaluationContext.java` | Add `PermuplateStringFunctions` class; register via JEXL `namespaces` |
| `permuplate-tests/src/test/java/.../ExpressionFunctionsTest.java` | New test class |
| `README.md` | Built-in expression functions section |
| `CLAUDE.md` | Non-obvious decisions entry |
