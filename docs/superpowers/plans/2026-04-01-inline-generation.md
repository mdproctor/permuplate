# Inline Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `inline = true` / `keepTemplate` to `@Permute`, extract shared logic to `permuplate-core`, implement `permuplate-maven-plugin` with inline generation, rename example module and add `permuplate-mvn-examples` with the Handlers demo, and thoroughly update all documentation.

**Architecture:** Three sequential phases: (1) annotation attribute changes + APT guard, (2) `permuplate-core` extraction + full Maven plugin, (3) module rename + examples + docs sweep. All transformation logic moves from `permuplate-processor` into a new `permuplate-core` module so both the APT and the Maven Mojo share it. The Maven plugin reads source files directly via JavaParser (no javac dependency), generates output to `target/generated-sources/permuplate/`, and calls `project.addCompileSourceRoot()` — no annotation processing involved.

**Tech Stack:** Java 17, JavaParser 3.25.9, Apache Commons JEXL3 3.3, Maven Plugin API 3.9.0, Maven Plugin Annotations 3.9.0

**Build command:** `/opt/homebrew/bin/mvn clean install` from project root. Tests run via `/opt/homebrew/bin/mvn test -pl permuplate-tests`.

---

## Phase 1: Annotation attributes + APT guard

### Task 1: Add `inline` and `keepTemplate` to `@Permute`

**Files:**
- Modify: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/Permute.java`

- [ ] **Add the two new attributes and update Javadoc**

Replace the entire file content:

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Drives the outer permutation loop on a class or interface (top-level or nested
 * static), or on a method.
 *
 * <p>
 * <b>Type permutation</b> ({@code @Permute} on a class or interface) — for each
 * combination of the permutation variables, clones the type declaration, applies all
 * transformations, and writes a new source file. Nested types are promoted to
 * top-level unless {@code inline = true} is set.
 *
 * <p>
 * <b>Inline generation</b> ({@code inline = true}, nested class only) — instead of
 * writing separate top-level files, all generated permutations are written as nested
 * siblings inside the parent class. Requires {@code permuplate-maven-plugin}; the APT
 * annotation processor reports a compile error if it encounters {@code inline = true}.
 *
 * <pre>{@code
 * public class Handlers {
 *     @Permute(varName = "i", from = 2, to = 5,
 *              className = "Handler${i}",
 *              inline = true,
 *              keepTemplate = true)
 *     public static class Handler1 { ... }
 * }
 * }</pre>
 *
 * Generates {@code Handlers.Handler2} through {@code Handlers.Handler5} as nested
 * classes inside {@code Handlers}. If {@code keepTemplate = true}, {@code Handler1}
 * is also retained in the output.
 *
 * <p>
 * <b>On a method</b> — generates a single new class containing one overload of the
 * method per combination. {@code inline} is not supported on methods.
 *
 * <p>
 * <b>className prefix rule (type permutation only):</b> the leading literal part of
 * {@code className} (everything before the first {@code ${...}}) must be a prefix of
 * the template class's simple name. The rule is skipped when {@code className} starts
 * with a {@code ${...}} variable expression, and does not apply when {@code @Permute}
 * is placed on a method.
 *
 * <p>
 * <b>String variables:</b> {@code strings} entries are {@code "key=value"} pairs
 * providing fixed named string constants in all {@code ${...}} expressions.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Permute {
    /** The primary integer loop variable name (e.g. {@code "i"}). */
    String varName();

    /** Inclusive lower bound for the primary variable. */
    int from();

    /** Inclusive upper bound for the primary variable. Must be &gt;= {@code from}. */
    int to();

    /**
     * Output type/class name template. For type permutation, evaluated per combination
     * (e.g. {@code "Join${i}"}). For method permutation, a fixed class name
     * (e.g. {@code "MultiJoin"}) containing all overloads.
     */
    String className();

    /**
     * Named string constants available in all {@code ${...}} expressions alongside
     * {@code varName}. Each entry must be in {@code "key=value"} format.
     * Keys must not match {@code varName} or any {@code extraVars} variable name.
     */
    String[] strings() default {};

    /**
     * Additional integer loop variables for cross-product generation.
     * @see PermuteVar
     */
    PermuteVar[] extraVars() default {};

    /**
     * When {@code true}, generates permuted classes as nested siblings inside the
     * parent class rather than as separate top-level files.
     *
     * <p>
     * Only valid on nested static classes. Requires {@code permuplate-maven-plugin};
     * the APT annotation processor reports a compile error if this is set to
     * {@code true}.
     *
     * <p>
     * Template files with {@code inline = true} must be placed in
     * {@code src/main/permuplate/} (the plugin's template directory) so they are
     * not compiled directly by javac. The augmented parent class is written to
     * {@code target/generated-sources/permuplate/} instead.
     */
    boolean inline() default false;

    /**
     * When {@code true} and {@code inline = true}, the template class itself is
     * retained in the generated parent alongside the permuted classes. When
     * {@code false} (default), the template class is removed — it was only a scaffold
     * and the generated classes replace it entirely.
     *
     * <p>
     * Has no effect when {@code inline = false}.
     */
    boolean keepTemplate() default false;
}
```

- [ ] **Build annotations module**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-annotations -q
```

Expected: BUILD SUCCESS

---

### Task 2: APT validation for `inline = true`

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

- [ ] **Add inline validation tests first** (in `DegenerateInputTest.java`)

Add to `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java`:

```java
// -------------------------------------------------------------------------
// inline = true on APT is an error
// -------------------------------------------------------------------------

/**
 * The APT processor cannot modify existing source files, so inline generation
 * is not supported. Using inline = true with the APT must produce a clear,
 * actionable error directing the user to the Maven plugin.
 */
@Test
public void testInlineTrueOnAptIsError() {
    var compilation = compile(Callable2.class, "Foo2",
            """
                    package %s;
                    import %s;
                    public class Foo2 {
                        @Permute(varName = "i", from = 3, to = 5,
                                 className = "Foo${i}", inline = true)
                        public static class FooNested {}
                    }
                    """.formatted(PKG, PERMUTE_FQN));

    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("inline");
    assertThat(compilation).hadErrorContaining("permuplate-maven-plugin");
}

/**
 * inline = true on a top-level class has no parent to inline into.
 */
@Test
public void testInlineTrueOnTopLevelClassIsError() {
    var compilation = compile(Callable2.class, "Foo2",
            """
                    package %s;
                    import %s;
                    @Permute(varName = "i", from = 3, to = 5,
                             className = "Foo${i}", inline = true)
                    public class Foo2 {}
                    """.formatted(PKG, PERMUTE_FQN));

    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("inline");
    assertThat(compilation).hadErrorContaining("nested");
}

/**
 * keepTemplate = true with inline = false is meaningless.
 * The processor must warn (not error) to avoid breaking existing builds.
 */
@Test
public void testKeepTemplateTrueWithInlineFalseIsWarning() {
    var compilation = compile(Callable2.class, "Foo2",
            """
                    package %s;
                    import %s;
                    @Permute(varName = "i", from = 3, to = 5,
                             className = "Foo${i}", keepTemplate = true)
                    public class Foo2 {}
                    """.formatted(PKG, PERMUTE_FQN));

    // keepTemplate without inline should still succeed (just a warning)
    assertThat(compilation).succeeded();
    assertThat(compilation).hadWarningContaining("keepTemplate");
}
```

- [ ] **Run tests to confirm they fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=DegenerateInputTest#testInlineTrueOnAptIsError,DegenerateInputTest#testInlineTrueOnTopLevelClassIsError,DegenerateInputTest#testKeepTemplateTrueWithInlineFalseIsWarning 2>&1 | grep -E "(PASS|FAIL|ERROR)"
```

Expected: all three FAIL (feature not implemented yet)

- [ ] **Add inline validation to `processTypePermutation` in `PermuteProcessor.java`**

