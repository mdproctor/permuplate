# Handover — 2026-04-18 (session 2)

**Head commit:** `82e10f2` — docs: session handover 2026-04-18 (previous session)
**Status:** All changes uncommitted — see below.

---

## What Changed This Session

### Documentation audit — all MD files reviewed and corrected

Six MD files updated for drift:

| File | What changed |
|---|---|
| `CLAUDE.md` | Trimmed 313→255 lines; removed 40 stale entries from key decisions table |
| `OVERVIEW.md` | Roadmap: IntelliJ plugin marked done (was listed as future work); module structure: 5 missing annotations added; dead `docs/design-snapshots/` and `docs/blog/` references fixed |
| `README.md` | Added `@PermuteAnnotation` and `@PermuteThrows` sections (both absent); fixed `inline` description (top-level classes now supported); fixed `from=1, to=4` int literals → strings; fixed blog footer |
| `docs/ROADMAP.md` | 4 shipped items (string-set, @PermuteFilter, records, @PermuteAnnotation) moved to completed section |
| `permuplate-ide-support/DESIGN.md` | VS Code extension marked parked (issue #4) |
| `permuplate-mvn-examples/DROOLS-DSL.md` | Blog reference fixed: `docs/blog/` → `site/_posts/` |

### New example file

`permuplate-apt-examples/.../AnnotatedCallable2.java` — covers the 4 annotations that had no runnable example: `@PermuteAnnotation`, `@PermuteThrows`, `@PermuteCase`, `@PermuteImport`. Generates `AnnotatedCallable3..5`. Build verified green.

### Bug discovered: @PermuteCase body string literals silently dropped

`PermuteCaseTransformer.buildSwitchEntry()` wraps the evaluated body in `{...}` and calls `StaticJavaParser.parseBlock()`. If the body contains Java string literals, the parser throws; the catch block returns `new BlockStmt()` silently. Cases appear in the generated switch but have no statements.

**Workaround documented:** avoid string literals in `body` — use primitives (`return ${k};`) or method calls (`return String.valueOf(${k});`). Documented in README, submitted to garden as `GE-20260418-90907d`.

---

## Immediate Next Step

Commit all staged changes:
```
git add -A
git commit -m "docs: audit and update all MD files; add AnnotatedCallable2 example"
```

Then: read actual droolsvol2 source at `/Users/mdproctor/dev/droolsoct2025/droolsvol2/` (at least one arity family) before writing any vol2 comparison article — the sandbox was built from tests, not source.

---

## References

| Context | Where |
|---|---|
| Previous handover | `git show HEAD:HANDOFF.md` |
| Garden entry (PermuteCase bug) | `~/.hortora/garden/permuplate/GE-20260418-90907d.md` |
| New blog entry | `site/_posts/2026-04-18-mdp02-bug-found-writing-example.md` |
| Vol2 source | `/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/main/java/org/drools/core/` |
