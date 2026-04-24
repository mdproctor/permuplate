---
---
# Permuplate — Architecture Reference

Contributor guide to the processor internals. For the annotation API reference, see [OVERVIEW.md](OVERVIEW.md).

---


## Transformation Pipeline

### Type permutation path (per permutation value `i`)

Inside `PermuteProcessor.generatePermutation()`:

1. **Clone** the class/interface declaration from the parsed template AST
2. **Strip** `static` modifier (if nested); ensure `public`
3. **Rename** the type using `ctx.evaluate(permute.className())`; rename all constructors to match (JavaParser does not propagate class renames to constructors automatically)
4. **`@PermuteExtends`** — explicit extends/implements override (inline mode only, before other transforms)
5. **`PermuteDeclrTransformer.transform()`**:
   - 5a. `transformFields()` — class-wide scope
   - 5b. `transformConstructorParams()` — constructor-body scope
   - 5c. `transformForEachVars()` — loop-body scope
6. **`PermuteParamTransformer.transform()`** — expand parameter list + anchor expansion at call sites (methods and constructors)
7. **`PermuteTypeParamTransformer.transform()`** — type parameter expansion
8. **`PermuteReturnTransformer.transform()`** — return type control + boundary omission
9. **`PermuteMethodTransformer.transform()`** — multiple overloads per class
10. **`PermuteCaseTransformer.transform()`** — switch case expansion
11. **`PermuteValueTransformer.transform()`** — field/local/method/constructor value replacement (BEFORE Statements so indices are stable)
12. **`PermuteStatementsTransformer.transform()`** — statement insertion (AFTER Value)
13. **Strip `@Permute`** from the cloned type; collect `@PermuteImport` entries
14. **Build fresh `CompilationUnit`** — copy package declaration, non-permuplate imports, and `@PermuteImport` entries
15. **Write** via `Filer.createSourceFile()`, reporting errors with `AnnotationMirror`/`AnnotationValue` precision

### Method permutation path (all values collected into one class)

Inside `PermuteProcessor.processMethodPermutation()`:

1. Parse the source of the enclosing class
2. Locate the annotated method in the AST by name + parameter count
3. Evaluate `outputClassName` using `from` value (the class name is fixed across all overloads)
4. For each `i` in `[from, to]`:
   a. Clone the method
   b. Strip `@Permute` from the clone
   c. **Wrap in a temporary `ClassOrInterfaceDeclaration`** so the existing transformers (`PermuteDeclrTransformer`, `PermuteParamTransformer`) can be applied without modification
   d. Extract the transformed method from the wrapper
5. Clone the enclosing class, clear its members, rename it, add all generated method overloads
6. Write one file

The wrapper-class approach means no changes to the transformers were needed to support method permutation — they operate on a class declaration and work correctly whether it's the real template or a temporary vehicle.

---

## Validation and Error Reporting

Errors are emitted with the most precise source location available, in this priority order:

1. **Attribute-level** — `messager.printMessage(ERROR, msg, element, annotationMirror, annotationValue)` — the IDE navigates to the specific annotation attribute value
2. **Annotation-level** — `messager.printMessage(ERROR, msg, element, annotationMirror)`
3. **Element-level** — `messager.printMessage(ERROR, msg, element)` — at minimum the annotated class or method

Helpers `findAnnotationMirror(element, fqn)`, `findAnnotationValue(mirror, attribute)`, and `error(msg, element, mirror, value)` in `PermuteProcessor` encapsulate this.

**Pre-generation validation** (before any permutations are generated):
- `from > to` — invalid range, errors on the `from` attribute value
- `className` prefix mismatch — errors on the `className` attribute value (type permutation only; skipped when className starts with `${...}`)
- `strings` format — each entry must be `"key=value"` with non-empty key and no collision with `varName`; errors on the `strings` attribute value
- `@PermuteDeclr` type/name prefix mismatches — checked by `PermuteDeclrTransformer.validatePrefixes()` with the TypeElement as location
- `@PermuteParam` name prefix mismatch — checked by `PermuteParamTransformer.validatePrefixes()` with the TypeElement as location

**Rule:** every error emitted anywhere in the processor pipeline must include at least an `Element` location. Bare `messager.printMessage(ERROR, msg)` calls without location are not permitted.

