# Permuplate — Claude context

## Project Type

**Type:** java

This file gives future Claude sessions everything needed to contribute to Permuplate without re-deriving the architecture from scratch. Read this first, then consult [OVERVIEW.md](OVERVIEW.md) for per-annotation API detail, transformer internals, the transformation pipeline, and the test coverage map. See [README.md](README.md) for the user-facing picture. For the current state of the DSL sandbox architecture, consult the [design snapshots](docs/design-snapshots/) — the 2026-04-06 snapshot is the current reference.

---

## What this project is

**Permuplate** is a Java annotation processor that generates type-safe arity permutations from a single template class. You write one class annotated with `@Permute`, and `javac` generates N classes — one per arity value — via AST transformation using JavaParser.

The key insight: **the template is valid, compilable Java**. The IDE can navigate it, refactor it, and type-check it. No external script. No committed generated files. No separate build step.

This is genuinely novel. Every comparable tool (Vavr generators, Freemarker templates, RxJava scripts) uses a template that is *not* valid Java. See [OVERVIEW.md § Market Comparison](OVERVIEW.md#market-comparison--why-this-is-novel) for details.

---

## Module layout

```
permuplate-parent/
├── permuplate-annotations/     All 14 annotations (no runtime deps)
├── permuplate-core/            shared transformation engine: EvaluationContext, transformers, PermuteConfig
├── permuplate-ide-support/     annotation string algorithm (matching, rename, validation); no IDE deps
├── permuplate-processor/       APT entry point only (thin shell depending on permuplate-core)
├── permuplate-maven-plugin/    Maven Mojo for pre-compilation generation including inline mode
├── permuplate-apt-examples/    APT examples (renamed from permuplate-example)
├── permuplate-mvn-examples/    Maven plugin examples with Handlers inline demo
└── permuplate-tests/           Unit tests via Google compile-testing

permuplate-intellij-plugin/     IntelliJ plugin (Gradle, Java 17) — NOT aggregated into Maven parent
```

The processor module uses `-proc:none` to prevent self-invocation during its own compilation. The apt-examples and test modules must list the processor **and its transitive deps** (javaparser-core, commons-jexl3) explicitly under `annotationProcessorPaths` — `maven-compiler-plugin` 3.x does not auto-discover them.

Maven is at `/opt/homebrew/bin/mvn`. The standard build command is:

```bash
/opt/homebrew/bin/mvn clean install
```

The IntelliJ plugin uses a separate Gradle build. From `permuplate-intellij-plugin/`:

```bash
./gradlew test          # run plugin tests (83 tests)
./gradlew buildPlugin   # produce installable zip in build/distributions/
```

Requires Maven modules built first (`mvn install`) — the plugin depends on `permuplate-ide-support` and `permuplate-annotations` jars from `target/`. IntelliJ's internal compiler does not support the `Trees` API — enable **Delegate IDE build/run actions to Maven** in IntelliJ settings (Build, Execution, Deployment → Build Tools → Maven → Runner).

**Java 17 required for Gradle:** If the shell default JDK is Java 21+ (especially Java 26), Gradle 8.6 fails with a cryptic `JavaVersion.parse` error. Set explicitly: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test`

---

## The annotations

18 annotations in `permuplate-annotations/`. For per-annotation API detail and usage examples, see [OVERVIEW.md § Annotation API Detail](OVERVIEW.md#annotation-api-detail).

| Annotation | Target | Purpose |
|---|---|---|
| `@Permute` | class, interface, method | Master: declares the permutation loop (integer range via `from`/`to`, or string set via `values`) |
| `@PermuteVar` | (nested in @Permute) | Extra loop axes for cross-product generation |
| `@PermuteDeclr` | field, parameter, for-each var, method | Rename type+name per permutation |
| `@PermuteParam` | method/constructor parameter | Expand a sentinel parameter into a sequence |
| `@PermuteConst` | field, local variable | Replace initializer with JEXL expression (backward-compat alias for @PermuteValue on fields) |
| `@PermuteValue` | field, local variable, method, constructor | Replace initializer or method/constructor statement RHS by index |
| `@PermuteStatements` | method, constructor | Insert statements at first/last position (inner loop optional) |
| `@PermuteCase` | method | Accumulate switch cases per permutation (inner loop) |
| `@PermuteImport` | type | Add JEXL-evaluated imports to each generated class |
| `@PermuteTypeParam` | type parameter | Expand type parameters per permutation |
| `@PermuteReturn` | method | Control return type per permutation |
| `@PermuteMethod` | method | Generate multiple overloads per permutation |
| `@PermuteExtends` | class | Set the extends/implements clause from JEXL expression (inline mode only) |
| `@PermuteFilter` | class, method | Skip a permutation when the JEXL expression is false (repeatable — conditions ANDed) |
| `@PermuteAnnotation` | class, interface, method, field | Add a Java annotation to the generated element per permutation; JEXL condition optional; repeatable |
| `@PermuteThrows` | method | Add an exception to a method's throws clause per permutation; JEXL condition optional; add-only; repeatable |
| `@PermuteSource` | class | Declare dependency on generated class family; enables ordering + type param inference; Maven plugin only (repeatable) |
| `@PermuteDelegate` | field | Synthesise delegating method bodies from source interface; optional modifier (e.g. "synchronized") |
| `@PermuteEnumConst` | enum constant (field) | Expand a sentinel enum constant into a sequence of constants per permutation |

**`from`/`to` are JEXL expression strings**, not int literals — `"3"`, `"${i-1}"`, `"${max}"` are all valid. Named constants resolve in priority order: system properties (`-Dpermuplate.*`) < APT options (`-Apermuplate.*`, APT only) < annotation `strings`. See [OVERVIEW.md § External Property Injection](OVERVIEW.md#external-property-injection).

---

## Key non-obvious decisions and past bugs

| Topic | Decision / Fix |
|---|---|
| Source reading | Use `getCharContent(true)` not `new File(sourceFile.toUri())` — the latter fails for in-memory compile-testing sources |
| Nested class lookup | Use `cu.findFirst(..., predicate)` not `cu.getClassByName()` — the latter only searches top-level types |
| `ForEachStmt` variable type | `getVariable()` returns `VariableDeclarationExpr`, not `Parameter`; type lives on `getVariables().get(0)` |
| Package resolution for nested classes | Walk `getEnclosingElement()` until `instanceof PackageElement`; the immediate enclosing element of a nested class is the outer class |
| Field vs for-each transform order | Fields first — their renames must be applied before the loop body is walked, so the loop body walker sees the already-renamed field references |
| `@PermuteParam` to range | Use `to="${i-1}"` not `to="${i}"` — the for-each variable occupies the ith slot; the params are `o1..o(i-1)` |
| `className` prefix validation | Only the **leading literal** (everything before the first `${`) is checked against the template class name — not all literal segments. `"Combo${i}x${k}"` → leading `"Combo"`, not `"Combox"`. Catches typos like `className="Bar${i}"` on `class Foo2`. |
| Cross-product iteration | `buildAllCombinations(permute)` starts with the primary variable's range, then for each `@PermuteVar` expands the list (cross-product). Result is `List<Map<String,Object>>` — each map contains all variables for one combination. |
| Template class name collision | Template class must not share a name with any generated class. `Combo1x1` with `from=2` generates `Combo2x2`, `Combo3x3` etc. — never `Combo1x1`. Same principle as `Join2` generating from 3. |
| Constructor name after class rename | After `classDecl.setName(newClassName)`, all constructor declarations must also be renamed (`classDecl.getConstructors().forEach(ctor -> ctor.setName(newClassName))`). JavaParser does not propagate class renames to constructors automatically. |
| Processor self-compilation | `-proc:none` in permuplate-processor/pom.xml |
| `annotationProcessorPaths` | Must list processor + javaparser-core + commons-jexl3 explicitly — maven-compiler-plugin 3.x isolates the processor classloader |
| Inline generation uses Maven plugin, not APT | APT can only CREATE new files; it cannot modify existing parent class files. `inline = true` therefore requires `permuplate-maven-plugin` (runs in `generate-sources`, reads source files directly with JavaParser). The APT errors on `inline = true` with a migration message. |
| Template directory not on compile path | `src/main/permuplate/` (inline templates) is never added as a Maven compile source root. The Maven plugin reads it at `generate-sources` time and writes augmented parent classes to `target/generated-sources/permuplate/`. Javac compiles only the output dir. IDE users mark `src/main/permuplate` as a source root manually. |
| InlineGenerator strips all permuplate annotations | When `keepTemplate = true`, the retained template class must have all Permuplate annotations stripped — otherwise javac cannot compile the annotations that reference types from `permuplate-annotations`. |
| `permuplate-core` null-checks Messager | `PermuteDeclrTransformer` and `PermuteParamTransformer` take `Messager` as a parameter. The Maven plugin passes `null` (no Messager in Maven). All `messager.printMessage(...)` calls are guarded with `if (messager != null)`. |
| Annotation string validation | All string attributes (`@PermuteDeclr type/name`, `@PermuteParam name`, `@Permute className`) are validated using `AnnotationStringAlgorithm.validate()` from `permuplate-ide-support`. The old `checkPrefix` (leading-literal + `startsWith`) is replaced by full substring matching (R2), orphan variable detection (R3), and no-anchor detection (R4). |
| R1 applies only to @Permute.className | Inner annotations (`@PermuteDeclr`, `@PermuteParam`) may have attributes with no variable (e.g. `type = "Object"`) when the type genuinely does not vary. R1 (no variables error) is enforced only for `@Permute.className` in `PermuteProcessor`, not by the transformers. |
| `alpha(n)` and `lower(n)` built-in JEXL functions | Registered as JEXL lambda scripts in `MapContext` (not via `JexlBuilder.namespaces`) because JEXL3's uberspect does not autobox `Integer` arguments to `int` for static method dispatch. Functions are available without prefix in all `${...}` expressions. Range 1–26; out-of-range throws at generation time. |
| `typeArgList(from, to, style)` | Returns `""` when `from > to` — the empty-range case for growing type arg lists. Styles: `"T"` → `T1,T2,...`, `"alpha"` → `A,B,...`, `"lower"` → `a,b,...`. Unknown style throws `IllegalArgumentException` at generation time. |
| `@PermuteTypeParam` implicit expansion | When `@PermuteParam(type="T${j}")` references a class type parameter, the class type params are automatically expanded to match (no `@PermuteTypeParam` annotation needed). Only fires in Maven plugin inline mode — APT templates must compile with fixed type params. |
| `@PermuteTypeParam` R1 restriction | If a method's return type references an expanding type parameter, the processor reports a compile error. The return type would be ambiguous across permutations. Use `Object` or a fixed container type instead. |
| `@PermuteTypeParam` on method type params (G4) | `@PermuteTypeParam` works on method type parameters inside `@PermuteMethod`. R3 prefix check is intentionally NOT applied — the sentinel (e.g. `PB` or `A`) is an arbitrary placeholder and need not match the generated names (`T1`, `B`, etc.). |
| `@PermuteReturn` implicit inference conditions | Two conditions must BOTH hold: (1) return type base class is in the generated set; (2) return type args consist of declared class params (fixed) followed by undeclared `T+number` vars (growing tip). Both APT and Maven plugin check the same conditions; only inline mode infers automatically. |
| `@PermuteReturn` boundary omission | Both implicit and explicit `@PermuteReturn`: when the evaluated return class is NOT in the generated set, the method is silently omitted. `when="true"` overrides this. Applies to `@PermuteMethod` overloads too (individual overload omitted when boundary fails). |
| `@PermuteReturn` leaf node pattern | Last class in a generated range has `join()` omitted automatically — same pattern as hand-written Drools `Join5First` having no `join()`. Without this, the generated code would reference a non-existent type and fail to compile. |
| `T${j}` naming enables zero-annotation inference | The processor needs a numeric suffix to identify the growing tip. Single-letter names (`A`, `B`, `C`) have no pattern — inference does not fire. Use `alpha(j)` + explicit `@PermuteReturn` for letter-based naming. |
| `@PermuteDeclr` method parameter (G2a) | `name=""` (default) changes only the type, no rename propagation. When name is non-empty, type AND name change and the new name is propagated through the method body via `renameAllUsages`. Method params are NOT validated in `validatePrefixes()` because the sentinel type (`Object`) deliberately doesn't match the annotation string (the actual generated type). |
| `@PermuteStatements` on constructors | `PermuteStatementsTransformer` processes both `MethodDeclaration` and `ConstructorDeclaration` even though `@Target` is `ElementType.METHOD`. The annotation is applied at the source level on methods that also include constructor-like syntax; the transformer walks constructors explicitly. |
| `@PermuteValue` `index` semantics | Statement positions in the original template body (0-based), evaluated BEFORE `@PermuteStatements` insertions. If `@PermuteStatements` runs first the index would shift — the transformer ordering (Value before Statements) preserves this invariant. |
| `@PermuteImport` in inline mode | Imports are added to the parent CU (not to a per-generated-class CU) because inline classes share the parent's import list. Duplicate-check before adding prevents duplicate imports when multiple permutations share the same base import. |
| `@PermuteExtends` blocks automatic expansion | When `@PermuteExtends` is present, `applyExtendsExpansion()` is skipped entirely for that class — the explicit override takes precedence. The annotation is always stripped from the generated output. |
| `@PermuteExtends` `interfaceIndex` | `0` = extends clause; `1+` = implements interface at `(interfaceIndex - 1)` in the `implementedTypes` list (0-indexed among implements). |
| `@PermuteFilter` and `@PermuteVar` cross-product | Filters applied AFTER `buildAllCombinations()` — each combination (i,j,...) evaluated independently. |
| `@Permute(values=...)` binds varName as String | When `values` is used, the loop variable is bound as a `String`, not an `Integer`. JEXL expressions that assume integer semantics (e.g. `@PermuteFilter("${T} > 2")`) will not work. Filter expressions for string-set templates must use string comparisons (e.g. `@PermuteFilter("!${T}.equals(\"Byte\")")`). |
| `@PermuteCase` body attribute extraction | Use `pair.getValue().asStringLiteralExpr().asString()` (not `stripQuotes(toString())`) for the `body` attribute. `toString()` returns the JavaParser-serialized form with escape sequences (e.g. `return \"hello\";`); `asString()` calls `unescapeJava()` giving valid Java source (`return "hello";`). Raw escaped form throws in `StaticJavaParser.parseBlock()`. |
| `@PermuteAnnotation` pipeline position | Runs LAST in the transform pipeline — after all other transformers — so `when` expressions see the final permutation state (field names renamed, type params expanded, etc.). |
| Lambda `@PermuteParam` — scoped anchor expansion | `@PermuteParam` on a typed lambda parameter (valid Java syntax) expands the lambda's param list and expands call sites **within the lambda body only** via `expandAnchorInStatement`. Method-body anchors do not bleed into lambdas; lambda anchors do not bleed into the outer method body. |
| `@PermuteMethod` empty range = leaf node | When `from > to` after evaluation (e.g. i=max, to=0), the method is silently omitted from that generated class. This is the multi-join leaf-node mechanism — not an error. |
| `@PermuteMethod.to` is optional | Inferred as `@Permute.to - i` from the enclosing class's `@Permute` annotation. Works in both APT and Maven plugin for same-module class families. Explicit `to` + `strings={"max=N"}` is the workaround for cross-module APT dependencies. |
| `@PermuteMethod` pipeline position | `applyPermuteMethod()` in InlineGenerator runs BEFORE `PermuteDeclrTransformer` — each overload clone has `@PermuteDeclr` on its parameters consumed with the inner (i,j) context, so the downstream transform sees no remaining annotations on these methods. |
| Standalone method `@PermuteTypeParam` | `ElementType.METHOD` target added to `@PermuteTypeParam`. Step 5 in `PermuteTypeParamTransformer.transform()` scans non-`@PermuteMethod` methods for method-level `@PermuteTypeParam` annotation, expands the first type parameter, removes the annotation, and propagates single-value renames into parameter types and return type. `@PermuteMethod` methods are guarded to prevent double-processing with the wrong context. |
| Propagation scope for single-value renames | `transformMethod()` and Step 5 propagate renames into parameter types and return type when `from==to` (single-value expansion). Uses word-boundary-safe `replaceTypeIdentifier()` — not `String.replace`. Multi-value expansions (`from<to`) intentionally skip propagation — no single target exists; use `@PermuteDeclr` explicitly for those parameters. `@PermuteDeclr` on a parameter always overrides propagated type. |
| `@PermuteDeclr` takes precedence over propagation | Parameters with `@PermuteDeclr` are skipped during type-param rename propagation. Explicit always wins. Allows callers to specify a different type structure when propagation alone is insufficient. |
| `@PermuteDeclr` on methods | Renames the method name and (optionally) return type. `type=""` means keep existing return type (useful for `void` setters). No call-site propagation — only the signature changes. `renameAllUsages` also covers `FieldAccessExpr` (`this.fieldName`) so that setter/constructor bodies with explicit `this.` access are correctly renamed when the field is renamed. |
| `@PermuteDeclr` TYPE_USE on qualified names | For `new @PermuteDeclr(type="X.Y") A.B<>()`, JavaParser places the annotation on the scope type (`A`), not the full type (`A.B`). `transformNewExpressions` checks both `type.getAnnotations()` and `type.getScope().getAnnotations()`. Simple unqualified names (`new @PermuteDeclr(type="X") Y<>()`) always work as expected. |
| `inline=true` on top-level class templates | Supported since the fix to `InlineGenerator.generate()`. When the template is not nested, generated classes are added to `outputCu.getTypes()` rather than `outputParent.getMembers()`. The template class name must differ from all generated class names (use `RuleBuilderTemplate` → generates `RuleBuilder`). This enables `@PermuteMethod` on top-level classes. |
| `@PermuteMethod` + TYPE_USE `@PermuteDeclr` in body — use reflection for qualified names | `transformNewExpressions(clone, innerCtx)` is called inside `applyPermuteMethod` after `PermuteParamTransformer` (node replacement order matters). For qualified types (`JoinBuilder.Join1First`), the scope annotation issue applies — use the reflection pattern from `extensionPoint()` instead: derive arity from the parameter class name at runtime via `ep.getClass().getSimpleName()`. |
| `BaseTuple.as()` varargs type capture | `as(T... v)` uses an empty varargs array to capture the target type at the call site — `v.getClass().getComponentType()` gives `T.class` without requiring an explicit `Class<T>` parameter. The caller passes no arguments; Java creates a zero-length `T[]` from the assignment context. |
| Varargs type-capture for nested generic DSL APIs | The pattern `public <T> T method(T... v)` captures the full inferred type — including nested generics like `Map<String, Map<String, Date>>` — from the assignment context. `Class<T>` parameter cannot do this because `Map<String, Map<String, Date>>.class` is illegal in Java (erasure). The varargs trick is broadly applicable to any DSL method that needs type-safe return without a `Class<T>` argument: `as()`, `type()`, `params()`, and future extensions all use this pattern in the Drools DSL sandbox. |
| `extensionPoint()` uses reflection (same pattern as `join()`) | `extensionPoint()` on `Join0Second` must instantiate `RuleExtendsPoint.RuleExtendsPointN` where N varies with arity. Since `new RuleExtendsPoint${i+1}<>(rd)` is not valid Java, the method reads the arity from `getClass().getSimpleName()` and uses `Class.forName()` to instantiate the right inner class — same pattern as `join()`. |
| `copyTuple()` at OOPath leaf | `executePipeline()` mutates the shared tuple via `set(index, value)`. Without copying at the leaf, collecting results from sibling branches corrupts each other's data. `copyTuple()` is called once per leaf — not per traversal step. |
| `addParamsFact()` for filter-trim correctness | When params are injected via `run(ctx, params)`, they occupy fact[0] at runtime but are NOT registered as a `TupleSource`. Without calling `rd.addParamsFact()` (which increments `accumulatedFacts`) at build time, `wrapPredicate()`'s trim logic miscounts the fact positions, causing single-fact filters to extract the wrong fact. |
| Record template support | `StaticJavaParser` configured for Java 17 in `PermuteProcessor.init()` and `InlineGenerator` static initializer. All 6 transformer signatures generalized from `ClassOrInterfaceDeclaration` to `TypeDeclaration<?>`. `RecordDeclaration.getParameters()` are `Parameter` nodes — `@PermuteParam` and `@PermuteDeclr` on record components work via `transformRecordComponents()` in both transformers. `@PermuteMethod`, `@PermuteReturn`, and extends expansion are COID-only and skipped for records. `TypeDeclaration<?>` doesn't implement `NodeWithTypeParameters` — `getTypeParameters()`/`setTypeParameters()` helpers cast through `NodeWithTypeParameters<?>`. |
| `@Permute` on enum types | `EnumDeclaration` extends `TypeDeclaration<?>` — all generic-typed transformers work without changes. `@PermuteMethod`, `@PermuteReturn`, and extends expansion are COID-only and guarded by `instanceof ClassOrInterfaceDeclaration`. `PermuteEnumConstTransformer.transform()` dispatches on `instanceof EnumDeclaration` — no-op for all other `TypeDeclaration` subtypes. `findTemplateType()` in `PermuteProcessor` and the equivalent lookup in `PermuteMojo` both chain `.or(() -> cu.findFirst(EnumDeclaration.class, ...))`. `SourceScanner` scans `cu.findAll(EnumDeclaration.class)`. `InlineGenerator` uses `instanceof` pattern matching (COID / EnumDeclaration / else-record) instead of the prior `isRecord` boolean. |
| `@PermuteSource` reads from `parentCu` | PermuteMojo chains generate() calls — Template A's output becomes Template B's parentCu. applySourceTypeParams() finds the already-generated class by name in parentCu. No separate file I/O needed — the chain handles ordering. |
| Builder synthesis trigger condition | applyBuilderSynthesis() fires only when: (1) @PermuteSource references a RecordDeclaration AND (2) the generated class body is empty. Non-empty body = skip synthesis entirely. |
| `@PermuteDelegate` synthesised methods need public | Source interface methods are implicitly public. When synthesising delegation, the method must be explicitly declared public or the override weakens access — which is a compile error. |
| `@PermuteSource` stripped from output | @PermuteSource and @PermuteSources annotations are stripped from generated classes (same as @Permute, @PermuteFilter etc.) to prevent "cannot find symbol" compile errors on the generated output. |
| IntelliJ rename propagation covers @PermuteAnnotation, @PermuteThrows, @PermuteSource | All three are in `AnnotationStringRenameProcessor.ALL_ANNOTATION_FQNS`. Renaming a class updates their `.value` strings atomically. `@PermuteFilter` is excluded — its `.value` is a boolean JEXL expression with no class references. |
| IntelliJ `PsiAnnotation.getQualifiedName()` simple-name fallback | When annotation imports are unresolved (e.g. in tests), `getQualifiedName()` returns the bare simple name with no dot prefix — `endsWith(".AnnotationName")` is false. All inspections add a third guard: `|| fqn.equals("AnnotationSimpleName")`. Required whenever adding a new `LocalInspectionTool` for a Permuplate annotation. |

---

## Error reporting standard

All errors emitted by `PermuteProcessor` must use the most precise location available, in this priority order:

1. **Attribute-level** — `messager.printMessage(ERROR, msg, element, annotationMirror, annotationValue)` — points the IDE cursor to the specific annotation attribute (e.g. `className = "Foo${i}"`). Use this for errors about a specific annotation attribute value.
2. **Annotation-level** — `messager.printMessage(ERROR, msg, element, annotationMirror)` — highlights the whole annotation. Use when the error is about the annotation as a whole.
3. **Element-level** — `messager.printMessage(ERROR, msg, element)` — highlights the annotated class/method. Use this minimum for all errors; never emit a locationless error.

The helpers `findAnnotationMirror(element, fqn)`, `findAnnotationValue(mirror, attribute)`, and `error(msg, element, mirror, value)` in `PermuteProcessor` encapsulate this pattern.

Transformer-level errors (from `PermuteDeclrTransformer`, `PermuteParamTransformer`) receive the `Element` of the annotated type/method threaded through from the processor, giving at minimum file-level precision. They do not have access to `AnnotationMirror` (they operate on the JavaParser AST), so element-level is their maximum precision.

**Rule: every new error added anywhere in the processor pipeline must include at least an `Element` location. No bare `messager.printMessage(ERROR, msg)` calls.**

---

## Refactoring safety limitation

Annotation string parameters (`type = "Callable${i}"`, `name = "c${i}"`) are opaque strings — the IDE does not treat them as references. If you rename `Callable2`, you must hand-edit these strings.

Workflow: do the rename → `git diff` to spot changed lines → update the matching annotation strings → rebuild.

This is a known limitation. See [OVERVIEW.md § Roadmap](OVERVIEW.md#possible-roadmap) for ideas on addressing it.

---

## Testing

Tests live in `permuplate-tests/` and use Google's `compile-testing` library. Each test compiles a Java source string in-process with `PermuteProcessor` attached and asserts on the generated source content.

```java
Compilation compilation = Compiler.javac()
        .withProcessors(new PermuteProcessor())
        .compile(source, callable2(), callable3());

assertThat(compilation).succeeded();
String src = sourceOf(compilation.generatedSourceFile("io.permuplate.example.Join3").orElseThrow(...));
assertThat(src).contains("c3.call(o1, o2, o3)");
```

For the full test coverage map (which test class covers which annotation), see [OVERVIEW.md § Testing Strategy](OVERVIEW.md#testing-strategy).

### Drools DSL Sandbox Tests

The sandbox (`permuplate-mvn-examples`) has its own test suite in
`src/test/java/io/quarkiverse/permuplate/example/drools/`. Tests are
organized one class per DSL feature, mirroring the Drools vol2 reference.

**Before beginning any DSL work, read all test files in the vol2 reference
suite — not just the one directly related to the feature.** The full suite
gives a much broader understanding of the DSL's intended behaviour and often
reveals design constraints not obvious from the API alone.

Vol2 tests are at:
`/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/test/java/org/drools/core/`

Key files: `ExtensionPointTest`, `OOPathTest`, `Filter1Test`,
`BiLinearTuplePredicateCacheTest`, `RuleBuilderTest`, `RuleBaseTest`,
`RuleProapgationAndExecutionTest`, `DataBuilderTest`, `ExecutorTest`.

After completing the extends feature, do a systematic review of all sandbox
work to date against the full vol2 test suite to identify gaps.

---

## What to read next

- [README.md](README.md) — user-facing overview with examples and quick start
- [OVERVIEW.md](OVERVIEW.md) — architecture deep-dive, market comparison, full roadmap (annotation processor core)
- [Design snapshots](docs/design-snapshots/) — frozen architecture state records; the 2026-04-06 snapshot is the current reference for sandbox architecture
- [ADRs](docs/adr/) — formal records of key architectural decisions (ADR-0001..0004 cover DSL sandbox)
- `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/` — the processor source files
- `permuplate-tests/src/test/java/io/quarkiverse/permuplate/` — test classes

---

## Blog

**Blog directory:** `site/_posts/`

Blog posts are Jekyll posts — they must have YAML frontmatter (layout, title, date, phase, phase_label). The site is built with Jekyll from `site/` and deployed to `mdproctor.github.io/permuplate/`.

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting any entry. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without first verifying it against the style guide's "What to Avoid" section.

The guide covers Mark's voice and personality, the three collaboration registers (I / we / Claude named directly), structural patterns, quantitative fingerprint, and the heading smell check.

**Phase labels for new posts:**
- Phase 1 — The Annotation Processor (Apr 4)
- Phase 2 — The Drools DSL Sandbox (Apr 6–7)
- Phase 3 — The IntelliJ Plugin (Apr 8–9)
- Add new phases as the project evolves.

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/permuplate
**Changelog:** GitHub Releases (run `gh release create --generate-notes` at milestones)

**Automatic behaviours (Claude follows these when this section is present):**
- Before starting any significant task, check if it spans multiple concerns.
  If it does, help break it into separate issues before beginning work.
- When staging changes before a commit, check if they span multiple issues.
  If they do, suggest splitting the commit using `git add -p`.
