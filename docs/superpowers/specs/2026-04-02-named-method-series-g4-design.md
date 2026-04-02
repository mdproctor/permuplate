# G4 — Named Method Series and Method-Level Type Parameter Expansion

**Date:** 2026-04-02  
**Status:** Approved  
**Gap reference:** N2 (method name templating) + G1/G2/G3 extensions  
**Depends on:** G1, G2, G3, N4

---

## Problem

G3's `@PermuteMethod` generates multiple overloads of the **same method name** differing in parameter types (e.g., multiple `join()` overloads). The OOPath use case requires a qualitatively different pattern: **multiple methods with different names** per class, each with growing method-level type parameters and complex return types referencing both the outer arity variable `i` and the inner depth variable `k`.

From `Join2Second` (arity 2) in the Drools RuleBuilder:

```java
<PB>           Path2<Join3First<..., Tuple2<C,PB>>,   Tuple1<C>,       C, PB>         path2()
<PB,PC>        Path3<Join3First<..., Tuple3<C,PB,PC>>, Tuple2<C,PB>,   C, PB, PC>     path3()
<PB,PC,PD>     Path4<Join3First<..., Tuple4<C,PB,PC,PD>>, Tuple4<...>, C, PB, PC, PD> path4()
<PB,PC,PD,PE>  Path4<Join3First<..., Tuple5<C,...,PE>>, Tuple5<...>, PB, PC, PD, PE>  path5(fn2, flt2)
<PB,PC,PD,PE,PF> Path6<Join3First<..., Tuple6<C,...,PF>>, Tuple6<...>, C, PB,...,PF>  path6()
```

Three things vary with the depth variable `k`:
1. The **method name** (`path2`, `path3`, ...)
2. The **method-level type parameters** (`<PB>`, `<PB,PC>`, ...)
3. The **return type's type arguments** — a mix of fixed values (`END`, `T`) and growing series that reference both `i` (outer arity) and `k` (path depth)

None of these three are expressible with G1–G3 as currently designed. G4 introduces three targeted extensions.

---

## Design Overview

G4 extends three existing mechanisms:

| Extension | Annotation changed | What it adds |
|---|---|---|
| `@PermuteMethod.name` | `@PermuteMethod` (G3) | Optional method name template — each overload gets a distinct name |
| Method-level `@PermuteTypeParam` | `@PermuteTypeParam` (G1) | Expands method type parameters driven by the `@PermuteMethod` inner variable |
| `@PermuteReturn.typeArgs` | `@PermuteReturn` (G2) | Full JEXL template for the complete type argument list — handles mixed fixed + growing args |

All three compose with each other and with existing G1/G2/G3 mechanisms.

---

## Extension 1: `@PermuteMethod.name`

### Attribute addition

```java
public @interface PermuteMethod {
    String varName();
    String from() default "1";
    String to() default "";

    /**
     * Optional method name template (e.g., {@code "path${k}"}). When set, each
     * generated overload has a distinct name produced by evaluating this expression
     * with the current inner loop value. When empty (the default), all overloads
     * share the sentinel method's name — the standard join() overload pattern.
     *
     * <p>The sentinel method's own name is used only as a template anchor; it does
     * not appear in the output when {@code name} is set.
     *
     * <p>APT and Maven plugin: both supported. No inference difference.
     */
    String name() default "";
}
```

### Behaviour

When `name` is set:
- Each `(i, k)` pair produces a method named `evaluate(name, context)` — e.g., `"path${k}"` → `path2`, `path3`, `path4`, ...
- `@PermuteReturn`, `@PermuteDeclr`, and `@PermuteTypeParam` on the sentinel method all apply per `(i, k)` as before
- Boundary omission (`@PermuteReturn.when`) still applies per overload
- When `from > to` (empty range), no methods are generated — same leaf-node behaviour as unnamed `@PermuteMethod`

When `name` is empty (default): existing behaviour — all overloads share the sentinel method name.

### Validation

New rule added to G3's validation table:

| Rule | Condition | Severity | Message |
|---|---|---|---|
| **M8** | `@PermuteMethod.name` evaluates to the same name for two different `k` values | Warning | `@PermuteMethod name="${name}" produces duplicate method name "${evaluated}" for k=N and k=M — this will produce a compile error` |

---

## Extension 2: Method-Level `@PermuteTypeParam`

### What changes

`@PermuteTypeParam` already has `@Target(ElementType.TYPE_PARAMETER)`, which covers type parameters on both **classes/interfaces** and **methods/constructors** in Java. No annotation change is needed — only the implementation needs extending to handle the method-level case.

When `@PermuteTypeParam` appears on a **method** type parameter (rather than a class type parameter):

