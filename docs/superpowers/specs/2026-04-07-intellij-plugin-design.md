# Permuplate IntelliJ Plugin — Design Spec

**Date:** 2026-04-07
**Status:** Approved
**Scope:** IntelliJ plugin (VS Code extension is a follow-on; same design, TypeScript port of algorithm)

---

## Goal

Make every refactoring operation in IntelliJ fully aware of the Permuplate permutation chain. Renaming, find usages, go-to-definition, safe delete, package moves, and code inspections all understand that a template class drives N generated siblings and that annotation string attributes are live references — not opaque strings.

All 11 interaction points identified in blog entry `docs/blog/2026-04-07-mdp02-cleaning-house-finding-gap.md` are covered in v1.

---

## Module

**Location:** `permuplate-intellij-plugin/` — sibling directory in the repo, not aggregated into the Maven parent  
**Build:** Gradle + `intellij-platform-gradle-plugin` (plugin standard)  
**Language:** Java (IntelliJ Platform APIs are Java; plugin matches project language)  
**Target IDEs:** IntelliJ IDEA, Android Studio, other IntelliJ-based IDEs — 2023.2+ baseline  
**Dependency:** `permuplate-ide-support` jar (the algorithm library; bundled in plugin)  
**Distribution:** JetBrains Marketplace (eventual); local installation during development via `runIde` Gradle task

---

## Architecture

**Strategy:** Intercept & Replay — annotation strings updated in the same rename transaction as the Java rename. One Ctrl+Z undoes everything. Generated files in `target/` are ephemeral; they catch up on the next build.

**Foundation:** A persistent `FileBasedIndex` that maps templates to their generated families. All other components query this index — no file scanning at action time.

---

## Component 1 — PermuteTemplateIndex

The shared foundation. All other components depend on it.

**Extension point:** `com.intellij.fileBasedIndex`

**Two co-emitted index structures** (from the same indexer pass per `.java` file):

### Forward index — `PermuteTemplateIndex`
Key: template class simple name (e.g. `"Join2"`)  
Value: `PermuteTemplateData`:
- `varName`, `from`, `to`, `classNameTemplate` — from `@Permute`
- `extraVars`, `strings` — cross-product axes and string constants
- `generatedNames` — pre-computed list: `["Join3", "Join4", …, "Join10"]`
- `templateFilePath` — absolute path to source file
- `mode` — `APT` or `INLINE`
- `memberAnnotationStrings` — all `@PermuteDeclr`, `@PermuteParam`, `@PermuteTypeParam`, `@PermuteMethod` string attributes on class members (pre-scanned so the rename processor has O(1) access)

### Reverse index — `PermuteGeneratedIndex`
Key: generated class simple name (e.g. `"Join4"`)  
Value: template class simple name (e.g. `"Join2"`)

Both are emitted by the same `DataIndexer` — one file scan, two index entries.

**Key queries exposed:**
- `isTemplate(className)` — forward index has entry
- `isGenerated(className)` — reverse index has entry
- `familyOf(templateName)` — forward → `generatedNames[]`
- `templateFor(generatedName)` — reverse → template name → forward → full data
- `allFamiliesReferencing(literal)` — linear scan through all forward index values' `memberAnnotationStrings` for strings containing the given literal (acceptable cost: only called at rename time, not on every keystroke)

**Source root handling:**
- APT mode: templates in `src/main/java/` — indexed as standard source
- Inline mode: templates in `src/main/permuplate/` — plugin detects this directory and registers it as an additional source root automatically
- Generated files in `target/generated-sources/` — indexed when registered as source root by Maven plugin (standard behaviour)

---

## Component 2 — AnnotationStringRenameProcessor

Intercepts renames originating in a template and updates all affected annotation strings in the same transaction.

**Extension point:** `com.intellij.renamePsiElementProcessor`