---

## Source Parsing Strategy

The processor reads template source via the compiler `Trees` API:

```java
String source = trees.getPath(typeElement)
        .getCompilationUnit()
        .getSourceFile()
        .getCharContent(true)
        .toString();
return StaticJavaParser.parse(source);
```

Using `getCharContent(true)` rather than constructing a `File` from the URI is critical: it works both for normal filesystem-based compilation and for the in-memory sources used by the `compile-testing` framework in unit tests.

For method permutation, `parseSource(enclosingClass)` is called with the method's enclosing `TypeElement`. Even for methods in nested classes, `trees.getPath(nestedClassElement).getCompilationUnit()` returns the compilation unit for the outer file, so the full source is available.

---

## Module Structure

```
permuplate-parent/
├── permuplate-annotations/
│   ├── Permute.java           — outer loop; type and method targets; strings + extraVars; inline + keepTemplate
│   ├── PermuteVar.java        — nested annotation for one extra integer loop axis
│   ├── PermuteDeclr.java      — field, constructor parameter, for-each, method param renaming
│   ├── PermuteParam.java      — sentinel parameter expansion + anchor call-site rewriting (methods + constructors)
│   ├── PermuteValue.java      — field/local initializer or method/constructor statement RHS replacement
│   ├── PermuteStatements.java — statement insertion into method/constructor bodies
│   ├── PermuteCase.java       — switch case expansion per inner-loop value
│   ├── PermuteImport.java     — add JEXL-evaluated imports to each generated class
│   ├── PermuteTypeParam.java  — class/method type parameter expansion
│   ├── PermuteReturn.java     — return type control + boundary omission
│   ├── PermuteMethod.java     — multiple method overloads per class
│   ├── PermuteExtends.java    — explicit extends/implements clause override (inline mode only)
│   ├── PermuteFilter.java     — skip permutations via JEXL boolean expression (repeatable)
│   ├── PermuteAnnotation.java — add Java annotations to generated elements (repeatable, conditional)
│   ├── PermuteThrows.java     — add exception types to method throws clauses (repeatable, conditional)
│   ├── PermuteSource.java     — declare dependency on another generated class family (Maven plugin only; repeatable)
│   └── PermuteDelegate.java   — synthesise delegating method bodies from a source interface
├── permuplate-core/
│   ├── EvaluationContext.java          — JEXL3 wrapper; Map<String,Object> for int + string vars; built-in functions
│   ├── PermuteDeclrTransformer.java    — fields, constructor params, for-each vars, method params
│   ├── PermuteParamTransformer.java    — parameter expansion + anchor mechanism (methods + constructors)
│   ├── PermuteValueTransformer.java    — @PermuteValue on fields, locals, methods, constructors
│   ├── PermuteStatementsTransformer.java — @PermuteStatements insertion (methods + constructors)
│   ├── PermuteCaseTransformer.java     — @PermuteCase switch expansion
│   └── PermuteConfig.java              — shared configuration model (parsed from annotations)
├── permuplate-ide-support/
│   └── AnnotationStringAlgorithm       — annotation string algorithm (matching, rename, validation; no IDE deps)
├── permuplate-processor/
│   └── PermuteProcessor.java           — APT entry point (thin shell); type and method permutation paths;
│                                          buildAllCombinations() for cross-product generation;
│                                          validation; error reporting with annotation precision;
│                                          rejects inline=true with migration message
├── permuplate-maven-plugin/
│   ├── PermuteMojo.java                — Maven Mojo; generate-sources phase; reads src/main/permuplate/
│   │                                      for inline templates; writes augmented parent CU to
│   │                                      target/generated-sources/permuplate/
│   └── InlineGenerator.java            — augmented parent CU generation; applyPermuteExtends/ExtendsExpansion;
│                                          implicit inference; boundary omission; @PermuteImport collection
├── permuplate-apt-examples/  APT examples (Join2, ContextJoin2, JoinLibrary, Callable1, ...)
├── permuplate-mvn-examples/  Maven plugin examples (Handlers inline demo, Drools DSL sandbox)
└── permuplate-tests/         compile-testing unit tests
```

