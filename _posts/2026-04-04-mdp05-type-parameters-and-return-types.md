---
layout: post
title: "Teaching Classes to Grow: Type Parameters and Return Types"
date: 2026-04-04
phase: 1
phase_label: "Phase 1 — The Annotation Processor"
---
# Teaching Classes to Grow: Type Parameters and Return Types

*Part 5 of the Permuplate development series.*

---

## The Type Erasure Gap

The gap analysis was blunt: Permuplate could generate interfaces with expanding parameter lists, but they were all type-erased. Every generated interface had `Object` parameters — no generics. For a tool aimed at eliminating boilerplate in type-safe DSLs, this was a significant gap.

The Drools DSL needed interfaces like:

```java
interface Consumer2<DS, A> {
    void accept(DS ctx, A a);
}

interface Consumer3<DS, A, B> {
    void accept(DS ctx, A a, B b);
}

interface Consumer4<DS, A, B, C> {
    void accept(DS ctx, A a, B b, C c);
}
```

The growing list isn't just the parameters — it's the *type parameter list on the class declaration itself* that grows. `Consumer2` has two type parameters; `Consumer3` has three. This isn't something `@PermuteParam` could handle, because `@PermuteParam` only expands method parameters, not class-level type declarations.

The design for G1 took the same pattern-based approach that had worked for method parameters: annotate a "sentinel" type parameter and expand it.

---

## G1: @PermuteTypeParam

The new annotation placed directly on a type parameter:

```java
@Permute(varName = "i", from = 2, to = 7, className = "Consumer${i}", 
         inline = false, keepTemplate = true)
public interface Consumer1<DS,
        @PermuteTypeParam(varName = "j", from = "1", to = "${i-1}", name = "${alpha(j)}") A> {
    void accept(
            DS ctx,
            @PermuteParam(varName = "j", from = "1", to = "${i-1}",
                          type = "${alpha(j)}", name = "${lower(j)}") A a);
}
```

At i=2, the `@PermuteTypeParam` expands `A` into just `A` (j from 1 to 1). At i=3, it expands to `A, B`. At i=7, `A, B, C, D, E, F`. The class type parameters grow in lockstep with the method parameters.

Two expansion modes:

**Explicit**: The `@PermuteTypeParam` annotation is present. Full control over the name pattern, range, and bounds. When you use alpha naming (`name="${alpha(j)}"`), explicit annotation is *required* everywhere — the implicit inference won't fire because it only recognizes the `T+number` pattern.

**Implicit**: When a method has `@PermuteParam(type="T${j}")` referencing a class type parameter, the class type parameter list auto-expands to match. No `@PermuteTypeParam` annotation needed. This only works in Maven plugin inline mode, since APT templates must compile with fixed type parameters.

The `to="${i-1}"` range expression in the Consumer template is easy to get wrong. The first version of the spec said `to="${i}"`, which at i=2 would produce `A, B` — making `Consumer2<DS, A, B>` with three type params total instead of two. The correct range is `to="${i-1}"` because `i` already counts the DS type param in the class name's arity. This was a spec bug, caught when testing the generated classes.

---

## Bounds Propagation

One of the nicer behaviors of `@PermuteTypeParam`: bounds propagate automatically.

```java
public interface SortedConsumer1<
        @PermuteTypeParam(varName="j", from="1", to="${i-1}", name="${alpha(j)}") 
        A extends Comparable<A>> {
    // ...
}
```

At i=3, this generates `SortedConsumer3<A extends Comparable<A>, B extends Comparable<B>, C extends Comparable<C>>`. Each new type parameter inherits the bounds expression with the name replaced. This handles the common case where you want a type-constrained family of types. It also handles the uncommon cases — self-referential bounds, multiple bounds — since the expansion just substitutes the name.

---

## G2: @PermuteReturn — Return Type Narrowing

The second major feature in this phase was `@PermuteReturn`. The motivation: in a fluent builder DSL, each `join()` call returns the *next arity class*. `join()` on a `Join2First` object should return `Join3First`. But how do you express this in a template?

```java
@Permute(varName = "i", from = 1, to = 6, className = "Join${i}Second")
public class Join0Second<DS, @PermuteTypeParam(...) A> {

    @PermuteReturn(className = "Join${i+1}First", 
                   typeArgs = "DS, ${typeArgList(1, i+1, 'alpha')}")
    public Object join(Object source) {
        rd.addSource(source);
        return rd.asNext();
    }
}
```

