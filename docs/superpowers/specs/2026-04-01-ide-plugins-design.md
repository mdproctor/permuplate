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

Multiple variables in sequence are fully supported:
```
Variable("prefix") | Variable("i") | Literal("Callable") | Variable("j") | Variable("suffix")
```

The **static literal** is the non-`${...}` text. It is the anchor — it identifies which part of the class name this string references. Variables capture the surrounding prefix and suffix text dynamically at compile/generate time; the algorithm treats them as wildcards and only acts on the literal.

### String constant expansion

Before any matching or validation, expand `strings` constants. `"${prefix}Callable${i}"` with `strings = {"prefix=My"}` becomes `"MyCallable${i}"`. Only integer loop variables (the permutation variable and `extraVars`) remain as `${...}` after expansion. The expanded form is what the algorithm operates on.

### Matching

Matching is purely **substring-based** — there is no camelCase splitting. The static literal must appear as a substring within the class name.

**Example 1:** literal `"Callable"`, class `"Callable2"` → `"Callable2".contains("Callable")` ✓

**Example 2:** literal `"Callable"`, class `"ThisIsMyPrefixCallable3"` → `"ThisIsMyPrefixCallable3".contains("Callable")` ✓

**Example 3:** literal `"Callable"`, class `"ThisIsMyPrefixCallableThisIsMySuffix3"` → ✓ (substring found; prefix = `"ThisIsMyPrefix"`, suffix = `"ThisIsMySuffix3"`)

### Rename computation

Given old class, new class, and annotation string — the algorithm:

1. Find the static literal `L` as a substring in the old class name
2. Record `old_prefix` = text before `L`; `old_suffix` = text after `L`
3. In the new class name, strip `old_prefix` from the start and `old_suffix` from the end
4. Whatever remains is the new literal

**Example — only literal changes** (`"ThisIsMyPrefixCallableThisIsMySuffix3"` → `"ThisIsMyPrefixHookThisIsMySuffix3"`):

- `L` = `"Callable"`, `old_prefix` = `"ThisIsMyPrefix"`, `old_suffix` = `"ThisIsMySuffix3"`
- New class starts with `"ThisIsMyPrefix"` ✓ → remainder = `"HookThisIsMySuffix3"`
- Remainder ends with `"ThisIsMySuffix3"` ✓ → new literal = `"Hook"`
- String `"${v1}Callable${v2}"` → `"${v1}Hook${v2}"` ✓

**Example — suffix also changes** (`"MyCallable2"` → `"YourHook3"` with string `"${v1}Callable${v2}"`):

- `L` = `"Callable"`, `old_prefix` = `"My"`, `old_suffix` = `"2"`
- `${v1}` and `${v2}` are variables — they capture whatever prefix/suffix text exists at generate time. The user has explicitly declared that the prefix (`"My"`/`"Your"`) and suffix (`"2"`/`"3"`) are covered by variables and should be ignored.
- New class `"YourHook3"` does not start with `"My"` → prefix changed; since it is captured by `${v1}`, ignore it
- New class `"YourHook3"` does not end with `"2"` → suffix changed; since it is captured by `${v2}`, ignore it
- The algorithm cannot automatically extract the new literal in this case; it flags the annotation string for manual review with a warning: *"annotation string may need manual update — class prefix/suffix also changed"*

**Key principle:** the variables explicitly declare "I don't care what text fills my slot." The algorithm only updates the static literal. If the prefix or suffix also changed simultaneously, that is outside the scope of automatic update — the user is responsible.

### Validation errors (all are compile errors)

| Rule | Condition | Error message |
|---|---|---|
| **No variables** | String contains no `${...}` variables at all (e.g. `type = "Callable2"`) | `@PermuteDeclr type "Callable2" contains no variables — it will generate the same type for every permutation` |
| **Unmatched literal** | Static literal (after expanding `strings` constants) does not appear as a substring of the class name | `@PermuteDeclr type literal "Foo" does not match any substring of the declared type "Callable2"` |
| **Orphan variable** | A `${var}` exists but the text it corresponds to in the class name is empty — applies to each orphan variable individually, so `"${v1}${v2}Callable"` on `"Callable2"` reports both `${v1}` and `${v2}` as orphans | `@PermuteDeclr: variable ${v1} has no corresponding text in "Callable2" (prefix before "Callable" is empty) — remove it` |
| **No anchor** | After expanding `strings` constants, the string contains only variables with no static literal | `@PermuteDeclr type string has no static literal to match against "Callable2" — add a literal portion or define the variable in @Permute strings` |

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

