# JEXL String Assistance — Design Spec

**Date:** 2026-04-22  
**Status:** Approved  
**Scope:** `permuplate-intellij-plugin`

---

## Problem

Permuplate annotation string attributes use `${expr}` template syntax throughout:

```java
@Permute(varName="i", from="3", to="${max}", className="Join${i}")
@PermuteReturn(className="Join${i}", when="i < max")
@PermuteMethod(varName="j", to="${i-1}", name="join${j}")
```

Today the IDE treats everything inside these strings as opaque text. There is no completion, no parameter hints, no highlighting, and no validation. Authors must remember the variable names and built-in function signatures from memory.

---

## Goal

Full IDE authoring assistance inside `${...}` expressions in all Permuplate annotation string attributes:

- Syntax highlighting of JEXL tokens
- Completion of variable names and built-in function names
- Parameter hints when the cursor is inside a built-in function call
- Warnings for undefined variables and malformed expressions

---

## Prior Art

No reusable JEXL IntelliJ plugin exists on the JetBrains Marketplace. The Jenkins Stapler plugin (`jenkinsci/idea-stapler-plugin`, BSD-2-Clause) has a single 385-line `JexlInspection` that uses the Apache Commons JEXL library as its parser to detect syntax errors — no lexer, highlighter, or completion. We borrow this validation pattern (with attribution) for the annotator; everything else is written from scratch. Grammar-Kit is not used — JEXL's expression syntax is simple enough for a hand-written lexer.

---

## Architecture

Three layers, built and tested bottom-up:

```
Layer 3: IDE services
  JexlCompletionContributor
  JexlParameterInfoHandler
  JexlAnnotator

Layer 2: Language injector
  JexlLanguageInjector (MultiHostInjector)

Layer 1: JEXL language infrastructure
  JexlLanguage · JexlFileType · JexlFile
  JexlLexer · JexlTokenTypes · JexlSyntaxHighlighter
  JexlParserDefinition

Shared:
  PermuteAnnotations  (ALL_ANNOTATION_FQNS, JEXL_BEARING_ATTRIBUTES)
  JexlContextResolver (collects variables from @Permute context)
```

---

## Layer 1 — JEXL Language Infrastructure

### Classes

| Class | Purpose |
|---|---|
| `JexlLanguage` | Singleton `Language("JEXL")`. No external deps. |
| `JexlFileType` | `LanguageFileType` wrapping `JexlLanguage`. Required by the injection framework. No `.jexl` files exist on disk. |
| `JexlFile` | `PsiFileBase`. PSI root for each injected fragment. |
| `JexlTokenTypes` | `IElementType` constants: `IDENTIFIER`, `NUMBER`, `STRING`, `LPAREN`, `RPAREN`, `COMMA`, `DOT`, `OPERATOR`, `WHITESPACE`, `BAD_CHARACTER`. |
| `JexlLexer` | Hand-written lexer (~150 lines). Tokenises JEXL expressions. Handles single-quoted strings (`'alpha'`), integers, identifiers, operators (`+-*/><==!=&&||!`), parens, commas, dots, whitespace. |
| `JexlSyntaxHighlighter` | Maps token types to `TextAttributesKey`. Identifiers: default colour. Built-in function names (static set): bold. Numbers: number colour. Operators: operator colour. Bad characters: error colour. |
| `JexlParserDefinition` | Supplies `JexlLexer`. Produces a flat `JexlFile` (no grammar tree needed — token-level is sufficient for all IDE services). |

### Registration (`plugin.xml`)

```xml
<lang.parserDefinition language="JEXL"
    implementationClass="...JexlParserDefinition"/>
<lang.syntaxHighlighterFactory language="JEXL"
    implementationClass="...JexlSyntaxHighlighterFactory"/>
<fileType name="JEXL" implementationClass="...JexlFileType"
    fieldName="INSTANCE" language="JEXL"/>
```

---

## Shared: PermuteAnnotations

Extract `ALL_ANNOTATION_FQNS` from `AnnotationStringRenameProcessor` into a new `PermuteAnnotations` constants class. Add:

```java
public static final Set<String> JEXL_BEARING_ATTRIBUTES = Set.of(
    "from", "to", "className", "name", "type", "when",
    "pattern", "body", "macros", "typeArgs", "value"
);
```

Both `AnnotationStringRenameProcessor` and `JexlLanguageInjector` import from here. No duplication of the annotation FQN list.

---

## Shared: JexlContextResolver

Responsible for walking the PSI tree from an injected JEXL fragment back to its host and collecting the JEXL variable set in scope.

**Input:** Any PSI element inside a `JexlFile` (the injected fragment).

