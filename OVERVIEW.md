# Permuplate — Architecture and Design Reference

For the user guide, quick start, and annotation API reference, see [README.md](README.md).
This document covers internal architecture, implementation decisions, and testing strategy — primarily for contributors and for anyone who wants to understand how the processor works.

---

## Architecture Overview

`PermuteProcessor` is the single `AbstractProcessor` entry point. It handles two distinct processing paths depending on where `@Permute` is placed:

**Type permutation** (`@Permute` on a class or interface) — for each value of the integer variable, clones the type declaration, applies all transformations, and writes a new `.java` source file. Produces N generated files.

**Method permutation** (`@Permute` on a method) — for each value of the integer variable, clones and transforms the method individually (via a temporary wrapper class so the existing transformers can be reused), then collects all variants into a single new class. Produces one generated file containing N method overloads.

In both paths, the source of the template is read via the compiler `Trees` API, parsed into a JavaParser AST, and all `${...}` expressions are evaluated with Apache Commons JEXL3.

**Inline generation** (`@Permute(inline = true)` on a nested class, Maven plugin only) — instead of writing separate top-level files, all permuted classes are written as nested siblings inside an augmented copy of the parent class. This requires the Maven plugin (`permuplate-maven-plugin`) which runs in `generate-sources` before javac; the APT processor rejects `inline = true` with a clear error directing users to the plugin. Templates for inline generation live in `src/main/permuplate/` rather than `src/main/java/` so javac never compiles them directly.

---

## Two-Tier Architecture

Permuplate operates at two levels:

**Tier 1 — The Annotation Processor** (this document)
The core tool: `@Permute`, `@PermuteDeclr`, `@PermuteParam`, and companions. Reads annotated Java source, generates N typed classes per template. Covered throughout this document.

**Tier 2 — DSL Applications**
What you build *with* Permuplate. The primary example is the Drools RuleBuilder DSL sandbox in `permuplate-mvn-examples/` — six phases of a type-safe rule-construction API, each generated from a single template class. See [`DROOLS-DSL.md`](../permuplate-mvn-examples/DROOLS-DSL.md) for the full architecture.

---

## Annotation API Detail

### `@Permute`

```java
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Permute {
    String   varName();          // primary integer loop variable name, e.g. "i"
    String   from();             // inclusive lower bound — JEXL expression, e.g. "3" or "${start}"
    String   to();               // inclusive upper bound — JEXL expression, e.g. "10" or "${max}"
    String   className();        // output type/class name template, e.g. "Join${i}"
    String[] strings()           // named string constants, e.g. {"prefix=Buffered"}
             default {};
    PermuteVar[] extraVars()     // additional integer axes for cross-product generation
             default {};
    String[] values()            // alternative to from/to: named string set to iterate
             default {};
    boolean  inline()            // default false — inline into parent class (Maven plugin only)
             default false;
    boolean  keepTemplate()      // default false — retain template class in inline output
             default false;
}
```