**Key build concern:** `permuplate-processor` uses `-proc:none` to prevent self-invocation during compilation. The example and test modules must list the processor **and its transitive dependencies** (`javaparser-core`, `commons-jexl3`) explicitly under `annotationProcessorPaths` — `maven-compiler-plugin` 3.x isolates the processor classloader and does not auto-discover transitive deps.

---

## Testing Strategy

Tests use Google's `compile-testing` library, which compiles Java source strings in-process and asserts on compilation outcome and generated source content. This approach supports both structural assertions (source text contains expected declarations) and behavioural assertions (load generated classes via `ClassLoader`, invoke methods via reflection).

Tests are organised into focused test classes:

| Class | Coverage area |
|---|---|
| `PermuteTest` | Type permutation range, nested class/interface promotion to top-level, double-digit arities, string variable in `className`, cross-product via `extraVars`, (inline generation tested via InlineGenerationTest) |
| `PermuteDeclrTest` | Field rename across all methods, constructor parameter rename, for-each variable rename, two annotated fields, dual for-each loops |
| `PermuteParamTest` | Fixed params before/after sentinel, multiple methods in same class, anchor expansion, lambda param expansion, pure-variable name templates |
| `PermuteValueFieldTest` | `@PermuteValue` on fields and local variables; combined with `@PermuteDeclr` on the same field |
| `ExampleTest` | Real-world domain templates: `ProductFilter2`, `AuditRecord2`, `ValidationSuite.FieldValidator2`, `BiCallable1x1` |
| `DogFoodingTest` | `Callable1` generates `Callable2`–`Callable10` — Permuplate describes its own foundational types |
| `DegenerateInputTest` | All error paths with message content and source-position assertions |
| `PrefixValidationTest` | String-literal prefix rules for `@PermuteDeclr` and `@PermuteParam` across all placements |
| `AptInlineGuardTest` | APT rejection of `inline=true`; `keepTemplate` warning |
| `OrphanVariableTest` | R2 (substring matching), R3 (orphan variable — single, adjacent, non-orphan), R4 (no anchor), R2 short-circuits R3/R4 |
| `InlineGenerationTest` | InlineGenerator (augmented parent CU), AnnotationReader (JavaParser to PermuteConfig), keepTemplate behavior |
| `ExpressionFunctionsTest` | Built-in JEXL functions: `alpha`, `lower`, `typeArgList` — unit tests + end-to-end compile tests |
| `PermuteTypeParamTest` | `@PermuteTypeParam`: explicit/implicit expansion, bounds propagation, fixed type params, R1/R3/R4 validation |
| `PermuteReturnTest` | `@PermuteReturn`: APT explicit mode, implicit inference, boundary omission, V2/V3/V6 validation |
| `PermuteMethodTest` | `@PermuteMethod`: multiple overloads, inferred `to`, leaf nodes, extends expansion, APT mode, method-level `@PermuteTypeParam` |

`ProcessorTestSupport` provides shared infrastructure: `templateSource()` reads real template `.java` files from `src/test/java/`; `compileTemplate()` adds generated `Callable{n}` support sources; `classLoaderFor()` loads generated `.class` bytes; `capturingProxy()` creates reflective proxies for behavioural assertions; `assertJoinN()` is a structural + behavioural assertion helper for the Join pattern.

---

## IntelliJ Plugin

The plugin (`permuplate-intellij-plugin/`, Gradle + Java 17) provides IDE support for Permuplate template authors. It is a separate Gradle build — not aggregated into the Maven parent — and depends on `permuplate-ide-support` and `permuplate-annotations` jars from `target/`.

**Rename propagation** — `AnnotationStringRenameProcessor` hooks into IntelliJ's rename refactoring. When a class is renamed, all Permuplate annotation string attributes referencing that class name are updated atomically. Covered annotations: all in `PermuteAnnotations.ALL_ANNOTATION_FQNS` that carry class-name strings (`@PermuteDeclr`, `@PermuteParam`, `@Permute`, `@PermuteAnnotation`, `@PermuteThrows`, `@PermuteSource`). `@PermuteFilter` is excluded — its value is a JEXL boolean expression with no class references.

