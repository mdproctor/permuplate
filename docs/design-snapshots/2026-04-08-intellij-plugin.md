# Permuplate IntelliJ Plugin — Design Snapshot
**Date:** 2026-04-08
**Topic:** IntelliJ plugin — full IDE refactoring awareness for Permuplate templates
**Supersedes:** *(none)*
**Superseded by:** [2026-04-08-intellij-plugin-complete](2026-04-08-intellij-plugin-complete.md)

---

## Where We Are

The IntelliJ plugin (`permuplate-intellij-plugin/`, Gradle, Java 17) is built, tested, and installable in IntelliJ 2023.2+. It covers all 11 interaction points from the design spec: annotation-string-aware rename, go-to-definition, generated file banner, safe delete redirect, package move, and three inline inspections. The plugin ships as a zip installable via **Settings → Plugins → Install Plugin from Disk**. 21 automated tests pass. Smoke tested in IntelliJ Ultimate 2025.3 (build IU-253.x).

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Build system for plugin | Gradle + `intellij-platform-gradle-plugin` 2.x | JetBrains' official tooling; `runIde`, sandboxed tests, zip packaging all built-in | Maven `intellij-platform-maven-plugin` — less mature, poor community support |
| Plugin language | Java | Project is Java; platform APIs are Java; no practical Kotlin benefit | Kotlin — standard for new plugins but no gain given Java expertise |
| Family mapping persistence | `FileBasedIndexExtension` pair (forward + reverse) | Persistent across IDE restarts; O(1) at action time; no rebuild required | On-demand PSI scanning — correct but slow; rebuilds on every IDE action |
| Rename strategy | `getPostRenameCallback()` + `SmartPsiElementPointer` | Runs after write action, avoiding `PostprocessReformattingAspect` adding spurious spaces; pointers survive PSI invalidation during write action | `renameElement()` — causes reformatter to add spaces around `=` in annotations |
| Annotation lookup in indexer | `getReferenceName()` (simple name, source text only) | No FQN resolution → no recursive index reads → no crash | `getAnnotation(fqn)` / `getQualifiedName()` — triggers stub index reads inside indexer, causing `StorageException` on arbitrary files |
| IDE compatibility bounds | `sinceBuild="232"`, `untilBuild=provider{null}` | Compatible with all future builds; no upper-bound auto-derivation | Omitting `untilBuild` — plugin 2.x auto-sets `untilBuild="${sinceBuild}.*"`, blocking all newer IDEs |
| Find usages across family | Custom action (planned #9), not intercepting standard | Standard Find Usages on a generated class should show callers of that class only — intercepting would surprise users | `FindUsagesHandlerFactory` interception — confusing: "why does Find Usages on Join5 show Join3 results?" |
| `renamePsiElementProcessor` ordering | `order="first"` | Required for `getPostRenameCallback()` to fire — only the primary processor (won via `forElement()` election) receives it | Default ordering — Java plugin's processor wins, callback silently never fires |

## Where We're Going

**Next steps:**
- #8 — Enhance rename from generated files: use `substituteElementToRename()` to redirect to template element rather than hard-blocking with a dialog
- #9 — Implement "Find Usages in Permutation Family" as a custom right-click action; remove `PermuteFamilyFindUsagesHandlerFactory`
- #4 — VS Code extension: TypeScript port of `AnnotationStringAlgorithm`, same 11 interaction points

**Open questions:**
- Should `PermuteFamilyFindUsagesHandlerFactory` be removed now (before #9 is implemented) or kept as a temporary working implementation?
- `untilBuild` strategy: keep `provider { null }` forever, or set a rolling ceiling before JetBrains Marketplace submission?
- Test coverage gap: annotation string navigation (#2) is hard to automate without a full project index — acceptable as manual smoke test only?

## Linked ADRs

*(No ADRs created for the plugin yet — key decisions are captured in this snapshot and in `docs/superpowers/specs/2026-04-07-intellij-plugin-design.md`)*

## Context Links

- Design spec: `docs/superpowers/specs/2026-04-07-intellij-plugin-design.md`
- Algorithm foundation: `permuplate-ide-support/DESIGN.md`
- Implementation plans: `docs/superpowers/plans/2026-04-07-intellij-plugin-p1-foundation-rename.md`, `docs/superpowers/plans/2026-04-07-intellij-plugin-p2-navigation-safety-inspections.md`
- GitHub issues: #1 (closed), #2 (closed), #3 (closed), #8 (rename ripple), #9 (custom find usages), #6 (IDE tooling parent epic)