**Algorithm:**
1. `InjectedLanguageManager.getInjectionHost(file)` → host `PsiLiteralExpression`
2. Walk up to containing `PsiAnnotation` → containing `PsiClass`
3. Collect from class-level annotations:
   - `@Permute.varName` → primary loop variable (default `"i"`)
   - Each `@PermuteVar.varName` → extra variable
   - `@Permute.strings` → parse each `"name=value"` entry for the name part
   - `@Permute.macros` → parse each `"name=expr"` entry for the name part
   - `@PermuteMacros.macros` on enclosing outer class → same parse
4. If the host attribute belongs to a `@PermuteMethod` or `@PermuteSwitchArm` annotation, also collect that annotation's `varName` as the inner loop variable
5. Return `JexlContext(variables: Set<String>, innerVariable: String?)`

**Built-ins (static, never vary):**

| Function | Signature |
|---|---|
| `alpha` | `(int n) → String` |
| `lower` | `(int n) → String` |
| `typeArgList` | `(int from, int to, String style) → String` |
| `capitalize` | `(String s) → String` |
| `decapitalize` | `(String s) → String` |
| `max` | `(int a, int b) → int` |
| `min` | `(int a, int b) → int` |

---

## Layer 2 — Language Injector

### `JexlLanguageInjector implements MultiHostInjector`

**`getLanguagesToInject(MultiHostRegistrar, PsiElement)`:**

1. Confirm element is `PsiLiteralExpression` with a `String` value.
2. Walk up to containing `PsiAnnotation`. Check FQN against `PermuteAnnotations.ALL_ANNOTATION_FQNS` (simple-name fallback per existing pattern).
3. Check `PsiNameValuePair.getName()` is in `JEXL_BEARING_ATTRIBUTES`.
4. Scan string value for all `${...}` occurrences. Skip `className="self"` (no `${`).
5. For each match at `[start, end)`, call:
   ```java
   registrar.startInjecting(JexlLanguage.INSTANCE)
            .addPlace("", "", host, new TextRange(dollarStart + 2, braceEnd))
            .doneInjecting();
   ```
   (offset +2 skips `${`; `}` is excluded from injection range; prefix/suffix are empty because JEXL expressions are self-contained fragments — no wrapper needed to make them valid)

**`elementsToInjectIn()`:** Returns `List.of(PsiLiteralExpression.class)`.

### Registration

```xml
<multiHostInjector
    implementationClass="...JexlLanguageInjector"/>
```

---

## Layer 3A — Completion

### `JexlCompletionContributor extends CompletionContributor`

**Trigger:** `PlatformPatterns.psiElement().withLanguage(JexlLanguage.INSTANCE)`

**`fillCompletionVariants`:**
1. Get current position element.
2. Call `JexlContextResolver.resolve(position)` → `JexlContext`.
3. If context is null (no enclosing `@Permute`) → return early.
4. Add `LookupElementBuilder` for each variable in context (bold, type hint `"variable"`).
5. Add `LookupElementBuilder` for each built-in function name (italic, type hint `"built-in"`).
6. Mark result as non-autopopup (only on explicit `Ctrl+Space` or after `${`).

---

## Layer 3B — Parameter Info

### `JexlParameterInfoHandler implements ParameterInfoHandler<PsiElement, JexlBuiltin>`

**`findElementForParameterInfo`:** Walk tokens left from cursor to find `IDENTIFIER` followed by `LPAREN`. If identifier is a known built-in, return it.

**`updateParameterInfo`:** Count commas between `LPAREN` and cursor to determine current argument index.

**`updateUI`:** Render the built-in's parameter list, bold the current argument.

**Static built-in registry:** `Map<String, JexlBuiltin>` where `JexlBuiltin` holds name + parameter descriptors. Defined once, shared with `JexlCompletionContributor` for the function name list.

### Registration

```xml
<lang.parameterInfoHandler language="JEXL"
    implementationClass="...JexlParameterInfoHandler"/>
```

---

## Layer 3C — Annotator

### `JexlAnnotator implements Annotator`

Two checks per injected `JexlFile`:

**1. Syntax validation** (pattern from Stapler plugin, BSD-2-Clause, with attribution)
```java
// Validation pattern adapted from jenkinsci/idea-stapler-plugin JexlInspection (BSD-2-Clause)
try {
    new JexlBuilder().create().createExpression(fragmentText);
} catch (JexlException e) {
    holder.newAnnotation(HighlightSeverity.WARNING, "JEXL syntax error: " + e.getMessage())
          .range(element.getTextRange())
          .create();
}
```

**2. Undefined variable detection**
- Collect all `IDENTIFIER` tokens in the fragment.
- Skip tokens that are: (a) immediately preceded by `DOT` (method calls), (b) immediately followed by `LPAREN` (function calls — checked against built-in set separately), (c) known built-in names.
- Any remaining identifier not in `JexlContext.variables` → `WARNING` annotation "Unknown variable '${name}'. Check @Permute varName, @PermuteVar, strings= or macros=."
- Severity is `WARNING` not `ERROR` because system properties (`-Dpermuplate.*`) are invisible to the IDE.

### Registration

```xml
<annotator language="JEXL"
    implementationClass="...JexlAnnotator"/>
```

