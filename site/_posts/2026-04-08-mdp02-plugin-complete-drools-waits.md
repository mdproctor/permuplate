---
layout: post
title: "Permuplate — The Plugin, Complete; Drools, More Complex Than Expected"
date: 2026-04-08
phase: 3
phase_label: "Phase 3 — The IntelliJ Plugin"
---
# Permuplate — The Plugin, Complete; Drools, More Complex Than Expected

**Date:** 2026-04-08
**Type:** phase-update

---

## What we were trying to achieve: close #8 and #9, then start on Drools

The previous entry left two items open: #8 (rename from a generated file should
silently redirect to the template, not block with a dialog) and #9 (Find Usages in
Permutation Family should be a deliberate right-click action, not an invisible hook
into every Find Usages call). We closed both. Then we tried to start the Drools
migration work and hit something unexpected.

## What I believed going in: droolsvol2 was ready for Permuplate templates

I assumed the droolsvol2 sandbox was in a compilable state and we could immediately
add `@Permute`-annotated template classes. The plan: finish the plugin, wire
Permuplate into the drools pom, start generating.

The droolsvol2 module is mid-refactor. It doesn't compile standalone.

## #8: three things we got wrong before one thing worked

The obvious implementation: `substituteElementToRename()` in
`AnnotationStringRenameProcessor`. When the element is in a generated file, return the
template class. IntelliJ redirects silently.

It didn't fire.

The reason: `GeneratedFileRenameHandler` — registered with `order="first"` — intercepts
before `substituteElementToRename()` is ever called. A `RenameHandler` that returns
`true` from `isAvailableOnDataContext()` takes full control; the processor pipeline
never runs. We had to make the handler step aside for Permuplate-managed files first,
then `substituteElementToRename()` could do its job. GE-0117 in the garden.

The second surprise came in tests. The method queries the `FileBasedIndex` reverse map
to find the template class for a generated name. In `BasePlatformTestCase`, custom
`FileBasedIndexExtension` entries aren't populated synchronously after
`addFileToProject()`. The index returns empty; the redirect fails silently. We added a
PSI scan fallback — `ProjectFileIndex.iterateContent()` with direct annotation
inspection — that runs when the index misses. Fast path in production, PSI scan in
tests. GE-0116, GE-0119.

The end-to-end integration test calls `myFixture.renameElement(join3, "Merge2")` and
asserts that `Join2.java` gains `class Merge2` and `className="Merge${i}"`. It passes.

## #9: simpler, but with one undocumented API

Replacing `FindUsagesHandlerFactory` with a right-click action was straightforward.
`PermuteFamilyFindUsagesAction` collects counterpart members across the family and calls
`FindUsagesManager.findUsages()` with primary and secondary element arrays.

What wasn't in the docs: `FindUsagesManager` has a public constructor. You don't need
`FindManagerImpl` (an internal class with a private field and no getter) to get one.
`new FindUsagesManager(project)` works; its `findUsages()` takes `FindUsagesHandlerBase`
(not the more commonly known subclass), and results appear in the standard Find panel.
GE-0120 in the garden.

## The audit: 22 tests to 48

Before moving to Drools we ran a coverage audit. `GeneratedFileRenameHandler` had zero
tests — the `isPermuteManagedFile()` logic we'd just written was entirely untested.
`collectFamilySiblings()` had no positive template case. `findMatchingMember()` and
`stripTrailingDigits()` had no unit tests at all.

We added `GeneratedFileRenameHandlerTest`, expanded `PermuteFamilyFindUsagesActionTest`
with field siblings and base-name matching, and added the end-to-end rename test to
`AnnotationStringRenameProcessorTest`. Twenty-six new tests. 48 total.

## Drools: correct plan, wrong assumption about prerequisites

We completed the gap analysis against the vol2 test suite, structured the GitHub epics
in `apache/incubator-kie-drools` (#6639 main, #6638 DSL sub-epic, #6646 Rule Base
sub-epic), and wired Permuplate into `droolsvol2/pom.xml`.

Then we tried to compile droolsvol2 standalone. It fails. Around 14 files still
reference `org.drools.core.common`, `.reteoo`, and `.rule` — packages from `drools-core`,
which vol2 is explicitly removing as a dependency. The module was always built from the
full drools parent repo; standalone was never the workflow.

I should have finished that refactor before wiring Permuplate in. The `drools-migration`
branch in permuplate is parked until the droolsvol2 refactor completes. That refactor is
now underway in a separate session.
