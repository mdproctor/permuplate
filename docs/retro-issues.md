# Retro Issues — JEXL String Assistance (2026-04-22/23)

Scope: commits from `a213231..HEAD` (19 commits, feature/jexl-completion branch).

All commits map to **existing** GitHub issues #114–#122. No new issues needed.
The goal is to close those issues with comments linking the unlinked fix commits.

---

## Epic

### #114 (existing) — JEXL string assistance in IntelliJ plugin

Commits that reference the epic directly but close no child issue:
- `2093032` docs: document JEXL language injection decisions and architecture

---

## Child Issues

### #115 (existing) — PermuteAnnotations shared constants refactor

Commits:
- `176b4e0` refactor: extract PermuteAnnotations shared constants — **closes #115** ✓
- `ec1e157` refactor: complete ALL_ANNOTATION_FQNS and optimise isPermuteAnnotation() — **part of #115** (unlinked)

### #116 (existing) — JEXL language infrastructure

Commits:
- `f82c1d9` feat: JexlTokenTypes and JexlLexer — part of #116 ✓
- `2cc8e28` fix: replace TWO_CHAR_OPS string with Set.of() — **part of #116** (unlinked)
- `a5719be` fix: add % operator, BUILTIN_NAMES to highlighter — **part of #116** (unlinked)
- `698ee28` feat: JEXL language infrastructure — **closes #116** ✓

### #117 (existing) — JexlContext, JexlBuiltin, JexlContextResolver

Commits:
- `505b208` feat: JexlContext, JexlBuiltin, JexlContextResolver — **closes #117** ✓
- `ef50608` fix: @PermuteVar lives in extraVars={} not as class annotation — **part of #117** (unlinked; "followup" informal)

### #118 (existing) — JexlLanguageInjector

Commits:
- `63ccdaa` feat: JexlLanguageInjector — **closes #118** ✓

### #119 (existing) — JexlCompletionContributor

Commits:
- `1353e1e` feat: JexlCompletionContributor — **closes #119** ✓
- `1376352` fix: insert handler adds (), BUILTIN_NAMES derives from JexlBuiltin.ALL — **part of #119** (unlinked)

### #120 (existing) — JexlParameterInfoHandler

Commits:
- `9ca8d48` feat: JexlParameterInfoHandler — **closes #120** ✓
- `90acb60` fix: updateParameterInfo tracks nested paren depth — **part of #120** (unlinked; "followup" informal)

### #121 (existing) — JexlAnnotator

Commits:
- `8cedc4c` feat: JexlAnnotator — **closes #121** ✓
- `2b02ac4` fix: annotator fires on JexlFile root only, add JEXL_KEYWORDS — **part of #121** (unlinked)

### #122 (existing) — End-to-end JEXL tests

Commits:
- `67a7085` test: end-to-end JEXL assistance tests — **closes #122** ✓

---

## Standalone (no child issue)

These are project-level documentation commits, not feature work:

- `63d2ec1` docs: extract DECISIONS.md from CLAUDE.md — project docs housekeeping
- `786d9ae` docs: slim CLAUDE.md — project docs housekeeping

These do not warrant GitHub issues. They will be mentioned in the epic closing comment.

---

## Excluded Commits

None. All 19 commits are non-trivial and covered above.

---

## Action plan (Step 8)

For each existing issue: add a GitHub comment listing the associated commits, then close it.

No new issues to create.
