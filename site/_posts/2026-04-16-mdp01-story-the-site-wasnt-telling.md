---
layout: post
title: "Permuplate — The Story the Site Wasn't Telling"
date: 2026-04-16
phase: 4
phase_label: "Phase 4 — Coverage and Marketing"
type: phase-update
entry_type: note
subtype: diary
projects: [permuplate]
---

The website had been selling the wrong thing. "One Template. Infinite Arities." is accurate, but it says nothing about why you'd want that. I looked at the hero copy and realised the core problem statement was missing: Java is the only mainstream language without native tuple types and no functional hierarchy above arity 2. Scala ships Tuple1–22 and the whole type hierarchy to match. Kotlin has Pair and Triple. C# has ValueTuple. Java ships `BiFunction` and stops.

The reframe puts that front and center. The hero now leads with the gap. The problem section is "Java's Arity Ceiling" — `Function` goes to 2, `Supplier` is unary, every API that varies by arity means hand-writing N files. A new section — "One Pattern. Every Arity." — makes the scope explicit: tuple families, `Callable1` through `CallableN`, stateful builder DSLs.

## The examples that were lying

The generated code examples had `private List<Object> right` in every Join class. That's wrong. `right` is the typed right-side data source — it should read `DataSource<Tuple3>`, `DataSource<Tuple4>`. I brought Claude in to fix it.

We added `@PermuteDeclr(type = "DataSource<Tuple${i}>")` on the `right` field and matching annotations on the `results` parameter and the for-each variable. The generated panes now show `DataSource<Tuple3>`, `List<Tuple3>`, and `Tuple3 t3` throughout — which is what the marketing is actually claiming.

## Three annotations the plugin couldn't track

Three newer annotations — `@PermuteAnnotation`, `@PermuteThrows`, `@PermuteSource` — had incomplete IntelliJ plugin support. The first two each had a value inspection but no rename propagation and no tests. `@PermuteSource` had nothing, despite its `.value` being a JEXL class-name template (`"SyncCallable${i}"`) structurally identical to `@Permute.className`.

We filled all three. The rename processor's `ALL_ANNOTATION_FQNS` now includes all of them — renaming a class updates their annotation string values atomically alongside the Java rename. `@PermuteFilter` is deliberately excluded: its `.value` is a boolean JEXL expression with no class references.

Writing the tests found two bugs in the existing inspections. Both `PermuteAnnotationValueInspection` and `PermuteThrowsTypeInspection` checked `annotation.getQualifiedName()` against the FQN and against `.endsWith(".PermuteAnnotation")`. In the test environment, annotation JARs aren't on the classpath, so PSI returns the bare simple name — `"PermuteAnnotation"`, no package, no dot. `"PermuteAnnotation".endsWith(".PermuteAnnotation")` is false. Both inspections were silently doing nothing, and the tests would have passed vacuously.

Claude caught this during TDD — the tests passed when they shouldn't have, which surfaced the root cause. Fix: a third guard, `|| fqn.equals("PermuteAnnotation")`. Any new `LocalInspectionTool` for a Permuplate annotation needs all three checks.

We also paired every shorthand-form test with an explicit `value=` form. `@PermuteAnnotation("@Override")` and `@PermuteAnnotation(value="@Override")` map to different PSI node types — `SingleMemberAnnotationExpr` vs `NormalAnnotationExpr` — and `getParameterList().getAttributes()` covers both, but the original tests only exercised one path.

The plugin test suite went from 56 to 83.
