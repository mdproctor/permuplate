---
layout: post
title: "Validation, IDE Support, and the Expression Language"
date: 2026-04-04
phase: 1
phase_label: "Phase 1 — The Annotation Processor"
---
# Validation, IDE Support, and the Expression Language

*Part 4 of the Permuplate development series.*

---

## The Problem With Annotation Strings

One of Permuplate's design choices — the annotation string as a template expression — creates an interesting problem for the developer experience.

Consider this annotation:

```java
private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
```

The strings `"Callable${i}"` and `"c${i}"` are opaque to the Java compiler. If you typo the class name — `"Calable${i}"` — the compiler won't catch it. You'll get a generated class that references `Calable3`, which then fails to compile with an unhelpful error pointing at the generated file rather than the template annotation.

The situation is even worse for variables. If you write `"Join${j}"` but the annotation's context only has `i` (not `j`), the JEXL evaluator will substitute nothing for `${j}` or throw at generation time. The annotation string has no way to validate variable references at the point where the developer writes them.

And then there's refactoring. If you rename `Callable2` to `TypedCallable2`, your IDE will update every reference to `Callable2` in Java code — but the annotation strings `"Callable${i}"` and `"Callable${i-1}"` are opaque strings. The IDE doesn't know they're references. They'll silently become wrong after a rename.

These problems pointed toward building a dedicated annotation string analysis library.

---

## permuplate-ide-support: The Validation Module

The `permuplate-ide-support` module contains `AnnotationStringAlgorithm` — a library for parsing, validating, and analyzing annotation strings. It doesn't depend on any IDE APIs; it's pure Java, usable from both the annotation processor and a future IDE plugin.

The algorithm breaks annotation strings into three kinds of segments: static literals, variable references (`${i}`), and expressions (`${i+1}`). A set of validation rules operates on the parsed result:

**R2 — Substring matching**: Every static literal in the annotation string must appear as a substring of the target name. `"Callable${i}"` has static literal `"Callable"`. The target name (`Callable3`, at i=3) contains `"Callable"`. Pass. `"Calable${i}"` has literal `"Calable"` which doesn't appear in `Callable3`. Fail.

This replaced an earlier, simpler check (leading-literal prefix matching) that was too strict. The substring approach correctly handles patterns like `"Join${i}Second"` where the static literal appears in the middle.

**R3 — Orphan variable detection**: A variable that appears before any static literal in the string is "orphan" — it provides no anchoring information. If the string is `"${i}Callable"` and the class `Callable2` gets renamed to `TypedCallable2`, the `${i}` part can still match `2` — but only because `Callable` still appears. If the class is renamed to something without `Callable`, the match fails. Orphan detection flags this fragility.

**R4 — No anchor detection**: An annotation string with *no* static literal at all (e.g. `"${i}"`) cannot be matched or renamed without ambiguity. Flagged as an error.

