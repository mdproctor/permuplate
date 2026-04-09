# Permuplate IntelliJ Plugin — PermuteElementResolver Snapshot
**Date:** 2026-04-09
**Topic:** Plugin rename redirect extended to methods, fields, and parameters via shared resolver
**Supersedes:** [2026-04-08-intellij-plugin-complete](2026-04-08-intellij-plugin-complete.md)
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

The IntelliJ plugin rename redirect now works from any element type in a
generated file — class, method, field, or parameter. The new
`PermuteElementResolver` class (in the `index` package) owns all
template-resolution logic: fast-path via `PermuteFileDetector` + PSI scan
fallback, base-name matching for member lookup, and graceful fallthrough when
no match is found. `substituteElementToRename()` in `AnnotationStringRenameProcessor`
is now a one-line delegate. 56 tests pass.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| `PermuteElementResolver` as shared utility in `index` package | Shared class, all resolution logic in one place | Eliminates 3 duplicate `stripTrailingDigits` copies and 2 duplicate `findTemplateByPsiScan` implementations; ready for `PermuteMethodNavigator` feature 2 | Inline extension of `AnnotationStringRenameProcessor` — no consolidation; private method extraction — still contained, no reuse |
| Base-name matching for member resolution | Strip trailing digits from both sides, first match wins | `c3` → base `c` → matches `c2` in template; handles `@PermuteDeclr`-renamed members that have no exact-name equivalent in template | Exact-name-only match — always falls through for `@PermuteDeclr` members, silently loses rename |
| Graceful fallthrough for all unmatched cases | Return original element unchanged | Same UX as before for unhandled cases; no regression | Returning null — breaks `RenamePsiElementProcessor` contract |
| Rename redirect as a temporary solution | Accepted — implement, then improve tests | Closes the most visible IDE gap; full graph-aware rename is the long-term goal | Deferring until graph-aware approach is designed — too long a gap in IDE usability |

## Where We're Going

**Next steps:**
- Testing improvements (deferred until rename redirect is smoke-tested in IDE):
  - `actionPerformed()` in `PermuteFamilyFindUsagesAction` — extract `collectAndFindFamilyUsages()`, test via `myFixture.findUsages()`
  - `PermuteMethodNavigator` feature 2 — navigate from generated element to template (resolver is already wired and ready)
  - `GeneratedFileRenameHandler.invoke()` — dialog + navigation, testable via headless `Messages.showDialog()` auto-selection (GE-0147)
- Smoke-test rename redirect in IntelliJ: rename method/field/parameter from a generated file, verify template + annotation strings update atomically
- Drools migration — blocked on droolsvol2 refactor completing

**Open questions:**
- Is base-name-only matching sufficient for production templates, or will cross-module families (template in one module, generated classes in another) require index-based resolution rather than PSI scan?
- What is the correct long-term architecture for graph-aware rename — redirect always to template, or a true bidirectional graph that propagates from any node?
- When droolsvol2 compiles, should git history be cleaned before `@Permute` templates are added?

## Linked ADRs

*(No ADRs for plugin — decisions captured in snapshots and `permuplate-ide-support/DESIGN.md`)*

## Context Links

- Spec: `docs/superpowers/specs/2026-04-09-permuplate-element-resolver-design.md`
- Plan: `docs/superpowers/plans/2026-04-09-permuplate-element-resolver.md`
- Algorithm foundation: `permuplate-ide-support/DESIGN.md`
- Previous plugin snapshot: [2026-04-08-intellij-plugin-complete](2026-04-08-intellij-plugin-complete.md)
- Drools migration snapshot: [2026-04-08-drools-migration-planning](2026-04-08-drools-migration-planning.md)
