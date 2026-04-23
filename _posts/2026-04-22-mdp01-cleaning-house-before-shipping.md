---
layout: post
title: "Cleaning house before shipping"
date: 2026-04-22
type: phase-update
entry_type: note
subtype: diary
projects: [permuplate]
tags: [permuplate, refactoring, annotation-processor]
---

The previous session ended with a question I'd been avoiding: are we actually improving the project each iteration, or just adding complexity for its own sake? I looked at the data — batch 10's test count didn't move, five of six items were cosmetic. The curve had flattened.

Before drawing any conclusions I wanted to answer a more specific question: with 37 annotations in the set, were any of them redundant?

Two came out immediately. `@PermuteConst` was always documented as a backward-compat alias for `@PermuteValue` — one file used it, one line, trivially migrated. `@PermuteNew` had been added in batch 10 as a fallback for cases where constructor-coherence inference couldn't determine which `new X<>()` to rename. But those cases don't actually exist: the common case is covered by inference, and edge cases were always covered by `@PermuteDeclr TYPE_USE` which predates `@PermuteNew` and works in both APT and Maven plugin modes. `@PermuteNew` added nothing. Both gone.

A tier-4 health check caught what the deletion had left behind: CLAUDE.md still had both annotations in the table, OVERVIEW.md had a full `### @PermuteConst` section with interface definition and examples, the pipeline description referenced `transformConstFields()` and `transformConstLocals()` (both deleted from `PermuteDeclrTransformer`), the file tree listed `PermuteConst.java`, and `PermuteConstTest` in the test coverage table. The README had JavaParser `3.25.9` when the actual version is `3.28.0`. About thirty minutes of mechanical cleanup.

The refine session found four things worth acting on.

The most significant: thirteen private utility methods were duplicated between `InlineGenerator.java` (3000 lines, the Maven plugin's generator) and `PermuteProcessor.java` (2500 lines, the APT entry point). Methods like `prefixBeforeFirstDigit`, `firstEmbeddedNumber`, `parseReturnTypeInfo`, `expandTypeStringForJ` — all copied verbatim. We extracted them to `AstUtils.java` in `permuplate-core`, where both consumers can reach them.

The interesting moment came during extraction. The subagent implementing the refactor ran the test suite after wiring up the shared utility class and hit a failure. `expandMethodTypesForJ` had diverged — PermuteProcessor's version had a type-parameter-addition block that InlineGenerator's version was missing. The files were large enough that nobody had noticed during normal development. A bug fix had landed in one copy and not the other. The extraction surfaced it; the tests caught it. That's exactly why this kind of duplication is dangerous.

The other three items: a `DroolsDslTestBase` shared by three sandbox test classes that all constructed identical Ctx fixtures; six section-heading comments in InlineGenerator for navigation (one comment in 3000 lines was the previous state); and a split of `OVERVIEW.md` into two documents — the annotation API reference stays in `OVERVIEW.md`, the transformation pipeline, module structure, and testing strategy move to `ARCHITECTURE.md`. The latter also solves a recurring problem: `java-git-commit` wants a `docs/DESIGN.md` and `OVERVIEW.md` has been serving that role without being named correctly.

Net change: 2 annotations removed, 12 utility methods deduplicated, ~228 lines removed from the two most-churned files, and the project's documentation now has a document for each audience.