In `processTypePermutation`, add these checks immediately after the `from > to` check:

```java
// Check for inline — APT cannot generate inline (requires Maven plugin)
if (permute.inline()) {
    // Additional check: inline on top-level class is always wrong
    boolean isNested = typeElement.getEnclosingElement() instanceof TypeElement;
    if (!isNested) {
        error("@Permute inline = true is only valid on nested static classes — " +
                "there is no parent class to inline into",
                typeElement, permuteMirror, findAnnotationValue(permuteMirror, "inline"));
    } else {
        error("@Permute inline = true requires permuplate-maven-plugin — " +
                "the annotation processor cannot modify existing source files. " +
                "See README §'APT vs Maven Plugin' for migration instructions.",
                typeElement, permuteMirror, findAnnotationValue(permuteMirror, "inline"));
    }
    return;
}

// Warn if keepTemplate is set but inline is false
if (permute.keepTemplate()) {
    processingEnv.getMessager().printMessage(
            Diagnostic.Kind.WARNING,
            "@Permute keepTemplate = true has no effect when inline = false",
            typeElement, permuteMirror, findAnnotationValue(permuteMirror, "keepTemplate"));
}
```

- [ ] **Build processor and run all tests**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor -am -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | grep -E "(Tests run|BUILD)"
```

Expected: all existing tests pass, new inline/keepTemplate tests pass, BUILD SUCCESS

- [ ] **Commit**

```bash
git add permuplate-annotations/src permuplate-processor/src permuplate-tests/src
git commit -m "feat: add inline and keepTemplate to @Permute; APT validates and rejects inline=true"
```

---

## Phase 2: Core extraction + Maven plugin

### Task 3: Create `permuplate-core` module

**Files:**
- Create: `permuplate-core/pom.xml`
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteConfig.java`
- Create: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteVarConfig.java`
- Move (copy then delete): `EvaluationContext.java`, `PermuteDeclrTransformer.java`, `PermuteParamTransformer.java` from `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/` to `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/`

- [ ] **Create `permuplate-core/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.quarkiverse.permuplate</groupId>
        <artifactId>quarkus-permuplate-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>quarkus-permuplate-core</artifactId>
    <name>Permuplate :: Core</name>
    <description>Shared transformation engine used by both the APT processor and Maven plugin.</description>

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

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <!-- Core must not process its own annotations -->
                    <compilerArgs>
                        <arg>-proc:none</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Create `PermuteConfig.java`** — data class mirroring the `@Permute` annotation so core logic works without javac

```java
package io.quarkiverse.permuplate.core;

/**
 * Data representation of a {@code @Permute} annotation, usable without javac's
 * annotation processing infrastructure. Both the APT processor (which reads from
 * {@code javax.lang.model} elements) and the Maven plugin (which reads from
 * JavaParser AST nodes) convert their native representations into this class
 * before calling the shared transformation engine.
 */
public final class PermuteConfig {

    public final String varName;
    public final int from;
    public final int to;
    public final String className;
    public final String[] strings;
    public final PermuteVarConfig[] extraVars;
    public final boolean inline;
    public final boolean keepTemplate;

    public PermuteConfig(String varName, int from, int to, String className,
            String[] strings, PermuteVarConfig[] extraVars,
            boolean inline, boolean keepTemplate) {
        this.varName = varName;
        this.from = from;
        this.to = to;
        this.className = className;
        this.strings = strings != null ? strings : new String[0];
        this.extraVars = extraVars != null ? extraVars : new PermuteVarConfig[0];
        this.inline = inline;
        this.keepTemplate = keepTemplate;
    }

    /** Constructs from the javac annotation proxy (APT path). */
    public static PermuteConfig from(io.quarkiverse.permuplate.Permute permute) {
        PermuteVarConfig[] extraVars = new PermuteVarConfig[permute.extraVars().length];
        for (int i = 0; i < permute.extraVars().length; i++) {
            extraVars[i] = PermuteVarConfig.from(permute.extraVars()[i]);
        }
        return new PermuteConfig(
                permute.varName(), permute.from(), permute.to(), permute.className(),
                permute.strings(), extraVars, permute.inline(), permute.keepTemplate());
    }
}
```

- [ ] **Create `PermuteVarConfig.java`**

```java
package io.quarkiverse.permuplate.core;

/**
 * Data representation of a {@code @PermuteVar} annotation.
 * @see PermuteConfig
 */
public final class PermuteVarConfig {

    public final String varName;
    public final int from;
    public final int to;

    public PermuteVarConfig(String varName, int from, int to) {
        this.varName = varName;
        this.from = from;
        this.to = to;
    }

    /** Constructs from the javac annotation proxy (APT path). */
    public static PermuteVarConfig from(io.quarkiverse.permuplate.PermuteVar extra) {
        return new PermuteVarConfig(extra.varName(), extra.from(), extra.to());
    }
}
```

- [ ] **Move the three transformer files to `permuplate-core`**

Copy these files, updating the package declaration from `io.quarkiverse.permuplate.processor` to `io.quarkiverse.permuplate.core`:

- `permuplate-processor/.../processor/EvaluationContext.java` → `permuplate-core/.../core/EvaluationContext.java`
- `permuplate-processor/.../processor/PermuteDeclrTransformer.java` → `permuplate-core/.../core/PermuteDeclrTransformer.java`
- `permuplate-processor/.../processor/PermuteParamTransformer.java` → `permuplate-core/.../core/PermuteParamTransformer.java`

In each moved file, change:
```java
package io.quarkiverse.permuplate.processor;
```
to:
```java
package io.quarkiverse.permuplate.core;
```

No other changes needed in the transformer files.

- [ ] **Add `permuplate-core` to root `pom.xml` module list**

```xml
<modules>
    <module>permuplate-annotations</module>
    <module>permuplate-core</module>          <!-- ADD THIS -->
    <module>permuplate-processor</module>
    <module>permuplate-example</module>
    <module>permuplate-tests</module>
</modules>
```

- [ ] **Build core module**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-core -am -q
```

Expected: BUILD SUCCESS

---

### Task 4: Update `permuplate-processor` to depend on `permuplate-core`

**Files:**
- Modify: `permuplate-processor/pom.xml`
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Delete: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/EvaluationContext.java`
- Delete: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteDeclrTransformer.java`
- Delete: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteParamTransformer.java`

- [ ] **Add `permuplate-core` dependency to `permuplate-processor/pom.xml`**

Add inside `<dependencies>`:
```xml
<dependency>
    <groupId>io.quarkiverse.permuplate</groupId>
    <artifactId>quarkus-permuplate-core</artifactId>
</dependency>
```

- [ ] **Update `PermuteProcessor.java` imports and usages**

In `PermuteProcessor.java`:

1. Replace `import io.quarkiverse.permuplate.processor.EvaluationContext;` with `import io.quarkiverse.permuplate.core.EvaluationContext;`
2. Replace `import io.quarkiverse.permuplate.processor.PermuteDeclrTransformer;` with `import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;`
3. Replace `import io.quarkiverse.permuplate.processor.PermuteParamTransformer;` with `import io.quarkiverse.permuplate.core.PermuteParamTransformer;`
4. Add `import io.quarkiverse.permuplate.core.PermuteConfig;`
5. Add `import io.quarkiverse.permuplate.core.PermuteVarConfig;`

Replace `buildAllCombinations(Permute permute)` with a version that takes `PermuteConfig`:

```java
private static List<Map<String, Object>> buildAllCombinations(PermuteConfig config) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (int i = config.from; i <= config.to; i++) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(config.varName, i);
        result.add(vars);
    }
    for (PermuteVarConfig extra : config.extraVars) {
        List<Map<String, Object>> expanded = new ArrayList<>();
        for (Map<String, Object> base : result) {
            for (int v = extra.from; v <= extra.to; v++) {
                Map<String, Object> copy = new HashMap<>(base);
                copy.put(extra.varName, v);
                expanded.add(copy);
            }
        }
        result = expanded;
    }
    for (Map<String, Object> vars : result) {
        for (String entry : config.strings) {
            int sep = entry.indexOf('=');
            vars.put(entry.substring(0, sep).trim(), entry.substring(sep + 1));
        }
    }
    return result;
}
```

