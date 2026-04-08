# Drools Migration Planning — Design Snapshot
**Date:** 2026-04-08
**Topic:** Permuplate-generated Drools vol2 DSL — gap analysis and issue structure
**Supersedes:** *(none)*
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

A full gap analysis has been completed against the vol2 reference test suite.
Four test classes pass in the existing sandbox (`RuleBuilderTest`, `ExtensionPointTest`,
`NamedRuleTest`, `TupleAsTest`); nine remain as gaps across six gap areas. GitHub epics
and child issues are created in `apache/incubator-kie-drools`. A `drools-migration` branch
exists in the permuplate repo for the Permuplate template work (parked until droolsvol2
compiles cleanly). Permuplate is wired into `droolsvol2/pom.xml` but no `@Permute`
templates exist yet.

The droolsvol2 module is currently mid-refactor: it does not compile standalone
because ~14 files still reference `org.drools.core.common` / `.reteoo` symbols from
`drools-core`, which vol2 is explicitly removing as a dependency. This refactor must
complete before Permuplate template work begins.

See [2026-04-08-intellij-plugin-complete](2026-04-08-intellij-plugin-complete.md) for
the plugin state that will eventually support IDE tooling for the generated DSL.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Epic structure | Capability area (not phase/timing) as primary axis | Area-based epics remain meaningful after work ships; phase names go stale immediately | Phase-based epics — "Phase 1: Foundation" meaningless once done |
| Sub-epic promotion threshold | Only promote to sub-epic when scope warrants multiple commits | Rule Base & Registry is architectural, many files → sub-epic; OOPath extension is incremental → child issue | One epic per gap area — Rule Base would be buried in DSL sub-epic despite being architecturally distinct |
| GitHub issue location | `apache/incubator-kie-drools` (blessed upstream repo) | Work belongs in the repo it affects; permuplate#5/#7 cross-reference | `mdproctor/drools` fork — issues were disabled (fork of Apache project) |
| Permuplate wiring | APT processor in `droolsvol2/pom.xml` annotationProcessorPaths | Annotations needed at compile time; APT mode generates separate top-level files (correct for JoinNFirst family) | Maven plugin (inline mode) — generates nested classes, wrong for this use case |
| Order of work | droolsvol2 refactor first, then @Permute templates | Cannot add templates until module compiles | Adding templates now — impossible without compilation |

## Where We're Going

**Next steps (immediate):**
- Finish droolsvol2 refactor — eliminate all remaining `drools-core` references from the 14 broken files
- droolsvol2 must compile standalone (`mvn test` passing) with four sandbox tests green

**Next steps (after refactor):**
- Add `@Permute`-annotated template classes to droolsvol2 (e.g. `Join0First2.java` → generates `Join0First3.java`…`Join0FirstN.java`)
- Work through gap issues in capability order: #6640 (OOPath) → #6641 (Data Store) → #6642 (DataBuilder) → #6643 (Type System) → #6644 (Context/Routing) → #6645 (Tuple/BiLinear)
- #6646 (Rule Base & Registry) and #6647 (Executor) are separate sub-epic, later

**Open questions:**
- droolsvol2 refactor: for each of the 14 broken files, decision needed — replace `drools-core` reference with vol2-native equivalent, copy-and-strip, or delete entirely?
- Once droolsvol2 compiles, does the git history need cleaning before Permuplate templates are added? (Mark flagged this — future git history audit task)
- kie-api surface: which parts will vol2 keep long-term vs extract into its own `drools-api`?

## Linked ADRs

*(No ADRs yet for Drools migration — decisions are in this snapshot and in `apache/incubator-kie-drools` issues)*

## Context Links

- GitHub main epic: `apache/incubator-kie-drools#6639`
- DSL sub-epic: `apache/incubator-kie-drools#6638` (child issues #6640–#6645)
- Rule Base sub-epic: `apache/incubator-kie-drools#6646` (child issue #6647)
- Permuplate tracking epics: mdproctor/permuplate#5, mdproctor/permuplate#7
- Gap analysis reference: vol2 test suite at `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/test/java/org/drools/core/`
- drools-migration branch: `mdproctor/permuplate` — `drools-migration`
- Drools sandbox: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/`
