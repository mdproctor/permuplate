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

Multiple static literals and multiple variables in sequence are fully supported:

```
Variable("v") | Literal("Async") | Variable("i") | Literal("Handler") | Variable("suffix")
```

**All static literals are anchors.** Every non-`${...}` segment participates in matching and validation. Variables are wildcards — they can match any text in the surrounding regions.

### String constant expansion

Before any matching or validation, expand `strings` constants. `"${prefix}Callable${i}"` with `strings = {"prefix=My"}` becomes `"MyCallable${i}"`. Only integer loop variables remain as `${...}` after expansion. The expanded form is what the algorithm operates on.

### Matching

Matching is purely **substring-based** — there is no camelCase splitting. All static literals must appear as substrings within the class name **in declaration order** (left to right).

**Single literal:** `"Callable"`, class `"ThisIsMyPrefixCallableThisIsMySuffix3"` → `"Callable"` found ✓

**Multiple literals — order matters:**
- `"Async${i}Handler"` (literals `"Async"` then `"Handler"`) on `"AsyncDiskHandler2"`:
  - Find `"Async"` → position 0 ✓
  - Find `"Handler"` after position 5 → found at position 9 ✓ → matches
- `"Async${i}Handler"` on `"HandlerAsyncDisk2"`:
  - Find `"Async"` → position 7
  - Find `"Handler"` after position 12 → NOT FOUND → no match (wrong order)

The region between two consecutive literals is captured by the variable(s) between them. The region before the first literal and after the last literal are captured by the surrounding variables.

### Rename computation

For each static literal in the string, apply the strip-prefix/strip-suffix operation independently:

1. For each literal `L` (in order): find its position in the old class name
2. `old_prefix_L` = text between the previous literal's end (or class start) and `L`'s start
3. `old_suffix_L` = text between `L`'s end and the next literal's start (or class end)
4. In the new class name, try to strip `old_prefix_L` from the corresponding region start and `old_suffix_L` from the region end
5. Whatever remains is the new literal for that segment