Update all call sites from `buildAllCombinations(permute)` to:
```java
buildAllCombinations(PermuteConfig.from(permute))
```

Update `validateStrings` and `validateExtraVars` to take `PermuteConfig` instead of `Permute`. Since these currently reference `permute.varName()`, `permute.strings()`, etc., replace all such calls with `config.varName`, `config.strings`, etc.

- [ ] **Delete the three moved files from processor**

```bash
rm permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/EvaluationContext.java
rm permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteDeclrTransformer.java
rm permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteParamTransformer.java
```

- [ ] **Build and verify all tests pass**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor -am -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | grep -E "(Tests run|BUILD)"
```

Expected: all 46+ tests pass, BUILD SUCCESS

- [ ] **Commit**

```bash
git add permuplate-core/ permuplate-processor/ pom.xml
git commit -m "refactor: extract shared transformation logic to permuplate-core"
```

---

### Task 5: Create `permuplate-maven-plugin` skeleton

**Files:**
- Create: `permuplate-maven-plugin/pom.xml`
- Create: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/PermuteMojo.java`

- [ ] **Create `permuplate-maven-plugin/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.quarkiverse.permuplate</groupId>
        <artifactId>quarkus-permuplate-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>quarkus-permuplate-maven-plugin</artifactId>
    <packaging>maven-plugin</packaging>
    <name>Permuplate :: Maven Plugin</name>
    <description>
        Maven plugin for Permuplate that supports all APT functionality plus inline
        generation — writing permuted nested classes into an augmented parent class
        rather than separate top-level files.
    </description>

    <dependencies>
        <dependency>
            <groupId>io.quarkiverse.permuplate</groupId>
            <artifactId>quarkus-permuplate-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.maven</groupId>
            <artifactId>maven-plugin-api</artifactId>
            <version>3.9.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.maven.plugin-tools</groupId>
            <artifactId>maven-plugin-annotations</artifactId>
            <version>3.9.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.maven</groupId>
            <artifactId>maven-core</artifactId>
            <version>3.9.0</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-plugin-plugin</artifactId>
                <version>3.9.0</version>
                <executions>
                    <execution>
                        <id>default-descriptor</id>
                        <goals><goal>descriptor</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Add `permuplate-maven-plugin` to root `pom.xml`**

```xml
<modules>
    <module>permuplate-annotations</module>
    <module>permuplate-core</module>
    <module>permuplate-processor</module>
    <module>permuplate-maven-plugin</module>   <!-- ADD THIS -->
    <module>permuplate-example</module>
    <module>permuplate-tests</module>
</modules>
```

Also add `maven-plugin-api` and related to `<dependencyManagement>`:

```xml
<dependency>
    <groupId>org.apache.maven</groupId>
    <artifactId>maven-plugin-api</artifactId>
    <version>3.9.0</version>
</dependency>
<dependency>
    <groupId>org.apache.maven.plugin-tools</groupId>
    <artifactId>maven-plugin-annotations</artifactId>
    <version>3.9.0</version>
</dependency>
<dependency>
    <groupId>org.apache.maven</groupId>
    <artifactId>maven-core</artifactId>
    <version>3.9.0</version>
</dependency>
```

- [ ] **Create `PermuteMojo.java` skeleton**

```java
package io.quarkiverse.permuplate.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Generates permuted classes from {@code @Permute}-annotated templates.
 *
 * <p>
 * Supports all functionality of the APT annotation processor plus inline
 * generation: permuted classes can be written as nested siblings inside the
 * parent class (via {@code @Permute(inline = true)}) rather than as separate
 * top-level files.
 *
 * <p>
 * Bind this goal in your {@code pom.xml}:
 *
 * <pre>{@code
 * <plugin>
 *     <groupId>io.quarkiverse.permuplate</groupId>
 *     <artifactId>quarkus-permuplate-maven-plugin</artifactId>
 *     <executions>
 *         <execution>
 *             <goals><goal>generate</goal></goals>
 *         </execution>
 *     </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class PermuteMojo extends AbstractMojo {

    /**
     * Directory containing regular (non-inline) {@code @Permute} templates.
     * Defaults to the project's main source directory.
     */
    @Parameter(defaultValue = "${project.build.sourceDirectory}")
    private File sourceDirectory;

    /**
     * Directory containing inline {@code @Permute} templates (those with
     * {@code inline = true}). These files are NOT compiled directly by javac —
     * the plugin reads them and writes augmented parent classes to
     * {@code outputDirectory}. Mark this directory as a source root in your
     * IDE for navigation and refactoring support.
     */
    @Parameter(defaultValue = "src/main/permuplate")
    private File templateDirectory;

    /**
     * Directory where all generated source files are written. Added as a
     * compile source root automatically.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/permuplate")
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            outputDirectory.mkdirs();
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
            getLog().info("Permuplate: scanning " + sourceDirectory + " for @Permute templates");
            // TODO: implement in Tasks 6 and 7
        } catch (Exception e) {
            throw new MojoExecutionException("Permuplate generation failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Build the plugin module**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-maven-plugin -am -q
```

Expected: BUILD SUCCESS

---

### Task 6: Implement non-inline generation in `PermuteMojo`

Non-inline generation replicates what the APT does, using JavaParser to scan source files directly.

**Files:**
- Create: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/SourceScanner.java`
- Create: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/PermuteMojo.java`

- [ ] **Create `AnnotationReader.java`** — reads `@Permute` values from a JavaParser `AnnotationExpr`

```java
package io.quarkiverse.permuplate.maven;

import java.util.ArrayList;
import java.util.List;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.core.PermuteVarConfig;

/**
 * Reads {@code @Permute} and {@code @PermuteVar} annotation attribute values
 * from a JavaParser {@link AnnotationExpr} AST node, producing a {@link PermuteConfig}.
 *
 * <p>
 * This is the Maven plugin's equivalent of {@code typeElement.getAnnotation(Permute.class)}
 * in the APT path — both produce a {@link PermuteConfig} that the shared core engine consumes.
 */
public class AnnotationReader {

    private AnnotationReader() {}

    /**
     * Converts a {@code @Permute} annotation expression to a {@link PermuteConfig}.
     *
     * @throws MojoAnnotationException if a required attribute is missing or malformed
     */
    public static PermuteConfig readPermute(AnnotationExpr ann) throws MojoAnnotationException {
        if (!(ann instanceof NormalAnnotationExpr)) {
            throw new MojoAnnotationException(
                    "@Permute must use named parameters (e.g. varName=\"i\", from=2, to=4, ...)");
        }
        NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;

        String varName = requireString(normal, "varName");
        int from = requireInt(normal, "from");
        int to = requireInt(normal, "to");
        String className = requireString(normal, "className");
        String[] strings = readStringArray(normal, "strings");
        PermuteVarConfig[] extraVars = readExtraVars(normal);
        boolean inline = readBoolean(normal, "inline", false);
        boolean keepTemplate = readBoolean(normal, "keepTemplate", false);

        return new PermuteConfig(varName, from, to, className, strings, extraVars, inline, keepTemplate);
    }

    private static String requireString(NormalAnnotationExpr ann, String name)
            throws MojoAnnotationException {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(name)) {
                return PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            }
        }
        throw new MojoAnnotationException("@Permute is missing required attribute: " + name);
    }

    private static int requireInt(NormalAnnotationExpr ann, String name)
            throws MojoAnnotationException {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(name)) {
                try {
                    return Integer.parseInt(pair.getValue().toString().trim());
                } catch (NumberFormatException e) {
                    throw new MojoAnnotationException(
                            "@Permute attribute '" + name + "' is not an integer: " + pair.getValue());
                }
            }
        }
        throw new MojoAnnotationException("@Permute is missing required attribute: " + name);
    }

    private static boolean readBoolean(NormalAnnotationExpr ann, String name, boolean defaultValue) {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(name)) {
                return Boolean.parseBoolean(pair.getValue().toString().trim());
            }
        }
        return defaultValue;
    }

    private static String[] readStringArray(NormalAnnotationExpr ann, String name) {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(name)) {
                Expression val = pair.getValue();
                if (val instanceof ArrayInitializerExpr) {
                    List<String> result = new ArrayList<>();
                    for (Expression e : ((ArrayInitializerExpr) val).getValues()) {
                        result.add(PermuteDeclrTransformer.stripQuotes(e.toString()));
                    }
                    return result.toArray(new String[0]);
                }
                return new String[]{ PermuteDeclrTransformer.stripQuotes(val.toString()) };
            }
        }
        return new String[0];
    }

    private static PermuteVarConfig[] readExtraVars(NormalAnnotationExpr ann)
            throws MojoAnnotationException {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals("extraVars")) {
                Expression val = pair.getValue();
                List<PermuteVarConfig> result = new ArrayList<>();
                List<Expression> exprs = val instanceof ArrayInitializerExpr
                        ? ((ArrayInitializerExpr) val).getValues()
                        : List.of(val);
                for (Expression e : exprs) {
                    if (e instanceof NormalAnnotationExpr) {
                        NormalAnnotationExpr varAnn = (NormalAnnotationExpr) e;
                        String varName = requireString(varAnn, "varName");
                        int from = requireInt(varAnn, "from");
                        int to = requireInt(varAnn, "to");
                        result.add(new PermuteVarConfig(varName, from, to));
                    }
                }
                return result.toArray(new PermuteVarConfig[0]);
            }
        }
        return new PermuteVarConfig[0];
    }

    /** Signals a malformed or missing annotation attribute found during source scanning. */
    public static class MojoAnnotationException extends Exception {
        public MojoAnnotationException(String message) { super(message); }
    }
}
```

- [ ] **Create `SourceScanner.java`** — walks a directory, parses Java files, finds `@Permute`

```java
package io.quarkiverse.permuplate.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