**`from` and `to` are JEXL expression strings**, not `int` literals. Plain integers (`"3"`), arithmetic (`"${max - 1}"`), and variable references (`"${max}"`) are all valid. Named constants are resolved from system properties, APT options, or annotation `strings` — see [External Property Injection](#external-property-injection).

**String-set iteration:** `values` is an alternative to `from`/`to`. When present, `varName` is bound to each string in turn instead of an integer. `values` and `from`/`to` are mutually exclusive — the APT processor reports a compile error if both are specified, or if `values={}` is empty. The loop variable is bound as `String` (not `Integer`) in JEXL context.

**Type permutation:** `className` is evaluated per-combination to name each generated file. The **leading literal** of `className` (everything before the first `${`) must be a prefix of the template type's simple name. Using only the leading literal (rather than all literal segments) correctly handles multi-variable class names: `"Combo${i}x${k}"` has leading literal `"Combo"`, not `"Combox"`. If `className` starts with a `${...}` expression, the prefix check is skipped entirely.

**Method permutation:** `className` is the fixed name of the single generated class containing all overloads. It is evaluated once using `from` as the value. The prefix check is not applied.

**Multiple permutation variables:** `extraVars` adds additional integer axes. `buildAllCombinations(permute)` generates the full cross-product: it starts with the primary variable's range, then for each `@PermuteVar` expands the list. Primary variable is outermost; `extraVars` are inner loops in declaration order. All N×M (×…) combinations are generated; `generatePermutation` receives an `EvaluationContext` with all variables already bound.

**String variables:** `strings` entries are `"key=value"` pairs merged into every combination map. Keys must be non-empty and must not duplicate `varName` or any `extraVars` name.

**Inline generation:** `inline = true` on a nested class instructs the Maven plugin to generate permuted classes as nested siblings in an augmented parent. The APT processor reports a compile error if this is set — use `permuplate-maven-plugin` instead.

**Nested types:** when placed on a nested `static` class or interface, the processor finds the nested declaration inside the compilation unit (using `cu.findFirst()` — the recursive form, not `cu.getClassByName()` which only searches top-level types), clones it, strips the `static` modifier, and generates it as a top-level type. The package is resolved by walking up `getEnclosingElement()` until a `PackageElement` is found — the immediate enclosing element of a nested type is the outer class, not the package.

**Record templates:** `@Permute` also works on record declarations. Records use the same transformation pipeline with two differences: `@PermuteMethod`, `@PermuteReturn`, and extends expansion are skipped (records don't support these patterns); and `@PermuteParam` on record components expands the component list directly via `RecordDeclaration.getParameters()` rather than a constructor parameter list. See `permuplate-apt-examples/example/Tuple2Record.java` for the canonical Tuple pattern.

### `@PermuteVar`

```java
@Retention(RetentionPolicy.SOURCE)
@Target({})        // only valid as an element of @Permute.extraVars
public @interface PermuteVar {
    String varName();   // variable name, available in all ${...} expressions
    String from();      // inclusive lower bound — JEXL expression string
    String to();        // inclusive upper bound — JEXL expression string
}
```

`@Target({})` is the standard Java idiom for a nested annotation type that may only appear as an array element value — it cannot be placed directly on any program element. Validation: `from > to` is reported as an error pointing to the `extraVars` attribute; duplicate `varName` values (vs `varName`, other `extraVars`, or `strings` keys) are also errors.

### `@PermuteDeclr`

```java
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.PARAMETER })
public @interface PermuteDeclr {
    String type(); // new type name template, e.g. "Callable${i}"
    String name(); // new identifier template, e.g. "c${i}" (default "" for method params)
}
```

Four supported placements and their rename scope:

**On a field** — the field's declared type and name are updated via the `VariableDeclarator`. All `NameExpr` nodes matching the old name anywhere in the class body are replaced (class-wide scope). Fields are processed before constructor parameters and for-each variables so that field renames are already applied when narrower scopes are walked.

**On a constructor parameter** — the `Parameter` node is updated. All `NameExpr` nodes matching the old name within the constructor body are replaced. Scope is limited to the constructor body. Note: `ConstructorDeclaration.getBody()` returns `BlockStmt` directly (always present), unlike `MethodDeclaration.getBody()` which returns `Optional<BlockStmt>`.

**On a for-each loop variable** — `ForEachStmt.getVariable()` returns a `VariableDeclarationExpr` (not `Parameter`); the type and name live on `getVariables().get(0)`. All `NameExpr` nodes matching the old name within the loop body are replaced. Scope is limited to the loop body.

**On a method parameter** — the `Parameter` node is updated. When `name` is non-empty, usages in the method body are renamed via `renameAllUsages`. When `name` is empty (default), only the type changes — the name is preserved. Method params are NOT validated in `validatePrefixes()` because the sentinel type (`Object`) deliberately doesn't match the annotation string (the actual generated type).

**Prefix validation:** the static (non-`${...}`) part of `type` and `name` must be a prefix of the actual declaration's type and name respectively (when placed on field, constructor param, or for-each var). This is checked once before any permutations are generated, with the annotated `TypeElement` as the error location.

### `@PermuteParam`

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface PermuteParam {
    String varName(); // inner loop variable, e.g. "j"
    String from();    // inner lower bound (expression), e.g. "1"
    String to();      // inner upper bound (expression), e.g. "${i-1}"
    String type();    // generated parameter type, e.g. "Object"
    String name();    // generated parameter name template, e.g. "o${j}"
}
```

Placed on a single sentinel parameter in a method or constructor. The sentinel's original name is registered as the **anchor**. The inner range is evaluated against the outer `EvaluationContext`: for `to="${i-1}"` with `i=3`, the range is `1..2`, producing `Object o1, Object o2`.

The expanded parameter list is rebuilt as: params before sentinel + generated params + params after sentinel. Position of the sentinel is found with `origParams.indexOf(sentinel)`.

**Multiple sentinels:** a method or constructor may carry more than one `@PermuteParam`. `transform()` uses a while loop: it repeatedly finds and processes the next annotated parameter until none remain. Each `transformMethod` call removes one sentinel from the parameter list (replacing it with expanded params that carry no annotation), so re-scanning naturally finds the next one. Anchor expansion for earlier sentinels is already applied to the method body before the next sentinel is processed — anchors at shared call sites accumulate correctly in sequence.

**Anchor expansion at call sites:** a `ModifierVisitor` walks all `MethodCallExpr` nodes in the method body. For each call whose argument list contains a `NameExpr` matching the anchor name, that single argument is replaced by the full generated argument sequence (preserving arguments before and after the anchor). This is why `c2.call(o1, o2)` becomes `c3.call(o1, o2, o3)` with no annotation on the call site.

**Prefix validation:** all sentinels are validated (not just the first). The static part of each `name` must be a prefix of that sentinel's parameter name. The `type` attribute is intentionally not checked — it describes the generated parameter type, not the sentinel's placeholder type.

Works on **record components** (via `RecordDeclaration.getParameters()`) as well as method and constructor parameters.

### `@PermuteConst`

```java
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE })
public @interface PermuteConst {
    String value(); // JEXL expression, e.g. "${i}", "${i * 2}"
}
```

Replaces the initializer of a field or local variable. A backward-compatible alias for `@PermuteValue` on fields and local variables. Both are supported; prefer `@PermuteValue` in new code.

### `@PermuteValue`

```java
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.METHOD })
public @interface PermuteValue {
    String value() default "";   // JEXL expression for the replacement value
    int    index() default -1;   // 0-based statement index in method/constructor body
}
```

Superset of `@PermuteConst`. On a field or local variable: replaces the initializer. On a method or constructor: replaces the RHS of the assignment statement at position `index` in the original template body (0-based, evaluated BEFORE `@PermuteStatements` insertions). Integer expressions produce `IntegerLiteralExpr`; all others produce `StringLiteralExpr`.

### `@PermuteStatements`

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteStatements {
    String varName() default "";  // inner loop variable (omit for single-statement)
    String from()    default "";  // inner loop lower bound
    String to()      default "";  // inner loop upper bound
    String position();            // "first" or "last"
    String body();                // JEXL template for statement(s) to insert
}
```

