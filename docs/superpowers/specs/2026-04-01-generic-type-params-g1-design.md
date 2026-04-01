# G1 — Generic Type Parameter Arity Design Spec

**Date:** 2026-04-01  
**Status:** Approved  
**Gap reference:** G1 in `docs/gap-analysis.md`

---

## Problem

Permuplate can generate interfaces and classes with expanding parameter lists (e.g. `void test(Object fact1, Object fact2, Object fact3)`) but cannot generate the matching type parameter list (`<T1, T2, T3>`). The result is type-erased APIs — all facts are `Object`. For type-safe DSLs (e.g. rule engine functional interfaces like `Consumer2<Context, Person>`, `Consumer3<Context, Person, Account>`), full type safety requires generated type parameters.

**Real-world driver:** The Drools RuleBuilder POC hand-writes `Consumer1`–`Consumer4`, `Predicate2`–`Predicate6`, `Function1`, `Function2` because there is no way to generate the `<A, B>`, `<A, B, C>` type parameter lists. G1 eliminates that boilerplate.

---

## Design

### New annotation: `@PermuteTypeParam`

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE_PARAMETER)
public @interface PermuteTypeParam {
    /** Inner loop variable name (e.g. {@code "j"}). */
    String varName();

    /** Inner lower bound — literal or expression (e.g. {@code "1"}). */
    String from();

    /** Inner upper bound — expression evaluated against outer context (e.g. {@code "${i}"}). */
    String to();

    /** Generated type parameter name template (e.g. {@code "T${j}"}). */
    String name();
}
```

Placed on a **sentinel type parameter**. Expands it into a sequence of type parameters, exactly mirroring `@PermuteParam`'s expansion of sentinel method parameters.

**Bounds** are read directly from the sentinel's JavaParser AST — no `bound` attribute needed. When generating `T2`, `T3`, etc., the processor substitutes the sentinel name within the bound (e.g. `T1 extends Comparable<T1>` → `T2 extends Comparable<T2>`).

---

### Implicit expansion (no `@PermuteTypeParam` needed)

When `@PermuteParam` generates parameters of type `T${j}` and the sentinel parameter's declared type (`T1`) is a type parameter on the enclosing **method or class**, those type parameters are automatically expanded to match — same `j` loop, same bounds propagation.

```java
// No @PermuteTypeParam — implicit expansion
public interface Condition1<T1 extends Comparable<T1>> {
    boolean test(
        @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="fact${j}") T1 fact1);
}
// For i=3 → Condition3<T1 extends Comparable<T1>, T2 extends Comparable<T2>, T3 extends Comparable<T3>>
//   with test(T1 fact1, T2 fact2, T3 fact3)
```

**`@PermuteTypeParam` is not needed in the common case.** Use it only when expanding type parameters *without* a corresponding `@PermuteParam` — typically phantom types for compile-time DSL safety.

```java
// Explicit — phantom type, no method parameters to expand
class Step1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1> { }
// → Step2<T1, T2>, Step3<T1, T2, T3>
```

---

### Return type restriction

When type parameters are being expanded (either implicitly or explicitly), the return type must **not** reference the expanding type parameter. The result would be ambiguous across permutations.

**Blocked — compile error + IDE error:**

```java
// BLOCKED: return type T1 references the expanding type parameter
public interface Mapper1<T1> {
    T1 map(
        @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="input${j}") T1 input1);
}
// Error: @PermuteParam implicit type expansion: return type "T1" references an expanding
// type parameter — ambiguous across permutations. Use Object or a fixed container type.
```

Fixed type parameters not involved in the expansion (e.g. `R` in `Transformer1<T1, R>`) are fine — they pass through unchanged.

**Workaround:** use `Object` or a fixed container:

```java
// VALID: return type does not reference expanding type parameter
List<Object> gather(
    @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="input${j}") T1 input1);
```

This restriction is enforced at compile time (APT + Maven plugin) and shown as an IDE error (IntelliJ inspection + VS Code diagnostic).

---

### Validation rules

| Rule | Condition | Severity | Error message |
|---|---|---|---|
| **R1** | Return type references an expanding type parameter | Error | `@PermuteParam implicit type expansion: return type "T1" references an expanding type parameter — ambiguous across permutations. Use Object or a fixed container type.` |
| **R2** | `@PermuteTypeParam` and `@PermuteParam` both expand the same type parameter | Error | `Duplicate expansion: T1 is already declared for expansion via @PermuteTypeParam; @PermuteParam with type="T${j}" would expand it again.` |
| **R3** | `@PermuteTypeParam` `name` prefix does not match sentinel type parameter name | Error | `@PermuteTypeParam name literal part "X" is not a prefix of type parameter "T1"` |
| **R4** | `from > to` on `@PermuteTypeParam` | Error | `@PermuteTypeParam has invalid range: from=N is greater than to=M` |
| **R5** | `@PermuteTypeParam` found outside a `@Permute`-annotated type | Error | `@PermuteTypeParam found outside a @Permute-annotated type — it can only appear on type parameters of classes or interfaces annotated with @Permute.` |

All errors are reported with the most precise source location available (attribute-level where possible, element-level minimum), per the project's error reporting standard.

---

### How bounds are propagated

1. Read the sentinel type parameter's bound from the JavaParser AST (e.g. `T1 extends Comparable<T1>`)
2. For each generated type parameter `T${j}` (j = 2, 3, ...):  
   - Substitute every occurrence of the sentinel name within the bound text: `T1 → T${j}`  
   - Result: `T2 extends Comparable<T2>`, `T3 extends Comparable<T3>`
3. If the sentinel has no bound, generated parameters also have no bound

This is the same substitution mechanism used by `@PermuteDeclr` for renaming identifiers in method bodies.

---

## Examples

### Drools-style functional interface (implicit expansion)

```java
// src/main/permuplate/.../RuleInterfaces.java
public class RuleInterfaces {