/**
 * Walks a source directory, parses each {@code .java} file with JavaParser, and
 * collects all annotated elements that carry {@code @Permute}.
 */
public class SourceScanner {

    private SourceScanner() {}

    public record AnnotatedType(CompilationUnit cu, ClassOrInterfaceDeclaration classDecl,
            AnnotationExpr permuteAnn, Path sourceFile) {}

    public record AnnotatedMethod(CompilationUnit cu, MethodDeclaration method,
            AnnotationExpr permuteAnn, Path sourceFile) {}

    public record ScanResult(List<AnnotatedType> types, List<AnnotatedMethod> methods) {}

    /**
     * Scans {@code directory} recursively for {@code .java} files. Returns all
     * classes and methods annotated with {@code @Permute} or
     * {@code @io.quarkiverse.permuplate.Permute}.
     */
    public static ScanResult scan(File directory) throws IOException {
        List<AnnotatedType> types = new ArrayList<>();
        List<AnnotatedMethod> methods = new ArrayList<>();

        if (!directory.exists() || !directory.isDirectory()) {
            return new ScanResult(types, methods);
        }

        Files.walk(directory.toPath())
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(path);
                        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                            findPermuteAnnotation(classDecl.getAnnotations()).ifPresent(ann ->
                                    types.add(new AnnotatedType(cu, classDecl, ann, path)));
                        });
                        cu.findAll(MethodDeclaration.class).forEach(method -> {
                            findPermuteAnnotation(method.getAnnotations()).ifPresent(ann ->
                                    methods.add(new AnnotatedMethod(cu, method, ann, path)));
                        });
                    } catch (Exception e) {
                        // Skip unparseable files — they'll fail at javac time
                    }
                });

        return new ScanResult(types, methods);
    }

    private static java.util.Optional<AnnotationExpr> findPermuteAnnotation(
            com.github.javaparser.ast.NodeList<AnnotationExpr> annotations) {
        return annotations.stream()
                .filter(a -> {
                    String name = a.getNameAsString();
                    return name.equals("Permute") ||
                           name.equals("io.quarkiverse.permuplate.Permute");
                })
                .findFirst();
    }
}
```

- [ ] **Implement non-inline generation in `PermuteMojo.execute()`**

Replace the TODO in `PermuteMojo.java` with:

```java
@Override
public void execute() throws MojoExecutionException {
    try {
        outputDirectory.mkdirs();
        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());

        // --- Non-inline: scan sourceDirectory for @Permute (inline=false) ---
        getLog().info("Permuplate: scanning " + sourceDirectory + " for @Permute templates");
        SourceScanner.ScanResult mainScan = SourceScanner.scan(sourceDirectory);
        for (SourceScanner.AnnotatedType entry : mainScan.types()) {
            processType(entry, false);
        }
        for (SourceScanner.AnnotatedMethod entry : mainScan.methods()) {
            processMethod(entry);
        }

        // --- Inline: scan templateDirectory for @Permute(inline=true) ---
        if (templateDirectory.exists()) {
            getLog().info("Permuplate: scanning " + templateDirectory + " for inline templates");
            SourceScanner.ScanResult templateScan = SourceScanner.scan(templateDirectory);
            for (SourceScanner.AnnotatedType entry : templateScan.types()) {
                processType(entry, true);
            }
        }
    } catch (Exception e) {
        throw new MojoExecutionException("Permuplate generation failed: " + e.getMessage(), e);
    }
}

private void processType(SourceScanner.AnnotatedType entry, boolean expectInline)
        throws Exception {
    PermuteConfig config;
    try {
        config = AnnotationReader.readPermute(entry.permuteAnn());
    } catch (AnnotationReader.MojoAnnotationException e) {
        throw new MojoExecutionException(entry.sourceFile() + ": " + e.getMessage(), e);
    }

    // Validate inline vs expectation
    if (expectInline && !config.inline) {
        getLog().debug("Skipping non-inline @Permute in template directory: " + entry.sourceFile());
        return;
    }
    if (!expectInline && config.inline) {
        getLog().warn("Found inline=true in sourceDirectory (should be in templateDirectory): "
                + entry.sourceFile() + " — skipping");
        return;
    }

    // Validate inline only on nested classes
    boolean isNested = entry.classDecl().isNestedType();
    if (config.inline && !isNested) {
        throw new MojoExecutionException(entry.sourceFile() + ": @Permute inline=true is only " +
                "valid on nested static classes — there is no parent class to inline into.");
    }

    // Validate range
    validateConfig(config, entry.sourceFile().toString());

    if (config.inline) {
        generateInline(entry, config);
    } else {
        generateTopLevel(entry, config);
    }
}

private void validateConfig(PermuteConfig config, String location) throws MojoExecutionException {
    if (config.from > config.to) {
        throw new MojoExecutionException(location + ": @Permute has invalid range: from=" +
                config.from + " is greater than to=" + config.to);
    }
    // Validate strings format
    for (String entry : config.strings) {
        int sep = entry.indexOf('=');
        if (sep < 0) throw new MojoExecutionException(location +
                ": @Permute strings entry \"" + entry + "\" is malformed — must be \"key=value\"");
        String key = entry.substring(0, sep).trim();
        if (key.isEmpty()) throw new MojoExecutionException(location +
                ": @Permute strings entry \"" + entry + "\" has an empty key");
        if (key.equals(config.varName)) throw new MojoExecutionException(location +
                ": @Permute strings key \"" + key + "\" conflicts with varName");
    }
    // Validate extraVars
    java.util.Set<String> seen = new java.util.HashSet<>();
    seen.add(config.varName);
    for (io.quarkiverse.permuplate.core.PermuteVarConfig extra : config.extraVars) {
        if (extra.from > extra.to) throw new MojoExecutionException(location +
                ": @PermuteVar \"" + extra.varName + "\" has invalid range: from=" +
                extra.from + " > to=" + extra.to);
        if (seen.contains(extra.varName)) throw new MojoExecutionException(location +
                ": @PermuteVar varName \"" + extra.varName + "\" conflicts with an existing variable");
        seen.add(extra.varName);
    }
}