**Inspections** — `LocalInspectionTool` subclasses flag common authoring mistakes at edit time:
- `AnnotationStringInspection` — validates `@PermuteDeclr`/`@PermuteParam` string attributes (R2 substring matching, R3 orphan variable, R4 no-anchor) via `AnnotationStringAlgorithm`
- `BoundaryOmissionInspection` — warns when a return type references a class outside the generated range
- `PermuteAnnotationValueInspection` — validates `@PermuteAnnotation.value`
- `PermuteThrowsTypeInspection` — validates `@PermuteThrows.value`
- `StaleAnnotationStringInspection` — flags annotation strings that no longer match the current class name after a rename

**Navigation** — `PermuteMethodNavigator` provides go-to-declaration from generated class references back to the template. `PermuteFamilyFindUsagesAction` finds all usages across a generated class family.

**Safe delete** — `PermuteSafeDeleteDelegate` prevents accidental deletion of template classes that have generated dependants.

**Package move** — `PermutePackageMoveHandler` keeps annotation string package prefixes consistent when a template class is moved to a different package.

**Index** — `PermuteTemplateIndex` (file-based index) maps generated class names to their template source files. `PermuteFileDetector` identifies generated files by the permuplate header comment. Used by navigation and rename to resolve cross-file references efficiently without a full project scan.

**Editor decoration** — `GeneratedFileNotification` shows a banner in generated files linking back to the template.

**Shared constants** — `PermuteAnnotations` (`shared/`) provides `ALL_ANNOTATION_FQNS` (all 29 Permuplate annotation FQNs), `JEXL_BEARING_ATTRIBUTES`, and `isPermuteAnnotation()` with O(1) simple-name lookup via a pre-computed `SIMPLE_NAMES` set. Single source of truth used across all plugin services.

### JEXL String Assistance

Full IDE authoring assistance inside `${...}` expressions in Permuplate annotation string attributes, implemented as an IntelliJ language injection stack:

**Language infrastructure** (`jexl/lang/`)
- `JexlLanguage` — singleton `Language("JEXL")` identifier
- `JexlLexer` — hand-written `LexerBase`: IDENTIFIER, NUMBER, STRING (single-quoted), OPERATOR (including two-char forms `==`, `!=`, `<=`, `>=`, `&&`, `||`), LPAREN, RPAREN, COMMA, DOT, WHITESPACE, BAD_CHARACTER. `%` (modulo) included. JEXL keywords (`true`, `false`, `null`, etc.) tokenise as IDENTIFIER.
- `JexlSyntaxHighlighter` — maps token types to `TextAttributesKey`; `BUILTIN_NAMES` is a public constant derived from `JexlBuiltin.ALL.keySet()`
- `JexlParserDefinition` — flat parse (no grammar tree); all IDE services operate at token level

**Injection** (`jexl/inject/`)
- `JexlLanguageInjector` (`MultiHostInjector`) — scans `PsiLiteralExpression` nodes in Permuplate annotations for `${...}` subranges; each range gets its own injection session (one `JexlFile` per expression, not one combined file per attribute)

**Context resolution** (`jexl/context/`)
- `JexlContext` — record: `variables: Set<String>`, `innerVariable: @Nullable String`
- `JexlBuiltin` — record per built-in function; `ALL` map is the single source of truth for function names, signatures, and parameter info
- `JexlContextResolver` — walks from injected element → host literal → `PsiNameValuePair` → `PsiAnnotation` → `PsiClass`; collects primary `varName`, `extraVars=@PermuteVar(...)` variables, `strings=` constants, `macros=` names, and `@PermuteMethod`/`@PermuteSwitchArm` inner `varName`; `BUILTIN_NAMES` delegates to `JexlBuiltin.ALL.keySet()`

**IDE services** (`jexl/completion/`, `jexl/paraminfo/`, `jexl/annotate/`)
- `JexlCompletionContributor` — offers all in-scope variables (bold) and built-in function names; insert handler appends `()` with caret between parens
- `JexlParameterInfoHandler` — shows parameter signature for built-in calls; walks flat token tree with depth tracking for nested calls (e.g. `typeArgList(1, max(i,2), 'T')`)
- `JexlAnnotator` — fires once per `JexlFile` root (`instanceof JexlFile` guard); (1) syntax validation via Apache Commons JEXL3 (`createExpression()` → WARNING); (2) undefined variable detection skipping builtins, function call targets, property access, and JEXL keywords

---