**R2 short-circuits R3/R4**: If R2 fails (the static literal isn't found as a substring), the orphan and no-anchor checks are skipped — the orphan computation is undefined when the literal isn't found.

These rules are wired into both the annotation processor (as compile errors at the template source level) and would eventually be wired into an IDE plugin for real-time feedback.

---

## The IDE Plugin I Didn't Build

At this point in the project, I seriously considered building an IntelliJ plugin. The `permuplate-ide-support` module was there. The algorithm was there. The IDE plugin would use it to:

- Validate annotation strings in real time (show errors as you type)
- Highlight renamed elements (yellow warning when a rename won't propagate to annotation strings)
- Offer a quick-fix dialog when renaming a class that has annotation string references

The design docs for this got fairly detailed — IDE mockups, a disambiguation dialog design, the "NeedsDisambiguation" concept for prefix/suffix renames that might be ambiguous.

I spent a meaningful chunk of time thinking through the IDE design. But the honest conclusion was that it was too much scope for where the project was. The validation rules in the processor were already a substantial improvement over no validation. The IDE plugin could come later, once the core was stable and the annotation model was settled.

The `permuplate-ide-support` module stands as the foundation for that future work. The algorithm is tested. The types are defined. The plugin is not yet built.

---

## N4: Built-In Expression Functions

Working through the Drools gap analysis, one pattern kept appearing: the need to express **type parameter names** that follow a naming convention. The Drools codebase uses single-letter names: `A`, `B`, `C`, `D`, `E`, `F`. RxJava uses `T1`, `T2`, `T3`. These are human-readable and conventional; the annotation strings needed a way to generate them.

Claude and I built a small library of built-in JEXL functions — what I called N4 in the gap analysis:

- `alpha(n)` → `A` when n=1, `B` when n=2, ..., `Z` when n=26
- `lower(n)` → `a`, `b`, ..., `z` (lowercase version)
- `typeArgList(from, to, style)` → `"T1, T2, T3"` (style="T"), `"A, B, C"` (style="alpha"), etc.

With these, you can write:

```java
@PermuteTypeParam(varName="j", from="1", to="${i}", name="${alpha(j)}")
```

and get `A` at j=1, `B` at j=2, and so on.

The `typeArgList` function was particularly important. Instead of writing a `@PermuteReturn` annotation that manually iterates `j` from 1 to `i` to build a type argument list, you write:

```java
@PermuteReturn(className="Join${i+1}First", 
               typeArgs="DS, ${typeArgList(1, i, 'alpha')}")
```

and get `DS, A, B, C` at i=3.

One edge case that turned out to matter: `typeArgList(from, to, style)` when `from > to` must return `""` — an empty string, not an error. This happens legitimately: `Join6Second.fn()` calls `Consumer${i+1}` with type args including `typeArgList(1, i, 'alpha')`. At i=0 (if it ever got there), or when building degenerate cases, the empty range shouldn't crash. It should just produce an empty argument list.

---

## The JEXL Autoboxing Trap

Implementing `alpha(n)` was straightforward in concept but had a non-obvious failure mode that took a debugging session to find.

The first approach was registering the functions as a namespace class via `JexlBuilder.namespaces`:

```java
// Intended approach — does NOT work
JexlEngine jexl = new JexlBuilder()
    .namespaces(Map.of("", MyFunctions.class))
    .create();
// Then: ${alpha(j)} would call MyFunctions.alpha(j)
```

In Java, `alpha(j)` takes an `int`. JEXL variables are evaluated as Java objects — `j=3` becomes `Integer(3)`, not `int(3)`. When JEXL's internal method resolution (the "uberspect") tries to find `MyFunctions.alpha(Integer)`, it doesn't find a match because the method signature is `alpha(int)`. The method lookup silently fails or throws at runtime.

The fix: register the functions not as a namespace class but as **JEXL lambda scripts** stored in the context:

```java
JexlScript alphaScript = jexl.createScript("n -> { /* A, B, C logic */ }");
context.set("alpha", alphaScript);
// Now ${alpha(j)} invokes the lambda directly, no type-matching issues
```

JEXL lambda scripts don't have the autoboxing problem because they don't do Java method resolution — arguments are passed as-is to the lambda.

This is a legitimate JEXL3 limitation that's not documented prominently. Registering utility functions as namespace classes is the documented approach; the autoboxing failure mode is something you only encounter when your functions take primitive parameters.

---

## The Validation Loop Pays Off

By the time the N4 functions were in place, the test suite had grown to cover the full validation path:

- `DegenerateInputTest` — every error condition in the annotation processor, with message content assertions
- `PrefixValidationTest` — R2/R3/R4 across all annotation placements
- `OrphanVariableTest` — focused tests for the substring matching algorithm
- `ExpressionFunctionsTest` — `alpha()`, `lower()`, `typeArgList()` including edge cases

We kept to the same discipline throughout: write a failing test first, implement the minimum to pass it, repeat. When bugs appeared (and they did), the fix was always accompanied by a test that would catch any regression. By the time the expression functions were stable, I had a high degree of confidence in the evaluation path.

The test suite was now around 80 tests. The real complexity was still ahead.

---

