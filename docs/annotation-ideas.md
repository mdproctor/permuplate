# Permuplate Annotation Enhancement Ideas

Collected 2026-04-15. Ideas ranging from immediately actionable to long-term architectural.

---

## Tier 1 — Immediately Useful, Clear Implementation Path

### String-set iteration (`@Permute(values=...)`)

Instead of integer ranges with `alpha(i)`/`lower(i)` as workarounds, iterate over a named set of strings:

```java
@Permute(varName="T", values={"Byte", "Short", "Int", "Long", "Float", "Double"},
         className="To${T}Function")
public class ToByteFunction {
    public abstract byte applyAsByte(ByteFunction source);
}
```

**Why:** Integer-indexed generation is unnatural for type-family APIs. You end up writing `alpha(i)`, `lower(i)`, or maintaining `strings={"T1=Byte","T2=Short"}` maps. String-set iteration makes the template's intent explicit. Naturally handles non-contiguous/non-numeric value sets.

**Design notes:**
- `values` attribute on `@Permute` replaces `from`/`to` — mutually exclusive
- `varName` still names the iteration variable; its value is a `String` in JEXL context
- `className` uses `${T}` exactly as today — JEXL string interpolation already works
- `@PermuteVar` could gain a `values` attribute for cross-product string iteration
- `from`/`to` remain for backward compat; both integer and string modes supported

---

### `@PermuteFilter` — skip specific permutation values

Conditionally exclude specific values from a range without restructuring the template:

```java
@Permute(varName="i", from="1", to="10", className="Tuple${i}")
@PermuteFilter("${i} != 1")  // Tuple1 is hand-written elsewhere
public class Tuple2 { ... }
```

**Why:** Currently the only way to exclude a value is via `@PermuteReturn` boundary omission (indirect, method-level) or adjusting `from`. Neither is clean when you genuinely want to skip a value in the middle of a range — common when bootstrapping, when the edge case is already hand-written, or when a specific arity has a special form.

**Design notes:**
- Single JEXL expression evaluated per permutation value; if `false`, skip generation entirely
- Repeatable: `@PermuteFilters` container, multiple conditions ANDed
- Applies before ALL transformers — the skipped value is never generated
- `@PermuteVar` inner loop values could also be filtered
- Error if filter expression would skip ALL values (emit a compile warning)

---

## Tier 2 — Medium-term, More Design Needed

### `@PermuteAnnotation` — add class-level annotations per permutation

```java
@Permute(varName="i", from="1", to="6", className="Join${i}Second",
         strings={"max=6"})
@PermuteAnnotation(when="${i == max}", value="@Deprecated(since=\"use higher arity\")")
@PermuteAnnotation(when="${i == 1}", value="@FunctionalInterface")
public class Join0Second { ... }
```

**Why:** Some arities deserve different metadata — the last class might be deprecated (no more join() available), the first might be `@FunctionalInterface`. Currently impossible without generating separate files manually.

---

### Record component expansion

Records are increasingly idiomatic. Being able to template record canonical constructors:

```java
@Permute(varName="i", from="3", to="6", className="Tuple${i}")
public record Tuple2<
        @PermuteTypeParam(varName="k", from="1", to="${i}", name="${alpha(k)}") A>(
        @PermuteParam(varName="j", from="1", to="${i}", type="${alpha(j)}", name="${lower(j)}")
        A a) {
}
// Generates: record Tuple3<A,B,C>(A a, B b, C c) etc.
```

**Implemented in #29.** `StaticJavaParser` configured for Java 17; all transformer signatures generalized from `ClassOrInterfaceDeclaration` to `TypeDeclaration<?>`. Record components work with `@PermuteParam` and `@PermuteDeclr`. Tuple2Record.java demonstrates Tuple3–Tuple6 generation.

---

### `@PermuteThrows` — throws clause control

```java
@PermuteThrows(when="${i > 4}", value="TooManyArgsException")
public void join(...) throws SomeException { ... }
```

Small but occasionally needed when a method's checked exceptions depend on arity.

---

## Tier 3 — Long-term / Architectural

### Template composition (generate-from-generated)

A second-order template that applies to an already-generated class family:

```java
@Permute(source="Join${i}First", varName="i", from="2", to="6",
         className="Join${i}Decorator")
```

Apply transformations to a generated family without re-writing the original template. Enables delegation wrappers, adapters, decorators — all from the same source of truth.

---

### Retrograde mode (template from existing)

Given `Join3First..Join6First` (hand-written), infer what the template would look like. The bootstrapping problem: you have an existing family and want to migrate to Permuplate without rewriting everything. Particularly relevant for Drools migration.

---

### Functional `from`/`to` references

Reference another template's range to keep families in sync without magic number duplication:

```java
@Permute(varName="i", fromRef="Consumer.from", toRef="Consumer.to", className="...")
```

When `Consumer`'s range changes, all dependent templates automatically follow.

---

### `@PermuteConditional` — annotation-based conditional blocks

Include/exclude code blocks based on a JEXL condition. Hard to make syntactically valid Java (would require `TYPE_USE` on statements), but could work as a comment-based marker or on if-statements:

```java
// @PermuteConditional("${i > 1}")
if (size > 1) {
    validate();
}
```

The preprocessor strips the block when the condition is false. Trade-off: breaks "template is valid, compilable Java" guarantee unless structured carefully.

---

## Implementation Priority

| Feature | Effort | Value | Status |
|---|---|---|---|
| String-set iteration | Medium | High | **Done** (#27) |
| `@PermuteFilter` | Low | Medium | **Done** (#28) |
| `@PermuteAnnotation` | Medium | Medium | Future |
| Record component expansion | High (two blockers) | Medium | Blocked — needs parser + AST refactor |
| `@PermuteThrows` | Low | Low | Future |
| Template composition | High | High | Long-term |
| Retrograde mode | High | Medium | Long-term |
| Functional from/to refs | Medium | Medium | Long-term |
| `@PermuteConditional` | High | Medium | Long-term |