---

## Test Strategy

All tests extend `BasePlatformTestCase`. Four categories required per feature:

### Happy path
- Completion inside `${<caret>}` in `@Permute.className` offers primary `varName` (`i`)
- Completion offers all seven built-in function names
- `typeArgList(` triggers parameter info showing `from, to, style`
- `alpha(` triggers parameter info showing `n`
- `${i}` with `i` defined produces no annotator warnings
- Syntax highlighting: identifier tokens get default colour; built-in names get bold

### End-to-end
- Full template: `@Permute` + two `@PermuteVar` + `@PermuteMethod` + `strings={"max=5"}` + `macros={"prev=${i-1}"}`. All of `i`, both `@PermuteVar` names, `max`, `prev`, and the `@PermuteMethod` inner variable appear in completions inside each attribute type.
- Inner variable from `@PermuteMethod(varName="j")` appears in `to="${<caret>}"` on the same annotation.
- Cross-layer: injection fires → completion fires → annotator runs without interference.

### Correctness
- `strings={"max=5"}` → `max` in completions (not `5`)
- `macros={"prev=${i-1}"}` → `prev` in completions
- `@PermuteVar(varName="k")` → `k` in completions alongside `i`
- `@PermuteMacros(macros={"suffix=alpha(i)"})` on outer class → `suffix` in completions inside nested `@Permute`
- Inner variable from `@PermuteSwitchArm(varName="k")` available in `pattern=` and `body=` but not in unrelated annotations
- `className="self"` — no injection, no completion, no crash
- Parameter info: second argument of `typeArgList(1, <caret>, ...)` highlights `to` parameter
- Undefined variable `${xyz}` produces exactly one warning

### Robustness
- Unterminated `${` in string — injector skips gracefully, no exception
- No `@Permute` annotation on class — completion returns empty, no crash
- Non-Permuplate annotation with a `from=` attribute — no injection
- Empty string `""` — no injection
- `@PermuteFilter` with bare JEXL boolean `"i > 2"` — no `${...}` found, no injection (filter values use bare JEXL, not template syntax — no completion for now, documented as known limitation)
- Concurrent injections in same file (multiple `@Permute` templates) — each resolves to its own context
- `macros=` entry with no `=` separator — parse skips gracefully
- `strings=` entry with no `=` separator — parse skips gracefully

---

## Known Limitations

- `@PermuteFilter` values are bare JEXL (no `${...}`) and `@PermuteFilter` is not in `ALL_ANNOTATION_FQNS`, so the injector does not process it at all. Completion not available there. Full-string JEXL injection for bare-JEXL attributes is a separate future feature.
- System properties (`-Dpermuplate.*`) and APT options (`-Apermuplate.*`) are not visible to the IDE. Variables injected via these mechanisms may appear as undefined — hence `WARNING` not `ERROR`.
- `body=` in `@PermuteBody` and `@PermuteSwitchArm` is Java code with `${...}` holes; injection fires on the JEXL parts only. The surrounding Java code is not syntax-checked.

---

## GitHub Issues / Epics

Before implementation begins, create:

- **Epic:** "JEXL string assistance in IntelliJ plugin"
- Child issues (one per implementation unit):
  1. `PermuteAnnotations` shared constants refactor
  2. JEXL language infrastructure (Language, FileType, File, Lexer, TokenTypes, SyntaxHighlighter, ParserDefinition)
  3. `JexlContextResolver`
  4. `JexlLanguageInjector`
  5. `JexlCompletionContributor`
  6. `JexlParameterInfoHandler`
  7. `JexlAnnotator`

All commits reference their child issue. PRs reference the epic.

---

## File Layout

```
permuplate-intellij-plugin/src/main/java/.../intellij/
  jexl/
    lang/
      JexlLanguage.java
      JexlFileType.java
      JexlFile.java
      JexlTokenTypes.java
      JexlLexer.java
      JexlParserDefinition.java
      JexlSyntaxHighlighter.java
      JexlSyntaxHighlighterFactory.java
    inject/
      JexlLanguageInjector.java
    completion/
      JexlCompletionContributor.java
      JexlBuiltin.java
    paraminfo/
      JexlParameterInfoHandler.java
    annotate/
      JexlAnnotator.java
    context/
      JexlContextResolver.java
      JexlContext.java
  shared/
    PermuteAnnotations.java        ← replaces inline ALL_ANNOTATION_FQNS

permuplate-intellij-plugin/src/test/java/.../intellij/
  jexl/
    lang/
      JexlLexerTest.java
      JexlSyntaxHighlighterTest.java
    inject/
      JexlLanguageInjectorTest.java
    completion/
      JexlCompletionContributorTest.java
    paraminfo/
      JexlParameterInfoHandlerTest.java
    annotate/
      JexlAnnotatorTest.java
    context/
      JexlContextResolverTest.java
    e2e/
      JexlFullTemplateTest.java
```