Inserts statements into a method or constructor body. Applied AFTER `PermuteValueTransformer` so that `@PermuteValue` index positions refer to the original template body. With `varName/from/to`: inserts one statement per loop value. Without loop: inserts once using the outer context.

Note: the `@Target` is `ElementType.METHOD` but the transformer processes both `MethodDeclaration` and `ConstructorDeclaration` by walking the class AST — constructor support is implemented without an additional `CONSTRUCTOR` target.

### `@PermuteCase`

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteCase {
    String varName();  // inner loop variable
    String from();     // inner loop lower bound
    String to();       // inner loop upper bound
    String index();    // JEXL expression for the case label integer
    String body();     // JEXL template for case body statements
}
```

Expands a switch statement by inserting new cases before `default` for each inner-loop value. The seed case and `default` case are preserved unchanged. No cases are inserted when `from > to`. All cases are inlined directly — no `super()` calls, no extra stack frames.

### `@PermuteImport`

```java
@Repeatable(PermuteImports.class)
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteImport {
    String value();  // JEXL-interpolated fully-qualified import string
}
```

Placed on the template class. For each permutation, evaluates `value` with the current context and adds the result as an import to the generated compilation unit. The annotation itself is stripped from the generated output. In inline mode, the import is added to the parent CU rather than a generated top-level file.

### `@PermuteTypeParam`

Expands a sentinel class type parameter into a sequence. Implicit expansion (no annotation needed) fires when `@PermuteParam(type="T${j}")` references an undeclared class type parameter — only in Maven plugin inline mode. Use `@PermuteTypeParam` explicitly for phantom type parameters (no corresponding `@PermuteParam`).

`@PermuteTypeParam` also works on method type parameters inside `@PermuteMethod`. The R3 prefix check is intentionally NOT applied in this context — the sentinel name is an arbitrary placeholder and need not match the generated names.

### `@PermuteReturn`

Controls the return type of a method per permutation. Boundary omission: when the evaluated return class is NOT in the generated set, the method is silently omitted from that generated class. `when="true"` overrides this. Applies to `@PermuteMethod` overloads too (individual overload omitted when boundary fails).

Two inference modes:
- **Implicit** (Maven plugin inline, `T${j}` naming): no annotation required — the processor detects undeclared `T+number` type args automatically
- **Explicit** (`@PermuteReturn` annotation): required in APT mode or when using `alpha(j)` naming

### `@PermuteMethod`

Generates multiple method overloads per class via an inner loop. `to` is optional — inferred as `@Permute.to - i` when absent. Empty range (`from > to`) silently omits all overloads from that class (leaf node).

`@PermuteMethod` pipeline position in `InlineGenerator`: runs BEFORE `PermuteDeclrTransformer` — each overload clone has its `@PermuteDeclr` parameters consumed with the inner (i,j) context so the downstream transform sees no remaining `@PermuteDeclr` annotations on these methods.

### `@PermuteExtends`

Explicit override of the extends or implements clause. Inline mode (Maven plugin) only. When present, the automatic same-N extends expansion is skipped for that class.

The automatic expansion logic (`applyExtendsExpansion`) uses name-prefix family matching + embedded number match to detect sibling classes. Two detection branches:
1. All-T+number type args → hardcodes `T1..TN`
2. Extends type args are a prefix of post-G1 type params → uses full post-G1 list (supports alpha naming when `@PermuteTypeParam` has fired)

### `@PermuteFilter`

```java
@Repeatable(PermuteFilters.class)
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface PermuteFilter {
    String value();  // JEXL boolean expression; combination skipped when false
}
```

Placed on a `@Permute`-annotated class or method. Evaluated once per permutation combination after cross-product expansion. A combination is skipped if any filter returns `false`. Multiple `@PermuteFilter` conditions are ANDed.

**APT vs Maven plugin:** The APT processor reports a compile error if all combinations are filtered out (`@PermuteFilter` makes the range empty). The Maven plugin silently produces no output in that case — it has no `Messager`.

**With `@PermuteVar` cross-products:** each combination (i, j, ...) is evaluated independently. The filter expression has access to all loop variables.

**`@PermuteMethod` inner variables not available:** `@PermuteMethod` creates an inner loop variable `j`. This variable is NOT available to `@PermuteFilter` because `PermuteDeclrTransformer` runs in the outer context after `PermuteMethodTransformer` has already consumed those overloads. Filters only see outer `@Permute` and `@PermuteVar` variables.

**`buildGeneratedSet` is filter-aware:** when `@PermuteFilter` excludes a class, that class is also absent from the set used by `@PermuteReturn` boundary omission. Methods that would reference the filtered-out class are correctly omitted.

---

## External Property Injection

Before building the per-permutation context, a base context is built from `PermuteConfig.buildExternalProperties()`:

| Source | APT mode | Maven plugin mode | Priority |
|---|---|---|---|
| System properties (`-Dpermuplate.*`) | Yes | Yes | Lowest |
| APT options (`-Apermuplate.*`) | Yes | No | Middle |
| Annotation `strings` | Yes | Yes | Highest |

The `permuplate.` prefix is stripped before adding to the context: `-Dpermuplate.max=10` becomes `${max}`. The base context is merged with `strings` constants before evaluating `from`/`to` and then threaded into every per-permutation `EvaluationContext`.

---

## Expression Evaluation

`EvaluationContext` wraps Apache Commons JEXL3. Variables are stored as `Map<String, Object>`:

- The primary loop variable (`varName`) is an `int` autoboxed to `Integer`
- String constants from `@Permute strings` are `String` values merged alongside it
- `withVariable(name, int)` creates a child context for the `@PermuteParam` inner loop variable

`evaluate(String template)` uses `Pattern.compile("\\$\\{([^}]+)}")` to find all placeholders, evaluates each expression with JEXL's `MapContext`, and assembles the result string.

`evaluateInt(String expression)` strips any `${...}` wrapper and evaluates the bare expression, asserting the result is a `Number`. Used for `@PermuteParam`, `@PermuteCase`, `@PermuteStatements`, and `@PermuteVar` `from`/`to` values.

**Built-in functions** (`alpha`, `lower`, `typeArgList`) are registered as JEXL lambda scripts in `MapContext` — not via `JexlBuilder.namespaces` — because JEXL3's uberspect does not autobox `Integer` arguments to `int` for static method dispatch.

---

## Transformation Pipeline

### Type permutation path (per permutation value `i`)

Inside `PermuteProcessor.generatePermutation()`:

1. **Clone** the class/interface declaration from the parsed template AST
2. **Strip** `static` modifier (if nested); ensure `public`
3. **Rename** the type using `ctx.evaluate(permute.className())`; rename all constructors to match (JavaParser does not propagate class renames to constructors automatically)
4. **`@PermuteExtends`** — explicit extends/implements override (inline mode only, before other transforms)
5. **`PermuteDeclrTransformer.transform()`**:
   - 5a. `transformFields()` — class-wide scope; also handles `@PermuteConst` initializer substitution on fields
   - 5b. `transformConstructorParams()` — constructor-body scope
   - 5c. `transformForEachVars()` — loop-body scope
   - 5d. `transformConstFields()` / `transformConstLocals()` — `@PermuteConst` on remaining fields/locals
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
│   ├── PermuteConst.java      — field/local initializer replacement (backward-compat alias for PermuteValue)
│   ├── PermuteValue.java      — field/local initializer or method/constructor statement RHS replacement
│   ├── PermuteStatements.java — statement insertion into method/constructor bodies
│   ├── PermuteCase.java       — switch case expansion per inner-loop value
│   ├── PermuteImport.java     — add JEXL-evaluated imports to each generated class
│   ├── PermuteTypeParam.java  — class/method type parameter expansion
│   ├── PermuteReturn.java     — return type control + boundary omission
│   ├── PermuteMethod.java     — multiple method overloads per class
│   └── PermuteExtends.java    — explicit extends/implements clause override (inline mode only)
├── permuplate-core/
│   ├── EvaluationContext.java          — JEXL3 wrapper; Map<String,Object> for int + string vars; built-in functions
│   ├── PermuteDeclrTransformer.java    — fields, constructor params, for-each vars, method params; @PermuteConst
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
| `PermuteConstTest` | `@PermuteConst` on interface fields and local variables; combined with `@PermuteDeclr` on the same field |
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

## Market Comparison — Why This Is Novel

All existing solutions for Java arity permutation involve a template that is *not* valid Java:

| Tool / Approach | How it solves arity permutation | Key gap vs. Permuplate |
|---|---|---|
| **Vavr / RxJava internal generators** | Python/Groovy scripts generate sources as a build step | Template is not valid Java; no IDE refactor support; generated files often committed |
| **Freemarker + Maven plugin** | `.ftl` template + Maven plugin generates `.java` | Template is not valid Java; external tooling required |
| **JavaPoet** | Programmatic source generation in a separate generator class | You write the generator imperatively, not as a template |
| **Lombok** | AST transformation via annotation processor | Fixed transformations only; no parameterised arity loop |
| **Manifold** | Structural typing, string templates, extensions | No arity permutation concept |
| **Kotlin `inline`/`reified`** | Solves some of this at the language level | Requires leaving Java |

**What Permuplate does differently:**

1. **The template is valid, compilable Java** — the IDE can navigate and refactor it before generation
2. **Annotation-processor driven** — invoked by `javac` automatically; no external build script, no separate code generator
3. **AST-aware, not text substitution** — renames usages with correct scope (class body, constructor body, loop body), expands call sites, preserves surrounding logic verbatim
4. **No committed generated sources** — they live in `target/generated-sources/annotations/`

The closest prior art is jOOQ's internal code generator for `Row1`–`Row22` and Reactor's flux arity classes — both use external Groovy/Python scripts. No library in the Java ecosystem is known to use the annotation-on-valid-Java-template pattern that Permuplate implements.

---

## Possible Roadmap

### Medium-term

**IntelliJ / IDE plugin**
The algorithm foundation (`permuplate-ide-support`) is complete — it provides annotation string parsing, validation, and rename calculation with no IDE dependencies. The remaining work is the IntelliJ plugin implementation (virtual navigation from template to generated variants and vice versa) and a VS Code extension.

**Gradle plugin support**
Currently only Maven is supported (via `annotationProcessorPaths`). A Gradle setup using `annotationProcessor` configuration would reach a wider audience.

### Longer-term

**Publishing to Maven Central**
The annotations and processor jars could be published independently. The annotations jar is tiny and has no runtime dependencies. The processor jar brings in JavaParser and JEXL3 as processor-only deps — they do not become compile or runtime dependencies of the consuming project.

---

## Design Records

The annotation processor architecture is stable and covered in this document. For the evolving DSL sandbox architecture:

- **Current state:** [`docs/design-snapshots/2026-04-06-drools-dsl-sandbox.md`](../docs/design-snapshots/2026-04-06-drools-dsl-sandbox.md)
- **Key decisions:** [`docs/adr/`](../docs/adr/) — ADR-0001 through ADR-0004
- **Development narrative:** [`docs/blog/`](../docs/blog/) — 11 diary entries
