# Design: Generated Family Rename Propagation

**Date:** 2026-04-15  
**Status:** Approved  
**Scope:** IntelliJ plugin — `permuplate-intellij-plugin`

---

## Problem

When a developer renames a method or field in a Permuplate template class (e.g. `join()` in
`Join2Second`), IntelliJ's standard Java rename finds and updates usages of that specific
method — but the corresponding methods in the generated sibling classes (`Join3Second.join()`
through `Join6Second.join()`) are different PSI elements. Their call sites are not updated,
leaving the codebase broken until the next build regenerates the files.

**Constructor renames are already handled.** In Java, renaming a constructor is renaming the
class. The existing `substituteElementToRename()` mechanism already redirects generated-class
renames to the template. No new code is needed for constructors.

---

## Solution

Extend `AnnotationStringRenameProcessor.prepareRenaming()` with a new helper,
`addGeneratedFamilyRenames()`, that adds the corresponding method or field from every
generated sibling class to the `allRenames` map before IntelliJ processes the rename.

IntelliJ then renames all elements — template and siblings — and updates all their call sites
atomically in one undo step. No new extension points, no new files.

---

## Architecture

### Affected file

`src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java`

### New helper: `addGeneratedFamilyRenames()`

Called from `prepareRenaming()` when the element being renamed is a `PsiMethod` or
`PsiField`. Algorithm:

1. Get the containing `PsiClass`; bail if null.
2. Call `PermuteFileDetector.isTemplate(className, project)` — bail if not a template class.
3. Call `PermuteFileDetector.templateDataFor(className, project)` — get `generatedNames` list.
4. Derive the package from the template's `PsiJavaFile`.
5. For each generated class name:
   a. Resolve to `PsiClass` via `JavaPsiFacade.getInstance(project).findClass(fqn, allScope)`.
   b. Skip if null (class not yet indexed or outside project scope).
   c. For `PsiMethod`: call `generatedClass.findMethodsByName(element.getName(), false)`;
      add the first match to `allRenames` if found.
   d. For `PsiField`: call `generatedClass.findFieldByName(element.getName(), false)`;
      add to `allRenames` if found.

### Integration point in `prepareRenaming()`

```
prepareRenaming(element, newName, renameRefactoringQueries, allRenames) {
    // Existing: annotation string updates
    collectAnnotationStringUpdates(element, newName);

    // New: generated family renames
    addGeneratedFamilyRenames(element, newName, allRenames);
}
```

The two concerns are independent: annotation strings are always updated (they reference
the template), and generated family members are added to `allRenames` when applicable.

---

## Edge Cases

| Situation | Handling |
|---|---|
| Method absent in a generated class (boundary omission at leaf) | `findMethodsByName()` returns empty — skip silently |
| Generated class not yet indexed | `findClass()` returns null — skip silently |
| Method carries `@PermuteMethod` annotation | Skip — its generated names are controlled by the `name` attribute string, not the method name; the existing annotation string update already handles `name` |
| Element is in a non-template class | `isTemplate()` returns false — bail immediately |
| Template has no generated names in index | `templateDataFor()` returns null or empty list — bail |
| Multiple overloads with the same name | All matching overloads are added; IntelliJ renames all |

---

## Testing (TDD Order)

Tests go in `AnnotationStringRenameProcessorTest`. Each test uses
`myFixture.addFileToProject()` for the template and generated files, then
calls `myFixture.renameElement()` and asserts on `allRenames` content or on
post-rename file text.

| # | Test | What it verifies |
|---|---|---|
| 1 | `testMethodRenameInTemplateAddsGeneratedSiblingsToAllRenames` | Renaming `join()` in template adds `join()` from all generated classes to `allRenames` |
| 2 | `testFieldRenameInTemplateAddsGeneratedSiblingsToAllRenames` | Same for field rename |
| 3 | `testMethodAbsentInBoundaryOmittedClassIsSkipped` | Generated class missing the method — no NPE, no error |
| 4 | `testRenameInNonTemplateClassProducesNoFamilyRenames` | Element in a plain class — `addGeneratedFamilyRenames` adds nothing |
| 5 | `testPermuteMethodAnnotatedSentinelIsSkipped` | Method with `@PermuteMethod` — not added to `allRenames` |
| 6 | `testEndToEndMethodRenameUpdatesCallSiteOnGeneratedType` | Full `renameElement()` call — verifies generated file and a call site both updated |

Test 6 is the integration test that validates the complete atomic rename. Tests 1–5 are unit
tests that validate the helper's logic in isolation.

---

## What Is Not In Scope

- **VS Code extension** — parked.
- **Generated file edit warning** — `GeneratedFileNotification` already shows a banner on
  open; no change needed.
- **`@PermuteMethod` generated method rename** — those methods have names derived from the
  `name` attribute string, not the sentinel method name. The annotation string update already
  handles the `name` attribute. Propagating the sentinel rename itself to the differently-named
  generated methods is a separate, lower-priority concern.
- **Inline mode** — generated classes in inline mode are nested siblings in a parent file;
  they share the same PSI space and IntelliJ's rename already finds them. No special handling
  needed.
