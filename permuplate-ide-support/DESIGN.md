# permuplate-ide-support — Design Reference

This module is the pure-Java algorithm foundation for all Permuplate IDE tooling. It has zero runtime dependencies (JUnit for tests only) and is shared across the IntelliJ plugin, the VS Code extension (via a TypeScript port), and the annotation processor's compile-time validation.

The full plugin design is in `docs/superpowers/specs/2026-04-07-intellij-plugin-design.md`. This document covers the algorithm layer in detail and explains how the plugin components build on top of it.

---

## Module Contents

```
permuplate-ide-support/
└── src/main/java/io/quarkiverse/permuplate/ide/
    ├── AnnotationStringPart.java       — parsed segment of a template string
    ├── AnnotationStringTemplate.java   — fully parsed template (list of parts)
    ├── AnnotationStringAlgorithm.java  — parse / match / rename / validate
    ├── RenameResult.java               — sealed result of computeRename()
    └── ValidationError.java           — structured validation error with suggestion
```

---

## The Problem This Module Solves

Permuplate annotation attributes like `className = "Join${i}"`, `type = "Callable${i}"`, and `name = "o${j}"` are opaque strings to the IDE. When you rename `Join2` → `Merge2`, IntelliJ updates the Java class name but leaves `"Join${i}"` untouched — the annotation string no longer refers to anything valid.

This module provides:
1. **Parsing** — turn `"Join${i}"` into a structured template
2. **Matching** — decide whether a template string refers to a given class name
3. **Rename computation** — given old name → new name, compute the updated template string
4. **Validation** — detect broken or malformed annotation strings at compile time and in the IDE

---

## Data Model

### `AnnotationStringPart` (record)

One segment of a parsed annotation string. Either a variable placeholder or a static literal.

```java
AnnotationStringPart.variable("i")        // from ${i}
AnnotationStringPart.literal("Callable")  // static text
AnnotationStringPart.literal("")          // empty literal (adjacent variables)
```

### `AnnotationStringTemplate` (record)

A fully parsed annotation string as an ordered list of `AnnotationStringPart` values.

```
"${v1}Callable${v2}"  →  [Variable("v1"), Literal("Callable"), Variable("v2")]
"Join${i}"            →  [Literal("Join"), Variable("i")]
"Object"              →  [Literal("Object")]
```

Key methods:
- `toLiteral()` — reconstruct the original string
- `staticLiterals()` — non-empty literal segments, in order
- `hasNoVariables()` — true if no `${…}` in the string
- `hasNoLiteral()` — true if no non-empty literal segment exists

### `ValidationError` (record)

A structured error from `validate()`, with:
- `ErrorKind`: `NO_VARIABLES`, `UNMATCHED_LITERAL`, `ORPHAN_VARIABLE`, `NO_ANCHOR`
- `varName` — the variable involved (for `ORPHAN_VARIABLE` only)
- `suggestion` — human-readable fix text (shown as quick-fix in the IDE)

### `RenameResult` (sealed interface)

Three possible outcomes from `computeRename()`:

| Variant | Meaning |
|---|---|
| `Updated(String newTemplate)` | Rename computed successfully; use the new template string |
| `NoMatch()` | This annotation string doesn't reference the renamed class |
| `NeedsDisambiguation(List<String> affectedLiterals)` | String references the class but the rename is ambiguous; IDE must show a dialog |

---

## Algorithm API — `AnnotationStringAlgorithm`

### `parse(String template) → AnnotationStringTemplate`

Parses a raw annotation string into structured parts using regex. Adjacent variables produce empty literal segments between them.

```java
parse("Join${i}")          // [Literal("Join"), Variable("i")]
parse("${v1}${v2}Join${i}") // [Literal(""), Variable("v1"), Literal(""), Variable("v2"), Literal("Join"), Variable("i")]
```

### `expandStringConstants(AnnotationStringTemplate, Map<String,String> constants) → AnnotationStringTemplate`

Substitutes named string constants into the template, merging adjacent literals that result. Used by the processor to resolve `strings = {"type=First"}` before validation.

```java
// template: "${type}${i}",  constants: {"type":"Join"}
// result:   "Join${i}"  →  [Literal("Join"), Variable("i")]
```

### `matches(AnnotationStringTemplate, String className) → boolean`

Returns `true` if all static literals in the template appear as substrings of `className` in order.