private void generateTopLevel(SourceScanner.AnnotatedType entry, PermuteConfig config)
        throws Exception {
    String templateClassName = entry.classDecl().getNameAsString();
    List<java.util.Map<String, Object>> allCombinations = buildAllCombinations(config);

    // Leading literal prefix check (same rule as APT)
    String leadingLiteral = config.className.contains("${")
            ? config.className.substring(0, config.className.indexOf("${"))
            : config.className;
    if (!leadingLiteral.isEmpty() && !templateClassName.startsWith(leadingLiteral)) {
        throw new MojoExecutionException(entry.sourceFile() +
                ": @Permute className leading literal \"" + leadingLiteral +
                "\" is not a prefix of the template class name \"" + templateClassName + "\"");
    }

    for (java.util.Map<String, Object> vars : allCombinations) {
        EvaluationContext ctx = new EvaluationContext(vars);

        // Find and clone the class (handles nested templates generating top-level)
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl =
                entry.classDecl().clone();
        classDecl.setStatic(false);
        if (!classDecl.isPublic()) classDecl.setModifier(
                com.github.javaparser.ast.Modifier.Keyword.PUBLIC, true);

        String newClassName = ctx.evaluate(config.className);
        classDecl.setName(newClassName);
        classDecl.getConstructors().forEach(ctor -> ctor.setName(newClassName));

        PermuteDeclrTransformer.transform(classDecl, ctx, null /* no Messager in plugin */);
        PermuteParamTransformer.transform(classDecl, ctx, null);

        classDecl.getAnnotations().removeIf(a -> {
            String n = a.getNameAsString();
            return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute");
        });

        com.github.javaparser.ast.CompilationUnit generatedCu =
                new com.github.javaparser.ast.CompilationUnit();
        entry.cu().getPackageDeclaration().ifPresent(p ->
                generatedCu.setPackageDeclaration(p.clone()));
        entry.cu().getImports().forEach(imp -> {
            if (!imp.getNameAsString().startsWith("io.quarkiverse.permuplate"))
                generatedCu.addImport(imp.clone());
        });
        generatedCu.addType(classDecl);

        String packageName = entry.cu().getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        String qualifiedName = packageName.isEmpty() ? newClassName
                : packageName + "." + newClassName;
        writeGeneratedFile(qualifiedName, generatedCu.toString());
    }
}

private void processMethod(SourceScanner.AnnotatedMethod entry) throws Exception {
    PermuteConfig config;
    try {
        config = AnnotationReader.readPermute(entry.permuteAnn());
    } catch (AnnotationReader.MojoAnnotationException e) {
        throw new MojoExecutionException(entry.sourceFile() + ": " + e.getMessage(), e);
    }
    validateConfig(config, entry.sourceFile().toString());
    // Method permutation: same logic as APT's processMethodPermutation
    // but adapted to write to outputDirectory instead of Filer
    // (implementation follows the same pattern as generateTopLevel above)
    getLog().debug("Processing method @Permute on: " + entry.method().getNameAsString());
    generateMethodPermutation(entry, config);
}

