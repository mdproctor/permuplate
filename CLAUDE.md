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
├── permuplate-annotations/     @Permute, @PermuteDeclr, @PermuteParam, @PermuteVar  (no runtime deps)
├── permuplate-core/            shared transformation engine: EvaluationContext, transformers, PermuteConfig
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

## The three annotations

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

---

## Processor internals

### Entry point: `PermuteProcessor`

`AbstractProcessor` subclass. Dispatches to `processTypePermutation` (TypeElement) or `processMethodPermutation` (ExecutableElement). `processTypePermutation` also validates `inline = true` (error — only the Maven plugin supports inline generation) and `keepTemplate = true` without `inline` (warning). For type permutation:

1. Reads source via `Trees` API using `getCharContent(true)` — works for file-based and in-memory sources
2. Parses with `StaticJavaParser`
3. Finds the class with `cu.findFirst(ClassOrInterfaceDeclaration.class, predicate)` — recursive form required for nested classes
4. Calls `buildAllCombinations(permute)` to generate the cross-product of all variables (primary + `extraVars`), then iterates over each combination
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

The four test cases:

| Test | What it specifically covers |
|---|---|
| `testBasicJoinPermutation` | Field rename, param expansion, call-site anchor, logic preservation |
| `testFixedParamsBeforeAndAfterPermuteParam` | Fixed params before/after sentinel preserved in correct position; `results.add(o2)` → `results.add(o3)` |
| `testNestedClassGeneratesTopLevelClass` | Nested static class → top-level, no `static` modifier |
| `testPermuteDeclrRenamesAllUsages` | Complex body: field used in null check + println before loop, call site inside loop, println after loop; for-each var used in null check, `skipped.add()`, call site, string concat |

---

## Example templates (in permuplate-example/)

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
- `permuplate-processor/src/main/java/com/example/permuplate/processor/` — the four processor source files
- `permuplate-tests/src/test/java/com/example/permuplate/ProcessorTest.java` — four test cases