The `@PermuteReturn` annotation on `join()` tells the transformer to replace the `Object` return type with `Join${i+1}First<DS, A, B, ...>`. At i=1, the return type becomes `Join2First<DS, A, B>`. At i=5, `Join6First<DS, A, B, C, D, E, F>`.

Two critical behaviors:

**Boundary omission**: When the evaluated return class is NOT in the generated set, the entire method is silently omitted. At i=6 (the last class), `Join${i+1}First` evaluates to `Join7First` — not in the generated set. So `join()` is simply omitted from `Join6Second`. This is the leaf node pattern. No annotation required; it happens automatically as a consequence of the boundary check.

**`when="true"` override**: Sometimes you want a method to survive even when its return type isn't in the generated set. `RuleDefinition` is not a generated class, but `fn()` must return it. `@PermuteReturn(className="RuleDefinition", typeArgs="DS", when="true")` forces the method to be generated regardless.

---

## Implicit Inference: Zero-Annotation Return Types

For the common case — a method whose return type already follows the `T+number` naming convention — no `@PermuteReturn` annotation is needed at all.

```java
public class Step1<T1> {
    public Step2<T1, T2> join(Object src) { return null; }
}
```

At i=2, the return type `Step2<T1, T2>` is already using the right names. The transformer detects that `Step2` is in the generated set and that the type arguments follow the `T+number` growing-tip pattern (declared type params + undeclared T-numbered vars), and auto-infers the correct expansion: `Step3<T1, T2, T3>` at i=2, `Step4<T1, T2, T3, T4>` at i=3.

Boundary omission applies here too: `Step4.join()` is omitted because `Step5` isn't in the generated set.

The inference conditions are deliberately strict:
1. The return type's base class must be in the generated set
2. The type args must consist of declared class params (fixed) followed by undeclared `T+number` vars (the "growing tip")

If either condition fails, inference doesn't fire and the return type is left unchanged. This prevents false-positive inferences on third-party classes that happen to follow a similar naming pattern.

---

## The Alpha Naming Trade-Off

There's a deliberate asymmetry in the system: implicit inference (zero annotations) only works with `T+number` naming. Alpha naming (`A, B, C`) requires explicit `@PermuteReturn` and `@PermuteDeclr` everywhere.

This is intentional. Inference works by recognizing `T1`, `T2`, `T3` as a numeric progression. `A`, `B`, `C` has no numeric suffix — the system can't detect the pattern without additional information.

For the Drools DSL, I chose alpha naming throughout to match the real Drools codebase — its convention is `A, B, C, D`, not `T1, T2, T3`. That means every return type, every parameter type, every filter/consumer requires an explicit annotation. More verbose, but more faithful to Drools conventions.

There's an open idea to provide two versions of the DSL side by side — one using `T${j}` naming (zero-annotation implicit inference), one using alpha naming (full explicit annotation) — to show the trade-off concretely. That's deferred for now.

---

## Two-Pass Scan: Building the Generated Set First

Both `@PermuteReturn` and the G1 validation rules require knowing the complete set of generated class names before transforming any individual class.

This led to the two-pass scan pattern that now runs in both APT and the Maven plugin:

1. **Scan all `@Permute` templates** → build the complete "generated names" set
2. **Generate each class** → use the set from step 1 for boundary omission and validation

In APT, the first pass uses `RoundEnvironment.getElementsAnnotatedWith(Permute.class)`. In the Maven plugin, it's a file scan of the template directory. Both produce the same result: a `Set<String>` of class names that will be generated by this build.

Without the two-pass approach, generating `Join2First` when `Join3First` hasn't been generated yet would produce an incorrect boundary omission (the set would look like `Join3First` doesn't exist because it hasn't been generated in this run).

---

## Testing @PermuteReturn

The `PermuteReturnTest` class covers the full range of behaviors:

- APT explicit mode: annotated return types in APT compilation tests
- Inline implicit mode: return type inference from `T+number` patterns
- Boundary omission: leaf nodes have the method omitted
- `when="true"` override: non-generated return types survive
- Alpha naming explicit: `AlphaStep2<A,B>` → `AlphaStep4<A,B,C,D>` chain
- Validation errors: `typeArgVarName` without `typeArgTo`, range errors, conflicting attributes

The test for alpha naming with `@PermuteReturn` revealed something important: the return type annotation for alpha-named classes can only return the *raw* class name — no type args — when the next-arity type params aren't in scope. `Join2First` returning `Join3First` (raw) rather than `Join3First<DS, A, B, C>` (parameterized). This is a real limitation; the workaround is that Java's type erasure makes the unchecked cast safe for fluent chains where the intermediate type is never stored in a variable.

---

