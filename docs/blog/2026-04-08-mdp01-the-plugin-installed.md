# Permuplate — The Plugin, Installed

**Date:** 2026-04-08
**Type:** phase-update

---

## What I was trying to achieve: turn annotation strings from opaque text into live references

The plugin gap was clear from the previous session: IntelliJ knows nothing about Permuplate. Rename `Join2` and `className="Join${i}"` stays wrong. Click on `"Callable${i}"` and nothing happens. Edit a generated file and it gets silently overwritten on the next build.

I wanted to close all eleven of those gaps in one pass.

## What we believed going in: the algorithm was the hard part

The algorithm foundation already existed — `permuplate-ide-support` had `AnnotationStringAlgorithm` with parse, validate, computeRename, and matches. I expected the plugin work to be wiring: hook into the right extension points, call the algorithm, done.

Roughly correct for the design. Less correct for the implementation.

## Eleven interaction points, twelve extension points, one session

I brought Claude in for the full design and implementation. We worked through a brainstorm, a full architecture decision, and two implementation plans before writing any code. The design is in `docs/superpowers/specs/2026-04-07-intellij-plugin-design.md`.

The architecture centres on a persistent `FileBasedIndex` pair — template→family forward, generated→template reverse — as shared foundation for nine plugin components. The rename processor is the core: it intercepts Shift+F6, calls `AnnotationStringAlgorithm.computeRename()` on each annotation string attribute, and applies the updates in the same write action as the Java rename. One Ctrl+Z undoes everything.

We executed both plans via subagent-driven development — fresh Claude instances per task, spec review after each. Twenty tasks. Seventeen tests passing, a 71KB plugin zip.

## Smoke testing in IntelliJ Ultimate 2025.3: three bugs

The fun started at install time.

**Bug 1 — Wrong annotation FQN.** We had used `io.quarkiverse.permuplate.annotations.Permute` throughout. The annotations live at `io.quarkiverse.permuplate.Permute`. Every component silently matched nothing. Fixed with a sed one-liner across 8 files. The painful part: the tests also passed with the wrong FQN, because the subagents had built both sides of the test wrong in the same way.

**Bug 2 — Indexer crashing on every Java file.** The `FileBasedIndex` was calling `cls.getAnnotation("io.quarkiverse.permuplate.Permute")` inside the indexer. That triggers FQN resolution — which reads the stub index — which is a recursive index read from inside an indexer. IntelliJ throws `StorageException`. The error reported "Failed to build index for `Callable1.java`" with no indication the bug was in our indexer. Fix: switch to `ann.getNameReferenceElement().getReferenceName()` for simple-name matching, with a `contains("@Permute")` text pre-filter before touching PSI. GE-0093 in the knowledge garden.

**Bug 3 — Plugin not compatible with IntelliJ 2025.3.** `intellij-platform-gradle-plugin` 2.x auto-derives `untilBuild = "${sinceBuild}.*"` when you only set `sinceBuild`. Setting `sinceBuild = "232"` silently produced `until-build="232.*"`, locking the plugin to 2023.2.x only. Fix: `untilBuild = provider { null }` in Kotlin DSL. GE-0111.

## The Find Usages rethink

We had built a `FindUsagesHandlerFactory` that intercepts standard Find Usages and extends results to cover all permutation siblings. It worked. Then I thought about the actual workflow: when I'm in `Join5.java`, I want Find Usages on `left()` to show callers of `Join5.left()` — not also `Join3.left()` and `Join4.left()` without asking.

The family-wide search is useful before a refactor, but it shouldn't hijack the default. That becomes issue #9: a dedicated "Find Usages in Permutation Family" right-click action. The underlying logic already exists; it just needs a different entry point.

## What it is now

Tests 1–3 and 5–6 pass: annotation string renames update atomically with the Java rename, Cmd+click on `"Callable${i}"` navigates to the template class, broken strings get inline warnings, generated files show the overwrite banner, and Shift+F6 inside a generated file redirects to the template rather than renaming in place.

Two open items: #8 (rename from generated files should silently redirect rather than show a dialog), and #9 (the custom Find Usages action). The VS Code extension is #4 and starts with a TypeScript port of `AnnotationStringAlgorithm`.
