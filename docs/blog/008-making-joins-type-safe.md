# Phase 1.5: Making Joins Type-Safe

*Part 8 of the Permuplate development series.*

---

## The Type Erasure Problem, Still Unsolved

Blog 007 ended honestly: the type system broke down after the first `.join()`. Every subsequent `.filter()` lambda received `Object`-typed parameters — `((Person) a).age() >= 18` instead of `a.age() >= 18`. Not a bug. A structural limitation: `join()`'s return type couldn't reference the next arity's type parameters because they weren't in scope in the template.

The obvious fix — `@PermuteReturn` pointing at `Join${i+1}First<END, DS, A, B>` — had a problem. `B`, the new type variable, needed to be a method-level type parameter. Permuplate's `@PermuteTypeParam` only worked on class type parameters. There was no equivalent for methods.

---

## Growing B from Inside the Method

Claude and I added standalone method-level `@PermuteTypeParam` — a new Step 5 in `PermuteTypeParamTransformer.transform()`. On a method, the annotation expands a sentinel type parameter and propagates the rename into the method's parameter types.

The typed `join()` template:

```java
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
@PermuteReturn(className = "Join${i+1}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i+1, 'alpha')")
public <B> Object join(Function<DS, DataSource<B>> source) {
    rd.addSource(source);
    // ... reflective instantiation
}
```

At i=1, `<B>` becomes `<B>` (alpha(2) = B — no visible change). At i=2, `<B>` becomes `<C>`. The rename propagates automatically into `DataSource<B>` in the parameter — no `@PermuteDeclr` needed. Without propagation the method would declare `<C>` but the parameter would still say `DataSource<B>` — a mismatch that compiles but generates the wrong signature.

The generated `Join2Second.join()`:

```java
public <C> Join3First<END, DS, A, B, C> join(Function<DS, DataSource<C>> source)
```

Fully typed. Lambda target types are now inferable all the way down the chain.

---

## Two filter() Overloads, Not One

With typed returns available, filters could express their real signatures. But one filter per arity wasn't enough — Drools has two patterns: a predicate applied to all accumulated facts, and one applied only to the most recently joined fact.

```java
// All-facts filter — always present
@PermuteReturn(className = "Join${i}First", typeArgs = "...", when = "true")
public Object filter(
        @PermuteDeclr(type = "Predicate${i+1}<DS, ${typeArgList(1, i, 'alpha')}>")
        Object predicate) { ... }

// Single-fact filter — suppressed at arity 1 via ternary from
@PermuteMethod(varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}", name = "filter")
@PermuteReturn(className = "Join${i}First", typeArgs = "...", when = "true")
public Object filterLatest(
        @PermuteDeclr(type = "Predicate2<DS, ${alpha(i)}>")
        Object predicate) { ... }
```

The JEXL ternary `from="${i > 1 ? i : i+1}"` with `to="${i}"` produces an empty range at i=1 — the single-fact overload is silently omitted. At arity 2 and above, `from=to=i`, and exactly one clone is generated per arity. No special-case logic. Just an empty range doing what empty ranges always do in Permuplate.

---

## A Maven Plugin Bug We Hadn't Planned For

JoinBuilder needed two templates in the same parent class: `Join0Second` and `Join0First`. The Maven plugin wasn't built for that. When processing the second template, it wrote fresh output that overwrote the first template's generated classes.

The fix was grouping templates from the same parent file and chaining `generate()` calls. The second template operates on the class that already contains the first template's output. Straightforward in hindsight — the one-template-per-file assumption was baked into the Mojo's source scan from the start.

The before/after at the call site tells the story of Phase 1.5:

```java
// Phase 1: casts everywhere after the first join
builder.from("persons", ctx -> ctx.persons())
       .join(ctx -> ctx.accounts())
       .filter((ctx, a, b) -> ((Person) a).age() >= 18 && ((Account) b).balance() > 500.0)

// Phase 1.5: fully inferred
builder.from("persons", ctx -> ctx.persons())
       .join(ctx -> ctx.accounts())
       .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
```

15 tests, all green. The type system held.