private void generateMethodPermutation(SourceScanner.AnnotatedMethod entry, PermuteConfig config)
        throws Exception {
    List<java.util.Map<String, Object>> allCombinations = buildAllCombinations(config);
    EvaluationContext firstCtx = new EvaluationContext(allCombinations.get(0));
    String outputClassName = firstCtx.evaluate(config.className);

    // Find enclosing class
    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration enclosingClass =
            entry.method().findAncestor(
                    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
            .orElseThrow(() -> new MojoExecutionException(
                    entry.sourceFile() + ": cannot find enclosing class for method @Permute"));

    List<com.github.javaparser.ast.body.MethodDeclaration> methods = new ArrayList<>();
    for (java.util.Map<String, Object> vars : allCombinations) {
        EvaluationContext ctx = new EvaluationContext(vars);
        com.github.javaparser.ast.body.MethodDeclaration clone = entry.method().clone();
        clone.getAnnotations().removeIf(a -> {
            String n = a.getNameAsString();
            return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute");
        });
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration wrapper =
                new com.github.javaparser.ast.body.ClassOrInterfaceDeclaration(
                        new com.github.javaparser.ast.NodeList<>(), false, "_W");
        wrapper.addMember(clone);
        PermuteDeclrTransformer.transform(wrapper, ctx, null);
        PermuteParamTransformer.transform(wrapper, ctx, null);
        methods.add(wrapper.getMethods().get(0));
    }

    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration generatedClass =
            enclosingClass.clone();
    generatedClass.setName(outputClassName);
    generatedClass.setStatic(false);
    if (!generatedClass.isPublic()) generatedClass.setModifier(
            com.github.javaparser.ast.Modifier.Keyword.PUBLIC, true);
    generatedClass.getAnnotations().removeIf(a -> {
        String n = a.getNameAsString();
        return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute");
    });
    generatedClass.setMembers(new com.github.javaparser.ast.NodeList<>());
    methods.forEach(m -> generatedClass.addMember(m));

    com.github.javaparser.ast.CompilationUnit generatedCu =
            new com.github.javaparser.ast.CompilationUnit();
    entry.cu().getPackageDeclaration().ifPresent(p ->
            generatedCu.setPackageDeclaration(p.clone()));
    entry.cu().getImports().forEach(imp -> {
        if (!imp.getNameAsString().startsWith("io.quarkiverse.permuplate"))
            generatedCu.addImport(imp.clone());
    });
    generatedCu.addType(generatedClass);

    String packageName = entry.cu().getPackageDeclaration()
            .map(p -> p.getNameAsString()).orElse("");
    String qualifiedName = packageName.isEmpty() ? outputClassName
            : packageName + "." + outputClassName;
    writeGeneratedFile(qualifiedName, generatedCu.toString());
}

private void writeGeneratedFile(String qualifiedName, String source) throws IOException {
    String path = qualifiedName.replace('.', '/') + ".java";
    Path outputPath = outputDirectory.toPath().resolve(path);
    outputPath.getParent().toFile().mkdirs();
    Files.writeString(outputPath, source);
    getLog().info("Permuplate: generated " + qualifiedName);
}

private static List<java.util.Map<String, Object>> buildAllCombinations(PermuteConfig config) {
    // Identical logic to PermuteProcessor.buildAllCombinations but taking PermuteConfig
    List<java.util.Map<String, Object>> result = new ArrayList<>();
    for (int i = config.from; i <= config.to; i++) {
        java.util.Map<String, Object> vars = new java.util.HashMap<>();
        vars.put(config.varName, i);
        result.add(vars);
    }
    for (io.quarkiverse.permuplate.core.PermuteVarConfig extra : config.extraVars) {
        List<java.util.Map<String, Object>> expanded = new ArrayList<>();
        for (java.util.Map<String, Object> base : result) {
            for (int v = extra.from; v <= extra.to; v++) {
                java.util.Map<String, Object> copy = new java.util.HashMap<>(base);
                copy.put(extra.varName, v);
                expanded.add(copy);
            }
        }
        result = expanded;
    }
    for (java.util.Map<String, Object> vars : result) {
        for (String entry : config.strings) {
            int sep = entry.indexOf('=');
            vars.put(entry.substring(0, sep).trim(), entry.substring(sep + 1));
        }
    }
    return result;
}
```

Note: `PermuteDeclrTransformer.transform()` and `PermuteParamTransformer.transform()` take a `Messager` as their third argument. In the Maven plugin there is no `Messager`. Pass `null` and update both transformer methods to null-check before any `messager.printMessage(...)` call.

- [ ] **Update transformers to null-check messager**

In `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java` and `PermuteParamTransformer.java`, find every occurrence of `messager.printMessage(` and change it to:

```java
if (messager != null) messager.printMessage(
```

For multi-line calls like:
```java
messager.printMessage(Diagnostic.Kind.ERROR,
        "some message",
        element);
```
Change to:
```java
if (messager != null) messager.printMessage(Diagnostic.Kind.ERROR,
        "some message",
        element);
```

Also update `validatePrefixes` and `extractTwoParams` similarly — these return `false`/`null` to signal failure, but the caller in the Maven plugin just skips generation rather than halting the build with a compile error (the error will surface at `javac` time instead).

- [ ] **Build the plugin**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-maven-plugin -am -q
```

Expected: BUILD SUCCESS

---

### Task 7: Implement inline generation in `PermuteMojo`

**Files:**
- Create: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

- [ ] **Create `InlineGenerator.java`**

```java
package io.quarkiverse.permuplate.maven;

import java.util.List;
import java.util.Map;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.core.PermuteParamTransformer;

/**
 * Generates an augmented parent {@link CompilationUnit} that contains all permuted
 * nested classes produced from a template nested class annotated with
 * {@code @Permute(inline = true)}.
 *
 * <p>
 * The augmented parent preserves all original content (fields, methods, other
 * nested classes) and either removes or retains the template class depending on
 * {@link PermuteConfig#keepTemplate}.
 */
public class InlineGenerator {

    private InlineGenerator() {}

    /**
     * Generates the augmented parent class.
     *
     * @param parentCu          the full compilation unit of the parent class
     * @param templateClassDecl the nested template class with {@code @Permute}
     * @param config            the parsed {@code @Permute} configuration
     * @param allCombinations   variable maps from {@code buildAllCombinations}
     * @return a new {@link CompilationUnit} containing the augmented parent
     */
    public static CompilationUnit generate(CompilationUnit parentCu,
            ClassOrInterfaceDeclaration templateClassDecl,
            PermuteConfig config,
            List<Map<String, Object>> allCombinations) {

        // Find the top-level parent class in the CU
        ClassOrInterfaceDeclaration parentClass = parentCu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> !c.isNestedType()).orElseThrow(() ->
                new IllegalStateException("Cannot find top-level class in: " + parentCu));

        // Clone the entire parent CU as the starting point for the output
        CompilationUnit outputCu = parentCu.clone();
        ClassOrInterfaceDeclaration outputParent = outputCu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> !c.isNestedType()).orElseThrow();

        // Remove the template nested class from the output (we'll re-add it conditionally)
        String templateClassName = templateClassDecl.getNameAsString();
        outputParent.getMembers().removeIf(member ->
                member instanceof ClassOrInterfaceDeclaration &&
                ((ClassOrInterfaceDeclaration) member).getNameAsString().equals(templateClassName));

        // Re-add the template class if keepTemplate = true
        if (config.keepTemplate) {
            ClassOrInterfaceDeclaration templateCopy = templateClassDecl.clone();
            // Strip @Permute from the retained template
            templateCopy.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute");
            });
            outputParent.addMember(templateCopy);
        }

        // Generate and append each permuted nested class
        for (Map<String, Object> vars : allCombinations) {
            EvaluationContext ctx = new EvaluationContext(vars);

            ClassOrInterfaceDeclaration generated = templateClassDecl.clone();

            // Rename the generated nested class
            String newClassName = ctx.evaluate(config.className);
            generated.setName(newClassName);
            generated.getConstructors().forEach(ctor -> ctor.setName(newClassName));

            // Apply transformations
            PermuteDeclrTransformer.transform(generated, ctx, null);
            PermuteParamTransformer.transform(generated, ctx, null);

            // Strip @Permute
            generated.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute");
            });

            outputParent.addMember(generated);
        }

        // Strip all permuplate imports from the output
        outputCu.getImports().removeIf(imp ->
                imp.getNameAsString().startsWith("io.quarkiverse.permuplate"));

        return outputCu;
    }
}
```

- [ ] **Wire `InlineGenerator` into `PermuteMojo.generateInline()`**

Add this method to `PermuteMojo.java`:

```java
private void generateInline(SourceScanner.AnnotatedType entry, PermuteConfig config)
        throws Exception {
    // The template class must be nested
    ClassOrInterfaceDeclaration templateClass = entry.classDecl();

    List<Map<String, Object>> allCombinations = buildAllCombinations(config);

    CompilationUnit outputCu = InlineGenerator.generate(
            entry.cu(), templateClass, config, allCombinations);

    // Write the augmented parent to outputDirectory with the PARENT's class name
    String parentClassName = entry.cu().findFirst(ClassOrInterfaceDeclaration.class,
            c -> !c.isNestedType())
            .orElseThrow(() -> new MojoExecutionException(
                    "Cannot find top-level class in " + entry.sourceFile()))
            .getNameAsString();

    String packageName = entry.cu().getPackageDeclaration()
            .map(p -> p.getNameAsString()).orElse("");
    String qualifiedName = packageName.isEmpty() ? parentClassName
            : packageName + "." + parentClassName;

    writeGeneratedFile(qualifiedName, outputCu.toString());
    getLog().info("Permuplate: generated inline classes in " + qualifiedName);
}
```

- [ ] **Build the plugin**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-maven-plugin -am -q
```

Expected: BUILD SUCCESS

- [ ] **Commit**

```bash
git add permuplate-maven-plugin/ permuplate-core/ pom.xml
git commit -m "feat: implement permuplate-maven-plugin with non-inline and inline generation"
```

---

## Phase 3: Module rename, examples, and documentation

### Task 8: Rename `permuplate-example` to `permuplate-apt-examples`

**Files:**
- Rename directory: `permuplate-example/` → `permuplate-apt-examples/`
- Modify: `permuplate-apt-examples/pom.xml` (artifactId)
- Modify: root `pom.xml` (module reference)

- [ ] **Rename the directory**

```bash
mv /Users/mdproctor/claude/permuplate/permuplate-example \
   /Users/mdproctor/claude/permuplate/permuplate-apt-examples
```

- [ ] **Update `permuplate-apt-examples/pom.xml`**

Change:
```xml
<artifactId>quarkus-permuplate-example</artifactId>
<name>Permuplate :: Example</name>
```
To:
```xml
<artifactId>quarkus-permuplate-apt-examples</artifactId>
<name>Permuplate :: APT Examples</name>
<description>
    Examples demonstrating Permuplate using the annotation processor (APT).
    All generated classes are separate top-level files. For inline generation
    (nested classes inside a parent), see permuplate-mvn-examples.
</description>
```

- [ ] **Update root `pom.xml` module reference**

Change `<module>permuplate-example</module>` to `<module>permuplate-apt-examples</module>`.

- [ ] **Update `dependencyManagement` in root pom.xml** if it references `quarkus-permuplate-example`:

Search for and update any reference from `quarkus-permuplate-example` to `quarkus-permuplate-apt-examples`.

- [ ] **Build everything**

```bash
/opt/homebrew/bin/mvn install -q
```

Expected: BUILD SUCCESS

---

### Task 9: Create `permuplate-mvn-examples` with Handlers

**Files:**
- Create: `permuplate-mvn-examples/pom.xml`
- Create: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/Handlers.java`
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/package-info.java`

- [ ] **Create `permuplate-mvn-examples/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.quarkiverse.permuplate</groupId>
        <artifactId>quarkus-permuplate-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>quarkus-permuplate-mvn-examples</artifactId>
    <name>Permuplate :: Maven Plugin Examples</name>
    <description>
        Examples demonstrating Permuplate using the Maven plugin. Includes inline
        generation: the Handlers class contains Handler1 through Handler5 as nested
        classes, all generated from a single template via @Permute(inline = true).
    </description>

    <dependencies>
        <dependency>
            <groupId>io.quarkiverse.permuplate</groupId>
            <artifactId>quarkus-permuplate-annotations</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!--
                The Permuplate Maven plugin replaces the APT annotation processor
                for users who need inline generation. It handles ALL @Permute
                processing — do NOT also configure the APT processor (permuplate-processor
                in annotationProcessorPaths) when using this plugin.
            -->
            <plugin>
                <groupId>io.quarkiverse.permuplate</groupId>
                <artifactId>quarkus-permuplate-maven-plugin</artifactId>
                <version>${project.version}</version>
                <executions>
                    <execution>
                        <goals><goal>generate</goal></goals>
                    </execution>
                </executions>
            </plugin>

            <!--
                Disable the default APT annotation processor — the Maven plugin
                handles all @Permute processing. This prevents double processing.
            -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <compilerArgs>
                        <arg>-proc:none</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Create the Handlers template**

Create directory: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/`

File: `Handlers.java`:

```java
package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;

/**
 * Demonstrates inline permutation: {@code Handler1} through {@code Handler5}
 * are generated as nested classes directly inside {@code Handlers} — no
 * top-level clutter, one clean entry point for the whole family.
 *
 * <p>
 * This template lives in {@code src/main/permuplate/} rather than
 * {@code src/main/java/} because it contains an inline template class that
 * the Permuplate Maven plugin must process before javac compiles anything.
 * Mark {@code src/main/permuplate} as a source root in your IDE for
 * navigation and refactoring support.
 *
 * <p>
 * Usage of a generated handler:
 * <pre>{@code
 * Handlers.Handler3 h = (a1, a2, a3) -> System.out.println(a1 + " " + a2 + " " + a3);
 * h.handle("hello", "world", "!");
 * }</pre>
 */
public class Handlers {

    /**
     * Template for a type-safe N-argument handler. Generates {@code Handler2}
     * through {@code Handler5} as nested siblings inside {@code Handlers}.
     *
     * <p>
     * {@code keepTemplate = true} retains {@code Handler1} in the output
     * alongside the generated classes — a single-argument handler is useful
     * in its own right and not merely a scaffold.
     */
    @Permute(varName = "i", from = 2, to = 5,
            className = "Handler${i}",
            inline = true,
            keepTemplate = true)
    public static class Handler1 {

        private @PermuteDeclr(type = "Callable${i}", name = "delegate${i}") Callable1 delegate1;

        /**
         * Handles the given arguments by forwarding them to the underlying
         * callable delegate.
         */
        public void handle(
                @PermuteParam(varName = "j", from = "1", to = "${i}",
                              type = "Object", name = "arg${j}") Object arg1) {
            delegate1.call(arg1);
        }
    }
}
```

- [ ] **Add `permuplate-mvn-examples` to root `pom.xml`**

```xml
<modules>
    <module>permuplate-annotations</module>
    <module>permuplate-core</module>
    <module>permuplate-processor</module>
    <module>permuplate-maven-plugin</module>
    <module>permuplate-apt-examples</module>
    <module>permuplate-mvn-examples</module>
    <module>permuplate-tests</module>
</modules>
```

- [ ] **Build and verify Handlers generates correctly**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-mvn-examples -am -q && \
ls permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/
```

Expected: `Handlers.java` (containing Handler1–Handler5 nested classes)

- [ ] **Verify generated content**

```bash
grep -n "class Handler" permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/Handlers.java
```

Expected output:
```
    public static class Handler1 {
    public static class Handler2 {
    public static class Handler3 {
    public static class Handler4 {
    public static class Handler5 {
```

- [ ] **Commit**

```bash
git add permuplate-apt-examples/ permuplate-mvn-examples/ pom.xml
git commit -m "feat: rename to permuplate-apt-examples; add permuplate-mvn-examples with Handlers"
```

---

### Task 10: Add tests for inline generation in `permuplate-tests`

**Files:**
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java`

The Maven plugin cannot be tested via `compile-testing` (that only works with APT). Instead we test the core `InlineGenerator` class directly by:
1. Parsing a template string with JavaParser
2. Calling `InlineGenerator.generate()`
3. Asserting on the output

- [ ] **Add `permuplate-core` dependency to `permuplate-tests/pom.xml`**

In `permuplate-tests/pom.xml`, inside `<dependencies>`:
```xml
<dependency>
    <groupId>io.quarkiverse.permuplate</groupId>
    <artifactId>quarkus-permuplate-core</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.quarkiverse.permuplate</groupId>
    <artifactId>quarkus-permuplate-maven-plugin</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Create `InlineGenerationTest.java`**

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteVarConfig;
import io.quarkiverse.permuplate.maven.InlineGenerator;

/**
 * Tests for {@link InlineGenerator}: verifies that inline permutation produces
 * an augmented parent class with correctly generated nested classes.
 */
public class InlineGenerationTest {

    // -------------------------------------------------------------------------
    // Basic inline generation: keepTemplate = false
    // -------------------------------------------------------------------------

    @Test
    public void testInlineGenerationRemovesTemplateByDefault() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                public class Parent {
                    public static class Child2 {
                        public void handle(Object o1) {}
                    }
                }
                """);

        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Child2")).orElseThrow();

        PermuteConfig config = new PermuteConfig("i", 3, 4, "Child${i}",
                new String[0], new PermuteVarConfig[0], true, false);

        List<Map<String, Object>> combos = List.of(
                Map.of("i", 3), Map.of("i", 4));

        CompilationUnit output = InlineGenerator.generate(cu, template, config, combos);
        String src = output.toString();

        // Template class removed (keepTemplate = false)
        assertThat(src).doesNotContain("class Child2");

        // Generated classes present
        assertThat(src).contains("class Child3");
        assertThat(src).contains("class Child4");

        // Parent class preserved
        assertThat(src).contains("class Parent");
    }

    // -------------------------------------------------------------------------
    // keepTemplate = true retains the template
    // -------------------------------------------------------------------------

    @Test
    public void testInlineGenerationKeepsTemplateWhenRequested() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                public class Handlers {
                    public static class Handler1 {
                        public void handle(Object arg1) {}
                    }
                }
                """);

        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Handler1")).orElseThrow();

        PermuteConfig config = new PermuteConfig("i", 2, 3, "Handler${i}",
                new String[0], new PermuteVarConfig[0], true, true);

        List<Map<String, Object>> combos = List.of(
                Map.of("i", 2), Map.of("i", 3));

        CompilationUnit output = InlineGenerator.generate(cu, template, config, combos);
        String src = output.toString();

        // Template retained
        assertThat(src).contains("class Handler1");

        // Generated classes added
        assertThat(src).contains("class Handler2");
        assertThat(src).contains("class Handler3");

        // All nested in Parent
        assertThat(src).contains("class Handlers");
    }

    // -------------------------------------------------------------------------
    // Other parent class content is preserved verbatim
    // -------------------------------------------------------------------------

    @Test
    public void testParentContentPreservedDuringInlineGeneration() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                public class Registry {
                    private static final String VERSION = "1.0";
                    public static String version() { return VERSION; }

                    public static class Worker2 {
                        public void work(Object o1) {}
                    }

                    public static class Helper {
                        public void help() {}
                    }
                }
                """);

        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Worker2")).orElseThrow();

        PermuteConfig config = new PermuteConfig("i", 3, 3, "Worker${i}",
                new String[0], new PermuteVarConfig[0], true, false);

        CompilationUnit output = InlineGenerator.generate(
                cu, template, config, List.of(Map.of("i", 3)));
        String src = output.toString();

        // Static field and method preserved
        assertThat(src).contains("VERSION");
        assertThat(src).contains("version()");

        // Unrelated nested class preserved
        assertThat(src).contains("class Helper");

        // Template removed
        assertThat(src).doesNotContain("class Worker2");

        // Generated added
        assertThat(src).contains("class Worker3");

        // No @Permute left
        assertThat(src).doesNotContain("@Permute");
    }

    // -------------------------------------------------------------------------
    // AnnotationReader: reads @Permute values from JavaParser AST
    // -------------------------------------------------------------------------

    @Test
    public void testAnnotationReaderParsesBasicPermute() throws Exception {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                import io.quarkiverse.permuplate.Permute;
                public class Outer {
                    @Permute(varName = "i", from = 3, to = 5,
                             className = "Foo${i}", inline = true, keepTemplate = true)
                    public static class Foo2 {}
                }
                """);

        var ann = cu.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Foo2"))
                .orElseThrow()
                .getAnnotationByName("Permute").orElseThrow();

        PermuteConfig config = io.quarkiverse.permuplate.maven.AnnotationReader.readPermute(ann);

        assertThat(config.varName).isEqualTo("i");
        assertThat(config.from).isEqualTo(3);
        assertThat(config.to).isEqualTo(5);
        assertThat(config.className).isEqualTo("Foo${i}");
        assertThat(config.inline).isTrue();
        assertThat(config.keepTemplate).isTrue();
    }
}
```

- [ ] **Run the new tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=InlineGenerationTest 2>&1 | grep -E "(Tests run|BUILD|FAILURE)"
```

