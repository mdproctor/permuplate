# Handover — 2026-04-23 (JEXL string assistance shipped)

**Head commit:** `cddc9c4` — everything committed and clean.
**Status:** Clean — nothing uncommitted.

---

## What Changed This Session

**JEXL string assistance** — full IDE authoring support for `${...}` expressions in all Permuplate annotation string attributes. Shipped in `permuplate-intellij-plugin`:

| Layer | What was built |
|---|---|
| `JexlLexer` | Hand-written flat lexer — 10 token types, two-char operators, % included |
| `JexlLanguageInjector` | One `startInjecting`/`doneInjecting` per `${...}` range (not one session per attribute) |
| `JexlContextResolver` | PSI walker — reads primary varName, `extraVars={@PermuteVar(...)}`, `strings=`, `macros=`, inner `@PermuteMethod` var |
| `JexlCompletionContributor` | Variables + built-ins; insert handler adds `()` |
| `JexlParameterInfoHandler` | Signatures for 7 built-ins; nested-paren depth tracking |
| `JexlAnnotator` | JEXL3 syntax validation + undefined variable warnings; `instanceof JexlFile` root guard |
| `PermuteAnnotations` | Shared constants — all 29 FQNs, `JEXL_BEARING_ATTRIBUTES`, O(1) `isPermuteAnnotation()` |

Also: `@PermuteReturn` and `@PermuteDefaultReturn` added to rename propagation (closes the `className=` staleness gap on class rename).

Plugin test count: 89 → 178. All green.

---

## Key Non-Obvious Decisions

- **`@PermuteVar @Target({})`** — only valid inside `@Permute(extraVars={...})`, never standalone. Scanning class annotations finds nothing.
- **Flat PSI annotator guard** — `element.getParent() != file` is always false for leaf tokens. Use `instanceof JexlFile` instead.
- **MultiHostInjector addPlace() concatenation** — multiple `addPlace()` in one session concatenates content into one `PsiFile`. One session per range.
- Both in `DECISIONS.md` (auto-loaded) and garden entries `GE-20260423-442a71`, `GE-20260423-af487b`.

---

## Immediate Next Step

Maven Central release. Group ID undecided:
- `io.github.mdproctor` — instant namespace approval
- `io.quarkiverse` — slower review; implies a real Quarkus extension

Pick one and start the Central publication process.

---

## Open Questions

- `@PermuteStatements` — still marked "under review for removal", no decision taken
- JEXL completion for bare-JEXL attributes (`@PermuteFilter(when="i > 2")` — no `${...}`, so injector doesn't fire)
- Gradle plugin (inline mode unavailable to Gradle today)

---

## References

| What | Where |
|---|---|
| Blog entry | `site/_posts/2026-04-23-mdp01-jexl-string-assistance.md` |
| Design snapshot | `snapshots/2026-04-23-jexl-string-assistance.md` |
| Spec | `docs/superpowers/specs/2026-04-22-jexl-completion-design.md` |
| Plan | `docs/superpowers/plans/2026-04-22-jexl-completion.md` |
| Retro-issues | `docs/retro-issues.md` |
| ROADMAP | `docs/ROADMAP.md` |
| Previous handover | `git show 754436f:HANDOFF.md` |