The new **substring-based** algorithm replaces the existing leading-literal prefix-only check for `className`. The existing `testClassNamePrefixMismatchIsError` tests remain valid — they are a subset of the new Rule 2 (unmatched literal).

**Tests:** New test class `OrphanVariableTest` (or extend `PrefixValidationTest`) with cases for all four rules on all four attributes. Required cases include:

- **No variables:** `type = "Callable2"` (no `${...}`) → compile error
- **Unmatched literal:** `type = "Foo${i}"` on field `Callable2` → error ("Foo" not in "Callable2")
- **Orphan variable — single:** `"${v1}Callable${v2}"` on `Callable2` → `${v1}` orphan (prefix before "Callable" is empty)
- **Orphan variable — multiple adjacent:** `"${v1}${v2}Callable${v3}"` on `Callable2` → both `${v1}` and `${v2}` orphan
- **No anchor — pure variables:** `"${v1}${v2}"` (no literal) → no-anchor error
- **No anchor — after strings expansion:** `"${prefix}${i}"` with no matching `strings` entry → no-anchor error
- **Valid — substring match:** `"${v1}Callable${v2}"` on `"ThisIsMyPrefixCallable3"` → no error
- **Valid — multiple string variables:** `"${prefix}Callable${i}"` with `strings = {"prefix=My"}`, field type `"MyCallable2"` → expanded to `"MyCallable${i}"`, literal `"MyCallable"` matches `"MyCallable2"` → no error
- **Valid — string variable expands to full literal:** `"${prefix}${i}"` with `strings = {"prefix=Callable"}`, field type `"Callable2"` → expanded to `"Callable${i}"`, literal `"Callable"` matches → no error

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
public enum ErrorKind { NO_VARIABLES, UNMATCHED_LITERAL, ORPHAN_VARIABLE, NO_ANCHOR }

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

- `parse()`: empty string, all variables, all literal, mixed, adjacent variables, nested `${}` not supported (literal)
- `expandStringConstants()`: single constant, multiple constants, constant not in strings (no expansion), constant that composes full literal
- `matches()`: literal at start of class name, literal in middle, literal at end, literal spanning most of name, no match, empty literal (always matches), class name with only numeric suffix
- `computeRename()`:
  - Only literal changes, prefix/suffix preserved: `"MyCallable2"` → `"MyHandler2"` → `"${v1}Handler${v2}"` ✓
  - Long prefix and suffix, only literal changes: `"ThisIsMyPrefixCallableThisIsMySuffix3"` → `"ThisIsMyPrefixHookThisIsMySuffix3"` → `"${v1}Hook${v2}"` ✓
  - Numeric suffix changes (captured by variable): `"Callable2"` → `"Handler3"` → `"Handler${i}"` ✓
  - Prefix/suffix also changed: returns `NoMatch` (cannot auto-update; user handles manually)
  - String has no match in old class: returns `NoMatch`
- `validate()`:
  - **No variables:** `"Callable2"` → `NO_VARIABLES` error
  - **Unmatched literal:** `"Foo${i}"` vs `"Callable2"` → `UNMATCHED_LITERAL` error
  - **Orphan single:** `"${v1}Callable${v2}"` vs `"Callable2"` (prefix empty) → `ORPHAN_VARIABLE` for `v1`
  - **Orphan multiple adjacent:** `"${v1}${v2}Callable${v3}"` vs `"Callable2"` → `ORPHAN_VARIABLE` for both `v1` and `v2`
  - **No anchor — pure variables:** `"${v1}${v2}"` → `NO_ANCHOR` error
  - **No anchor after expansion:** `"${prefix}${i}"` with empty constants → `NO_ANCHOR` error
  - **Valid — middle match:** `"${v1}Callable${v2}"` vs `"ThisIsMyPrefixCallable3"` → no errors
  - **Valid — string constant composes literal:** `"${prefix}${i}"` with `{"prefix":"Callable"}` vs `"Callable2"` → no errors (expands to `"Callable${i}"`, literal matches)
  - **Valid — multiple string variables:** `"${a}${b}Callable${i}"` with `{"a":"My","b":""}` vs `"MyCallable2"` → no errors (expands to `"MyCallable${i}"`)
  - **Valid — no variables before literal:** `"Callable${i}"` vs `"Callable2"` → no errors

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