    @Permute(varName="i", from=2, to=5, className="Consumer${i}", inline=true, keepTemplate=true)
    public interface Consumer1<A> {
        void accept(
            @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="arg${j}") A arg1);
    }

    @Permute(varName="i", from=2, to=5, className="Predicate${i}", inline=true, keepTemplate=true)
    public interface Predicate1<A> {
        boolean test(
            @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="arg${j}") A arg1);
    }
}
```

Generated output:

```java
public class RuleInterfaces {
    public interface Consumer1<T1>          { void accept(T1 arg1); }
    public interface Consumer2<T1, T2>      { void accept(T1 arg1, T2 arg2); }
    public interface Consumer3<T1, T2, T3>  { void accept(T1 arg1, T2 arg2, T3 arg3); }
    // ...

    public interface Predicate1<T1>          { boolean test(T1 arg1); }
    public interface Predicate2<T1, T2>      { boolean test(T1 arg1, T2 arg2); }
    // ...
}
```

### Phantom type (explicit `@PermuteTypeParam`)

```java
// Compile-time-only type tag — no method parameters
@Permute(varName="i", from=2, to=5, className="Step${i}", inline=true, keepTemplate=true)
public static class Step1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1> {
    private final Object[] facts;
    // Runtime: always Object[]; T1..TN exist only at compile time for type safety
}
```

Generated: `Step2<T1, T2>`, `Step3<T1, T2, T3>`, etc.

### With bounds

```java
public interface SortedCondition1<T1 extends Comparable<T1>> {
    boolean test(
        @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="fact${j}") T1 fact1);
}
// For i=3 → SortedCondition3<T1 extends Comparable<T1>, T2 extends Comparable<T2>, T3 extends Comparable<T3>>
//   with test(T1 fact1, T2 fact2, T3 fact3)
```

---

## Testing

New test class `PermuteTypeParamTest`:

- **Implicit / no bounds:** `Condition1<T1>` with `@PermuteParam(type="T${j}")` → `Condition3<T1, T2, T3>` with correct method signature
- **Implicit / with bounds:** `Condition1<T1 extends Comparable<T1>>` → `Condition3<T1 extends Comparable<T1>, T2 extends Comparable<T2>, T3 extends Comparable<T3>>`
- **Implicit / method-level type params:** `<T1> void process(...)` → `<T1, T2, T3> void process(...)`
- **Implicit / fixed type params survive:** `Transformer1<T1, R>` where R is fixed → `Transformer3<T1, T2, T3, R>`, R unchanged
- **Explicit / phantom type class:** `Step1<@PermuteTypeParam T1>` with no `@PermuteParam` → `Step2<T1, T2>`, `Step3<T1, T2, T3>`
- **Explicit / empty interface:** `Tag1<@PermuteTypeParam T1>` with no methods → `Tag2<T1, T2>`, etc.
- **Degenerate R1:** return type references expanding param → compile error
- **Degenerate R2:** `@PermuteTypeParam` + implicit conflict → error
- **Degenerate R3:** prefix mismatch → error
- **Degenerate R4:** from > to → error
- **Drools-style `RuleInterfaces`:** two inline templates (`Consumer1–5`, `Predicate1–5`) in one parent class — covers S1 and S2 from the soft gaps simultaneously

---

## Documentation

- **`@PermuteTypeParam` Javadoc:** states it is not normally needed; phantom type example; return type restriction stated explicitly
- **`@PermuteParam` Javadoc:** add paragraph on implicit type parameter expansion and the return type restriction
- **README:** new row in annotations table; note under `@PermuteParam` section; inline example in the `RuleInterfaces` proposal
- **CLAUDE.md:** add to non-obvious decisions table: implicit expansion rule, return type restriction
- **`docs/gap-analysis.md`:** mark G1 as resolved

---

## Files created or modified

| File | Change |
|---|---|
| `permuplate-annotations/src/main/java/.../PermuteTypeParam.java` | New annotation |
| `permuplate-annotations/src/main/java/.../Permute.java` | (no change — `@PermuteTypeParam` is standalone) |
| `permuplate-core/src/main/java/.../PermuteDeclrTransformer.java` | Extend `transformFields` / `transformForEachVars` / `transformConstructorParams` to also handle type parameter expansion |
| `permuplate-processor/src/main/java/.../PermuteProcessor.java` | Add R1 return type check; add R5 placement check |
| `permuplate-maven-plugin/src/main/java/.../InlineGenerator.java` | Handle `@PermuteTypeParam` expansion in inline mode |
| `permuplate-maven-plugin/src/main/java/.../AnnotationReader.java` | Read `@PermuteTypeParam` from JavaParser AST |
| `permuplate-tests/src/test/java/.../PermuteTypeParamTest.java` | New test class |
| `permuplate-tests/src/test/java/.../example/RuleInterfaces.java` | New template (covers G1 + S1 + S2 soft gaps) |
| `permuplate-mvn-examples/src/main/permuplate/.../RuleInterfaces.java` | Updated example |
| `README.md`, `OVERVIEW.md`, `CLAUDE.md` | Documentation updates |
