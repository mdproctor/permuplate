# Inline Generation — Design Spec

**Date:** 2026-04-01
**Status:** Approved

---

## Overview

Permuplate's annotation processor (APT) can only create new files — it cannot modify existing source files. This means generated permutations must be separate top-level classes. For nested class templates where the generated classes logically belong inside the parent, this produces unwanted top-level clutter.

This feature adds **inline generation**: permuted classes are written as nested classes inside an augmented copy of the parent, not as separate top-level files. Because inline generation requires reading and rewriting an existing file, it is implemented in a new **Maven plugin** (`permuplate-maven-plugin`) rather than the existing APT processor.

The feature decomposes into three sequenced sub-projects:

1. **Annotation changes + APT guard** — new `inline` and `keepTemplate` attributes; APT errors on `inline = true`
2. **`permuplate-maven-plugin` + `permuplate-core` refactor** — new Maven module; shared logic extracted to core module
3. **Module reorganisation + examples + full documentation sweep**

---

## Sub-project 1: Annotation changes and APT guard

### New attributes on `@Permute`

```java
boolean inline() default false;
// When true: generate permuted classes as nested siblings inside the parent class.
// Requires permuplate-maven-plugin. Illegal on top-level (non-nested) classes.
// Illegal when processed by the APT annotation processor.

boolean keepTemplate() default false;
// When true: retain the template class in the generated output alongside the
// permuted classes. Only meaningful when inline = true; validated and warned
// otherwise. Defaults to false (template class is removed from output).
```

### Validation rules

| Condition | Error / Warning |
|---|---|
| `inline = true` seen by APT processor | **Error** (attribute-level precision): *"inline generation requires permuplate-maven-plugin — the annotation processor cannot modify existing source files. See README §APT vs Maven Plugin."* |
| `inline = true` on a top-level (non-nested) class | **Error**: *"@Permute inline = true is only valid on nested static classes — there is no parent class to inline into."* |
| `keepTemplate = true` with `inline = false` | **Warning**: *"keepTemplate has no effect when inline = false."* |

All errors use `AnnotationValue`-level precision per the project's error-reporting standard.

---

## Sub-project 2: Module refactor and Maven plugin

### New module structure

```
permuplate-parent/
├── permuplate-annotations/    — @Permute, @PermuteDeclr, @PermuteParam, @PermuteVar (no runtime deps)
├── permuplate-core/           — ALL shared transformation logic (NEW)
│   ├── EvaluationContext.java
│   ├── PermuteDeclrTransformer.java
│   ├── PermuteParamTransformer.java
│   └── buildAllCombinations() (moved from PermuteProcessor)
├── permuplate-processor/      — APT entry point only (thin shell)
│   └── PermuteProcessor.java  — depends on permuplate-core + javac Trees API
├── permuplate-maven-plugin/   — Maven Mojo (NEW)
│   └── PermuteMojo.java       — depends on permuplate-core + maven-plugin-api
├── permuplate-apt-examples/   — renamed from permuplate-example (content unchanged)
├── permuplate-mvn-examples/   — new example module using Maven plugin
└── permuplate-tests/          — unchanged (depends on permuplate-processor for compile-testing)
```

### `permuplate-core` dependencies

```xml
<dependencies>
    <dependency>
        <groupId>io.quarkiverse.permuplate</groupId>
        <artifactId>quarkus-permuplate-annotations</artifactId>
    </dependency>
    <dependency>
        <groupId>com.github.javaparser</groupId>
        <artifactId>javaparser-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-jexl3</artifactId>
    </dependency>
</dependencies>
```

`permuplate-processor` adds: `tools.jar` / `javac` APIs (provided scope).
`permuplate-maven-plugin` adds: `maven-plugin-api`, `maven-plugin-annotations`.

### `PermuteMojo` — goal `generate`, phase `generate-sources`

**Parameters:**

| Parameter | Default | Description |
|---|---|---|
| `sourceDirectory` | `${project.build.sourceDirectory}` | Where to scan for non-inline `@Permute` templates |
| `templateDirectory` | `src/main/permuplate` | Where inline templates live; NOT added as compile source root |
| `outputDirectory` | `${project.build.directory}/generated-sources/permuplate` | All generated files (inline and non-inline) go here |

**Algorithm:**

```
1. Parse all .java files in sourceDirectory using JavaParser
2. Find @Permute on top-level types/nested types/methods with inline = false
   → apply full transformation pipeline (same as APT today)
   → write generated files to outputDirectory
3. Parse all .java files in templateDirectory using JavaParser
4. Find @Permute on nested types with inline = true
   → for each combination of variables:
       a. Clone the nested template class
       b. Apply full transformation pipeline (PermuteDeclrTransformer, PermuteParamTransformer)
       c. Name the output class via ctx.evaluate(className)
   → Build augmented parent:
       - Clone parent class AST verbatim (all fields, methods, other nested classes preserved)
       - If keepTemplate = false: remove the template nested class
       - If keepTemplate = true: retain the template nested class
       - Append all generated nested classes after the template position
       - Strip @Permute from all nodes
   → Write augmented parent to outputDirectory (same package path as original)
5. project.addCompileSourceRoot(outputDirectory)
```