**Trigger:** any rename of a class, method, field, parameter, or type parameter in a Permuplate context (template file or a class that is referenced by another template's annotation strings).

**Mechanism:** IntelliJ calls `prepareRenaming(element, newName, allRenames)` before committing. The processor:
1. Queries `PermuteTemplateIndex` for all annotation strings related to this element
2. Calls `AnnotationStringAlgorithm.computeRename(template, oldName, newName)` on each string
3. Adds the resulting `PsiLiteralExpression` → new string value into `allRenames`
4. IntelliJ commits everything atomically

**Element types handled:**

| Element | Annotation string updated |
|---|---|
| Class rename | `@Permute(className=…)`, cross-family `@PermuteDeclr(type=…)`, `@PermuteParam(type=…)` |
| Method rename | `@PermuteMethod(name=…)` if present |
| Field rename | `@PermuteDeclr(name=…)` on the field |
| Parameter rename | `@PermuteParam(name=…)` on the sentinel |
| Type parameter rename | `@PermuteTypeParam(name=…)` |

**NeedsDisambiguation handling:**  
When `computeRename()` returns `NeedsDisambiguation` (the literal changed ambiguously), the processor shows a small dialog listing the affected annotation strings with editable text fields pre-filled with best-guess new values. User confirms or adjusts. Confirmed edits join the rename transaction.

---

## Component 3 — GeneratedFileRenameHandler

Blocks renames originating inside generated files and redirects the user to the template.

**Extension point:** `com.intellij.renameHandler` (higher priority than default)

**Trigger:** Shift+F6 (or any rename action) when the cursor is in a file under `target/generated-sources/`.

**Flow:**
1. `isAvailableOnDataContext()` → true when file is generated (reverse index hit)
2. Rename is intercepted before IntelliJ's default handler
3. Dialog: *"Join3.java is generated by Permuplate from Join2.java. Edits will be overwritten on next build."* [Go to Template] [Cancel]
4. "Go to Template" opens `Join2.java` at the nearest corresponding element position
5. User performs the rename there; `AnnotationStringRenameProcessor` handles it

---

## Component 4 — PermuteFamilyFindUsagesHandlerFactory

Extends Find Usages to include all permutation siblings.

**Extension point:** `com.intellij.findUsagesHandlerFactory`

**Triggered from template:** Find Usages on `Join2.join()` returns usages of `Join2.join()` plus `Join3.join()` through `Join10.join()`, grouped in the results panel:
```
▾ join() — Permuplate family
  ▾ Join2.join() — 3 usages
    …
  ▾ Join3.join() — 5 usages
    …
```

**Triggered from generated file:** handler redirects to the sentinel method in the template and runs the same family search. Results are identical regardless of which family member you start from.

---

## Component 5 — PermuteMethodNavigator

Go-to-definition on a generated overload navigates to the sentinel method in the template.

**Extension point:** `com.intellij.gotoDeclarationHandler`

**Flow:** Ctrl+B on `join(Object o1, Object o2, Object o3)` in `Join3.java` (generated) → reverse index identifies template as `Join2.java` → finds `join(Object o1)` annotated with `@PermuteMethod` → navigates there.

Also handles: Ctrl+click on a string literal inside annotation attributes (`"Callable${i}"`) resolves the literal part "Callable" to the `Callable2` template class and navigates there. Annotation strings are no longer opaque.

Falls through to IntelliJ default if no template match is found.

---

## Component 6 — PermuteSafeDeleteDelegate

**Extension point:** `com.intellij.refactoring.safeDeleteProcessor`

**Deleting a generated file:** blocked with redirect.  
*"Join3.java is generated by Permuplate. To remove it, delete the template Join2.java — this will remove all 8 generated permutations."* [Go to Template] [Cancel]

**Deleting the template:** allowed, preceded by an impact dialog listing all generated permutations. Template deletion then proceeds through IntelliJ's normal safe-delete flow (unused reference checks etc.).

---

## Component 7 — GeneratedFileNotification

**Extension point:** `com.intellij.editorNotificationProvider`

Fires whenever a file under `target/generated-sources/` is opened. Shows a yellow banner:

> ⚠ **Generated by Permuplate** from `Join2.java`. Edits will be overwritten on next build. [Go to Template]

"Go to Template" navigates to the template at the nearest corresponding element.

---

## Component 8 — PermutePackageMoveHandler

**Extension point:** `com.intellij.moveFileHandler`

Generated files in `target/` regenerate into the correct new package on the next build — no orphan problem there. The real risk is import statements elsewhere referencing the old generated package.

Plugin extends IntelliJ's move handler to also update imports for all generated family names (`Join3`…`Join10`) across the project in the same move transaction.

---

## Component 9 — Inspections

### AnnotationStringInspection
Runs on all `@Permute`, `@PermuteDeclr`, `@PermuteParam`, `@PermuteTypeParam` string attributes. Calls `AnnotationStringAlgorithm.validate()` and surfaces R2/R3/R4 errors inline with `ValidationError.suggestion` as quick-fix text.

### StaleAnnotationStringInspection
Detects strings that no longer match anything in the index after a rename (i.e. the rename happened but the string wasn't updated). Quick-fix: recompute the expected string from the current class name.

### BoundaryOmissionInspection
Warns on `@PermuteReturn` / `@PermuteMethod` methods where boundary evaluation will silently omit the method from the first or last generated class. Severity: warning (not error). Suggestion: add `when="true"` to override, or adjust `from`/`to`.

---

## Interaction Points Coverage

| # | Interaction Point | Component(s) |
|---|---|---|
| 1 | Rename ripple — template → generated | 2 (AnnotationStringRenameProcessor) |
| 2 | Rename ripple — generated → template | 3 (GeneratedFileRenameHandler) |
| 3 | className= string opacity | 2, 5 (rename + navigation) |
| 4 | Cross-family annotation string refs | 2 (cross-family string update) |
| 5 | @PermuteMethod overload identity | 5 (PermuteMethodNavigator) |
| 6 | Boundary omission awareness | 9 (BoundaryOmissionInspection) |
| 7 | Find Usages across family | 4 (PermuteFamilyFindUsagesHandlerFactory) |
| 8 | Safe Delete redirect | 6 (PermuteSafeDeleteDelegate) |
| 9 | Generated file edit warning | 7 (GeneratedFileNotification) |
| 10 | Package move orphan prevention | 8 (PermutePackageMoveHandler) |
| 11 | Type param rename in annotation strings | 2 (AnnotationStringRenameProcessor) |

---

## Source Layout

```
permuplate-intellij-plugin/
├── build.gradle.kts
├── gradle/
│   └── wrapper/
├── src/
│   ├── main/
│   │   ├── java/io/quarkiverse/permuplate/intellij/
│   │   │   ├── index/
│   │   │   │   ├── PermuteTemplateIndex.java
│   │   │   │   ├── PermuteGeneratedIndex.java
│   │   │   │   └── PermuteTemplateData.java
│   │   │   ├── rename/
│   │   │   │   ├── AnnotationStringRenameProcessor.java
│   │   │   │   └── GeneratedFileRenameHandler.java
│   │   │   ├── findusages/
│   │   │   │   └── PermuteFamilyFindUsagesHandlerFactory.java
│   │   │   ├── navigation/
│   │   │   │   └── PermuteMethodNavigator.java
│   │   │   ├── safedelete/
│   │   │   │   └── PermuteSafeDeleteDelegate.java
│   │   │   ├── editor/
│   │   │   │   └── GeneratedFileNotification.java
│   │   │   ├── move/
│   │   │   │   └── PermutePackageMoveHandler.java
│   │   │   └── inspection/
│   │   │       ├── AnnotationStringInspection.java
│   │   │       ├── StaleAnnotationStringInspection.java
│   │   │       └── BoundaryOmissionInspection.java
│   │   └── resources/META-INF/
│   │       └── plugin.xml
│   └── test/
│       └── java/io/quarkiverse/permuplate/intellij/
│           ├── index/PermuteTemplateIndexTest.java
│           ├── rename/AnnotationStringRenameProcessorTest.java
│           └── inspection/AnnotationStringInspectionTest.java
```

---

## Testing Strategy

IntelliJ plugin tests use `LightJavaCodeInsightTestCase` / `UsefulTestCase` from the IntelliJ test framework (available via `intellij-platform-gradle-plugin`).

- **Index tests:** write template source files to the light fixture, trigger indexing, assert forward/reverse entries
- **Rename tests:** invoke rename refactoring programmatically, assert annotation string values updated and undo works
- **Inspection tests:** use `LightInspectionTestCase`, assert highlighting positions and quick-fix text
- **Find usages tests:** assert family member results included
- **Navigation tests:** assert go-to-declaration target

---

## Out of Scope (v1)

- VS Code extension — same design, TypeScript port of `AnnotationStringAlgorithm` required; follow-on
- Live preview of generated output in a tool window
- Gutter icons linking template ↔ generated
- `@Permute`-aware code completion in annotation string attributes