Expected: 4 tests pass, BUILD SUCCESS

- [ ] **Run the full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | grep -E "(Tests run|BUILD)"
```

Expected: all tests pass, BUILD SUCCESS

- [ ] **Commit**

```bash
git add permuplate-tests/
git commit -m "test: add InlineGenerationTest for InlineGenerator and AnnotationReader"
```

---

### Task 11: Documentation sweep

**Files:**
- Modify: `README.md`
- Modify: `OVERVIEW.md`
- Modify: `CLAUDE.md`
- Modify: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDeclr.java` (Javadoc reference to inline)
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java` (package note)

- [ ] **Add "APT vs Maven Plugin" section to README**

Insert after the "## Quick start" section and before "## The killer example":

```markdown
---

## APT vs Maven Plugin

Permuplate offers two generation modes. Choose based on what your project needs.

### Annotation Processor (APT) — for top-level generation

The APT is the simplest setup. Add `permuplate-processor` to `annotationProcessorPaths`
and `javac` invokes it automatically. Every permuted class is written as a separate
top-level `.java` file.

**Use the APT when:**
- All your generated classes can be top-level files
- You want minimal build configuration
- You don't need `inline = true`

**Complete `pom.xml` for APT:**
```xml
<dependencies>
    <dependency>
        <groupId>io.quarkiverse.permuplate</groupId>
        <artifactId>quarkus-permuplate-annotations</artifactId>
        <version>VERSION</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.quarkiverse.permuplate</groupId>
                        <artifactId>quarkus-permuplate-processor</artifactId>
                        <version>VERSION</version>
                    </path>
                    <path>
                        <groupId>com.github.javaparser</groupId>
                        <artifactId>javaparser-core</artifactId>
                        <version>3.25.9</version>
                    </path>
                    <path>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-jexl3</artifactId>
                        <version>3.3</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Place template files in `src/main/java/` as normal.

### Maven Plugin — for inline generation (and everything the APT does)

The Maven plugin runs in the `generate-sources` phase, before `javac` touches
anything. It supports all APT functionality **plus** `@Permute(inline = true)`:
permuted classes can be written as nested siblings inside the parent class.

**Use the Maven plugin when:**
- You want generated classes nested inside a parent (`inline = true`)
- You want a pre-compilation approach (plugin runs before javac)
- You are switching from APT and want to keep all existing templates working

> **Important:** Do not configure both the APT processor and the Maven plugin in the
> same project. They would process the same annotations twice, producing duplicate
> generated classes and compile errors.

**Complete `pom.xml` for Maven plugin:**
```xml
<dependencies>
    <dependency>
        <groupId>io.quarkiverse.permuplate</groupId>
        <artifactId>quarkus-permuplate-annotations</artifactId>
        <version>VERSION</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>io.quarkiverse.permuplate</groupId>
            <artifactId>quarkus-permuplate-maven-plugin</artifactId>
            <version>VERSION</version>
            <executions>
                <execution>
                    <goals><goal>generate</goal></goals>
                </execution>
            </executions>
        </plugin>
        <!-- Disable APT — the Maven plugin handles all @Permute processing -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <compilerArgs>
                    <arg>-proc:none</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Template directories:**