**No duplicate class risk:** `templateDirectory` (`src/main/permuplate`) is never added as a compile source root. Only `outputDirectory` is. javac never sees both versions of the parent class.

**Same validation as APT:** All prefix checks, range checks, `strings`/`extraVars` conflict checks are applied identically. Error messages use the same text but reported via `Log` (Maven logger) rather than `Messager`.

### IDE support for template directory

`src/main/permuplate` is deliberately **not** added as a Maven compile source root (to avoid duplicate class errors). IDEs will not recognise it automatically.

**Recommended setup:** Mark `src/main/permuplate` as a source root in your IDE manually:
- **IntelliJ IDEA:** Right-click folder → *Mark Directory As → Sources Root*
- **VS Code:** The Java extension picks it up if listed in `.classpath` or via a Maven extension — add `src/main/permuplate` to the project's source paths in settings

This manual marking persists in IDE project files (`.idea/`, `.vscode/`) and does not affect Maven compilation. Templates in this folder are read by the Maven plugin at `generate-sources` time; their augmented versions in `target/generated-sources/permuplate/` are what javac actually compiles.

The README documents this step clearly so users are not surprised.

---

## Sub-project 3: Module reorganisation, examples, documentation

### Module rename

`permuplate-example` → `permuplate-apt-examples`
- Artifact ID, directory name, and all internal references updated
- Content unchanged — all existing examples remain valid APT examples
- Parent POM module list updated

### `permuplate-mvn-examples` — Handlers example

Template file: `src/main/permuplate/io/quarkiverse/permuplate/example/Handlers.java`

```java
package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.*;

/**
 * Host class for the Handler family generated via inline permutation.
 *
 * <p>After processing, this class contains Handler1 through Handler5 — a complete,
 * type-safe handler family with no top-level file clutter. All variants are nested
 * inside Handlers for clean API organisation: {@code Handlers.Handler3}, etc.
 */
public class Handlers {

    /**
     * Template for a type-safe arity-N handler. Generates Handler2 through Handler5
     * as nested siblings via inline permutation. Handler1 is retained (keepTemplate = true)
     * since a single-argument handler is useful in its own right.
     */
    @Permute(varName = "i", from = 2, to = 5,
             className = "Handler${i}",
             inline = true,
             keepTemplate = true)
    public static class Handler1 {

        private @PermuteDeclr(type = "Callable${i}", name = "delegate${i}") Callable1 delegate1;

        /**
         * Handles {@code i} typed arguments by delegating to the underlying callable.
         */
        public void handle(
                @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "arg${j}") Object arg1) {
            delegate1.call(arg1);
        }
    }
}
```

Generated output: `Handlers.java` in `target/generated-sources/permuplate/` containing:
- `Handlers.Handler1` (retained, `keepTemplate = true`)
- `Handlers.Handler2` — `delegate2: Callable2`, `handle(Object arg1, Object arg2)`
- `Handlers.Handler3` — `delegate3: Callable3`, `handle(Object arg1, Object arg2, Object arg3)`
- `Handlers.Handler4` — `delegate4: Callable4`, `handle(..., arg4)`
- `Handlers.Handler5` — `delegate5: Callable5`, `handle(..., arg5)`

**`pom.xml` for `permuplate-mvn-examples`:**

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.quarkiverse.permuplate</groupId>
            <artifactId>permuplate-maven-plugin</artifactId>
            <version>${project.version}</version>
            <executions>
                <execution>
                    <goals><goal>generate</goal></goals>
                </execution>
            </executions>
        </plugin>
        <!-- src/main/permuplate is the template directory — mark it as a source root
             in your IDE (IntelliJ: right-click → Mark Directory As → Sources Root).
             It is intentionally NOT a Maven source root to avoid duplicate compilation. -->
    </plugins>
</build>
```

### README additions

New section *"APT vs Maven Plugin"* positioned after Quick Start, covering:
- Comparison table (capabilities, when to use each, configuration complexity)
- Complete pom.xml for APT path
- Complete pom.xml for Maven plugin path  
- Explanation of `inline`, `keepTemplate`, and the template directory
- The `src/main/permuplate` convention and IDE setup
- Note: cannot use both simultaneously (explains why)

---

## Files created or modified

| File | Change |
|---|---|
| `permuplate-annotations/.../Permute.java` | Add `inline`, `keepTemplate`; update Javadoc |
| `permuplate-core/` | New module: extract transformers + EvaluationContext |
| `permuplate-processor/.../PermuteProcessor.java` | Validate `inline = true`; remove transformer code (now in core) |
| `permuplate-maven-plugin/` | New module: PermuteMojo |
| `permuplate-apt-examples/` | Renamed from `permuplate-example` |
| `permuplate-mvn-examples/` | New module: Handlers example |
| `README.md` | APT vs Maven Plugin section |
| `OVERVIEW.md` | Module structure, Maven plugin description |
| `CLAUDE.md` | Updated module layout, new non-obvious decision entries |
| `pom.xml` | New modules added |