**Single literal, only literal changes** (`"AsyncDiskHandler2"` → `"AsyncDiskProcessor2"`, string `"Async${i}Handler"`):
- Literal 1: `"Async"`, old_prefix=`""`, old_suffix=`"DiskHandler2"` → new starts with `""` ✓, ends with `"DiskProcessor2"` not `"DiskHandler2"` → literal 1 unchanged ✓ (it's preserved in new name)
- Literal 2: `"Handler"`, old_prefix=`"AsyncDisk"`, old_suffix=`"2"` → new starts with `"AsyncDisk"` ✓, strip `"2"` from remainder `"Processor2"` → new literal = `"Processor"`
- Result: `"Async${i}Processor"` ✓

**Multiple literals, both change** (`"AsyncDiskHandler2"` → `"SyncSSDProcessor2"`, string `"Async${i}Handler"`):
- Literal 1 `"Async"`: `"SyncSSDProcessor2"` does not start with `""` → trivially strips; does not start with `"Async"` in position — wait, `"Async"` was at position 0, new name at position 0 has `"Sync"` → literal changed; old_prefix=`""` strips, old_suffix=`"DiskHandler2"` doesn't match rest → **flag for manual review**
- IDE shows: *"annotation string may need manual update — rename affected multiple anchors"*

**Prefix/suffix also changed** (`"MyCallable2"` → `"YourHook3"`, string `"${v1}Callable${v2}"`):
- `${v1}` and `${v2}` explicitly declare their slots are wildcards; prefix `"My"`→`"Your"` and suffix `"2"`→`"3"` are captured by those variables and are ignored
- `"YourHook3"` does not start with `"My"` → strip by length of `"My"` as fallback? No — spec says: since the prefix is covered by a variable, the algorithm cannot determine the new literal automatically → **flag for manual review**
- The Rule 2 error (unmatched literal) will fire on the stale string, directing the user to fix it

**Rule ordering:** Rule 2 (unmatched literal) is checked first and **short-circuits** Rules 3 and 4. If the literal is not found in the class name at all, checking for orphan variables is undefined and is skipped.

### Validation errors (all are compile errors)

| Rule | Scope | Condition | Error message |
|---|---|---|---|
| **R1 — `@Permute.className` has no variable** | `@Permute` only | `className` contains no `${...}` — every permutation produces the same class name | `@Permute className "FixedName" contains no variables — every permutation will produce the same class name; add a ${varName} expression` |
| **R1b — `className` missing declared variable** | `@Permute` only | A declared `varName` or `extraVars` variable does not appear anywhere in `className` — that axis produces no variation | `@Permute className "Foo${i}" never uses extraVars variable "k" — every value of k generates the same class name, producing duplicates` |
| **R2 — Unmatched literal** | All annotations | Any static literal (after expanding `strings`) is not a substring of the class name (checked in order; first mismatch short-circuits R3/R4) | `@PermuteDeclr type literal "Handler" does not match any substring of "AsyncDiskCallable2"` |
| **R3 — Orphan variable** | All annotations | A `${var}` exists but the region it corresponds to in the class name is empty; reported per orphan variable | `@PermuteDeclr: variable ${v1} has no corresponding text in "Callable2" (no text before "Callable") — remove it` |
| **R4 — No anchor** | All annotations | After `strings` expansion, string contains only variables with no static literal | `@PermuteDeclr type string has no static literal to match against "Callable2" — add a literal or define the variable in @Permute strings` |

**Notes:**
- R1 applies **only to `@Permute.className`** — inner annotations (`@PermuteDeclr`, `@PermuteParam`) may legitimately have attributes with no variable (e.g. `type = "Object"` when the type genuinely does not vary)
- R1b catches the `from == to` case: with `from=3, to=3` and `className = "FixedName"`, only one class is generated so the Filer never fires a duplicate error — R1 catches it early with a clearer message
- R2 short-circuits: if any literal is not found, R3 and R4 are skipped for that string
- Multiple adjacent variables on a non-empty prefix/suffix (e.g. `"${v1}${v2}Callable"` on `"MyCallable2"`) — orphan detection is applied per-variable; since "My" is split ambiguously between v1 and v2, both are considered non-orphan as long as the region is non-empty. V1 edge case: behaviour when the region is non-empty but smaller than the number of preceding variables is undefined; the IDE shows a warning rather than error.

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

**Tests:** New test class `OrphanVariableTest` plus additions to `DegenerateInputTest`. Required cases:

**R1 — `@Permute.className` has no variable:**
- `className = "FixedName"`, `from=3, to=5` → error (Filer duplicate would also catch this, but R1 catches it first)
- `className = "FixedName"`, `from=3, to=3` (single permutation) → error (Filer would NOT catch this — only R1 does)
- `className = "Foo${i}"`, `from=3, to=5` → no error (has variable)

**R1b — declared variable absent from `className`:**
- `varName="i"`, `extraVars={@PermuteVar(varName="k",...)}`, `className="Foo${i}"` (k not used) → error
- `varName="i"`, `extraVars={@PermuteVar(varName="k",...)}`, `className="Foo${i}x${k}"` → no error

**R2 — Unmatched literal:**
- `type="Foo${i}"` on field `Callable2` → error ("Foo" not in "Callable2")
- `type="Async${i}Handler"` on field `AsyncDiskCallable2` → error ("Handler" not in "AsyncDiskCallable2" after "Async")
- `type="Async${i}Handler"` on field `AsyncDiskHandler2` → no error (both in order)
- Multiple literals, first correct but second wrong: `"Async${i}Cache"` on `AsyncDiskHandler2` → error ("Cache" not found after "Async")

**R3 — Orphan variable:**
- `"${v1}Callable${v2}"` on `Callable2` → error on `${v1}` (prefix before "Callable" is empty)
- `"${v1}${v2}Callable${v3}"` on `Callable2` → error on `${v1}` and `${v2}` (both orphan)
- `"${v1}Callable${v2}"` on `MyCallable2` → no error (prefix "My" is non-empty)
- `"Callable${v1}"` on `Callable2` → no error (`${v1}` corresponds to "2")

**R4 — No anchor:**
- `"${v1}${v2}"` (no literal) → error
- `"${prefix}${i}"` with no `strings` entry for `prefix` → error

**R2 short-circuits R3/R4:**
- `"${v1}Foo${v2}"` on `Callable2` → only R2 fires ("Foo" not in "Callable2"); no additional orphan error for `${v1}` even though prefix is also empty

**Valid — should NOT error:**
- `"${v1}Callable${v2}"` on `MyCallable2` → valid (literal "Callable" found, prefix "My" non-empty, suffix "2" non-empty)
- `"Async${i}Handler"` on `AsyncDiskHandler2` → valid (both literals found in order)
- `"${prefix}${i}"` with `strings={"prefix=Callable"}`, field `Callable2` → expands to `"Callable${i}"`, valid
- `type="Object"`, `name="o${i}"` on for-each `Object o2` → valid (type "Object" matches, name prefix "o" matches; type having no variable is intentional and allowed for inner annotations)

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
- `matches()`:
  - Single literal: at start, middle, end of class name; no match; class name with only numeric suffix
  - Multiple literals in correct order: `"Async${i}Handler"` on `"AsyncDiskHandler2"` → true
  - Multiple literals in wrong order: `"Async${i}Handler"` on `"HandlerAsyncDisk2"` → false
  - First literal present, second absent: `"Async${i}Cache"` on `"AsyncDiskHandler2"` → false
- `computeRename()`:
  - Single literal, only literal changes: `"Callable2"` → `"Handler2"`, `"Callable${i}"` → `"Handler${i}"` ✓
  - Long prefix+suffix preserved: `"ThisIsMyPrefixCallableThisIsMySuffix3"` → `"ThisIsMyPrefixHookThisIsMySuffix3"` → `"${v1}Hook${v2}"` ✓
  - Numeric suffix changes (variable captures it): `"Callable2"` → `"Handler3"`, `"Callable${i}"` → `"Handler${i}"` ✓
  - Multiple literals, second changes: `"AsyncDiskHandler2"` → `"AsyncDiskProcessor2"`, `"Async${i}Handler"` → `"Async${i}Processor"` ✓
  - Multiple literals, both change: `"AsyncDiskHandler2"` → `"SyncSSDProcessor2"`, `"Async${i}Handler"` → `NoMatch` (manual review)
  - Prefix also changed: `"MyCallable2"` → `"YourHook3"` → `NoMatch` (manual review)
  - String has no match in old class: → `NoMatch`
- `validate()`:
  - **R2 — Unmatched single literal:** `"Foo${i}"` vs `"Callable2"` → `UNMATCHED_LITERAL`
  - **R2 — Unmatched second literal:** `"Async${i}Cache"` vs `"AsyncDiskHandler2"` → `UNMATCHED_LITERAL` for `"Cache"`
  - **R2 — short-circuits R3/R4:** `"${v1}Foo${v2}"` vs `"Callable2"` → only `UNMATCHED_LITERAL`; no `ORPHAN_VARIABLE` reported
  - **R3 — Orphan single:** `"${v1}Callable${v2}"` vs `"Callable2"` → `ORPHAN_VARIABLE` for `v1` (prefix empty)
  - **R3 — Orphan multiple adjacent:** `"${v1}${v2}Callable${v3}"` vs `"Callable2"` → `ORPHAN_VARIABLE` for both `v1` and `v2`
  - **R3 — Not orphan:** `"${v1}Callable${v2}"` vs `"MyCallable2"` → no errors (prefix "My" non-empty)
  - **R3 — Suffix not orphan:** `"Callable${v1}"` vs `"Callable2"` → no errors (`${v1}` covers "2")
  - **R4 — Pure variables:** `"${v1}${v2}"` → `NO_ANCHOR`
  - **R4 — No expansion:** `"${prefix}${i}"` with no matching `strings` entry → `NO_ANCHOR`
  - **Valid — substring match:** `"${v1}Callable${v2}"` vs `"ThisIsMyPrefixCallable3"` → no errors
  - **Valid — multiple literals in order:** `"Async${i}Handler"` vs `"AsyncDiskHandler2"` → no errors
  - **Valid — string constant composes literal:** `"${prefix}${i}"` with `{"prefix":"Callable"}` vs `"Callable2"` → expands to `"Callable${i}"`, no errors
  - **Valid — type with no variable (inner annotation):** `type="Object"`, `name="o${i}"` vs for-each `Object o2` → no errors (R1 does not apply to inner annotations)

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
