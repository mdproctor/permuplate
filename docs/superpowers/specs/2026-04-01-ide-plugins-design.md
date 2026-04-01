# IDE Plugins — Design Spec

**Date:** 2026-04-01
**Status:** Approved

---

## Overview

Permuplate annotation strings (`type = "Callable${i}"`, `name = "c${i}"`, `className = "Join${i}"`) are invisible to IDE refactoring tools — renaming `Callable2` leaves the string stale. This feature adds:

1. **Compile-time validation** — new annotation processor rules detecting strings that can't be validated or maintained
2. **Shared algorithm library** (`permuplate-ide-support`) — pure Java, no IDE dependency
3. **IntelliJ plugin** (`permuplate-intellij`) — native Kotlin/Platform SDK integration
4. **VS Code extension** (`permuplate-vscode`) — TypeScript, mirrors the Java algorithm

All four features are sequenced sub-projects. Each depends on the previous.

---

## The Algorithm

The rename algorithm is the core of the feature. Java is the **source of truth**. The TypeScript port in `permuplate-vscode/src/algorithm.ts` must be kept exactly in sync (see CLAUDE.md standing rules).

### Annotation string structure

An annotation string like `"${v1}Callable${v2}"` parses into alternating parts:

```
Variable("v1") | Literal("Callable") | Variable("v2")
```

The **static literal** is the non-`${...}` text. It is the anchor — it identifies which part of the class name this string references.

### Matching

Given static literal `"Callable"` and class `"MyCallable2"`:

1. Strip numeric suffix from class name base: `"MyCallable2"` → `"MyCallable"`
2. Split by camelCase boundaries: `["My", "Callable"]`
3. The literal `"Callable"` matches word[1]

Given `"Callable"` and `"Callable2"` → base `"Callable"` → `["Callable"]` → word[0].

### Rename computation

Given old class `"MyCallable2"`, new class `"YourHook2"`, annotation string `"${v1}Callable${v2}"`:

1. Old base: `"MyCallable"` → words: `["My", "Callable"]`
2. New base: `"YourHook"` → words: `["Your", "Hook"]`
3. Literal `"Callable"` = word[1] in old → word[1] in new = `"Hook"`
4. Replace: `"${v1}Hook${v2}"`

The numeric suffix change (2→3) and the non-matching prefix/suffix change ("My"→"Your") don't affect the annotation string — only the matched word does.

### String constant expansion

Before matching, expand any `strings` constants. `"${prefix}${i}"` with `strings = {"prefix=Callable"}` becomes `"Callable${i}"` for validation purposes.

### Validation errors (all are compile errors)

| Rule | Condition | Error message |
|---|---|---|
| Unmatched literal | Static literal doesn't match any camelCase word of the class name (even after expanding `strings` constants) | `@PermuteDeclr type literal "Foo" does not match any part of the declared type "Callable2"` |
| Orphan variable | A `${var}` exists but the text it would correspond to in the class name is empty | `@PermuteDeclr: variable ${v1} has no corresponding text in "Callable2" — remove it or rename the class first` |
| No anchor | String contains only variables, no static literal (even after expanding `strings`) | `@PermuteDeclr type string has no static literal to match against "Callable2" — add a literal portion or define the variable in @Permute strings` |

---

## Sub-project 1: Compile-time validation

**Scope:** Extend `PermuteProcessor`, `PermuteDeclrTransformer`, `PermuteParamTransformer` with the three new rules above. Depends on `permuplate-ide-support` for the algorithm.

**Attributes validated:**

| Annotation | Attribute | Matched against |
|---|---|---|
| `@PermuteDeclr` | `type` | Declared field/param/var type simple name |
| `@PermuteDeclr` | `name` | Declared field/param/var identifier |
| `@PermuteParam` | `name` | Sentinel parameter name |
| `@Permute` | `className` | Template class simple name |

The new general camelCase-word algorithm **replaces** the existing leading-literal prefix-only check for `className`. The existing `testClassNamePrefixMismatchIsError` tests remain valid — they are a subset of the new Rule 1 (unmatched literal).

