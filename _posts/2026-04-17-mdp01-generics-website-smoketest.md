---
layout: post
title: "Permuplate — Getting the Example Right, Then the Tests"
date: 2026-04-17
phase: 4
phase_label: "Phase 4 — Coverage and Marketing"
type: phase-update
entry_type: note
subtype: diary
projects: [permuplate]
---

The website example went through three attempts before it was correct. I'm documenting them in order because the path matters.

## The example that lied three times

**Attempt one:** `right` was `List<Object>`. The entire point of the library is type safety and the showcase example had `List<Object>` running through it. We fixed it to `DataSource<Tuple3>` but kept `Object o1, Object o2` as parameters. Still wrong.

**Attempt two:** We switched to a Callable interface example — `Callable2<A>` generating `Callable3<A,B,C>` — to show `@PermuteTypeParam` and proper generics. No Objects. But it dropped tuples entirely, which the marketing copy had been billing as the whole point.

**Attempt three:** I finally articulated what the example actually needed to show. The join has tuples coming in AND calls matching typed Callables. All three types grow together:

```java
public class Join2<A,
        @PermuteTypeParam(varName = "k", from = "2", to = "${i}",
                          name = "${alpha(k)}") B> {

    private @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>",
            name = "c${i}") Callable2<A, B> c2;

    private @PermuteDeclr(
            type = "DataSource<Tuple${i}<${typeArgList(1,i,'alpha')}>>")
            DataSource<Tuple2<A, B>> right;
}
```

Generated `Join3<A,B,C>` has `Callable3<A,B,C>` and `DataSource<Tuple3<A,B,C>>`. No Object. The diff block on the problem section now shows the three-way expansion — `class`, `Callable`, and `DataSource<Tuple>` all updating in lockstep.

## The IntelliJ smoke test

After installing the new plugin build, we ran a systematic smoke test via the IntelliJ MCP tools. Seven scenarios, seven passes:

- Rename from template file → `@Permute.className` updated ✓
- Field rename → `@PermuteDeclr.name` updated ✓
- `@PermuteParam.name` updated ✓
- Rename from mid-range generated file → redirected to template, string updated ✓
- Rename `SyncCallable1` → `@PermuteSource("SyncCallable${i}")` updated to `"AsyncCallable${i}"` ✓
- Rename from end-range generated file → same redirect ✓
- Deliberate mismatch → both `AnnotationStringInspection` and `StaleAnnotationStringInspection` fired ✓

One non-obvious find: `ide_diagnostics` in the MCP toolkit does not run registered `LocalInspectionTool` plugins. It returns 0 problems even when a deliberate mismatch is in the file. `get_file_problems` is the right tool — it runs the full inspection framework including custom plugins.

## The generics sweep

I noticed the test examples hadn't been updated to match the website's type-safety story. `Join2.java` was still generating `Object o1, Object o2`. So was every other example file in the test suite — 21 files total.

We swept them all. The pattern is now consistent across `RichJoin2`, `ProductFilter2`, `AuditRecord2`, `ValidationSuite.FieldValidator2`, and the rest: `<A, @PermuteTypeParam B>` on the class, typed Callable fields via `typeArgList`, typed iterable fields, `${alpha(j)}`/`${lower(j)}` in `@PermuteParam`. Five test files needed assertion updates — checking `"Callable3<A, B, C> c3"` instead of `"Callable3 c3"`, typed for-each variables instead of `Object`.

One limitation surfaced: cross-product templates using `@PermuteVar` can't take two independent `@PermuteTypeParam` annotations — the processor generates duplicate type params. `BiCallable1x1`, `Combo1x1`, and `DualParam2` stay with `Object` until that's fixed.

208 tests, 0 failures.
