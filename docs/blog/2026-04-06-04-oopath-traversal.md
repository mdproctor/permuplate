# Permuplate — OOPath: Walking the Object Graph

**Date:** 2026-04-06
**Type:** phase-update

*Part 11 of the Permuplate development series.*

---

## What I was trying to achieve: correlated traversal in a cross-product engine

OOPath is XPath for Java object graphs. A `Library` fact traverses into `rooms()`, then each room into `books()`, filtering at each step. The result — a `Tuple3<Library, Room, Book>` — joins back into the outer rule chain as a new typed fact.

The chain needs to stay fully typed. Calling `path3()` on a `Join1Second<END, DS, Library>` should produce a builder that knows the root type, tracks the new type parameters introduced by the traversal, and returns the right `JoinNFirst` at the end.

---

## path3() instead of path().path().path().end()

The obvious API for variable-depth traversal is a single `path()` method that chains and terminates with `end()`. I chose fixed-arity methods instead — `path2()` through `path6()`.

The reason is readability. Changing the number on the method name is less noise than adding `end()` at the tail, and it makes the depth visible at a glance. You can scan a rule and immediately see how deep the traversal goes without counting `.path()` calls. I believed that made the intent clearer, especially once paths start combining with outer joins and scope constraints.

---

## Three ways to execute a correlated traversal

The existing `matchedTuples()` treats all sources as independent — cross-products them. OOPath breaks that assumption. Each child collection depends on the current parent fact. You can't cross-product a correlated source.

Claude and I worked through three options. Option A: add a separate pipeline to `RuleDefinition` (`ooPathRootIndex` + `ooPathSteps`), with `matchedTuples()` switching to correlated mode when it's set. Option B: generalise `TupleSource` to accept the current partial fact array, so OOPath sources fit naturally into the existing pipeline. Option C: pre-compute a lazy executor in the `PathN` builders; `RuleDefinition` stays completely unchanged.

Option B was the most elegant. We rejected it — making `TupleSource` correlated would touch every existing source, even ones that will never need it. Option C was appealing but made execution timing opaque. We chose Option A: purely additive, zero changes to `TupleSource`, fires only when `ooPathRootIndex >= 0`.

---

## Why inheritance, not records: the mutable tuple

`path3()` produces a `Tuple3<Library, Room, Book>`. The traversal populates it step by step — root first, then each child as it passes the filter. Records are immutable. You can't half-fill a record and pass it to the next step.

`BaseTuple` uses an inheritance chain with mutable fields: `Tuple2<A,B> extends Tuple1<A>`, `Tuple3<A,B,C> extends Tuple2<A,B>`, and so on to `Tuple6`. Each adds one field and inherits the typed getters from the parent. `set(int, T)` populates the current slot during traversal; `getA()`, `getB()` etc. give typed access throughout.

`PathContext<T>` wraps the tuple-in-progress and passes it to every step function and filter predicate. At step 2 of a three-level traversal, `pathCtx.getTuple().getA()` gives back the original Library — even while you're filtering a Book two levels deep.

---

## PathContext: a clean rewrite

The Drools `PathContext` has a buggy constructor — a `switch` without `break` statements falls through all cases, overwriting every field, then a loop overwrites everything again. I spotted this reading the Drools source before starting Phase 3b. The sandbox version is eight lines:

```java
public class PathContext<T extends BaseTuple> {
    private final T tuple;
    public PathContext(T tuple) { this.tuple = tuple; }
    public T getTuple() { return tuple; }
}
```

This sandbox exists partly as an API evolution testbed for Drools. Finding bugs in the original while building it is a side effect worth keeping.

---

## The shared-tuple problem

`executePipeline()` mutates the tuple-in-progress at each depth: `pathCtx.getTuple().set(stepIndex + 1, child)`. When a step has multiple qualifying children — three matching rooms — the loop writes each one into slot 1 before recursing deeper. By the time results come back from the second room, slot 1 has already been overwritten.

The fix: copy the tuple at the leaf node before adding it to the result list. Returning a reference is the bug; `copyTuple()` is the discipline.

---

## @PermuteReturn at full stretch

The `path2()` through `path6()` methods needed the most complex `@PermuteReturn` typeArgs in the project. At `i=1`, `path2()` should produce:

```java
public <B> Path2<Join2First<END,DS,A,B>, Tuple1<A>, A, B> path2()
```

Each `pathN` method introduces N-1 new type parameters and must thread them through the `PathN` builder's four type arguments. The `typeArgList` JEXL function made the expressions tractable — without it, each method would have needed manually enumerated type lists rather than a single expression that evaluates correctly at every arity.

---

## Five tests, 32 total

```java
builder.from("libs", ctx -> ctx.libraries())
       .path3()
       .path((pathCtx, lib) -> lib.rooms(),  (pathCtx, room) -> true)
       .path((pathCtx, room) -> room.books(), (pathCtx, book) -> book.published())
       .fn((ctx, lib, t) -> {});
```

Five OOPath tests: basic path2, two-level path3, filter at each step, PathContext cross-reference, and OOPath combined with an outer join. All passing. The sandbox is at 32 tests across Phases 1 through 3b.

`Variable<T>` cross-fact binding is the last major pattern before real Drools migration becomes viable.