**Tests:** New test class `OrphanVariableTest` with cases for all three rules on all four attributes. At least one "valid" test per rule (showing it doesn't fire when the string is correct).

---

## Sub-project 2: `permuplate-ide-support`

New Maven module. No IDE dependencies. Pure Java 17.

**Dependencies:** none beyond JDK (no JavaParser, no JEXL — this is pure string/word manipulation).

**API:**

```java
// Core types
public record AnnotationStringPart(boolean isVariable, String text) {}
public record AnnotationStringTemplate(List<AnnotationStringPart> parts) {
    public String toLiteral() { /* rejoins parts */ }
    public List<String> staticLiterals() { /* non-variable parts */ }
}
public sealed interface RenameResult {
    record Updated(String newTemplate) implements RenameResult {}
    record OrphanVariable(String varName, String suggestion) implements RenameResult {}
    record NoMatch() implements RenameResult {}  // string doesn't reference this class
}
public record ValidationError(ErrorKind kind, String varName, String suggestion) {}
public enum ErrorKind { UNMATCHED_LITERAL, ORPHAN_VARIABLE, NO_ANCHOR }

// Operations
public class AnnotationStringAlgorithm {
    public static AnnotationStringTemplate parse(String template);
    public static boolean matches(AnnotationStringTemplate t, String className);
    public static RenameResult computeRename(AnnotationStringTemplate t,
                                              String oldClassName, String newClassName);
    public static List<ValidationError> validate(AnnotationStringTemplate t,
                                                  String targetName,
                                                  Map<String, String> stringConstants);
    // Helper: split "MyCallable" into ["My","Callable"]
    static List<String> camelCaseWords(String classBase);
    // Helper: strip trailing digits from "Callable2" → "Callable"
    static String stripNumericSuffix(String className);
}
```

**Test coverage** (JUnit 5):

- `parse()`: empty string, all variables, all literal, mixed, nested `${}`
- `camelCaseWords()`: single word, two words, acronym, leading lowercase
- `matches()`: prefix match, suffix match, middle match, no match, empty literal
- `computeRename()`: single-word class, multi-word class, numeric suffix changes, orphan variable detected, no match returns `NoMatch`
- `validate()`: all three error kinds, string constant expansion, valid strings don't error

---

## Sub-project 3: `permuplate-intellij`

New module. Kotlin. IntelliJ Platform Plugin SDK (target: IntelliJ IDEA 2023.1+). Depends on `permuplate-ide-support`.

**Key components:**

### PsiReference

`PermuteStringReference` extends `PsiReferenceBase<PsiLiteralExpression>`. Registered via `PermuteReferenceContributor` (implements `PsiReferenceContributor`) on annotation attribute values where the annotation is `@PermuteDeclr`, `@PermuteParam`, or `@Permute`. The reference `resolve()` uses `AnnotationStringAlgorithm.matches()` to find the target `PsiClass` or `PsiField`/`PsiParameter`.

### Rename

`PermuteRenameProcessor` extends `RenamePsiElementProcessor`. On `prepareRenaming()`, collects all `PermuteStringReference`s that resolve to the renamed element. On `renameElement()`, calls `computeRename()` and applies the `PsiElement` changes. The rename dialog shows a "✦ Update Permuplate annotation strings (N occurrences)" checkbox via `findReferences()` returning the string references.

### Inspection

`PermuteAnnotationInspection` extends `LocalInspectionTool`. For each annotation string attribute in a Java file, calls `validate()`. Reports `ProblemHighlightType.GENERIC_ERROR` with:
- `RemoveOrphanVariableQuickFix` — removes the `${var}` from the string literal
- `AddStringConstantQuickFix` — adds the appropriate `strings = {"var=..."}` entry to `@Permute`

### Navigation

`PsiReference.resolve()` makes Ctrl+Click and Go to Declaration (⌘B) work automatically. `PermuteDocumentationProvider` implements `DocumentationProvider` for the hover tooltip.

### Find Usages

`PemuteUsageTypeProvider` implements `UsageTypeProvider` to label the references as "Permuplate annotation string". `PemuteGroupingRuleProvider` puts them in a dedicated "Permuplate annotation strings" group in the Find Usages panel.

### Plugin descriptor (`plugin.xml`)

```xml
<extensions defaultExtensionNs="com.intellij">
  <psiReferenceContributor implementation="...PermuteReferenceContributor"/>
  <renamePsiElementProcessor implementation="...PermuteRenameProcessor"/>
  <localInspection implementationClass="...PermuteAnnotationInspection"
                   language="JAVA" groupName="Permuplate"
                   displayName="Annotation string validation"/>
  <documentationProvider implementation="...PermuteDocumentationProvider"/>
  <usageTypeProvider implementation="...PermuteUsageTypeProvider"/>
</extensions>
```

**Tests:** `LightJavaCodeInsightFixtureTestCase` for each feature:
- Reference resolves to the correct `PsiClass`
- Rename updates annotation string correctly
- Rename with multi-word class name
- Inspection reports error for orphan variable
- Inspection reports error for unmatched literal
- Inspection reports error for no anchor
- Quickfix removes orphan variable correctly
- Find Usages returns annotation strings in the right group

---

## Sub-project 4: `permuplate-vscode`

New directory (not a Maven module). Node.js/TypeScript project. Output: `permuplate-*.vsix`.

**`src/algorithm.ts`** — direct TypeScript port of `AnnotationStringAlgorithm`. Every function maps 1:1 to the Java equivalent. Tests in Jest mirror the Java JUnit tests exactly (same input/output pairs). This file has a header comment:

```typescript
/**
 * IMPORTANT: This file is a TypeScript port of the Java source of truth:
 *   permuplate-ide-support/src/main/java/.../AnnotationStringAlgorithm.java
 *
 * Any bug fix or behaviour change to the Java implementation MUST be reflected
 * here in the same commit. The Jest tests below mirror the Java tests exactly.
 * See CLAUDE.md for the standing rule.
 */
```

**`src/providers.ts`** — VS Code provider implementations:

- `PermuteReferenceProvider` implements `vscode.ReferenceProvider` — scans workspace for annotation strings, calls `matches()`, returns `vscode.Location[]`
- `PermuteRenameProvider` implements `vscode.RenameProvider` — calls `computeRename()`, returns `vscode.WorkspaceEdit`
- `PermuteDefinitionProvider` implements `vscode.DefinitionProvider` — resolves to source file of target class
- `PermuteDiagnosticsProvider` — calls `validate()`, publishes `vscode.Diagnostic[]` with severity `Error`
- `PermuteCodeActionProvider` implements `vscode.CodeActionProvider` — provides quickfixes for each diagnostic kind

**`src/extension.ts`** — entry point; registers all providers for `java` language; hooks `vscode.workspace.onWillRenameFiles` for Explorer-based file renames.

**Tests:** VS Code extension test runner (`@vscode/test-electron`) for each provider, plus Jest unit tests for `algorithm.ts`.

**`package.json` contributes:**

```json
{
  "activationEvents": ["onLanguage:java"],
  "contributes": {
    "languages": [{ "id": "java" }]
  }
}
```

---

## Documentation

### README.md additions

New section *"IDE Plugins"* (after "Compile-time error messages") containing:

- The four mockup screenshots (IntelliJ + VS Code × rename + error/quickfix + navigation + find usages)
- IntelliJ installation:
  > Settings → Plugins → ⚙ → Install Plugin from Disk → select `permuplate-intellij-*.zip`
  > *Marketplace publication coming soon.*
- VS Code installation:
  > Extensions view (`Ctrl+Shift+X`) → `...` → Install from VSIX → select `permuplate-*.vsix`
  > *Marketplace publication coming soon.*
- Brief algorithm explanation: the static literal in an annotation string is treated as a reference to the matching camelCase word in the class name — when the class is renamed, only that word is updated, leaving `${...}` variable parts untouched

### OVERVIEW.md

- Module structure updated to include `permuplate-ide-support`, `permuplate-intellij`, `permuplate-vscode`
- Market Comparison: "IDE refactor support" column → **Yes** for Permuplate

### CLAUDE.md — standing rules added

1. **Java is the algorithm source of truth.** `permuplate-ide-support` defines all algorithm behaviour. Any change (bug fix, edge case, new rule) must be reflected in `permuplate-vscode/src/algorithm.ts` in the same commit. The TypeScript Jest tests mirror the Java tests exactly — adding a Java test requires adding the matching Jest test.
2. **Mockup screenshots are canonical UI references.** The HTML files in `docs/screenshots/` define what the plugins should look like. Update them if UX changes.
3. **Three compile-time string validation rules** added to the non-obvious decisions table (unmatched literal, orphan variable, no anchor).

---

## New Module Structure

```
permuplate-parent/
├── permuplate-annotations/
├── permuplate-core/
├── permuplate-ide-support/      — algorithm library (NEW)
├── permuplate-processor/        — depends on ide-support for new validation
├── permuplate-maven-plugin/
├── permuplate-intellij/         — IntelliJ plugin (NEW, Kotlin)
├── permuplate-apt-examples/
├── permuplate-mvn-examples/
└── permuplate-tests/

permuplate-vscode/               — VS Code extension (NEW, TypeScript, outside Maven)
```

---

## Mockup Screenshots

Saved to `docs/screenshots/plugin-mockups.html` (already committed by the brainstorming session). The four scenarios:

1. **Smart rename** — rename dialog with live preview of annotation string updates
2. **Error + quickfix** — red squiggle on orphan `${v1}`; quickfix removes it
3. **Navigation** — hover tooltip; Ctrl+click jumps to resolved class
4. **Find usages** — annotation strings in dedicated "Permuplate annotation strings" group
