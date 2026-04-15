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
./gradlew test          # run plugin tests (56 tests)
./gradlew buildPlugin   # produce installable zip in build/distributions/
```

Requires Maven modules built first (`mvn install`) — the plugin depends on `permuplate-ide-support` and `permuplate-annotations` jars from `target/`. IntelliJ's internal compiler does not support the `Trees` API — enable **Delegate IDE build/run actions to Maven** in IntelliJ settings (Build, Execution, Deployment → Build Tools → Maven → Runner).

**Java 17 required for Gradle:** If the shell default JDK is Java 21+ (especially Java 26), Gradle 8.6 fails with a cryptic `JavaVersion.parse` error. Set explicitly: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test`

---

## The annotations

14 annotations in `permuplate-annotations/`. For per-annotation API detail and usage examples, see [OVERVIEW.md § Annotation API Detail](OVERVIEW.md#annotation-api-detail).

| Annotation | Target | Purpose |
|---|---|---|
| `@Permute` | class, interface, method | Master: declares the permutation loop (integer range via `from`/`to`, or string set via `values`) |
| `@PermuteVar` | (nested in @Permute) | Extra loop axes for cross-product generation |
| `@PermuteDeclr` | field, parameter, for-each var | Rename type+name per permutation |
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
| permuplate-core null-checks Messager | `PermuteDeclrTransformer` and `PermuteParamTransformer` take `Messager` as a parameter. The Maven plugin passes `null` (no Messager in Maven). All `messager.printMessage(...)` calls are guarded with `if (messager != null)`. |
| Annotation string validation | All string attributes (`@PermuteDeclr type/name`, `@PermuteParam name`, `@Permute className`) are validated using `AnnotationStringAlgorithm.validate()` from `permuplate-ide-support`. The old `checkPrefix` (leading-literal + `startsWith`) is replaced by full substring matching (R2), orphan variable detection (R3), and no-anchor detection (R4). |
| R1 applies only to @Permute.className | Inner annotations (`@PermuteDeclr`, `@PermuteParam`) may have attributes with no variable (e.g. `type = "Object"`) when the type genuinely does not vary. R1 (no variables error) is enforced only for `@Permute.className` in `PermuteProcessor`, not by the transformers. |
| R2 short-circuits R3/R4 | If any static literal is not found as a substring of the target name, orphan variable (R3) and no-anchor (R4) checks are skipped — the orphan computation is undefined when the literal isn't found. |
| Adjacent variables are collective | `${v1}${v2}Callable${v3}` — the variables before "Callable" collectively cover the prefix region. Orphan detection applies to the region as a whole, not per-variable. If prefix is non-empty, neither is orphan. If prefix is empty, both are orphan. |
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
| `@PermuteConst` initializer substitution | `toExpression()` tries `Integer.parseInt` first; non-integer strings become `StringLiteralExpr`. Processed in `PermuteDeclrTransformer` after field rename so `@PermuteDeclr` renames are already visible. |
| `@PermuteConst` + `@PermuteDeclr` on same field | Order of annotations on the source element does not matter — `transformFields()` (`@PermuteDeclr`) runs before `transformConstFields()` (`@PermuteConst`), so the rename is applied first and the `@PermuteConst` annotation is still present on the (already renamed) field. |
| `@PermuteValue` on constructors | `PermuteValueTransformer` processes `ConstructorDeclaration` via `classDecl.findAll(ConstructorDeclaration.class)`. `constructor.getBody()` returns `BlockStmt` directly (not Optional). |
| `@PermuteStatements` on constructors | `PermuteStatementsTransformer` processes both `MethodDeclaration` and `ConstructorDeclaration` even though `@Target` is `ElementType.METHOD`. The annotation is applied at the source level on methods that also include constructor-like syntax; the transformer walks constructors explicitly. |
| `@PermuteValue` vs `@PermuteConst` | `@PermuteConst` is a semantic alias — backward-compatible, same `toExpression()` path. `@PermuteValue` is the preferred form in new code. Both are stripped from the generated output. |
| `@PermuteValue` `index` semantics | Statement positions in the original template body (0-based), evaluated BEFORE `@PermuteStatements` insertions. If `@PermuteStatements` runs first the index would shift — the transformer ordering (Value before Statements) preserves this invariant. |
| `@PermuteCase` empty range | `from > to` after evaluation: annotation is removed but no cases are inserted. Not an error. |
| `@PermuteImport` in inline mode | Imports are added to the parent CU (not to a per-generated-class CU) because inline classes share the parent's import list. Duplicate-check before adding prevents duplicate imports when multiple permutations share the same base import. |
| `@PermuteExtends` blocks automatic expansion | When `@PermuteExtends` is present, `applyExtendsExpansion()` is skipped entirely for that class — the explicit override takes precedence. The annotation is always stripped from the generated output. |
| `@PermuteExtends` `interfaceIndex` | `0` = extends clause; `1+` = implements interface at `(interfaceIndex - 1)` in the `implementedTypes` list (0-indexed among implements). |
| `@PermuteFilter` all-filtered-out in Maven plugin | Maven plugin has no `Messager` — silently produces zero output rather than erroring. APT errors with annotation-mirror precision. |
| `@PermuteFilter` and `@PermuteVar` cross-product | Filters applied AFTER `buildAllCombinations()` — each combination (i,j,...) evaluated independently. |
| `@Permute(values=...)` binds varName as String | When `values` is used, the loop variable is bound as a `String`, not an `Integer`. JEXL expressions that assume integer semantics (e.g. `@PermuteFilter("${T} > 2")`) will not work. Filter expressions for string-set templates must use string comparisons (e.g. `@PermuteFilter("!${T}.equals(\"Byte\")")`). |
| `@Permute` `values` XOR `from`/`to` | Both `from` and `to` default to `""` in the annotation definition. The APT processor validates exactly one mode is provided: `values` (non-empty) OR both `from` and `to` (non-empty). The empty string is the sentinel for "not provided". |
| `validatePrefixes` skipped in string-set mode | R4 (no-anchor), R2 (unmatched literal), and R3 (orphan variable) checks are all skipped when `values` is present. R4 fires spuriously for pure-variable expressions like `"${T}"` where the variable substitutes the entire type name with no literal prefix. |
| String-set in IntelliJ index (version 5) | `getStringArrayAttr()` reads the `values` array from PSI. Index versions bumped from 4→5 in both `PermuteTemplateIndex` and `PermuteGeneratedIndex` to invalidate stale caches. |
| Lambda `@PermuteParam` — scoped anchor expansion | `@PermuteParam` on a typed lambda parameter (valid Java syntax) expands the lambda's param list and expands call sites **within the lambda body only** via `expandAnchorInStatement`. Method-body anchors do not bleed into lambdas; lambda anchors do not bleed into the outer method body. |
| Pure-variable `@PermuteParam.name` — R4 exemption | `name="${lower(j)}"` and `name="${alpha(j)}"` have no static literal, which would normally trigger R4. For `@PermuteParam.name`, if the template has no static literals the R4/R2/R3 check is skipped entirely — the generated param names are fresh and have no obligation to match the sentinel's placeholder name. Literal+variable names like `name="o${j}"` still receive full R2/R3 validation. |
| `@PermuteTypeParam` R1 skips `@PermuteReturn` methods | `validateR1()` in `PermuteTypeParamTransformer` skips methods annotated with `@PermuteReturn` — such methods explicitly manage their own return type, so the restriction against expanding type params in the return type is inapplicable. |
| `@PermuteMethod` empty range = leaf node | When `from > to` after evaluation (e.g. i=max, to=0), the method is silently omitted from that generated class. This is the multi-join leaf-node mechanism — not an error. |
| `@PermuteMethod.to` is optional | Inferred as `@Permute.to - i` from the enclosing class's `@Permute` annotation. Works in both APT and Maven plugin for same-module class families. Explicit `to` + `strings={"max=N"}` is the workaround for cross-module APT dependencies. |
| `@PermuteMethod` pipeline position | `applyPermuteMethod()` in InlineGenerator runs BEFORE `PermuteDeclrTransformer` — each overload clone has `@PermuteDeclr` on its parameters consumed with the inner (i,j) context, so the downstream transform sees no remaining annotations on these methods. |
| Extends clause implicit expansion (G3) | `applyExtendsExpansion()` uses name-prefix family matching + embedded number match to detect sibling classes. Third-party classes are safely skipped. Generates same-N extends (`JoinNFirst extends JoinNSecond`). Two detection branches: (1) all-T+number type args → hardcodes `T1..TN`; (2) extends type args are a prefix of post-G1 type params → uses full post-G1 list (supports alpha naming when `@PermuteTypeParam` fires first). Both branches use `newNum = currentEmbeddedNum` (same-N formula). |
| Two-pass scan for dependency ordering | Both APT and Maven plugin scan all `@Permute` templates before generating any class — APT via `RoundEnvironment.getElementsAnnotatedWith`, Maven via file scan. The generated class set is built first; generation happens in topological order. |
| Standalone method `@PermuteTypeParam` | `ElementType.METHOD` target added to `@PermuteTypeParam`. Step 5 in `PermuteTypeParamTransformer.transform()` scans non-`@PermuteMethod` methods for method-level `@PermuteTypeParam` annotation, expands the first type parameter, removes the annotation, and propagates single-value renames into parameter types and return type. `@PermuteMethod` methods are guarded to prevent double-processing with the wrong context. |
| Propagation scope for single-value renames | `transformMethod()` and Step 5 propagate renames into parameter types and return type when `from==to` (single-value expansion). Uses word-boundary-safe `replaceTypeIdentifier()` — not `String.replace`. Multi-value expansions (`from<to`) intentionally skip propagation — no single target exists; use `@PermuteDeclr` explicitly for those parameters. `@PermuteDeclr` on a parameter always overrides propagated type. |
| `@PermuteDeclr` takes precedence over propagation | Parameters with `@PermuteDeclr` are skipped during type-param rename propagation. Explicit always wins. Allows callers to specify a different type structure when propagation alone is insufficient. |
| `@PermuteMethod` ternary `from` for conditional generation | `from="${i > 1 ? i : i+1}"` with `to="${i}"` produces an empty range at `i=1` → method omitted. At `i≥2` it produces `from=to=i` → one clone. JEXL3 supports `?:` natively. Used by `filterLatest` sentinel in `JoinBuilder` to suppress the single-fact filter at arity 1 where it would duplicate the all-facts filter. |
| `Variable.of("name")` vs `new Variable<>()` | Named variables carry a diagnostic name used in error messages and match DRL's `$person` convention. Anonymous `new Variable<>()` remains valid; `of()` is preferred for DRL migration work. |
| `from(Function)` shorthand | `RuleBuilder.from(Function)` delegates to `from("rule", source)`. Preferred when the string name isn't needed for debugging. Both forms are valid. |
| `BaseTuple.as()` varargs type capture | `as(T... v)` uses an empty varargs array to capture the target type at the call site — `v.getClass().getComponentType()` gives `T.class` without requiring an explicit `Class<T>` parameter. The caller passes no arguments; Java creates a zero-length `T[]` from the assignment context. |
| `type()` is a compile-time no-op | `Join0Second.type()` uses the same varargs type-capture trick as `as()` but discards the class at runtime — just `return cast(this)`. It exists only to provide the compiler a narrowed type parameter when a source returns a base type (e.g., `DataSource<Object>`). |
| Varargs type-capture for nested generic DSL APIs | The pattern `public <T> T method(T... v)` captures the full inferred type — including nested generics like `Map<String, Map<String, Date>>` — from the assignment context. `Class<T>` parameter cannot do this because `Map<String, Map<String, Date>>.class` is illegal in Java (erasure). The varargs trick is broadly applicable to any DSL method that needs type-safe return without a `Class<T>` argument: `as()`, `type()`, `params()`, and future extensions all use this pattern in the Drools DSL sandbox. |
| END phantom type added proactively in Phase 2 | Adding END alongside the First/Second split (not deferred to Phase 3) avoided retroactive updates to all class signatures and call sites. `Join0First<END, DS, A>` carries END throughout so scope chain-back can return it via `end()`. See ADR-0003. |
| `JoinNFirst extends JoinNSecond` — two-family hierarchy | `filter()` lives on `Join0First` (after fact accumulation); `join()`, `fn()`, `not()`, `exists()`, `path2()..path6()`, `extensionPoint()`, `var()` live on `Join0Second` (before or independent of arity). This split enables `fn()` on Second while keeping filter on First. |
| `fn()` on `Join0Second`, not `Join0First` | `fn()` terminates the builder and must be callable at any arity without a filter having been applied first. Placing it on Second makes it available immediately after `from()` or `join()`. |
| `NegationScope` as separate class, not `JoinNSecond` subtype | Inheriting `join()` from `JoinNSecond` would add sources to the outer `RuleDefinition`; inside a scope all sources must go to the scope's own private `RuleDefinition`. The conflict makes inheritance impossible. `NegationScope<OUTER, DS>` is independent, captures outer builder as `OUTER`, returns it from `end()`. See ADR-0004. |
| OOPath runtime as separate pipeline on `RuleDefinition` | OOPath traversal is correlated (each child collection depends on the current parent fact) — incompatible with the cross-product `TupleSource` model. A separate `ooPathRootIndex + ooPathSteps` pipeline fires only when set, leaving all existing sources unchanged. See ADR-0002. |
| `path2()..path6()` fixed-arity instead of `path().path().end()` | Fixed-arity methods avoid an `end()` call at the tail and make traversal depth visible at a glance — you can scan a rule and immediately see how deep it goes without counting `.path()` calls. |
| `BaseTuple` mutable inheritance, not records | OOPath traversal populates the tuple incrementally — one slot per step as it passes the filter. Records are immutable; incremental population requires mutable `set(int, T)`. `Tuple2 extends Tuple1` so typed `getA()`, `getB()` getters are inherited. |
| `PathContext` clean rewrite | The Drools `PathContext` has a buggy constructor (fall-through switch without breaks). The sandbox version is eight lines. Primary use case: cross-referencing earlier path elements while filtering the current one. |
| `copyTuple()` at OOPath leaf | `executePipeline()` mutates the shared tuple via `set(index, value)`. Without copying at the leaf, collecting results from sibling branches corrupts each other's data. `copyTuple()` is called once per leaf — not per traversal step. |
| `from(Function)` is the only entry point; `from(String, Function)` removed | The string-based form was a sandbox invention not present in vol2. Now that `builder.rule("name")` provides the rule name, `from(String, Function)` is redundant, non-idiomatic, and non-type-safe. Removed entirely. |
| `extensionPoint()` uses reflection (same pattern as `join()`) | `extensionPoint()` on `Join0Second` must instantiate `RuleExtendsPoint.RuleExtendsPointN` where N varies with arity. Since `new RuleExtendsPoint${i+1}<>(rd)` is not valid Java, the method reads the arity from `getClass().getSimpleName()` and uses `Class.forName()` to instantiate the right inner class — same pattern as `join()`. |
| `extendsRule()` is authoring-time deduplication | `extendsRule()` simply copies the base rule's sources and filters into the child `RuleDefinition` at build time via `copyInto()`. The Rete network performs node sharing automatically regardless of how rules are authored — no special runtime concept is needed. See `knowledge-garden/drools/rule-builder-dsl.md`. |
| `addParamsFact()` for filter-trim correctness | When params are injected via `run(ctx, params)`, they occupy fact[0] at runtime but are NOT registered as a `TupleSource`. Without calling `rd.addParamsFact()` (which increments `accumulatedFacts`) at build time, `wrapPredicate()`'s trim logic miscounts the fact positions, causing single-fact filters to extract the wrong fact. |
| `RuleResult<DS>` replaces `RuleDefinition<DS>` as `fn()` return | `fn()` now returns `RuleResult<DS>` — a thin wrapper exposing the same query API (`run`, `executionCount`, `capturedFact`, etc.) plus a no-op `end()`. This allows `.fn(...).end()` to compile, matching vol2's chain syntax where `end()` closes a named-rule scope. Existing tests required no changes since `RuleResult` exposes the same methods. |
| `ParametersFirst` is the canonical entry point; `RuleBuilder.from()` is a shorthand | In vol2, every rule starts with `builder.rule("name")`. Our `builder.from(source)` is a shorthand equivalent to `builder.rule("rule").from(source)`. The canonical form for migration-ready code is always `builder.rule("name")`. |
| Record template support | `StaticJavaParser` configured for Java 17 in `PermuteProcessor.init()` and `InlineGenerator` static initializer. All 6 transformer signatures generalized from `ClassOrInterfaceDeclaration` to `TypeDeclaration<?>`. `RecordDeclaration.getParameters()` are `Parameter` nodes — `@PermuteParam` and `@PermuteDeclr` on record components work via `transformRecordComponents()` in both transformers. `@PermuteMethod`, `@PermuteReturn`, and extends expansion are COID-only and skipped for records. `TypeDeclaration<?>` doesn't implement `NodeWithTypeParameters` — `getTypeParameters()`/`setTypeParameters()` helpers cast through `NodeWithTypeParameters<?>`. |
| Record in IntelliJ plugin | The plugin's PSI (IntelliJ's own AST) already supports Java 17 records — no index version bump needed. The `PermuteTemplateIndex`, `PermuteGeneratedIndex`, and `PermuteElementResolver` work with record templates without changes because PSI resolution handles record declarations transparently. |

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
organized one class per DSL feature, mirroring the Drools vol2 reference:

| Sandbox class | DSL feature |
|---|---|
| `RuleBuilderTest` | Core builder chain, joins, filters (positional + single-fact + variable), bilinear, not/exists scopes, OOPath traversal, Variable binding, run-reset, fn side-effects, filter AND-composition |
| `ExtensionPointTest` | `extensionPoint()` / `extendsRule()` — filter-only, add-join, two-base-joins, fan-out, extend-of-extend, not/exists scope inheritance |
| `NamedRuleTest` | Named rules (`builder.rule()`), four param approaches (typed/list/map/individual), `fn().end()` chain, extension point integration |
| `TupleAsTest` | `BaseTuple.as()` projection — Tuple2/Tuple3 to typed records, OOPath filter use case |

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

## Example templates (in permuplate-apt-examples/)

| File | What it demonstrates |
|---|---|
| `Join2.java` | Baseline template: field rename, param expansion, for-each rename |
| `ContextJoin2.java` | Fixed `String ctx` before sentinel, `List<Object> results` after sentinel |
| `JoinLibrary.java` | `@Permute` on a nested static class inside `JoinLibrary` |
| `Callable1.java` – `Callable10.java` | Hand-written multi-arity callable interfaces (future: could be generated by Permuplate itself) |

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
