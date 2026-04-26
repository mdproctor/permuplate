# Handover — 2026-04-24 (DSL Gate Rename and Vol2 Discovery)

**Head commit:** `003793f` — 3 files uncommitted (CLAUDE.md, _posts/INDEX.md, new blog entry).

---

## What Changed This Session

1. **Issues closed** — #91–#113 (batches 8 and 9) verified shipped and closed.
2. **Jekyll site restructured** — `site/` removed; Jekyll builds from repo root. OVERVIEW.md and ARCHITECTURE.md now served as native site pages at `/overview/` and `/architecture/`.
3. **DROOLS-DSL.md accuracy pass** — test count 32+ → 118+, filter suppression corrected, missing sections added.
4. **JoinNSecond → JoinNGate rename** — sandbox and vol2 (`droolsvol2` branch). One file each.
5. **Vol2 discovery** — Consumer2/Predicate2 already have ctx removed; RuleBuilder already a Permuplate template (arity 2–10); `src/main/permuplate/` has RuleBuilder, RuleExtendsPoint, RuleOOPathBuilder, BaseTuple.

---

## Open Design Decisions (queued)

- **Remove END phantom type** — `Join2<CTX, B, C>` in vol2 confirms END was never there. `Join2First<END, DS, A, B>` → `Join2First<DS, A, B>`. Do together with:
- **Lambda-scope for not/exists** — `not(scope -> scope.join(...).filter(...))` replaces `not().join().filter().end()`. Removes END and the double-cast on scope filter.
- **ctx at end** — direction confirmed (vol2 already removed ctx from Consumer/Predicate). Big change; do separately.

---

## Immediate Next Steps

1. **Maven Central** — group ID still the gate: `io.github.mdproctor` (instant) vs `io.quarkiverse` (slower, implies Quarkus commitment).
2. **Remove END + lambda-scope** — linked refactor, do together.

---

## Notes

- Vol2 pre-existing build failure: 165 errors in Function8–10 re `@PermuteConst` — unrelated to Gate rename.
- Vol2 location: `/Users/mdproctor/dev/droolsoct2025/droolsvol2/`, branch `droolsvol2`.

*Everything else unchanged — `git show HEAD~1:HANDOFF.md`*