- It is driven by the enclosing `@PermuteMethod`'s inner variable (e.g., `k`) — that variable is available in the `from` and `to` expressions
- The expanded type parameters are available within `@PermuteReturn`, `@PermuteDeclr` on the method's parameters, and within the method body for that overload
- The same bounds propagation rules apply as for class-level expansion

### Example

```java
@PermuteMethod(varName="k", from=2, to=6, name="path${k}")
public <@PermuteTypeParam(varName="j", from="1", to="${k-1}", name="${alpha(j+1)}") PB>
       Path2<...> path2() { ... }
```

For k=2: method type params = `<PB>` (j=1: alpha(2) = B → PB)  
For k=3: `<PB, PC>` (j=1,2: alpha(2)=B, alpha(3)=C)  
For k=4: `<PB, PC, PD>`  
...

### `varName` scoping

The method-level `@PermuteTypeParam` introduces a third-level loop variable (`j`), inside the `@PermuteMethod` variable (`k`), inside the outer `@Permute` variable (`i`). All three are available in JEXL expressions on the method for that overload:
- `i` — outer class permutation (which `JoinNSecond` class we're generating)
- `k` — path depth (which `path${k}` method we're generating)
- `j` — method type parameter index (which `P${alpha(j+1)}` type param we're generating)

### Implicit inference (Maven plugin inline mode)

When `@PermuteMethod(name="path${k}")` is present and the return type or parameter types reference the sentinel method type parameter in the growing tip — same `T${j}` convention rules as G1/G2 — `@PermuteTypeParam` can be omitted and is inferred automatically. This requires the method type param name to follow a detectable numeric pattern (`PB1`, `T1`, etc.) rather than pure alpha (`PB`). For the `alpha(j)` path case, explicit `@PermuteTypeParam` is required.

### APT vs Maven plugin

| Feature | Maven plugin | APT |
|---|---|---|
| Method-level `@PermuteTypeParam` (explicit) | ✓ | ✓ |
| Implicit inference (from return type's growing tip) | ✓ | ✗ — template must compile; use explicit `@PermuteTypeParam` |

---

## Extension 3: `@PermuteReturn.typeArgs`

### Attribute addition

```java
public @interface PermuteReturn {
    String className();
    String typeArgVarName() default "";
    String typeArgFrom() default "1";
    String typeArgTo() default "";
    String typeArgName() default "";
    String when() default "";

    /**
     * Full type argument list — a JEXL expression producing the complete
     * comma-separated type argument list for the return type. Use when the type
     * arguments are a mix of fixed values and growing series that cannot be expressed
     * with the loop-based {@code typeArgVarName}/{@code typeArgFrom}/{@code typeArgTo}/
     * {@code typeArgName} mechanism.
     *
     * <p>All permutation variables ({@code i}, {@code k}, etc.) and N4 functions
     * ({@code alpha}, {@code lower}, {@code typeArgList}) are available.
     *
     * <p>Examples:
     * <pre>{@code
     * // Fixed DS prefix + growing T series:
     * typeArgs = "DS, ${typeArgList(1, i, 'T')}"
     *
     * // Join result wrapping a Tuple — references both i and k:
     * typeArgs = "Join${i+1}First<END, DS, ${typeArgList(1, i, 'T')}, Tuple${k}<${typeArgList(i, k, 'alpha')}>>, Tuple${k-1}<${typeArgList(i, k-1, 'alpha')}>, ${typeArgList(i, k, 'alpha')}"
     * }</pre>
     *
     * <p>When set, overrides {@code typeArgVarName}/{@code typeArgFrom}/{@code typeArgTo}/
     * {@code typeArgName}. Compatible with all modes (APT and Maven plugin).
     */
    String typeArgs() default "";
}
```

### When to use `typeArgs`

| Situation | Why `typeArgs` is needed |
|---|---|
| Fixed prefix + growing series | `"DS, ${typeArgList(1, i, 'T')}"` — `typeArgVarName` loop can only produce uniform series |
| Multiple growing segments | Both `i` and `k` contribute distinct type arg groups |
| Descending return type chain | `Path${i-1}` with type args that are a suffix of the class's type params |
| Mixed naming conventions | Some type args use `alpha(j)`, others use `T${j}` |

### Interaction with existing attributes

`typeArgs` and `typeArgVarName` are mutually exclusive — a validation error is raised if both are set (new rule V6).

### `typeArgs` also solves the `extensionPoint()` gap

Previously identified (during the big-picture review) — `extensionPoint()` returning `RuleExtendsPoint${i+1}<DS, T1,...,T${i}>` cannot be expressed with the loop-based mechanism because `DS` is a fixed prefix. With `typeArgs`:

```java
@PermuteReturn(className="RuleExtendsPoint${i+1}",
               typeArgs="DS, ${typeArgList(1, i, 'T')}")
public RuleExtendsPoint2<DS, T1> extensionPoint() { ... }
```

---

## Path Use Case — Complete Template

### `Path${k}` family (in `RuleOOPathBuilder.java`)

```java
// Path2 is hand-written (the leaf — its path() returns END directly, not another Path class).
// Path3..Path6 are generated:
@Permute(varName="i", from=3, to=6, className="Path${i}", inline=true, keepTemplate=true)
public static class Path3<END, T extends BaseTuple,
        @PermuteTypeParam(varName="j", from="1", to="${i-2}", name="${alpha(j+1)}") A,
        B, C> extends BasePath<END, A, B, T> {

    // Descending return type: Path3.path() → Path2, Path4.path() → Path3, etc.
    // typeArgs drops the leading type param (A) — the remaining are B, C, ...
    @PermuteReturn(className="Path${i-1}",
                   typeArgs="END, T, ${typeArgList(2, i-2, 'alpha')}")
    public Path2<END, T, B, C> path(Function2<PathContext<T>, A, ?> fn2,
                                     Predicate2<PathContext<T>, B> flt2) { ... }
}
```

### `path${k}()` methods on `JoinNSecond`

```java
@Permute(varName="i", from=1, to=5, className="Join${i}Second", inline=true, keepTemplate=true)
public class Join1Second<@PermuteTypeParam(varName="k", from="1", to="${i}", name="T${k}") T1> {

    // join() overloads — same name, vary by step size j (G3 @PermuteMethod without name)
    @PermuteMethod(varName="j")
    public Join2First<T1, T2> join(Join1First<T2> fromJ) { ... }

    // path${k}() methods — different names, vary by path depth k
    // Method type params: k-1 of them (PB for k=2, PB+PC for k=3, etc.)
    // Return type references both i (arity) and k (depth) — requires typeArgs
    @PermuteMethod(varName="k", from=2, to=6, name="path${k}")
    @PermuteReturn(className="Path${k-1}",
                   typeArgs="Join${i+1}First<END, DS, ${typeArgList(1, i, 'T')}, Tuple${k}<T${i}, ${typeArgList(2, k, 'alpha')}>>, Tuple${k-1}<T${i}, ${typeArgList(2, k-1, 'alpha')}>, T${i}, ${typeArgList(2, k, 'alpha')}")
    public <@PermuteTypeParam(varName="j", from="1", to="${k-1}", name="${alpha(j+1)}") PB>
           Path2<Join2First<END, DS, T1, Tuple2<T1, PB>>, Tuple1<T1>, T1, PB> path2() { ... }

    public void execute(Consumer1<T1> action) { ... }
}
```

### APT mode equivalent (all explicit)

```java
@Permute(varName="i", from=1, to=5, className="Join${i}Second",
         strings={"max=5", "maxPath=6"})
public class Join1Second<@PermuteTypeParam(varName="k", from="1", to="${i}", name="T${k}") T1> {

    @PermuteMethod(varName="j", from="1", to="${max - i}")
    @PermuteReturn(className="Join${i+j}First",
                   typeArgVarName="k", typeArgFrom="1", typeArgTo="${i+j}", typeArgName="T${k}")
    public Object join(@PermuteDeclr(type="Join${j}First<${typeArgList(i+1, i+j, 'T')}>") Object fromJ) { ... }

    @PermuteMethod(varName="k", from=2, to="${maxPath}", name="path${k}")
    @PermuteReturn(className="Path${k-1}",
                   typeArgs="Join${i+1}First<END, DS, ${typeArgList(1, i, 'T')}, Tuple${k}<T${i}, ${typeArgList(2, k, 'alpha')}>>, Tuple${k-1}<T${i}, ${typeArgList(2, k-1, 'alpha')}>, T${i}, ${typeArgList(2, k, 'alpha')}")
    public <@PermuteTypeParam(varName="j", from="1", to="${k-1}", name="${alpha(j+1)}") PB>
           Object path2Sentinel() { ... }

    public void execute(Consumer1<T1> action) { ... }
}
```

---

## APT vs Maven Plugin

| Feature | Maven plugin (inline) | APT |
|---|---|---|
| `@PermuteMethod.name` (explicit) | ✓ | ✓ |
| Method-level `@PermuteTypeParam` (explicit) | ✓ | ✓ |
| Method-level `@PermuteTypeParam` implicit inference | ✓ | ✗ — template must compile; use explicit |
| `@PermuteReturn.typeArgs` | ✓ | ✓ |
| `to` inferred for named methods | ✓ (from `@Permute.to - i`) | ✓ (same-module); explicit for cross-module |

For APT mode, `@PermuteMethod.to` must be set explicitly when path depth is independent of the outer arity (path depth 2–6 is fixed, not derived from join arity). Use `strings={"maxPath=6"}` and `to="${maxPath}"`.

---

## Validation Rules

G4 does not introduce new validation rules beyond what it adds to G2 and G3:

- **M8** (in G3) — `@PermuteMethod.name` evaluates to the same string for two different inner loop values → Warning. Defined in G3 because it belongs to `@PermuteMethod`.
- **V6** (in G2) — `@PermuteReturn.typeArgs` and `typeArgVarName` both set → Error. Defined in G2 because it belongs to `@PermuteReturn`.

See G3 and G2 validation tables for the full rule definitions.

---

## Documentation Requirements

### `@PermuteMethod` Javadoc (update)
- Document `name` attribute: when set, each overload is a distinct named method; when absent, all overloads share the sentinel name
- Clearly state the two modes: named series (G4) vs same-name overloads (G3 join pattern)

### `@PermuteTypeParam` Javadoc (update)
- Add section: method-level type parameters — driven by `@PermuteMethod`'s inner variable; three-level nesting (i → k → j)
- Note that implicit inference requires a detectable numeric pattern; alpha naming requires explicit annotation

### `@PermuteReturn` Javadoc (update)
- Document `typeArgs`: when to prefer it over `typeArgVarName` loop; list the four situations (fixed prefix, multiple segments, descending chain, mixed naming)
- Show `extensionPoint()` and path examples

### README / user guide
- New section: **Named method series** — the path use case as the worked example
- **Callout box:** Three-level nesting — outer `i` (class arity), middle `k` (method name/depth), inner `j` (method type parameter) — explain how all three variables compose
- APT vs Maven table for G4 features

### CLAUDE.md non-obvious decisions table
- Add: `@PermuteMethod.name` produces distinct methods not overloads — name conflicts (same evaluated name for different k) produce a warning not an error (user may intentionally generate overloads with the same name by different k paths)
- Add: `@PermuteReturn.typeArgs` overrides the loop mechanism entirely; mutually exclusive with `typeArgVarName`
- Add: Method-level `@PermuteTypeParam` introduces a third loop level (i → k → j); `j` is only in scope for the method's own annotations, not the class body

---

## Testing

New test class `PermuteMethodNameTest` and updates to `PermuteTypeParamTest`, `PermuteReturnTest`:

- **Named method series / basic:** `@PermuteMethod(varName="k", from=2, to=4, name="path${k}")` → `path2()`, `path3()`, `path4()` generated with correct names
- **Named + method type params:** `@PermuteTypeParam` on method param with `to="${k-1}"` → correct type param count per named method
- **Named + `typeArgs`:** complex return type with both `i` and `k` in `typeArgList` expressions → correct for all `(i, k)` combinations
- **Named + outer arity:** `JoinNSecond` with path methods — outer i changes Join arity in return type, inner k changes path depth; full matrix correct
- **`typeArgs` / fixed prefix:** `"DS, ${typeArgList(1, i, 'T')}"` → `DS, T1` for i=1, `DS, T1, T2` for i=2
- **`typeArgs` / extensionPoint:** `RuleExtendsPoint${i+1}<DS, T1,...,T${i}>` — correct for i=1..5
- **Descending chain:** `Path${i-1}` return type dropping leading type param — correct suffix
- **Degenerate M8:** `name="${k%2}"` produces duplicate names → warning
- **Degenerate V6:** both `typeArgs` and `typeArgVarName` set → error
- **APT mode:** all explicit; `Object` sentinel return type; `@PermuteTypeParam` explicit — correct output for all (i, k)

---

## Files Created or Modified

| File | Change |
|---|---|
| `permuplate-annotations/src/main/java/.../PermuteMethod.java` | Add `name` attribute |
| `permuplate-annotations/src/main/java/.../PermuteReturn.java` | Add `typeArgs` attribute |
| `permuplate-core/src/main/java/.../PermuteParamTransformer.java` | Handle named method series (distinct method names per overload) |
| `permuplate-core/src/main/java/.../PermuteDeclrTransformer.java` | Handle method-level `@PermuteTypeParam` expansion |
| `permuplate-processor/src/main/java/.../PermuteProcessor.java` | V6, M8 validation; method-level type param expansion in APT path |
| `permuplate-maven-plugin/src/main/java/.../InlineGenerator.java` | Named method series; method-level type param expansion; `typeArgs` evaluation |
| `permuplate-maven-plugin/src/main/java/.../AnnotationReader.java` | Read `name` and `typeArgs` from JavaParser AST |
| `permuplate-tests/src/test/java/.../PermuteMethodNameTest.java` | New test class |
| `permuplate-tests/src/test/java/.../PermuteReturnTest.java` | Add `typeArgs` tests |
| `permuplate-tests/src/test/java/.../PermuteTypeParamTest.java` | Add method-level type param tests |
| `README.md`, `CLAUDE.md` | Documentation updates |
