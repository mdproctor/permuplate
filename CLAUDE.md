# Permuplate — Claude context

## Project Type

**Type:** java

This file gives future Claude sessions everything needed to contribute to Permuplate without re-deriving the architecture from scratch. Read this first, then consult [OVERVIEW.md](OVERVIEW.md) for deeper detail and [README.md](README.md) for the user-facing picture.

---

## What this project is

**Permuplate** is a Java annotation processor that generates type-safe arity permutations from a single template class. You write one class annotated with `@Permute`, and `javac` generates N classes — one per arity value — via AST transformation using JavaParser.

The key insight: **the template is valid, compilable Java**. The IDE can navigate it, refactor it, and type-check it. No external script. No committed generated files. No separate build step.

This is genuinely novel. Every comparable tool (Vavr generators, Freemarker templates, RxJava scripts) uses a template that is *not* valid Java. See [OVERVIEW.md § Market Comparison](OVERVIEW.md#market-comparison--why-this-is-novel) for details.

---

## Module layout

```
permuplate-parent/
├── permuplate-annotations/     @Permute, @PermuteDeclr, @PermuteParam, @PermuteVar, @PermuteTypeParam, @PermuteReturn, @PermuteMethod, @PermuteExtends  (no runtime deps)
├── permuplate-core/            shared transformation engine: EvaluationContext, transformers, PermuteConfig
├── permuplate-ide-support/     annotation string algorithm (matching, rename, validation); no IDE deps
├── permuplate-processor/       APT entry point only (thin shell depending on permuplate-core)
├── permuplate-maven-plugin/    Maven Mojo for pre-compilation generation including inline mode
├── permuplate-apt-examples/    APT examples (renamed from permuplate-example)
├── permuplate-mvn-examples/    Maven plugin examples with Handlers inline demo
└── permuplate-tests/           Unit tests via Google compile-testing
```

The processor module uses `-proc:none` to prevent self-invocation during its own compilation. The apt-examples and test modules must list the processor **and its transitive deps** (javaparser-core, commons-jexl3) explicitly under `annotationProcessorPaths` — `maven-compiler-plugin` 3.x does not auto-discover them.

Maven is at `/opt/homebrew/bin/mvn`. The standard build command is:

```bash
/opt/homebrew/bin/mvn clean install
```

---

## The four annotations

### `@Permute` — on a class or interface (top-level or nested static), or on a method

```java
@Permute(varName = "i", from = 3, to = 10, className = "Join${i}")
```

**On a type:** for each value of `i` from `from` to `to` inclusive, clones the class/interface, runs transformations, and writes a new `.java` file named by evaluating `className`. Nested types have `static` stripped and are written as top-level types.

**On a method:** for each value of `i`, clones and transforms the method via a temporary wrapper class, then collects all variants into ONE new class named `className`. The `className` should be fixed (no `${i}`). The `processMethodPermutation` path skips the className-prefix validation and the per-type `validatePrefixes` calls.

### `@PermuteDeclr` — on a field or for-each variable

```java
private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;

for (@PermuteDeclr(type = "Object", name = "o${i}") Object o2 : right) { ... }
```

Renames the declaration's type and identifier, then propagates the rename to all `NameExpr` usages within the declaration's scope:
- **Field**: entire class body
- **Constructor parameter**: constructor body only
- **For-each variable**: loop body only

Transform order: fields first (broadest scope), then constructor params, then for-each variables.

### `@PermuteParam` — on a single "sentinel" method parameter

```java
public void join(
        String ctx,
        @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1,
        List<Object> results) { ... }
```

The sentinel (`o1`) is replaced by a generated sequence (`Object o1, Object o2` for `i=3`). Parameters before and after the sentinel are preserved in position.

The sentinel's original name is registered as an **anchor**. Every method call in the body where the anchor appears as an argument has it replaced by the full generated sequence. This is how `c2.call(o1, o2)` becomes `c3.call(o1, o2, o3)` — there is no annotation on the call site because Java's syntax does not allow it.

### `@PermuteVar` — nested annotation for extra integer loop axes

```java
@Permute(varName = "i", from = 2, to = 4,
         className = "BiCallable${i}x${k}",
         extraVars = { @PermuteVar(varName = "k", from = 2, to = 4) })
```

Defines an additional integer loop variable for cross-product generation. Each `@PermuteVar` adds one axis; one output type is generated per combination of the primary variable and all extra variables. The primary variable (`varName` on `@Permute`) is the outermost loop; `extraVars` are inner loops in declaration order. Variable names must not conflict with `varName` or `strings` keys.

---

## Processor internals

### Entry point: `PermuteProcessor`

`AbstractProcessor` subclass. Dispatches to `processTypePermutation` (TypeElement) or `processMethodPermutation` (ExecutableElement). `processTypePermutation` also validates `inline = true` (error — only the Maven plugin supports inline generation) and `keepTemplate = true` without `inline` (warning). For type permutation:

1. Reads source via `Trees` API using `getCharContent(true)` — works for file-based and in-memory sources
2. Parses with `StaticJavaParser`
3. Finds the class with `cu.findFirst(ClassOrInterfaceDeclaration.class, predicate)` — recursive form required for nested classes
4. Calls `buildStringConstants(permute)` to extract string constants and `buildAllCombinations(permute)` to generate the cross-product of all variables (primary + `extraVars`), then iterates over each combination
5. When writing, walks up `getEnclosingElement()` until a `PackageElement` is found

### Transformation pipeline (per permutation value)

```
1. Clone class declaration
2. Strip static / ensure public
3. Rename class  (ctx.evaluate(permute.className()))
4. PermuteDeclrTransformer.transform()   — fields first, then for-each vars
5. PermuteParamTransformer.transform()   — param expansion + anchor expansion
6. Remove @Permute from class
7. Build fresh CompilationUnit (copy package + non-permuplate imports)
8. Write via Filer
```

### `EvaluationContext`

Wraps Apache Commons JEXL3. All `${...}` placeholders are evaluated against a `Map<String, Object>` of variable bindings. The primary loop variable (`varName`) is an integer; `extraVars` integer axes and `strings` string constants are all merged into the same map by `buildAllCombinations()`. `withVariable(name, int)` creates a child context for the `@PermuteParam` inner loop variable. `evaluateInt()` accepts either a bare expression (`"1"`) or a wrapped one (`"${i-1}"`).

### `PermuteDeclrTransformer`

- `transformFields()`: snapshot annotated fields, evaluate new type/name, update `VariableDeclarator`, call `renameAllUsages(classDecl, old, new)` for class-wide scope
- `transformConstructorParams()`: snapshot annotated `Parameter` nodes in each constructor, update type/name, call `renameAllUsages(constructor.getBody(), old, new)` for constructor-body scope only
- `transformForEachVars()`: walk `ForEachStmt` nodes; `getVariable()` returns `VariableDeclarationExpr` (not `Parameter`); update via `getVariables().get(0).setType/setName`; call `renameAllUsages(forEachStmt.getBody(), old, new)` for loop-body scope only
- `renameAllUsages(Node scope, String old, String new)`: `ModifierVisitor` that replaces matching `NameExpr` nodes in-place

### `PermuteParamTransformer`

- Processes ALL `@PermuteParam`-annotated parameters in each method via a while loop (not just the first); each `transformMethod` call removes one sentinel, so re-scanning finds the next
- Records the sentinel's name as the anchor
- Evaluates inner range (`from`/`to`) using outer context
- Builds expanded `Parameter` list via inner `EvaluationContext` loop
- Rebuilds method parameter list: params before sentinel + expanded params + params after sentinel (uses `origParams.indexOf(sentinel)`)
- `expandAnchorAtCallSites()`: `ModifierVisitor` over `MethodCallExpr` nodes; finds the anchor by name in each call's argument list; replaces it with the full generated argument sequence, preserving args before and after

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
| InlineGenerator strips all permuplate annotations | When `keepTemplate = true`, the retained template class must have `@Permute`, `@PermuteDeclr`, and `@PermuteParam` all stripped — not just `@Permute` — otherwise javac cannot compile the annotations that reference types from `permuplate-annotations`. |
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
| `@PermuteMethod` empty range = leaf node | When `from > to` after evaluation (e.g. i=max, to=0), the method is silently omitted from that generated class. This is the multi-join leaf-node mechanism — not an error. |
| `@PermuteMethod.to` is optional | Inferred as `@Permute.to - i` from the enclosing class's `@Permute` annotation. Works in both APT and Maven plugin for same-module class families. Explicit `to` + `strings={"max=N"}` is the workaround for cross-module APT dependencies. |
| `@PermuteMethod` pipeline position | `applyPermuteMethod()` in InlineGenerator runs BEFORE `PermuteDeclrTransformer` — each overload clone has `@PermuteDeclr` on its parameters consumed with the inner (i,j) context, so the downstream transform sees no remaining annotations on these methods. |
| Extends clause implicit expansion (G3) | `applyExtendsExpansion()` uses name-prefix family matching + embedded number match to detect sibling classes. Third-party classes are safely skipped. Generates same-N extends (`JoinNFirst extends JoinNSecond`). Two detection branches: (1) all-T+number type args → hardcodes `T1..TN`; (2) extends type args are a prefix of post-G1 type params → uses full post-G1 list (supports alpha naming when `@PermuteTypeParam` fires first). Both branches use `newNum = currentEmbeddedNum` (same-N formula). |
| Two-pass scan for dependency ordering | Both APT and Maven plugin scan all `@Permute` templates before generating any class — APT via `RoundEnvironment.getElementsAnnotatedWith`, Maven via file scan. The generated class set is built first; generation happens in topological order. |
| Standalone method `@PermuteTypeParam` | `ElementType.METHOD` target added to `@PermuteTypeParam`. Step 5 in `PermuteTypeParamTransformer.transform()` scans non-`@PermuteMethod` methods for method-level `@PermuteTypeParam` annotation, expands the first type parameter, removes the annotation, and propagates single-value renames into parameter types and return type. `@PermuteMethod` methods are guarded to prevent double-processing with the wrong context. |
| Propagation scope for single-value renames | `transformMethod()` and Step 5 propagate renames into parameter types and return type when `from==to` (single-value expansion). Uses word-boundary-safe `replaceTypeIdentifier()` — not `String.replace`. Multi-value expansions (`from<to`) intentionally skip propagation — no single target exists; use `@PermuteDeclr` explicitly for those parameters. `@PermuteDeclr` on a parameter always overrides propagated type. |
| `@PermuteDeclr` takes precedence over propagation | Parameters with `@PermuteDeclr` are skipped during type-param rename propagation. Explicit always wins. Allows callers to specify a different type structure when propagation alone is insufficient. |
| `@PermuteMethod` ternary `from` for conditional generation | `from="${i > 1 ? i : i+1}"` with `to="${i}"` produces an empty range at `i=1` → method omitted. At `i≥2` it produces `from=to=i` → one clone. JEXL3 supports `?:` natively. Used by `filterLatest` sentinel in `JoinBuilder` to suppress the single-fact filter at arity 1 where it would duplicate the all-facts filter. |

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

| Class | Coverage area |
|---|---|
| `PermuteTest` | Type permutation range, nested class/interface promotion, string variables, cross-product via `extraVars` |
| `PermuteDeclrTest` | Field, constructor param, for-each variable renaming; two annotated fields; dual for-each loops |
| `PermuteParamTest` | Fixed params before/after sentinel, multiple sentinels, anchor expansion |
| `ExampleTest` | Real-world domain templates: `ProductFilter2`, `AuditRecord2`, `ValidationSuite.FieldValidator2`, `BiCallable1x1` |
| `DogFoodingTest` | `Callable1` generates `Callable2`–`Callable10` |
| `DegenerateInputTest` | All `@Permute` attribute error paths with message content and source-position assertions |
| `PrefixValidationTest` | String-literal prefix rules for `@PermuteDeclr` and `@PermuteParam` across all placements |
| `AptInlineGuardTest` | APT rejection of `inline=true`; `keepTemplate` warning |
| `OrphanVariableTest` | R2 (substring matching), R3 (orphan variable), R4 (no anchor), R2 short-circuits R3/R4 |
| `InlineGenerationTest` | `InlineGenerator` and `AnnotationReader` for Maven plugin inline generation |
| `ExpressionFunctionsTest` | Built-in JEXL functions: `alpha`, `lower`, `typeArgList` — unit tests + end-to-end compile tests |
| `PermuteTypeParamTest` | `@PermuteTypeParam`: explicit/implicit expansion, bounds propagation, fixed type params, R1/R3/R4 validation |
| `PermuteReturnTest` | `@PermuteReturn`: APT explicit mode, implicit inference, boundary omission, V2/V3/V6 validation |
| `PermuteMethodTest` | `@PermuteMethod`: multiple overloads, inferred `to`, leaf nodes, extends expansion, APT mode, method-level `@PermuteTypeParam` |

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
- [OVERVIEW.md](OVERVIEW.md) — architecture deep-dive, market comparison, full roadmap
- `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/` — the processor source files
- `permuplate-tests/src/test/java/io/quarkiverse/permuplate/` — test classes

---

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries in `docs/blog/`.** Load it in full before drafting any entry. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without first verifying it against the style guide's "What to Avoid" section.

The guide covers Mark's voice and personality, the three collaboration registers (I / we / Claude named directly), structural patterns, quantitative fingerprint, and the heading smell check.