- Non-inline templates (`inline = false`, the default): place in `src/main/java/` as normal
- Inline templates (`inline = true`): place in `src/main/permuplate/` (the plugin's template directory)

**IDE setup for `src/main/permuplate/`:**
The template directory is intentionally NOT added as a Maven compile source root
(to prevent javac from compiling templates directly). Mark it as a source root in
your IDE manually:
- **IntelliJ IDEA:** Right-click `src/main/permuplate` → *Mark Directory As → Sources Root*
- **VS Code:** Add the path to your Java project's source directories in `.vscode/settings.json`

---
```

- [ ] **Update `@Permute` `className` prefix rule note in README**

Find the `className` prefix rule note and add: *"This rule does not apply when `className` starts with a variable expression, when `@Permute` is placed on a method, or when `inline = true` (where the leading literal check applies to the nested class name, not the parent)."*

- [ ] **Add `inline` and `keepTemplate` rows to the `@Permute` table in README**

```markdown
| `inline` | `boolean` | Default `false`. When `true`, generates permuted classes as nested siblings inside the parent class rather than separate top-level files. Only valid on nested static classes. Requires `permuplate-maven-plugin`; the APT annotation processor reports an error if this is set. |
| `keepTemplate` | `boolean` | Default `false`. When `true` and `inline = true`, the template class itself is retained in the generated parent alongside the permuted classes. When `false`, the template class is removed (it was only a scaffold). Has no effect when `inline = false`. |
```

- [ ] **Update OVERVIEW.md**

1. Update `@Permute` code block to show `inline` and `keepTemplate` attributes with comments
2. Add `InlineGenerator.java` to module structure for `permuplate-maven-plugin`  
3. Update module structure section to show `permuplate-core` and `permuplate-maven-plugin`
4. Update Testing Strategy table to include `InlineGenerationTest`
5. Remove `permuplate-example` references; replace with `permuplate-apt-examples`
6. Add a paragraph describing `permuplate-maven-plugin` in the Architecture section

- [ ] **Update CLAUDE.md**

1. Update module layout table to show new modules (`permuplate-core`, `permuplate-maven-plugin`, `permuplate-apt-examples`, `permuplate-mvn-examples`)
2. Add row to Key non-obvious decisions: *"Maven plugin null-checks Messager | `PermuteDeclrTransformer` and `PermuteParamTransformer` take `Messager` as a parameter. In the Maven plugin there is no `Messager` — `null` is passed and all `messager.printMessage(...)` calls are null-guarded."*
3. Add row: *"Template directory not on compile path | `src/main/permuplate` (inline templates) is never added as a Maven compile source root. Only `target/generated-sources/permuplate` is. This prevents duplicate class errors. IDE users must mark it manually."*
4. Update Error reporting standard section to note that Maven plugin uses `Log.error(msg)` instead of `Messager`

- [ ] **Run full build and tests**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | grep -E "(Tests run|BUILD|ERROR)" | tail -20
```

Expected: all modules build, all tests pass, BUILD SUCCESS

- [ ] **Final commit**

```bash
git add README.md OVERVIEW.md CLAUDE.md \
        permuplate-annotations/src \
        permuplate-core/src \
        permuplate-maven-plugin/src
git commit -m "docs: comprehensive APT vs Maven Plugin documentation; inline generation javadocs"
```