Returns `false` if the template has no literals (can't anchor to anything).

```java
matches(parse("Join${i}"), "Join3")     // true  — "Join" is in "Join3"
matches(parse("Join${i}"), "Callable2") // false — "Join" is not in "Callable2"
matches(parse("${i}"),     "Join3")     // false — no literals
```

### `computeRename(AnnotationStringTemplate, String oldClassName, String newClassName) → RenameResult`

The core rename algorithm. Given the old class name, the new class name, and the current annotation string template, computes how the template should change.

**`NoMatch`** — no literal in the template is a substring of `oldClassName`. This string doesn't reference the renamed class; leave it alone.

**`Updated`** — each literal that appeared in `oldClassName` has a clear counterpart in `newClassName` (same relative position, predictable change). Returns the rewritten template.

```java
computeRename(parse("Join${i}"), "Join2", "Merge2")
// → Updated("Merge${i}")   — "Join" → "Merge", variable preserved

computeRename(parse("Callable${i}"), "Callable2", "Task2")
// → Updated("Task${i}")   — "Callable" → "Task"
```

**`NeedsDisambiguation`** — the literal changed in a way that can't be resolved automatically (e.g. a multi-segment template where different literals changed differently, or where the new class name has a structural mismatch). The IDE must show a dialog with the affected literals and let the user confirm.

### `validate(AnnotationStringTemplate, String targetName) → List<ValidationError>`

Validates a template after string constant expansion against its target class name. Checks four rules:

| Rule | ErrorKind | Condition |
|---|---|---|
| R4 | `NO_ANCHOR` | Template has no non-empty literal after expansion |
| R2 | `UNMATCHED_LITERAL` | A literal is not a substring of `targetName` |
| R3 | `ORPHAN_VARIABLE` | A variable group covers an empty region (both prefix and suffix are empty) |
| R1 | `NO_VARIABLES` | No variables in `className` — only enforced by the processor on `@Permute.className`, not here |

R2 short-circuits R3 and R4 — orphan computation is undefined when a literal isn't found.

---

## The 11 IDE Interaction Points

These are the refactoring operations that break without IDE plugin support, catalogued in blog entry `docs/blog/2026-04-07-mdp02-cleaning-house-finding-gap.md`.

| # | Interaction Point | What breaks without the plugin |
|---|---|---|
| 1 | **Rename ripple — template → generated** | Rename `join()` in `Join2`; generated `Join3`…`Join10` still have old name until next build, which is fine — but annotation strings like `@PermuteMethod(name="join${j}")` aren't updated at all |
| 2 | **Rename ripple — generated → template** | Rename inside `Join3.java`; only that one generated file changes; template is untouched; next build overwrites back to old name |
| 3 | **className= string opacity** | Rename `Join2` → `Merge2`; `className="Join${i}"` is not updated; processor generates wrong class names on next build |
| 4 | **Cross-family annotation string refs** | Rename `Callable2` → `Task2`; `@PermuteDeclr(type="Callable${i}")` in `Join2` is not updated; generated classes reference a non-existent type |
| 5 | **@PermuteMethod overload identity** | Go-to-definition on `join(o1, o2, o3)` in generated `Join3` navigates to the generated method, not the sentinel `join(o1)` in the template |
| 6 | **Boundary omission awareness** | A rename silently causes a method to be omitted from the first or last generated class; no warning is given |
| 7 | **Find Usages family blindness** | Find Usages on `Join2.join()` misses call sites that use `Join3.join()`, `Join4.join()`, etc. |
| 8 | **Safe Delete no-op** | Delete `Join3.java`; IntelliJ deletes the file; next build regenerates it silently |
| 9 | **Direct-edit overwrite** | Edit `Join3.java` directly; next build overwrites with no warning |
| 10 | **Package move orphans** | Move template package; generated file import references elsewhere in the project point to the old package and are not updated |
| 11 | **Type param rename ambiguity** | Rename `T${j}` naming convention; `@PermuteTypeParam(name="T${j}")` annotation string not updated |

---

## Plugin Architecture (IntelliJ)

The IntelliJ plugin (`permuplate-intellij-plugin/`, Gradle, Java) builds directly on this module. Full spec: `docs/superpowers/specs/2026-04-07-intellij-plugin-design.md`.

### Strategy: Intercept & Replay

Annotation strings are updated in the same rename transaction as the Java rename — one Ctrl+Z undoes everything. Generated files in `target/` are ephemeral and catch up on the next build.

### Foundation: Index-Based Family Awareness

A persistent `FileBasedIndex` maps templates to their generated families. All plugin components query the index at O(1) cost (except `allFamiliesReferencing`, which is a linear scan — acceptable since it only runs at rename time).

### Components

| Component | Extension Point | Covers # |
|---|---|---|
| **PermuteTemplateIndex** | `fileBasedIndex` | all (shared foundation) |
| **AnnotationStringRenameProcessor** | `renamePsiElementProcessor` | 1, 2, 3, 4, 11 |
| **GeneratedFileRenameHandler** | `renameHandler` (high priority) | 2 (reverse rename block) |
| **PermuteFamilyFindUsagesAction** | `<actions>` (EditorPopupMenu, ProjectViewPopupMenu) | 7 |
| **PermuteMethodNavigator** | `gotoDeclarationHandler` | 5 |
| **PermuteSafeDeleteDelegate** | `refactoring.safeDeleteProcessor` | 8 |
| **GeneratedFileNotification** | `editorNotificationProvider` | 9 |
| **PermutePackageMoveHandler** | `moveFileHandler` | 10 |
| **AnnotationStringInspection** | `localInspectionTool` | 3, 4, 6, 11 (lint) |
| **StaleAnnotationStringInspection** | `localInspectionTool` | 3, 4, 11 (drift detection) |
| **BoundaryOmissionInspection** | `localInspectionTool` | 6 |

### Index Structure

**Forward index (PermuteTemplateIndex)**
- Key: template class simple name (e.g. `"Join2"`)
- Value: `PermuteTemplateData` — varName, from, to, classNameTemplate, extraVars, strings, generatedNames[], templateFilePath, mode (APT/INLINE), memberAnnotationStrings[]

**Reverse index (PermuteGeneratedIndex)**
- Key: generated class simple name (e.g. `"Join4"`)
- Value: template class simple name (e.g. `"Join2"`)

Both emitted by the same indexer pass per `.java` file. No build required — pure static analysis of `@Permute` annotations.

### Rename Flow (template side)

```
User renames Join2 → Merge2
  → AnnotationStringRenameProcessor.prepareRenaming()
  → query PermuteTemplateIndex for all annotation strings referencing "Join" family
  → AnnotationStringAlgorithm.computeRename("Join${i}", "Join2", "Merge2") → Updated("Merge${i}")
  → add PsiLiteralExpression → "Merge${i}" to allRenames
  → IntelliJ commits: class renamed + strings updated atomically
  → next build: processor regenerates Join3→Merge3 … Join10→Merge10
```

### Rename Flow (generated side)

```
User presses Shift+F6 on a class inside Join3.java (target/generated-sources/)
  → GeneratedFileRenameHandler.isAvailableOnDataContext()
      → isPermuteManagedFile() checks index: is "Join3" a known generated class?
      → YES → returns false (handler steps aside — no block dialog)
  → Standard rename flow runs
  → AnnotationStringRenameProcessor.substituteElementToRename(Join3, editor)
      → fast path: FileBasedIndex reverse lookup "Join3" → "Join2"
      → fallback: PSI scan for @Permute annotation producing "Join3"
      → returns Join2 (template class)
  → IntelliJ renames Join2 (not Join3) with user's new name
  → prepareRenaming(Join2, "Merge2") updates annotation strings
  → next build regenerates Merge3…Merge5

For non-Permuplate generated files (no known template in index):
  → GeneratedFileRenameHandler.isAvailableOnDataContext() → true
  → Dialog: "Join3.java is generated from Join2.java. Rename there instead?"
  → [Go to Template] → opens Join2.java at corresponding element
```

### NeedsDisambiguation

When `computeRename()` returns `NeedsDisambiguation`, the plugin shows a dialog listing the affected annotation strings with editable text fields pre-filled with best-guess new values. User confirms or adjusts. Confirmed edits join the rename transaction.

### Generated File Detection

Files in `target/generated-sources/` are identified via the reverse index. The `GeneratedFileNotification` shows a banner on open:

> ⚠ **Generated by Permuplate** from `Join2.java`. Edits will be overwritten on next build. [Go to Template]

### Safe Delete

- **Deleting a generated file:** blocked. Dialog redirects to template with family impact summary.
- **Deleting the template:** allowed after impact dialog listing all generated permutations. Proceeds through IntelliJ's normal safe-delete flow.

### Package Move

Generated files regenerate into the correct new package automatically. The real risk is import statements elsewhere that reference the old generated package. The move handler extends IntelliJ's import update to cover all generated family names in the same transaction.

### Inspections

- **AnnotationStringInspection** — runs `AnnotationStringAlgorithm.validate()` on all annotation string attributes; surfaces R2/R3/R4 errors with `ValidationError.suggestion` as quick-fix
- **StaleAnnotationStringInspection** — detects strings that no longer match the index (rename happened but string wasn't updated); quick-fix recomputes from current class name
- **BoundaryOmissionInspection** — warns (not error) when `@PermuteReturn` / `@PermuteMethod` boundary evaluation will silently omit a method

---

## VS Code Extension — Porting Guide

### Algorithm Port (TypeScript)

The extension needs a TypeScript port of `permuplate-ide-support`. The port must:
- Implement `parse()`, `matches()`, `computeRename()`, `validate()` with identical semantics
- Ship matching Jest tests for every case covered by `AnnotationStringAlgorithmTest.java`
- Be the single source of truth — no divergence from the Java reference
- Bug fixes applied to both implementations simultaneously with matching tests in both

The port lives at `permuplate-vscode/src/algorithm.ts` (directory does not exist yet).

Start here: the Java source is in `permuplate-ide-support/src/main/java/io/quarkiverse/permuplate/ide/`.
The tests that define expected behaviour: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/` — specifically `AnnotationStringAlgorithmTest.java` in `permuplate-ide-support/src/test/`.

### Index Equivalent (no FileBasedIndex in VS Code)

IntelliJ uses `FileBasedIndex` (persistent, incremental). VS Code needs an equivalent:

**Option A — Language Server (recommended):** Implement a small Java Language Server (LSP) using `eclipse.jdt.ls` or a custom LSP that leverages the existing `PermuteTemplateIndex` logic. The client-side TypeScript extension communicates via LSP.

**Option B — in-process workspace scan:** On extension activation (and on file-save), scan all `.java` files in the workspace for `@Permute` annotations using a regex pre-filter (same as `containsPermute()` in the indexers). Build an in-memory map:
```typescript
// Forward: templateName → { varName, from, to, classNameTemplate, generatedNames[], templateFilePath }
// Reverse: generatedName → templateName
```
Re-scan on `workspace.onDidSaveTextDocument` when the saved file contains `@Permute`. This is O(changed files) on save — acceptable for most projects.

**Option B is the right starting point.** It needs no Java tooling, works immediately, and the scan logic mirrors `PermuteGeneratedIndex.getIndexer()` almost line-for-line.

### Feature Priority for VS Code

Not all 11 interaction points are equally valuable or equally feasible:

| Priority | # | Feature | VS Code API | Notes |
|---|---|---|---|---|
| **P1** | 3, 4 | Annotation string rename (className=, type=, name=) | `vscode.languages.registerRenameProvider` | Core value prop. Triggered on class/member rename. |
| **P1** | 11 | Same for `@PermuteTypeParam` name | same rename provider | Same provider, same algorithm. |
| **P2** | 1, 2 | Rename ripple template↔generated | rename provider + `substituteElementToRename` equivalent | VS Code rename providers can redirect; harder to hook cleanly. |
| **P2** | 3, 4 | Validation squiggles (R2/R3/R4) | `vscode.languages.createDiagnosticCollection` | Run `validate()` on annotation string literals. |
| **P3** | 7 | Find family usages | `vscode.commands.registerCommand` + right-click | Custom command, show in References panel. |
| **P3** | 5 | Go-to template method | `vscode.languages.registerDefinitionProvider` | Navigate from `@PermuteMethod` sentinel. |
| **P4** | 9 | Generated file banner | `vscode.window.showInformationMessage` on open | Simple — check file path for `generated-sources`. |
| **P4** | 8 | Safe delete warning | Not a direct VS Code concept — show warning dialog on delete | `workspace.onDidDeleteFiles` with undo prompt. |
| **Low** | 6 | Boundary omission warning | `DiagnosticCollection` | Requires evaluating `@PermuteReturn` / `@PermuteMethod` range expressions. Complex. |
| **Low** | 10 | Package move | Not a VS Code concept | Java refactoring only. Skip. |

**Recommended first milestone:** P1 rename provider only. This is the feature users hit most and it directly uses the TypeScript algorithm port.

### VS Code Rename Provider — How It Works

```typescript
// Triggered when user renames a Java symbol:
vscode.languages.registerRenameProvider('java', {
    provideRenameEdits(document, position, newName, token) {
        // 1. Find the symbol at position (class name, field, method)
        // 2. Look up in workspace index: is this symbol in a Permuplate template?
        // 3. For each annotation string in the template file:
        //      const template = parse(annotationStringValue);
        //      const result = computeRename(template, oldName, newName);
        //      if result is Updated: add WorkspaceEdit for the string literal
        //      if result is NeedsDisambiguation: prompt user (QuickPick or InputBox)
        // 4. Return WorkspaceEdit with all annotation string updates
    }
});
```

The `WorkspaceEdit` merges with IntelliJ's Java rename atomically (VS Code 1.75+). If using the Java extension's LSP, hook `client.onRequest('workspace/applyEdit', ...)`.

### Disambiguation Dialog

`computeRename()` can return `NeedsDisambiguation(affectedLiterals)`. In VS Code:
- Use `vscode.window.showQuickPick()` to list affected strings
- Pre-fill with best-guess values (same logic as IntelliJ's `DisambiguationDialog`)
- User selects which strings to update

### Generated File Detection

Same as IntelliJ: check if `document.uri.fsPath` contains `target/generated-sources`. No index needed.

### What NOT to Port

- `expandStringConstants()` — only needed by the annotation processor (Maven plugin), not for IDE tooling
- The `NeedsDisambiguation` dialog is a nice-to-have; ship the `Updated` path first
- Boundary omission inspection requires evaluating JEXL expressions — skip until later
