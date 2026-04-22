# Handover — 2026-04-22 (annotation audit + codebase refine)

**Head commit:** `394fe8e` — everything committed and clean.
**Status:** Clean — nothing uncommitted.

---

## What Changed This Session

### Annotation removals

| Removed | Why |
|---|---|
| `@PermuteConst` | Backward-compat alias for `@PermuteValue`; one usage migrated in `FormatSerializer.java` |
| `@PermuteNew` | Redundant — coherence inference covers common case; `@PermuteDeclr TYPE_USE` covers edge cases |

Annotation count: 37 → 35. All traces removed from CLAUDE.md, OVERVIEW.md, tests.

### Codebase refine (4 items)

| Item | What |
|---|---|
| `AstUtils.java` in permuplate-core | 12 utility methods extracted from InlineGenerator + PermuteProcessor. `expandMethodTypesForJ` had diverged — PermuteProcessor had a bug fix InlineGenerator lacked; extraction surfaced it. |
| `DroolsDslTestBase` | Shared `@Before setUp()` for 3 sandbox test classes with identical Ctx fixture |
| InlineGenerator section comments | 6 navigation headers added to 3000-line file |
| OVERVIEW.md → OVERVIEW + ARCHITECTURE | API reference stays in OVERVIEW; pipeline/modules/testing → ARCHITECTURE.md (now the project's DESIGN.md equivalent) |

### Docs cleanup (from health check)

- CLAUDE.md annotation table: removed `@PermuteConst` + `@PermuteNew` rows; count updated 26→27
- OVERVIEW.md: removed full `@PermuteConst` section, pipeline refs to deleted methods, stale file tree
- README.md: JavaParser version `3.25.9` → `3.28.0`
- `docs/design-snapshots/` broken links removed from CLAUDE.md

---

## Key non-obvious decisions

- **`AstUtils` is the canonical home** for AST name/type-string utilities — add new ones there, not as private methods in InlineGenerator or PermuteProcessor
- **`@PermuteStatements`** is marked "under review for removal" in its Javadoc — no current template uses it; `@PermuteBody` covers everything

*Previous session decisions: `git show HEAD~8:HANDOFF.md`*

---

## Immediate Next Step

Maven Central release. Group ID still undecided:
- `io.github.mdproctor` — instant namespace approval
- `io.quarkiverse` — slower Quarkiverse review

**305 tests green. Everything pushed.**

---

## References

| What | Where |
|---|---|
| Blog entry | `site/_posts/2026-04-22-mdp01-cleaning-house-before-shipping.md` |
| Garden entries | `GE-20260422-b3423e` (diverged-extraction gotcha), `GE-20260422-9d2c28` (grep-l technique) |
| Previous handover | `git show HEAD~8:HANDOFF.md` |
| ROADMAP | `docs/ROADMAP.md` |
