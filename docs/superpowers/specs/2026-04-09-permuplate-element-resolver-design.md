# PermuteElementResolver — Design Spec
**Date:** 2026-04-09
**Topic:** Extend rename redirect to method/field/parameter; consolidate template-resolution logic

---

## Problem

`AnnotationStringRenameProcessor.substituteElementToRename()` only redirects
class-level renames from generated files to the template. If you rename a
**method or field** inside a generated file, the redirect does not fire — the
generated element is renamed directly and overwritten on the next build.

Additionally, the same "find template element for a generated class name" logic
is duplicated between `AnnotationStringRenameProcessor` (`findTemplateByPsiScan`)
and `PermuteMethodNavigator`, and `stripTrailingDigits` exists as separate private
copies in both `AnnotationStringRenameProcessor` and `PermuteFamilyFindUsagesAction`.

---

## Goals

1. Rename redirect works from any element in a generated file — class, method, field, parameter
2. Eliminate duplicate template-resolution and `stripTrailingDigits` logic
3. Leave `PermuteMethodNavigator` feature 2 (generated-file navigation) easy to wire up later

---

## Architecture

New class `PermuteElementResolver` in the `index` package (alongside
`PermuteFileDetector`). It owns one concern: given a PSI element in a generated
file, find the corresponding template element.

```
index/
  PermuteFileDetector.java          (unchanged)
  PermuteElementResolver.java       (new)
  PermuteTemplateIndex.java         (unchanged)
rename/
  AnnotationStringRenameProcessor.java   (substituteElementToRename delegates)
navigation/
  PermuteMethodNavigator.java           (feature 2 will delegate later — no change now)
  PermuteFamilyFindUsagesAction.java    (stripTrailingDigits deduped)
```

---

## `PermuteElementResolver` API

```java
public class PermuteElementResolver {

    /**
     * Given a PSI element in a generated file, returns the corresponding
     * element in the template. Returns the original element unchanged if:
     * - it is not in a generated file, or
     * - no template match can be found (graceful fallthrough).
     *
     * Handles: PsiClass, PsiMethod, PsiField, PsiParameter.
     */
    @Nullable
    public static PsiElement resolveToTemplateElement(
            @NotNull PsiElement element, @Nullable Editor editor);

    /**
     * Find the template PsiClass for a given generated class name.
     * Fast path: FileBasedIndex (PermuteGeneratedIndex reverse lookup).
     * Fallback: PSI scan over project source files (used when index not yet
     * populated, e.g. in tests).
     */
    @Nullable
    public static PsiClass findTemplateClass(
            @NotNull String generatedName, @NotNull Project project);

    /**
     * Strip trailing digits from a name. "c3" → "c", "join2" → "join".
     * Package-private — used by tests and internal callers.
     */
    static String stripTrailingDigits(String name);
}
```

---

## Resolution Logic

### PsiClass
1. Check if the element's containing file is a generated file (`PermuteFileDetector.isGeneratedFile`)
2. Call `findTemplateClass(cls.getName(), project)` — fast path via index, PSI fallback
3. Return the template class, or original element if not found

### PsiMethod / PsiField
1. Get the containing class; check if it is in a generated file
2. Find the template class via `findTemplateClass`
3. Strip trailing digits from the member name → base name
4. Find the first member in the template class whose name (digits stripped) matches
5. Return matching template member, or original element if no match

### PsiParameter
1. Get the containing method; get its containing class; check generated file
2. Find the template class, then find the matching template method (by base name)
3. Within the template method, find the first parameter whose base name matches
4. Return matching parameter, or original element if no match

### Failure modes
All steps degrade gracefully: if any lookup returns null or finds no match,
`resolveToTemplateElement` returns the original element unchanged — identical
to today's behaviour for the unhandled cases.

---

## Changes to Existing Classes

### `AnnotationStringRenameProcessor`
- `substituteElementToRename()` — replace body with single call to
  `PermuteElementResolver.resolveToTemplateElement(element, editor)`
- `findTemplateByPsiScan()` — delete; logic moves to `PermuteElementResolver.findTemplateClass()`
- `stripTrailingDigits()` — delete private copy; call `PermuteElementResolver.stripTrailingDigits()`

### `PermuteFamilyFindUsagesAction`
- `stripTrailingDigits()` — delete private copy; call `PermuteElementResolver.stripTrailingDigits()`

### `PermuteMethodNavigator`
- No changes. Feature 2 (navigate from generated element to template) will
  call `PermuteElementResolver.resolveToTemplateElement()` when implemented.

---

## Tests

New test class: `PermuteElementResolverTest` (`BasePlatformTestCase`)

| Test | What it asserts |
|---|---|
| `testResolvesGeneratedClassToTemplate` | `Join3` (generated) → `Join2` (template class) |
| `testResolvesGeneratedMethodToTemplateMethod` | `join()` in `Join3` → `join()` in `Join2` |
| `testResolvesGeneratedFieldToTemplateField` | `c3` in `Join3` → `c2` in `Join2` |
| `testResolvesGeneratedParameterToTemplateParameter` | `o3` param in `Join3.join()` → sentinel `o1` in `Join2.join()` |
| `testReturnsElementForNonGeneratedFile` | Template/source file → no redirect, element returned unchanged |
| `testReturnsElementWhenNoTemplateInProject` | Generated file but no `@Permute` template → original element returned |
| `testStripTrailingDigits` | Consolidates the two existing copies of this test |

Existing tests in `AnnotationStringRenameProcessorTest` covering
`substituteElementToRename()` continue to pass — they now exercise the
resolver indirectly. The `stripTrailingDigits` tests in
`AnnotationStringRenameProcessorTest` and `PermuteFamilyFindUsagesActionTest`
are replaced by the consolidated test in `PermuteElementResolverTest`.

---

## Out of Scope

- `PermuteMethodNavigator` feature 2 implementation (deferred — resolver is ready for it)
- Testing improvements for `actionPerformed()` and `GeneratedFileRenameHandler.invoke()`
  (deferred until rename redirect is working and smoke-tested)
- VS Code extension (#4)
