# When APT Hits a Wall

*Part 3 of the Permuplate development series.*

---

## A Constraint I Hadn't Fully Considered

Java's Annotation Processing Tool (APT) is powerful but has one hard constraint that doesn't show up in most documentation: **it can create new source files, but it cannot modify existing ones**.

For Permuplate's basic use case, this is fine. The template is in one file; the generated classes go into new files. `Join2.java` (template) → `Join3.java`, `Join4.java`, `Join5.java` (new files). APT can do that.

But there's a pattern I wanted to support that APT fundamentally cannot handle: **inline generation** — where the generated classes live as nested classes *inside the parent class file that also defines the template*.

Why would you want this? Consider the `JoinBuilder` pattern in the Drools DSL. The entire `JoinFirst` class family — `Join1First`, `Join2First`, ... `Join6First` — is most naturally organized as nested static classes inside a containing `JoinBuilder` class. Users reference them as `JoinBuilder.Join2First`, not as top-level `Join2First`. The container class is part of the public API.

If the generated classes need to be nested inside an existing class, APT can't do it. You'd have to put the container class and the template in the same file, but APT is only allowed to *write* new files, not *augment* existing ones. There's no API for "add this class to the end of this file I didn't create."

---

## The Maven Plugin Approach

We built a Maven plugin to run at `generate-sources` — before compilation — giving us read-write access to source files directly.

The flow:
1. Maven plugin reads `src/main/permuplate/` (a non-compiled source directory for templates)
2. For each template file, runs the transformation pipeline
3. Writes augmented class files to `target/generated-sources/permuplate/`
4. Javac compiles the output

The template directory (`src/main/permuplate/`) is never added to the compile source path. It's read only by the Maven plugin. IDE users mark it as a source root manually for navigation purposes, but it's never compiled as-is.

The key difference from APT: the Maven plugin can read the parent file (the class containing the template), add the generated nested classes to it, and write the augmented version. APT cannot do this step.

---

## One Codebase, Two Paths

This created an important architectural split. Permuplate now has two execution modes:

**APT mode** (annotation processor): Activated by adding `permuplate-processor` to `annotationProcessorPaths` in the Maven build. Templates are top-level classes. Generated classes are top-level classes. Works with any Java build that supports annotation processing.

**Inline mode** (Maven plugin): Activated by adding `permuplate-maven-plugin` to the build plugins. Templates live in `src/main/permuplate/` as nested static classes inside a parent class. Generated classes are also nested inside the same parent. Enables the container class pattern.

Two important attributes on `@Permute` control which mode is expected:
- `inline=true` — tells the processor this template expects inline generation. If you accidentally use it in APT mode, the processor emits an error with a migration message: "inline=true requires the Maven plugin. Use the permuplate-maven-plugin instead."
- `keepTemplate=true` — retain the template class in the output (alongside the generated classes). Useful when the template itself is a valid base-case instance (e.g. `Consumer1` is the arity-1 version, while the template generates `Consumer2`–`Consumer7`).

---

## Extracting the Core

Building the Maven plugin forced a refactoring that I should have done earlier: extracting the transformation logic into `permuplate-core`.

Before this, the APT processor contained the transformer classes (`PermuteDeclrTransformer`, `PermuteParamTransformer`). When we needed the same logic in the Maven plugin, the options were: duplicate the code, or extract it. Duplication would have been a maintenance nightmare — any bug fix in one would need to be applied to the other. So we extracted.

The module layout after this refactor:

```
permuplate-annotations/    @Permute, @PermuteDeclr, @PermuteParam, @PermuteVar
permuplate-core/           EvaluationContext, PermuteDeclrTransformer, PermuteParamTransformer
permuplate-processor/      PermuteProcessor (APT) — thin shell over core
permuplate-maven-plugin/   PermuteMojo + InlineGenerator — uses core
```

One subtlety: the Maven plugin passes `null` as the `Messager` parameter to the transformers (there's no Messager in a Maven Mojo). Every call to `messager.printMessage(...)` had to be guarded with `if (messager != null)`. Missing even one of these causes a `NullPointerException` in the Maven path only — an easy bug to miss since the APT tests pass fine.

---

## InlineGenerator: The Heart of Inline Mode

The Maven plugin's transformation engine is `InlineGenerator.generate()`. It's a richer version of the APT transformation pipeline, designed specifically for the nested-class inline pattern.

The pipeline, in order:

1. Rename the generated nested class
2. Run `PermuteTypeParamTransformer` (G1 — expanding class type parameters)
3. Capture post-G1 type parameter names (`postG1TypeParams` — relevant to extends expansion, as we'll see in G3)
4. Run `applyPermuteMethod()` (G3 — generate method overloads)
5. Run `PermuteDeclrTransformer` and `PermuteParamTransformer`
6. Run `applyPermuteReturn()` (G2 — explicit return type narrowing)
7. Run `applyImplicitInference()` (G2 — implicit return type inference)
8. Run `applyExtendsExpansion()` (G3 — extends clause sibling expansion)
9. Strip `@Permute` annotation from the generated class

The ordering matters in non-obvious ways. G1 must run before `applyExtendsExpansion` because the extends expansion needs to know the post-expansion type parameter names. G3's method generation must run before `PermuteDeclrTransformer` because each overload clone has its own `@PermuteDeclr` annotations that need processing in the inner (i,j) context.

Getting this ordering wrong produces bugs that are hard to diagnose — transformations silently succeed but produce wrong output.

---

## The Two-Pass Scan

Both APT and the Maven plugin use a "two-pass scan" pattern that took a while to get right.

The problem: **boundary omission**. When a generated class has a method whose return type is `Join7First`, and the template range only goes up to `Join6First`, that method should be silently omitted from the generated class. This avoids generating code that references a non-existent type.

But to know whether `Join7First` is in the generated set, you need to know the full set of all generated class names *before* you start generating any individual class. This is the two-pass requirement:

**Pass 1:** Scan all `@Permute` annotations and build the complete set of class names that will be generated.  
**Pass 2:** Generate each class, using the set from Pass 1 to make boundary omission decisions.

In APT mode, Pass 1 uses `RoundEnvironment.getElementsAnnotatedWith()`. In the Maven plugin, Pass 1 scans the source files in the template directory. In both cases, the set is built once and shared across all generation steps.

This pattern also handles topological ordering — when one generated class references another, the dependency set determines generation order.

---

## What Inline Mode Unlocked

With the Maven plugin and inline generation in place, a range of patterns became possible that APT couldn't handle:

- Container classes with many generated nested types (`JoinBuilder` containing `Join1First`–`Join6First`)
- `keepTemplate=true` with alpha naming (`Consumer1` retained as the arity-1 Consumer, while generating `Consumer2`–`Consumer7`)
- `@PermuteReturn` boundary omission (working correctly, since `InlineGenerator` has the full generated-names set)

The inline mode also made the overall user experience cleaner for complex DSLs. Instead of scattering generated classes across many top-level files, you can organize them naturally inside a parent class that represents the DSL's entry point or structure.

The tradeoff: inline mode is more complex to configure (requires the Maven plugin, a separate source directory) and harder to debug (generated files appear in `target/`, IDE requires manual source root configuration). For simple use cases, APT mode is still the right choice.

---

*Next: Growing the expression language — validation rules, built-in functions, and the JEXL autoboxing trap.*