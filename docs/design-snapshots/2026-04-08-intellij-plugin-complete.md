# Permuplate IntelliJ Plugin — Design Snapshot
**Date:** 2026-04-08
**Topic:** IntelliJ plugin — feature-complete, all 11 interaction points shipped
**Supersedes:** [2026-04-08-intellij-plugin](2026-04-08-intellij-plugin.md)
**Superseded by:** [2026-04-09-intellij-plugin-element-resolver](2026-04-09-intellij-plugin-element-resolver.md)

---

## Where We Are

The IntelliJ plugin is feature-complete for all 11 interaction points from the design
spec. Issues #8 and #9 were implemented and tested this session. The plugin has 48
automated tests (up from 21 at previous snapshot), covers all positive and negative
paths for the rename, find-usages, and navigation features, and is installable in
IntelliJ 2023.2+.

The Drools migration work is planned and tracked — see
[2026-04-08-drools-migration-planning](2026-04-08-drools-migration-planning.md).

## How We Got Here

*Core decisions unchanged from previous snapshot — `git show HEAD~1:docs/design-snapshots/2026-04-08-intellij-plugin.md`*

New decisions this session:

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Rename redirect from generated files (#8) | `substituteElementToRename()` with PSI scan fallback | Silent redirect — no dialog; PSI fallback makes tests work without custom FileBasedIndex being populated | `GeneratedFileRenameHandler.invoke()` doing the redirect — requires programmatic rename invocation, complex; hard-block dialog — poor UX |
| Find usages across family (#9) | `PermuteFamilyFindUsagesAction` — explicit right-click action | User opts in; results in standard Find panel; no surprise augmentation of standard Find Usages | `FindUsagesHandlerFactory` interception — silently augments every Find Usages call on template members, confusing |
| PSI scan fallback for index-dependent code | Dual-path: fast FileBasedIndex + fallback `ProjectFileIndex.iterateContent()` | Makes both production and `BasePlatformTestCase` tests work; custom `FileBasedIndexExtension` not populated synchronously in tests | Force-populating index in tests — complex, fragile; mocking — diverges from production |

## Where We're Going

**Next steps:**
- #4 — VS Code extension: TypeScript port of `AnnotationStringAlgorithm`; full porting guide now in `permuplate-ide-support/DESIGN.md`
- #5/#7 — Drools migration: blocked on droolsvol2 refactor completing (see Drools snapshot)

**Open questions:**
- `actionPerformed()` in `PermuteFamilyFindUsagesAction` is untested (calls `FindUsagesManager.findUsages()` which opens UI panel — hard to assert in headless tests). Acceptable as manual smoke test only?
- `untilBuild` strategy: keep `provider { null }` forever, or set a rolling ceiling before JetBrains Marketplace submission?
- Annotation string navigation (#2) still has no automated test — hard to automate without full project index. Acceptable gap?

## Linked ADRs

*(No ADRs for plugin — decisions captured in this snapshot and in `permuplate-ide-support/DESIGN.md`)*

## Context Links

- Algorithm foundation + VS Code porting guide: `permuplate-ide-support/DESIGN.md`
- Design spec: `docs/superpowers/specs/2026-04-07-intellij-plugin-design.md`
- GitHub issues: #6 (IDE tooling parent epic), #8 (done), #9 (done), #4 (VS Code — low priority)
- Related: [2026-04-08-drools-migration-planning](2026-04-08-drools-migration-planning.md)
