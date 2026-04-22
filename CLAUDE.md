# Permuplate — Claude context

## Project Type

**Type:** java

This file gives future Claude sessions everything needed to contribute to Permuplate without re-deriving the architecture from scratch. Read this first, then consult [OVERVIEW.md](OVERVIEW.md) for the annotation API reference, and [ARCHITECTURE.md](ARCHITECTURE.md) for the transformation pipeline, module structure, and test coverage map. See [README.md](README.md) for the user-facing picture.

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

27 annotations in `permuplate-annotations/`. For per-annotation API detail and usage examples, see [OVERVIEW.md § Annotation API Detail](OVERVIEW.md#annotation-api-detail).

| Annotation | Target | Purpose |
|---|---|---|
| `@Permute` | class, interface, method | Master: declares the permutation loop (integer range via `from`/`to`, or string set via `values`) |
| `@PermuteVar` | (nested in @Permute) | Extra loop axes for cross-product generation |
| `@PermuteDeclr` | field, parameter, for-each var, method | Rename type+name per permutation |
| `@PermuteParam` | method/constructor parameter | Expand a sentinel parameter into a sequence |
| `@PermuteValue` | field, local variable, method, constructor | Replace initializer or method/constructor statement RHS by index |
| `@PermuteStatements` | method, constructor | Insert statements at first/last position (inner loop optional) |
| `@PermuteCase` | method | Accumulate switch cases per permutation (inner loop) |
| `@PermuteSwitchArm` | method | Generate Java 21+ arrow-switch pattern arms per permutation; `pattern`/`body`/`when` are JEXL templates; IntelliJ rename propagation covers `pattern` |
| `@PermuteImport` | type | Add JEXL-evaluated imports to each generated class |
| `@PermuteTypeParam` | type parameter | Expand type parameters per permutation |
| `@PermuteReturn` | method | Control return type per permutation; repeatable (first matching `when=` wins); `typeParam=` sets return to a named type param |
| `@PermuteSelf` | method | Set return type to current generated class + type params; runs after @PermuteTypeParam expansion |
| `@PermuteMethod` | method | Generate multiple overloads per permutation |
| `@PermuteExtends` | class | Set the extends/implements clause from JEXL expression (inline mode only) |
| `@PermuteFilter` | class, method | Skip a permutation when the JEXL expression is false (repeatable — conditions ANDed) |
| `@PermuteMacros` | outer class | Declares JEXL macros available to all nested `@Permute` templates in the same type hierarchy; innermost wins; macros= on @Permute shadows container macros |
| `@PermuteAnnotation` | class, interface, method, field | Add a Java annotation to the generated element per permutation; JEXL condition optional; repeatable |
| `@PermuteThrows` | method | Add an exception to a method's throws clause per permutation; JEXL condition optional; add-only; repeatable |
| `@PermuteSource` | class | Declare dependency on generated class family; enables ordering + type param inference; Maven plugin only (repeatable) |
| `@PermuteDelegate` | field | Synthesise delegating method bodies from source interface; optional modifier (e.g. "synchronized") |
| `@PermuteEnumConst` | enum constant (field) | Expand a sentinel enum constant into a sequence of constants per permutation |
| `@PermuteDefaultReturn` | class | Class-level default return type for all `Object`-returning methods lacking `@PermuteReturn`. `typeArgs` evaluates to full `<...>` suffix. Special value: `className="self"` → current generated class + all its type params (typeArgs ignored). Explicit `@PermuteReturn` always takes precedence. |
| `@PermuteBody` | method, constructor | Replace an entire method or constructor body per permutation; repeatable (first matching `when=` wins); works inside `@PermuteMethod` clones |
| `@PermuteBodyFragment` | template class or enclosing type | Defines a named Java code fragment; substituted as `${name}` in `@PermuteBody` strings before JEXL evaluation. JEXL-evaluated first. `@Repeatable`. Maven plugin only. Fragment names must not clash with JEXL built-ins (alpha, lower, max, min, etc.). |
| `@PermuteMixin` | class | Inject methods from a named mixin class into the template (or non-template class in `src/main/permuplate/`) before the transform pipeline; Maven plugin only; mixin must be in same source root |
| `@PermuteSealedFamily` | class | Auto-generates a sealed marker interface in the enclosing type and adds `implements InterfaceName<typeParams>` to each generated class. `typeParams` is used verbatim in both the interface declaration and the implements clause. Maven plugin only. |
| `@PermuteExtendsChain` | class | Shorthand extends-previous-in-family: auto-generates `@PermuteExtends` pointing to `${className}${i-1}` with matching alpha typeArgList; explicit `@PermuteExtends` always wins |

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
| Alpha growing-tip inference | When `@PermuteReturn` has `className` but no `typeArgs`/`typeArgVarName`, and the method has a single-value `@PermuteTypeParam` (from==to) whose `name` template contains `alpha`, `typeArgs` is inferred as the current class's type parameters plus the new alpha letter. Fires in both APT and Maven plugin. Two-phase approach: Phase 1 collects alpha letters BEFORE `PermuteTypeParamTransformer` consumes the method-level annotation; Phase 2 combines with post-expansion class type params AFTER. `IdentityHashMap<MethodDeclaration, String>` keys prevent invalidation when downstream transformers rename parameter types. Explicit `typeArgs` always takes precedence. Applied to `join()` in JoinBuilder — eliminates `typeArgList(1,i+1,'alpha')` expression. |
| `@PermuteReturn replaceLastTypeArgWith=` | When set, the return type = current generated class + all its type parameters except the last, which is replaced by the specified value. Reads `classDecl.getTypeParameters()` directly (after `@PermuteTypeParam` expansion). Mutually exclusive with `typeArgs` and `typeArgVarName` (checked before the alpha inference block). Applied in both APT (`applyPermuteReturn` and `applyPermuteReturnSimple`) and Maven plugin (`applyPermuteReturn`). Applied to `type()` in JoinBuilder — removes the `(i > 1 ? ', ' : '')` ternary that was needed to handle the boundary between 0 and 1 alpha growing type args. |
| `T${j}` naming enables zero-annotation inference | The processor needs a numeric suffix to identify the growing tip. Single-letter names (`A`, `B`, `C`) have no pattern — inference does not fire. Use `alpha(j)` + `@PermuteTypeParam` (single-value) to enable alpha growing-tip inference for the typeArgs. |
| `@PermuteDeclr` method parameter (G2a) | `name=""` (default) changes only the type, no rename propagation. When name is non-empty, type AND name change and the new name is propagated through the method body via `renameAllUsages`. Method params are NOT validated in `validatePrefixes()` because the sentinel type (`Object`) deliberately doesn't match the annotation string (the actual generated type). |
| `@PermuteStatements` on constructors | `PermuteStatementsTransformer` processes both `MethodDeclaration` and `ConstructorDeclaration` even though `@Target` is `ElementType.METHOD`. The annotation is applied at the source level on methods that also include constructor-like syntax; the transformer walks constructors explicitly. |
| `@PermuteValue` `index` semantics | Statement positions in the original template body (0-based), evaluated BEFORE `@PermuteStatements` insertions. If `@PermuteStatements` runs first the index would shift — the transformer ordering (Value before Statements) preserves this invariant. |
| `@PermuteImport` in inline mode | Imports are added to the parent CU (not to a per-generated-class CU) because inline classes share the parent's import list. Duplicate-check before adding prevents duplicate imports when multiple permutations share the same base import. |
| `@PermuteExtends` blocks automatic expansion | When `@PermuteExtends` is present, `applyExtendsExpansion()` is skipped entirely for that class — the explicit override takes precedence. The annotation is always stripped from the generated output. |
| `@PermuteExtends` `interfaceIndex` | `0` = extends clause; `1+` = implements interface at `(interfaceIndex - 1)` in the `implementedTypes` list (0-indexed among implements). |
| `@PermuteFilter` and `@PermuteVar` cross-product | Filters applied AFTER `buildAllCombinations()` — each combination (i,j,...) evaluated independently. |
| `@Permute(values=...)` binds varName as String | When `values` is used, the loop variable is bound as a `String`, not an `Integer`. JEXL expressions that assume integer semantics (e.g. `@PermuteFilter("${T} > 2")`) will not work. Filter expressions for string-set templates must use string comparisons (e.g. `@PermuteFilter("!${T}.equals(\"Byte\")")`). |
| `@PermuteVar` string-set axis | `values={"A","B"}` on `@PermuteVar` produces cross-product with string variable. Fully wired: annotation has `String[] values() default {}`, `PermuteVarConfig` carries it, `AnnotationReader.readExtraVars()` reads it, `PermuteConfig.buildAllCombinations()` cross-products correctly. Variable binds as `String` in JEXL — arithmetic expressions don't work, string functions like `capitalize()` do. The R1b validator accepts `${fn(varName)}` forms via word-boundary matching. Tested in `StringSetIterationTest`. |
| `@PermuteCase` body attribute extraction | Use `pair.getValue().asStringLiteralExpr().asString()` (not `stripQuotes(toString())`) for the `body` attribute. `toString()` returns the JavaParser-serialized form with escape sequences (e.g. `return \"hello\";`); `asString()` calls `unescapeJava()` giving valid Java source (`return "hello";`). Raw escaped form throws in `StaticJavaParser.parseBlock()`. |
| `@PermuteSwitchArm` body parsing | Constructs a synthetic `switch (__x__) { case <pattern> [when <guard>] -> { <body> } default -> { throw ...; } }` with global JAVA_21 parser level, extracts the first SwitchEntry. Auto-appends `;` to non-block bodies lacking one. Simpler than save/restore since JAVA_21 is the permanent global level. |
| `@PermuteSwitchArm` vs `@PermuteCase` | `@PermuteCase` targets colon-switch (`case N:`); `@PermuteSwitchArm` targets arrow-switch (`case Type var ->`). Java forbids mixing both in one switch, so they are intentionally separate annotations. `@PermuteSwitchArm` is registered immediately after `@PermuteCase` in both APT and Maven pipelines. |
| `@PermuteCase` arrow-switch detection | `PermuteCaseTransformer` inspects existing `SwitchEntry.Type`: if any entry is `EXPRESSION`, `BLOCK`, or `THROWS_STATEMENT`, arrow form is used for new arms; otherwise colon form (existing behaviour). Handles both `SwitchStmt` and `SwitchExpr` via `findSwitchEntries()`. Arrow arm built via synthetic switch parse at the global JAVA_21 level (set in `PermuteProcessor.init()` and `InlineGenerator` static block). Auto-appends `;` to non-block bodies. |
| `@PermuteSwitchArm` APT source-level validation | `PermuteProcessor` checks `processingEnv.getSourceVersion().ordinal() < 21` after parsing the template class and detecting `@PermuteSwitchArm` on any method. Uses ordinal (not `SourceVersion.RELEASE_21` by name) to avoid a compile-time dependency on Java 21 tools when building the processor with Java 17. Error is at element-level (the annotated class). Returns early to skip generating source that javac would reject. |
| JavaParser 3.28.0 + JAVA_21 parser level | JavaParser 3.25.9 does not support `ParserConfiguration.LanguageLevel.JAVA_21` — bumped to 3.28.0 to enable @PermuteSwitchArm pattern arm parsing. Global parser language level changed from JAVA_17 to JAVA_21 (backward compatible — all Java 17 code is valid Java 21). Change is safe because APT runs single-threaded. |
| `@PermuteAnnotation` pipeline position | Runs LAST in the transform pipeline — after all other transformers — so `when` expressions see the final permutation state (field names renamed, type params expanded, etc.). |
| `@PermuteSelf` pipeline position | Runs immediately after `PermuteTypeParamTransformer` in both APT and Maven plugin so type params are already expanded. Reads `classDecl.getNameAsString()` and `classDecl.getTypeParameters()` directly from the already-renamed generated class. `TypeDeclaration<?>` does not expose `getTypeParameters()` — dispatches through `NodeWithTypeParameters<?>` cast; returns empty list for `EnumDeclaration` (no type params). When combined with `@PermuteMethod`, `@PermuteSelf` fires on the template method BEFORE cloning; each clone inherits the outer class's return type. Safe only when `@PermuteMethod`'s inner variable does not affect the expected return type (e.g., `filterLatest` in JoinBuilder uses from=to=i, generating one clone whose return type is the outer class). Do NOT use `@PermuteSelf` on a `@PermuteMethod`-annotated method if the return type should vary with the inner variable — use explicit `@PermuteReturn` instead. |
| Lambda `@PermuteParam` — scoped anchor expansion | `@PermuteParam` on a typed lambda parameter (valid Java syntax) expands the lambda's param list and expands call sites **within the lambda body only** via `expandAnchorInStatement`. Method-body anchors do not bleed into lambdas; lambda anchors do not bleed into the outer method body. |
| `@PermuteMethod` empty range = leaf node | When `from > to` after evaluation (e.g. i=max, to=0), the method is silently omitted from that generated class. This is the multi-join leaf-node mechanism — not an error. |
| `@PermuteMethod` string-set axis | `values={"A","B"}` generates one clone per string value, binding `varName` as a String (same as `@Permute(values=...)`). Mutually exclusive with `from`/`to`. Implemented in both `applyPermuteMethodClone` (InlineGenerator) and `applyPermuteMethodAptClone` (PermuteProcessor). The clone loop body is identical to the integer path; only `innerCtx` creation differs (`ctx.withVariable(name, stringValue)` vs `ctx.withVariable(name, intValue)`). J-based implicit type expansion is skipped for string-set path (passes `j=-1` to the shared clone helper). |
| `@PermuteMethod macros=` | `String[] macros()` on `@PermuteMethod`. Same format as `@Permute macros=` (`"name=jexlExpr"`) but evaluated with the inner method variable (`j`/`n`) in scope. Enables n-dependent expressions like `typeArgList(i,i+n-1,'alpha')` to be named (`tail`, `prev`) and reused in `typeArgs`, `@PermuteReturn`, and `@PermuteBody`. Applied in `applyPermuteMethod` (InlineGenerator, via `applyMethodMacros()` helper) and `applyPermuteMethodApt` (PermuteProcessor, via `applyMethodMacrosApt()` helper) immediately after `innerCtx` is created with the loop variable, before clone processing. Both the integer-range path and the string-set path apply macros. Evaluation failure is silently skipped. |
| `@PermuteMethod.to` is optional | Inferred as `@Permute.to - i` from the enclosing class's `@Permute` annotation. Works in both APT and Maven plugin for same-module class families. Explicit `to` + `strings={"max=N"}` is the workaround for cross-module APT dependencies. |
| `@PermuteMethod` pipeline position | `applyPermuteMethod()` in InlineGenerator runs BEFORE `PermuteDeclrTransformer` — each overload clone has `@PermuteDeclr` on its parameters consumed with the inner (i,j) context, so the downstream transform sees no remaining annotations on these methods. |
| Standalone method `@PermuteTypeParam` | `ElementType.METHOD` target added to `@PermuteTypeParam`. Step 5 in `PermuteTypeParamTransformer.transform()` scans non-`@PermuteMethod` methods for method-level `@PermuteTypeParam` annotation, expands the first type parameter, removes the annotation, and propagates single-value renames into parameter types and return type. `@PermuteMethod` methods are guarded to prevent double-processing with the wrong context. |
| Propagation scope for single-value renames | `transformMethod()` and Step 5 propagate renames into parameter types and return type when `from==to` (single-value expansion). Uses word-boundary-safe `replaceTypeIdentifier()` — not `String.replace`. Multi-value expansions (`from<to`) intentionally skip propagation — no single target exists; use `@PermuteDeclr` explicitly for those parameters. `@PermuteDeclr` on a parameter always overrides propagated type. |
| `@PermuteDeclr` takes precedence over propagation | Parameters with `@PermuteDeclr` are skipped during type-param rename propagation. Explicit always wins. Allows callers to specify a different type structure when propagation alone is insufficient. |
| `@PermuteDeclr` on methods | Renames the method name and (optionally) return type. `type=""` means keep existing return type (useful for `void` setters). No call-site propagation — only the signature changes. `renameAllUsages` also covers `FieldAccessExpr` (`this.fieldName`) so that setter/constructor bodies with explicit `this.` access are correctly renamed when the field is renamed. |
| `@PermuteDeclr` TYPE_USE on qualified names | For `new @PermuteDeclr(type="X.Y") A.B<>()`, JavaParser places the annotation on the scope type (`A`), not the full type (`A.B`). `transformNewExpressions` checks both `type.getAnnotations()` and `type.getScope().getAnnotations()`. Simple unqualified names (`new @PermuteDeclr(type="X") Y<>()`) always work as expected. |
| `inline=true` on top-level class templates | Supported since the fix to `InlineGenerator.generate()`. When the template is not nested, generated classes are added to `outputCu.getTypes()` rather than `outputParent.getMembers()`. The template class name must differ from all generated class names (use a distinct template class name that generates the target name). For non-template mixin classes, use `@PermuteMixin` without `@Permute` — see `@PermuteMixin` on non-template classes in the decisions table. This enables `@PermuteMethod` on top-level classes. |
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
| Sealed JoinFirst/JoinSecond hierarchy | `JoinBuilderFirst<END,DS>` and `JoinBuilderSecond<END,DS>` sealed interfaces live inside `JoinBuilder.java`. Template classes (`Join0First`, `Join0Second`) declare `implements` and `permits` respectively. `expandSealedPermits` in InlineGenerator replaces the template name with all generated names in the permits clause. Generated classes must be `non-sealed` (not bare `class`) because they directly implement a sealed interface — Java 17+ requires `sealed`, `non-sealed`, or `final` on direct implementors. `Join0Second` cannot be `final` because `Join0First` extends it. Generated classes are always regenerated — sealing has no downside and enables Java 21+ pattern dispatch over the family. |
| `@PermuteDelegate` synthesised methods need public | Source interface methods are implicitly public. When synthesising delegation, the method must be explicitly declared public or the override weakens access — which is a compile error. |
| `@PermuteSource` stripped from output | @PermuteSource and @PermuteSources annotations are stripped from generated classes (same as @Permute, @PermuteFilter etc.) to prevent "cannot find symbol" compile errors on the generated output. |
| IntelliJ rename propagation covers @PermuteAnnotation, @PermuteThrows, @PermuteSource | All three are in `AnnotationStringRenameProcessor.ALL_ANNOTATION_FQNS`. Renaming a class updates their `.value` strings atomically. `@PermuteFilter` is excluded — its `.value` is a boolean JEXL expression with no class references. |
| IntelliJ `PsiAnnotation.getQualifiedName()` simple-name fallback | When annotation imports are unresolved (e.g. in tests), `getQualifiedName()` returns the bare simple name with no dot prefix — `endsWith(".AnnotationName")` is false. All inspections add a third guard: `|| fqn.equals("AnnotationSimpleName")`. Required whenever adding a new `LocalInspectionTool` for a Permuplate annotation. |
| `evaluateIntOrError` / `evaluateOrError` helpers | Private methods in `PermuteProcessor` that wrap JEXL evaluation and call `error()` with the annotation name, attribute name, failing expression, and JEXL message when evaluation fails. Apply to every new catch site that processes user-facing JEXL expressions. Leave intentional silent-fallback sites (inference, optional expansion steps) unchanged. |
| `@Permute macros=` attribute | Format: `"name=jexlExpr"`. Evaluated after all loop variables (`varName`, `extraVars`, `strings`) are bound, in declaration order — later macros may reference earlier ones. Implemented in `buildAllCombinations()` as a post-pass over the fully-built cross-product: for each combination map, evaluates each macro expression (wrapped as `${expr}` if no `${` present) and `put`s the result into the mutable `vars` HashMap so subsequent macros and all downstream JEXL expressions see it. Malformed macros (no `=`) and evaluation failures are silently skipped. Stored as `String[]` field on `PermuteConfig`; populated via `permute.macros()` in the APT factory and `readStringArray(normal, "macros")` in `AnnotationReader`. |
| Self-return inference (Maven plugin) | Post-pass in `InlineGenerator.generate()` after all transformers: detects methods with `Object` sentinel return, no explicit `@PermuteReturn`, and body returning only `this` or `cast(this)`. Auto-sets return type to current generated class. `isSelfReturning()` checks all `ReturnStmt` nodes in the body. Runs after `PermuteSelfTransformer` so explicit `@PermuteSelf` annotations are already consumed. APT mode uses `@PermuteSelf` instead (no inference in APT). `applySelfReturnInference()` uses `NodeWithTypeParameters<?>` cast to get type params from `TypeDeclaration<?>`. |
| `@PermuteDefaultReturn` pipeline position | Runs in APT (`applyDefaultReturn`) and Maven plugin (`applyInlineDefaultReturn`) immediately AFTER `applyPermuteReturn`, so explicit `@PermuteReturn` annotations are already consumed and removed before the default is applied. Only `Object`-returning methods with no remaining `@PermuteReturn` receive the default. `typeArgs` evaluates to the full type argument suffix **including angle brackets** (e.g. `"'<END, A>'"` → appended as `"<END, A>"`); this differs from `@PermuteReturn.typeArgs` which omits the brackets. The annotation is stripped from the generated class regardless of whether any methods were affected. `alwaysEmit` is a boolean in the annotation; in InlineGenerator it is read via `BooleanLiteralExpr`, not `asStringLiteralExpr()`. |
| `extendsRule()` duplication (see ADR-0006) | Cannot be deduplicated. `@PermuteSelf` does not apply — `extendsRule()` returns `JoinBuilder.Join${j-1}First`, not `this`. `@PermuteDefaultReturn` does not apply — each template has exactly one `@PermuteReturn`. Structural deduplication is blocked by three constraints: (1) `@PermuteMethod` on base-class (`AbstractRuleEntry`) methods is invisible to the pipeline — no overloads generated; (2) `RuleBuilder` and `ParametersFirst` (now non-template `@PermuteMixin` classes) have completely different surrounding method sets, making a single string-set template infeasible; (3) the body difference is already abstracted via `ruleName()` — one abstract method per class, not per overload. See [docs/adr/0006-extendsrule-duplication.md](docs/adr/0006-extendsrule-duplication.md) for full analysis. |
| `macros=` name must not collide with built-in JEXL functions | `buildJexlContext()` calls `vars.putAll(JEXL_FUNCTIONS)` AFTER copying the user variable map — built-ins overwrite any macro with the same name. The built-in names are: `alpha`, `lower`, `typeArgList`, `capitalize`, `decapitalize`, `max`, `min`, `__throwHelper`. Using `macros={"alpha=..."}` silently shadows the macro with the `alpha(n)` function script. Use a distinct name (e.g. `alphaList`, `tArgs`) to avoid the collision. JoinBuilder uses `alphaList=typeArgList(1,i,'alpha')` on both `Join0Second` and `Join0First` `@Permute` annotations, eliminating 5 repeated `typeArgList(1,i,'alpha')` calls. macros= defined on @Permute are local to that template. @PermuteMacros on an enclosing outer class provides macros to all nested @Permute templates (innermost wins). Built-in JEXL names (alpha, lower, typeArgList, capitalize, decapitalize, max, min, __throwHelper) always win — container or template macros with the same name are silently overwritten. |
| `@PermuteReturn(typeParam=)` | When non-empty, sets the method return type to the named type parameter (e.g. `"END"`) rather than a generated class name. Always emits the method — boundary omission does not apply. Mutually exclusive with `className`, `typeArgs`, `typeArgVarName`, `replaceLastTypeArgWith`. Maven plugin only (APT templates must use real return types). Applied in `InlineGenerator.applyPermuteReturn` before the `className` evaluation block. |
| `@PermuteReturn` is repeatable | `@PermuteReturn` is now `@Repeatable` with `@PermuteReturns` container. Multiple `@PermuteReturn` on one method are processed in order — the first one whose `when=` condition evaluates to `true` is selected. JavaParser reads two `@PermuteReturn` annotations as separate nodes (not wrapped in the container) since the compiler wraps them at the class-file level, not in the source AST. The new `applyPermuteReturn` in `InlineGenerator` iterates all direct `@PermuteReturn` and any unwrapped from `@PermuteReturns` container; all `@PermuteReturn`/`@PermuteReturns` annotations are removed after processing. `collectExplicitReturnMethodNames` also detects `@PermuteReturns`. `stripPermuteAnnotations` strips both `PermuteReturn` and `PermuteReturns`. |
| `@PermuteBody` is repeatable with `when=` | `@PermuteBody` has a new `when() default ""` attribute (JEXL expression without `${...}` wrapper) and is now `@Repeatable` with `@PermuteBodies` container. `PermuteBodyTransformer.selectBody()` iterates all `@PermuteBody` entries (direct or unwrapped from container) and returns the first matching one. An entry with no `when=` always matches. Multiple `@PermuteBody` on one method are processed in order — first match wins. `stripPermuteAnnotations` strips both `PermuteBody` and `PermuteBodies`. |
| `@PermuteTypeParam` empty range in Maven plugin mode | When `from > to` on a class-level `@PermuteTypeParam`, APT mode reports an error and keeps the sentinel (unchanged behavior). Maven plugin mode (messager=null) silently removes the sentinel — analogous to `@PermuteMethod` empty range omitting a method. Enables unified templates that start below the minimum expansion arity, e.g. `@PermuteTypeParam(from="3", to="${i}")` with i=2 removes the sentinel, giving `<END, T, A, B>` instead of `<END, T, A, B, C>`. The `when=` attribute in `@PermuteReturn` and `@PermuteBody` uses bare JEXL expressions (no `${...}` wrapper) — the framework wraps them before evaluation: `ctx.evaluate("${" + when + "}")`. |
| `max()`/`min()` JEXL built-ins | `max(a,b)` and `min(a,b)` registered as JEXL lambda scripts in `EvaluationContext.buildJexlContext()`, alongside `alpha`, `lower`, `capitalize`, etc. Both accept `int` arguments (passed directly by JEXL). `max(2,i)` eliminates ternary `(i > 2 ? i : 2)` patterns in e.g. `filterLatest`. Added to the built-in name collision list in `macros=` documentation. |
| `typeArgList` custom prefix | Unknown `style` values in `TypeArgListHelper.typeArgList()` are now treated as a literal string prefix: `style + k` for each k. `'V'` → `V1,V2,V3`; `'Param'` → `Param1,Param2`. Only `'alpha'` and `'lower'` retain letter-mapping. The `'T'` style (previously spelled out explicitly) now also falls through to the default prefix path, producing the same `T1,T2,...` result. Unknown styles no longer call `JexlThrowHelper.throwFor()` — that method has been removed. |
| Constructor super-call inference | Fires in `InlineGenerator` when: (1) the generated class has `@PermuteExtends` or `@PermuteExtendsChain`; AND (2) the constructor has ≥2 parameters; AND (3) no existing `super(...)` call; AND (4) no `@PermuteStatements` on the constructor. Inserts `super(p1, p2, ..., p_{N-1})` — all params except the last, which is the new type-specific parameter. Explicit `@PermuteStatements` always wins; if present, inference is skipped entirely so the template author retains full control. |
| `@PermuteExtendsChain` suppresses implicit expansion | Same semantics as explicit `@PermuteExtends`: `applyExtendsExpansion()` is NOT called when `@PermuteExtendsChain` fires. Explicit `@PermuteExtends` takes precedence; `@PermuteExtendsChain` is silently ignored when both are present. Generated `extends` clause is `${className}${i-1}<${typeArgList(1,i-1,'alpha')}>` where `className` is the template's `@Permute.className` prefix. The template must have a `className` pattern for chain inference to resolve the base name. |
| `@PermuteMixin` method filter | Only methods carrying at least one annotation whose simple name starts with `"Permute"` are injected into the template class. Plain override stubs (e.g. `ruleName()`, `cast()`) are excluded to prevent "method already defined" compile errors when the template also declares those methods. This is deliberate: mixins inject only annotated, template-aware methods. |
| `@PermuteMixin` resolution | Mixin class is resolved by simple name across all CUs in the source root, parsed on demand by `SourceScanner.parseAll()`. Must be in the same Maven source root as the template. The mixin class itself is never compiled or included in generated output — it is a source-only artifact. Maven plugin only (APT has no access to sibling source files). |
| `@PermuteMixin` pipeline position | Method injection runs BEFORE all transformers in `InlineGenerator.generate()` — the injected methods are present in the template AST when `applyPermuteMethod`, `PermuteDeclrTransformer`, etc. run. This means injected methods can carry any Permuplate annotation and participate fully in the transformation pipeline. |
| `@PermuteBodyFragment` substitution order | Fragments collected from template + enclosing types (outermost first, innermost wins). Each fragment JEXL-evaluated with current ctx. Text substitution of `${name}` in `@PermuteBody.body` happens BEFORE PermuteBodyTransformer processes the body. Enum path also applies fragment substitution. Evaluation failure throws RuntimeException (unlike macros= which silently skips). |
| `@PermuteDefaultReturn(className="self")` | "self" is a reserved literal (not JEXL). Maps to `classDecl.getNameAsString()` + all `classDecl.getTypeParameters()`. `typeArgs` attribute is ignored. Works in both inline and APT modes. |
| `@PermuteParam` inside `@PermuteMethod` | `PermuteParamTransformer` skips methods carrying `@PermuteMethod` in the outer pass. Each `@PermuteMethod` clone is processed by `PermuteParamTransformer.transform(clone, innerCtx)` BEFORE `@PermuteBody`, enabling `to="${m}"` to evaluate with the inner variable in scope. Call-site anchor expansion fires naturally. |
| `@PermuteReturn` inference from `@PermuteBody` | Post-pass after `PermuteBodyTransformer`: if last return stmt is `return new X<>()` or `return cast(new X<>())` and X ∈ generated set → infer return type as X. Cross-file classes not in generated set. Explicit `@PermuteReturn` always wins (already consumed before inference). APT and Maven plugin both implemented. |
| `PermuteAnnotationTransformer` in non-inline pipeline | `PermuteMojo.generateTopLevel` previously omitted `PermuteAnnotationTransformer`, causing `@PermuteAnnotation` to have no effect on non-inline templates. Fixed in batch 9 task 10 — now calls transformer before `stripPermuteAnnotations`. |
| `Consumer1`/`Predicate1` location after batch 9 | These are now hand-written arity-1 interfaces in `src/main/java/` (not templates in `src/main/permuplate/`). `FunctionalTemplate1.java` generates Consumer2..7 and Predicate2..7 via `@PermuteVar` cross-product. |
| `NegationScope.java` orphan (batch 9 fix) | Batch 8's `git rm` of `NegationScope.java` did not persist through a subsequent `git add -u`. File was generating `NegationScope` + `ExistenceScope` (both dead since batch 8 renamed to `NotScope`/`ExistsScope`). Deleted in batch 9. |
| `@PermuteMacros` integer macro type preservation | Macros evaluated by `buildAllCombinations` via `EvaluationContext.evaluateRaw()` — preserves the raw JEXL type (Integer) for single-interpolation expressions like `${i-1}`. Previously used `evaluate()` which always returned String; String macros failed silently when used in `@PermuteTypeParam to="${prev}"` because `evaluateInt` rejects non-Number results. `evaluateRaw()` returns raw Object for single `${...}` templates, falls back to String for multi-segment templates. |
| Constructor-coherence inference | After `applyPermuteReturn` resolves a method's return type to class X, any `ObjectCreationExpr` in the method body (non-`@PermuteBody`) whose type simple name strips to the same digit-free family as X is renamed automatically. Family matching uses `replaceAll("\\d+", "")` — strips ALL digit sequences, not just trailing (required for embedded-arity names like `Join2First → JoinFirst`). Cross-file families work because the digit-presence + family-equality double guard is sufficient without `allGeneratedNames` membership. Not called from the `typeParam=` path. |
| `@PermuteMixin` on non-template classes | Classes in `src/main/permuplate/` with `@PermuteMixin` but NO `@Permute` are processed by `PermuteMojo.processNonTemplateMixins()`. A synthetic `PermuteConfig(from=1,to=1)` is constructed internally; the class is augmented with mixin-generated methods and written to generated sources. Only top-level `ClassOrInterfaceDeclaration` (not interfaces, records, or nested classes) are processed. Eliminates the dummy `@Permute(varName="i",from="1",to="1")` boilerplate that `RuleBuilder` and `ParametersFirst` previously required. |
| `@PermuteSealedFamily` generates interface from scratch | Distinct from `expandSealedPermits` which expands a manually-declared `permits` clause. `applyPermuteSealedFamily` creates the sealed interface declaration + adds `implements InterfaceName<typeParams>` to each generated class. Called after `expandSealedPermits` in `generate()`. `typeParams` is used verbatim for both the interface's generic declaration and the implements type args. `keepTemplate=true` adds the template name to the permits clause. |
| `addVariableFilter` m=2..6 via `VariableFilterMixin` | `RuleDefinition.java` is now in `src/main/permuplate/` (not `src/main/java/`) with `@PermuteMixin(VariableFilterMixin.class)`. The mixin generates typed `addVariableFilter(v1..vm, predicate)` overloads for m=2..6. Each overload delegates to `addVariableFilterGeneric(predicate, variables...)` which snapshots indices and uses reflection for runtime invocation — intentionally bypasses `wrapPredicate`'s trim logic (variable indices are explicit). `filterVar` in `Join0First` generates filter overloads for m=2..6 matching these. |
| `createEmptyTuple` reflection | `RuleDefinition.createEmptyTuple(int size)` uses `Class.forName(BaseTuple.class.getName() + "$Tuple" + size)` rather than a hard-coded switch. Scales automatically with any future extension of the `BaseTuple.TupleN` family. |

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

For the full test coverage map (which test class covers which annotation), see [ARCHITECTURE.md § Testing Strategy](ARCHITECTURE.md#testing-strategy).

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
- [OVERVIEW.md](OVERVIEW.md) — annotation API reference, market comparison, roadmap (user-facing)
- [ARCHITECTURE.md](ARCHITECTURE.md) — transformation pipeline, module structure, testing strategy (contributor-facing). **Serves as `DESIGN.md` for this project** — `java-git-commit` references to `docs/DESIGN.md` should read `ARCHITECTURE.md` instead.
- [ADRs](docs/adr/) — formal records of key architectural decisions (ADR-0001..0006 cover DSL sandbox)
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
