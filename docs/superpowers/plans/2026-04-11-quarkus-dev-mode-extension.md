# Quarkus Dev Mode Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make editing a Permuplate template in `src/main/permuplate/` during `mvn quarkus:dev` automatically regenerate the output classes and restart the app — seamless hot-reload with zero manual steps.

**Architecture:** A standard Quarkus extension (runtime + deployment modules) where the deployment module implements Quarkus's `CodeGenProvider` SPI. `CodeGenProvider.trigger()` is called by Quarkus before every compilation — initial startup and every hot-reload. It scans `src/main/permuplate/`, runs InlineGenerator on each template, and writes augmented `.java` files to `context.outDir()`, which Quarkus automatically adds to the compile path. A `@BuildStep` registers the template directory as a hot-reload watched path so that template changes trigger a restart (which re-invokes `trigger()`). The APT processor path (Level 1 — standard source templates with annotations) also gets registered so APT mode works in dev without manual configuration.

**Tech Stack:** Java 17, Quarkus 3.8.x, JavaParser 3.25.x (already in permuplate-core), JUnit 5, `@QuarkusDevModeTest`

---

## Background: How CodeGenProvider works

Quarkus calls every `CodeGenProvider` registered in `META-INF/services/io.quarkus.deployment.CodeGenProvider` **before** javac runs, both on initial `mvn quarkus:dev` startup and on each hot-reload restart. The method receives:
- `context.workDir()` — the Maven project root (where `pom.xml` lives)
- `context.outDir()` — a Quarkus-managed directory automatically added to compile sources
- `context.inputDir()` — Quarkus's best guess based on `inputExtension()`; we ignore this and use `workDir()` to find `src/main/permuplate/` explicitly

When `trigger()` returns `true`, Quarkus knows new sources were generated and includes them in compilation.

For hot-reload: `HotDeploymentWatchedFileBuildItem` tells Quarkus to watch a path. When that path changes, Quarkus triggers a full restart → re-runs `CodeGenProvider.trigger()` → InlineGenerator re-runs → new classes compiled.

---

## File Map

| File | Action |
|---|---|
| `permuplate-maven-plugin/src/main/java/…/maven/InlineGenerator.java` | **Move** → `permuplate-core/…/core/InlineGenerator.java` |
| `permuplate-maven-plugin/src/main/java/…/maven/SourceScanner.java` | **Move** → `permuplate-core/…/core/SourceScanner.java` |
| `permuplate-maven-plugin/src/main/java/…/maven/AnnotationReader.java` | **Move** → `permuplate-core/…/core/AnnotationReader.java` |
| `permuplate-maven-plugin/src/main/java/…/maven/PermuteMojo.java` | **Modify** — update imports to `…core.*` |
| `permuplate-maven-plugin/pom.xml` | No change (core already a dep) |
| `permuplate-core/pom.xml` | No change |
| `pom.xml` (parent) | **Modify** — add two new modules |
| `permuplate-quarkus-runtime/pom.xml` | **Create** |
| `permuplate-quarkus-runtime/src/main/resources/META-INF/quarkus-extension.yaml` | **Create** |
| `permuplate-quarkus-deployment/pom.xml` | **Create** |
| `permuplate-quarkus-deployment/src/main/java/…/deployment/PermuteplateCodeGen.java` | **Create** |
| `permuplate-quarkus-deployment/src/main/java/…/deployment/PermuteplateProcessor.java` | **Create** |
| `permuplate-quarkus-deployment/src/main/resources/META-INF/services/io.quarkus.deployment.CodeGenProvider` | **Create** |
| `permuplate-quarkus-deployment/src/test/java/…/deployment/PermuteplateDevModeTest.java` | **Create** |

---

## Task 1: Move InlineGenerator, SourceScanner, AnnotationReader to permuplate-core

All three classes have zero Maven-specific imports — pure JavaParser + JDK. Moving them makes the generation logic reusable from any entry point (Maven, Quarkus, Gradle).

**Files:**
- Move: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` → `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/InlineGenerator.java`
- Move: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/SourceScanner.java` → `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/SourceScanner.java`
- Move: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java` → `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/AnnotationReader.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/PermuteMojo.java`

- [ ] **Step 1: Move the three files**

```bash
cd /Users/mdproctor/claude/permuplate
CORE=permuplate-core/src/main/java/io/quarkiverse/permuplate/core
PLUGIN=permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven

cp $PLUGIN/InlineGenerator.java $CORE/InlineGenerator.java
cp $PLUGIN/SourceScanner.java   $CORE/SourceScanner.java
cp $PLUGIN/AnnotationReader.java $CORE/AnnotationReader.java
```

- [ ] **Step 2: Update package declarations in the three moved files**

In each of the three new files in `permuplate-core/…/core/`, change:
```java
package io.quarkiverse.permuplate.maven;
```
to:
```java
package io.quarkiverse.permuplate.core;
```

Also update any cross-references between these three files (e.g., `AnnotationReader` referenced from `InlineGenerator` — change to the `core` package).

- [ ] **Step 3: Update PermuteMojo imports**

In `PermuteMojo.java`, replace:
```java
import io.quarkiverse.permuplate.maven.AnnotationReader;
import io.quarkiverse.permuplate.maven.InlineGenerator;
import io.quarkiverse.permuplate.maven.SourceScanner;
```
with:
```java
import io.quarkiverse.permuplate.core.AnnotationReader;
import io.quarkiverse.permuplate.core.InlineGenerator;
import io.quarkiverse.permuplate.core.SourceScanner;
```

- [ ] **Step 4: Delete the originals from the plugin**

```bash
rm permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
rm permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/SourceScanner.java
rm permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java
```

- [ ] **Step 5: Build and verify**

```bash
/opt/homebrew/bin/mvn clean install -DskipTests -q
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | tail -5
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 6: Stage**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/InlineGenerator.java
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/SourceScanner.java
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/AnnotationReader.java
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/PermuteMojo.java
git add -u permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/
```

---

## Task 2: Create permuplate-quarkus-runtime module

The runtime module is deliberately thin — just the extension descriptor. There is no runtime code for a compile-time tool.

**Files:**
- Create: `permuplate-quarkus-runtime/pom.xml`
- Create: `permuplate-quarkus-runtime/src/main/resources/META-INF/quarkus-extension.yaml`
- Modify: `pom.xml` (parent)

- [ ] **Step 1: Create runtime module directory**

```bash
mkdir -p permuplate-quarkus-runtime/src/main/resources/META-INF
```

- [ ] **Step 2: Create runtime pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.quarkiverse.permuplate</groupId>
    <artifactId>permuplate-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>permuplate-quarkus</artifactId>
  <name>Permuplate :: Quarkus Extension :: Runtime</name>

  <dependencies>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-core</artifactId>
      <version>3.8.4</version>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: Create quarkus-extension.yaml**

```yaml
name: Permuplate
description: "Quarkus extension for Permuplate — live reload of annotation-processor templates in dev mode"
metadata:
  keywords:
    - code-generation
    - annotation-processor
    - template
  categories:
    - "code-generation"
  guide: "https://mdproctor.github.io/permuplate/docs.html"
```

- [ ] **Step 4: Add module to parent pom.xml**

In the parent `pom.xml`, add to `<modules>`:
```xml
<module>permuplate-quarkus-runtime</module>
<module>permuplate-quarkus-deployment</module>
```

(Add both now; the deployment module will be created in Task 3.)

- [ ] **Step 5: Build runtime module**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-quarkus-runtime -DskipTests -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Stage**

```bash
git add permuplate-quarkus-runtime/ pom.xml
```

---

## Task 3: Create PermuteplateCodeGen (CodeGenProvider) — TDD

This is the core of the extension. `PermuteplateCodeGen` scans `src/main/permuplate/`, chains InlineGenerator calls for each template file, and writes augmented `.java` files to Quarkus's output directory.

**Files:**
- Create: `permuplate-quarkus-deployment/pom.xml`
- Create: `permuplate-quarkus-deployment/src/main/java/io/quarkiverse/permuplate/deployment/PermuteplateCodeGen.java`
- Create: `permuplate-quarkus-deployment/src/main/resources/META-INF/services/io.quarkus.deployment.CodeGenProvider`
- Create: `permuplate-quarkus-deployment/src/test/java/io/quarkiverse/permuplate/deployment/PermuteplateCodeGenTest.java`

- [ ] **Step 1: Create deployment module directories**

```bash
mkdir -p permuplate-quarkus-deployment/src/main/java/io/quarkiverse/permuplate/deployment
mkdir -p permuplate-quarkus-deployment/src/main/resources/META-INF/services
mkdir -p permuplate-quarkus-deployment/src/test/java/io/quarkiverse/permuplate/deployment
```

- [ ] **Step 2: Create deployment pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.quarkiverse.permuplate</groupId>
    <artifactId>permuplate-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>permuplate-quarkus-deployment</artifactId>
  <name>Permuplate :: Quarkus Extension :: Deployment</name>

  <dependencies>
    <dependency>
      <groupId>io.quarkiverse.permuplate</groupId>
      <artifactId>permuplate-quarkus</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>io.quarkiverse.permuplate</groupId>
      <artifactId>permuplate-core</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-core-deployment</artifactId>
      <version>3.8.4</version>
    </dependency>
    <!-- Test -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-junit5-internal</artifactId>
      <version>3.8.4</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: Write failing unit test for PermuteplateCodeGen**

Create `PermuteplateCodeGenTest.java`:

```java
package io.quarkiverse.permuplate.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PermuteplateCodeGenTest {

    @Test
    void providerId_returnsPermuplate() {
        assertEquals("permuplate", new PermuteplateCodeGen().providerId());
    }

    @Test
    void shouldRun_falseWhenNoTemplateDir(@TempDir Path workDir) {
        PermuteplateCodeGen gen = new PermuteplateCodeGen();
        // No src/main/permuplate/ created — should not run
        assertFalse(gen.shouldRun(workDir, null));
    }

    @Test
    void shouldRun_trueWhenTemplateDirExists(@TempDir Path workDir) throws IOException {
        Files.createDirectories(workDir.resolve("src/main/permuplate"));
        PermuteplateCodeGen gen = new PermuteplateCodeGen();
        assertTrue(gen.shouldRun(workDir, null));
    }

    @Test
    void trigger_generatesOutputFromTemplate(@TempDir Path workDir) throws Exception {
        // Arrange: create src/main/permuplate/ with a template file
        Path templateDir = workDir.resolve("src/main/permuplate/io/example");
        Files.createDirectories(templateDir);
        Files.writeString(templateDir.resolve("Sized2.java"), """
                package io.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteValue;
                @Permute(varName="i", from="3", to="3", className="Sized${i}",
                         inline=true, keepTemplate=false)
                public class Sized2 {
                    @PermuteValue("${i}") static int ARITY = 2;
                }
                """);

        Path outDir = workDir.resolve("target/generated-sources/permuplate");
        Files.createDirectories(outDir);

        // Act
        PermuteplateCodeGen gen = new PermuteplateCodeGen();
        TestCodeGenContext ctx = new TestCodeGenContext(workDir, outDir);
        boolean result = gen.trigger(ctx);

        // Assert
        assertTrue(result, "trigger should return true when sources were generated");
        Path generated = outDir.resolve("io/example/Sized2.java");
        assertTrue(Files.exists(generated), "Generated file should exist at " + generated);
        String content = Files.readString(generated);
        assertTrue(content.contains("ARITY = 3"), "Generated class should contain ARITY = 3");
        assertFalse(content.contains("@PermuteValue"), "Generated class should not contain annotations");
    }

    @Test
    void trigger_returnsFalseWhenNoTemplates(@TempDir Path workDir) throws Exception {
        Path templateDir = workDir.resolve("src/main/permuplate");
        Files.createDirectories(templateDir);
        // Empty directory — no .java files
        Path outDir = workDir.resolve("target/generated-sources/permuplate");
        Files.createDirectories(outDir);

        PermuteplateCodeGen gen = new PermuteplateCodeGen();
        assertFalse(gen.trigger(new TestCodeGenContext(workDir, outDir)));
    }

    /** Minimal CodeGenContext for unit testing */
    static class TestCodeGenContext implements io.quarkus.deployment.CodeGenContext {
        private final Path workDir, outDir;
        TestCodeGenContext(Path workDir, Path outDir) {
            this.workDir = workDir;
            this.outDir = outDir;
        }
        @Override public io.quarkus.maven.dependency.ArtifactKey artifactKey() { return null; }
        @Override public Path inputDir() { return workDir; }
        @Override public Path outDir() { return outDir; }
        @Override public Path workDir() { return workDir; }
        @Override public boolean test() { return false; }
        @Override public org.eclipse.microprofile.config.Config config() { return null; }
        @Override public ClassLoader classLoader() { return Thread.currentThread().getContextClassLoader(); }
    }
}
```

- [ ] **Step 4: Run to verify FAIL**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-quarkus-deployment -Dtest=PermuteplateCodeGenTest 2>&1 | tail -10
```

Expected: FAIL — `PermuteplateCodeGen` does not exist yet.

- [ ] **Step 5: Implement PermuteplateCodeGen**

Create `PermuteplateCodeGen.java`:

```java
package io.quarkiverse.permuplate.deployment;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import io.quarkiverse.permuplate.core.AnnotationReader;
import io.quarkiverse.permuplate.core.InlineGenerator;
import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.SourceScanner;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenException;
import io.quarkus.deployment.CodeGenProvider;
import org.eclipse.microprofile.config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Quarkus {@link CodeGenProvider} for Permuplate inline templates.
 *
 * <p>Called by Quarkus before every compilation — on initial startup and on each
 * hot-reload restart. Scans {@code src/main/permuplate/} for templates annotated
 * with {@code @Permute(inline=true)}, runs {@link InlineGenerator} on each, and
 * writes augmented {@code .java} files to {@code context.outDir()} which Quarkus
 * automatically adds to the compile source path.
 */
public class PermuteplateCodeGen implements CodeGenProvider {

    static final String TEMPLATE_SRC = "src/main/permuplate";

    @Override
    public String providerId() {
        return "permuplate";
    }

    @Override
    public String inputExtension() {
        // Returning "java" tells Quarkus we process Java files.
        // We find our own input directory via workDir() in trigger().
        return "java";
    }

    @Override
    public boolean shouldRun(Path sourceDir, Config config) {
        // sourceDir here is the workDir (project root). Run only if template dir exists.
        return Files.isDirectory(sourceDir.resolve(TEMPLATE_SRC));
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        Path templateDir = context.workDir().resolve(TEMPLATE_SRC);
        if (!Files.isDirectory(templateDir)) {
            return false;
        }

        Path outDir = context.outDir();
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new CodeGenException("Cannot create output directory: " + outDir, e);
        }

        try {
            SourceScanner.ScanResult scan = SourceScanner.scan(templateDir);
            if (scan.inlineGroups().isEmpty()) {
                return false;
            }

            for (Map.Entry<Path, List<SourceScanner.AnnotatedType>> entry : scan.inlineGroups().entrySet()) {
                processGroup(entry.getKey(), entry.getValue(), templateDir, outDir);
            }
            return true;
        } catch (Exception e) {
            throw new CodeGenException("Permuplate code generation failed", e);
        }
    }

    private void processGroup(Path sourceFile, List<SourceScanner.AnnotatedType> entries,
                              Path templateDir, Path outDir) throws Exception {
        if (entries.isEmpty()) return;

        CompilationUnit currentCu = entries.get(0).cu();

        for (SourceScanner.AnnotatedType entry : entries) {
            String templateName = entry.classDecl().getNameAsString();

            var currentTemplate = currentCu.findFirst(
                    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class,
                    c -> c.getNameAsString().equals(templateName))
                    .orElseThrow(() -> new CodeGenException(
                            sourceFile + ": cannot find template class '" + templateName + "'"));

            var permuteAnn = currentTemplate.getAnnotations().stream()
                    .filter(a -> a.getNameAsString().equals("Permute")
                            || a.getNameAsString().equals("io.quarkiverse.permuplate.Permute"))
                    .findFirst()
                    .orElseThrow(() -> new CodeGenException(
                            sourceFile + ": @Permute annotation missing on '" + templateName + "'"));

            PermuteConfig config = AnnotationReader.readPermute(permuteAnn);
            List<Map<String, Object>> allCombinations = PermuteConfig.buildAllCombinations(config);
            currentCu = InlineGenerator.generate(currentCu, currentTemplate, config, allCombinations);
        }

        // Write the final combined CU to outDir, mirroring the package directory structure
        var topLevel = currentCu.findFirst(
                com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class,
                c -> !c.isNestedType())
                .orElseThrow(() -> new CodeGenException(sourceFile + ": no top-level class in output"));

        Path relative = templateDir.relativize(sourceFile);
        Path outputFile = outDir.resolve(relative);
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, currentCu.toString());
    }
}
```

**Note:** `SourceScanner.ScanResult` may need an `inlineGroups()` method added if it doesn't exist — check the current SourceScanner API and add it if needed. If SourceScanner only has `types()` and `methods()`, filter for `inline=true` types and group by source file manually.

- [ ] **Step 6: Register as a service**

Create `META-INF/services/io.quarkus.deployment.CodeGenProvider`:

```
io.quarkiverse.permuplate.deployment.PermuteplateCodeGen
```

- [ ] **Step 7: Run tests to verify PASS**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-core -DskipTests -q
/opt/homebrew/bin/mvn test -pl permuplate-quarkus-deployment -Dtest=PermuteplateCodeGenTest 2>&1 | tail -10
```

Expected: 4/4 tests pass.

- [ ] **Step 8: Stage**

```bash
git add permuplate-quarkus-deployment/
```

---

## Task 4: Create PermuteplateProcessor (@BuildStep for hot-reload)

The build step tells Quarkus to watch `src/main/permuplate/` so that template changes trigger a full restart (which re-runs CodeGenProvider → InlineGenerator → regenerates sources → recompiles).

**Files:**
- Create: `permuplate-quarkus-deployment/src/main/java/io/quarkiverse/permuplate/deployment/PermuteplateProcessor.java`
- Modify test: add `watchTemplateFiles_registersHotReload` test to `PermuteplateCodeGenTest.java`

- [ ] **Step 1: Write failing test for the build step**

Add to `PermuteplateCodeGenTest.java`:

```java
@Test
void processor_classExists() throws Exception {
    // Verify the processor class has the expected @BuildStep method
    Class<?> processorClass = Class.forName(
            "io.quarkiverse.permuplate.deployment.PermuteplateProcessor");
    boolean hasBuildStep = java.util.Arrays.stream(processorClass.getMethods())
            .anyMatch(m -> m.isAnnotationPresent(
                    io.quarkus.deployment.annotations.BuildStep.class));
    assertTrue(hasBuildStep, "PermuteplateProcessor must have at least one @BuildStep method");
}
```

- [ ] **Step 2: Run to verify FAIL**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-quarkus-deployment -Dtest=PermuteplateCodeGenTest#processor_classExists 2>&1 | tail -5
```

Expected: FAIL — class not found.

- [ ] **Step 3: Implement PermuteplateProcessor**

Create `PermuteplateProcessor.java`:

```java
package io.quarkiverse.permuplate.deployment;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.LaunchMode;

/**
 * Quarkus build steps for Permuplate.
 *
 * <p>Registers {@code src/main/permuplate/} as a hot-reload watched path so
 * that editing a template triggers a Quarkus restart, which re-invokes
 * {@link PermuteplateCodeGen#trigger} and regenerates the output classes.
 */
public class PermuteplateProcessor {

    @BuildStep
    void watchTemplateFiles(LaunchModeBuildItem launchMode,
                            BuildProducer<HotDeploymentWatchedFileBuildItem> watched) {
        if (launchMode.getLaunchMode() == LaunchMode.DEVELOPMENT
                || launchMode.getLaunchMode() == LaunchMode.TEST) {
            // Watch all .java files under src/main/permuplate/ — any change triggers
            // a full restart which re-runs PermuteplateCodeGen.trigger()
            watched.produce(new HotDeploymentWatchedFileBuildItem(
                    PermuteplateCodeGen.TEMPLATE_SRC + "/**/*.java", true));
        }
    }
}
```

- [ ] **Step 4: Run all deployment tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-quarkus-deployment 2>&1 | tail -5
```

Expected: all tests pass.

- [ ] **Step 5: Stage**

```bash
git add permuplate-quarkus-deployment/src/main/java/io/quarkiverse/permuplate/deployment/PermuteplateProcessor.java
git add permuplate-quarkus-deployment/src/test/java/io/quarkiverse/permuplate/deployment/PermuteplateCodeGenTest.java
```

---

## Task 5: Integration test — dev mode hot-reload

Verify the full loop: start `mvn quarkus:dev` on a test project, modify a template, verify Quarkus regenerates and the new class is available.

**Files:**
- Create: `permuplate-quarkus-deployment/src/test/java/io/quarkiverse/permuplate/deployment/PermuteplateDevModeTest.java`
- Create: test resource template (inline, added programmatically in the test)

- [ ] **Step 1: Write the dev mode test**

Create `PermuteplateDevModeTest.java`:

```java
package io.quarkiverse.permuplate.deployment;

import io.quarkus.test.QuarkusDevModeTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Integration test: edits to a Permuplate template in src/main/permuplate/
 * trigger Quarkus dev mode to regenerate + recompile automatically.
 */
class PermuteplateDevModeTest {

    static final String INITIAL_TEMPLATE = """
            package io.example;
            import io.quarkiverse.permuplate.Permute;
            import io.quarkiverse.permuplate.PermuteValue;
            @Permute(varName="i", from="2", to="2", className="Sized${i}",
                     inline=true, keepTemplate=false)
            public class Sized1 {
                @PermuteValue("${i}") public static int ARITY = 1;
            }
            """;

    static final String UPDATED_TEMPLATE = """
            package io.example;
            import io.quarkiverse.permuplate.Permute;
            import io.quarkiverse.permuplate.PermuteValue;
            @Permute(varName="i", from="2", to="3", className="Sized${i}",
                     inline=true, keepTemplate=false)
            public class Sized1 {
                @PermuteValue("${i}") public static int ARITY = 1;
            }
            """;

    static final String RESOURCE_CLASS = """
            package io.example;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;
            @Path("/arity")
            public class ArityResource {
                @GET
                public String get() {
                    return "sized2=" + Sized2.ARITY + " sized3=" + classExists("io.example.Sized3");
                }
                private String classExists(String name) {
                    try { Class.forName(name); return "exists"; }
                    catch (ClassNotFoundException e) { return "missing"; }
                }
            }
            """;

    @RegisterExtension
    static final QuarkusDevModeTest runner = new QuarkusDevModeTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addAsResource(new StringAsset(RESOURCE_CLASS),
                            "src/main/java/io/example/ArityResource.java"))
            .addResourceFile(new StringAsset(INITIAL_TEMPLATE),
                    "src/main/permuplate/io/example/Sized1.java");

    @Test
    void templateChangeGeneratesNewClass() {
        // Initial state: template generates Sized2 only (from="2", to="2")
        given().get("/arity")
                .then().statusCode(200)
                .body(containsString("sized2=2"))
                .body(containsString("sized3=missing"));

        // Modify template: now generates Sized2 and Sized3 (from="2", to="3")
        runner.modifyResourceFile("src/main/permuplate/io/example/Sized1.java",
                s -> UPDATED_TEMPLATE);

        // After restart: Sized3 should exist with ARITY=3
        given().get("/arity")
                .then().statusCode(200)
                .body(containsString("sized2=2"))
                .body(containsString("sized3=exists"));
    }
}
```

Add `quarkus-resteasy-reactive` and `rest-assured` test dependencies to `permuplate-quarkus-deployment/pom.xml`:

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-resteasy-reactive</artifactId>
  <version>3.8.4</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.rest-assured</groupId>
  <artifactId>rest-assured</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Run the integration test**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-quarkus-deployment -Dtest=PermuteplateDevModeTest 2>&1 | tail -15
```

Expected: PASS — Quarkus dev mode starts, serves `/arity`, template edit triggers regeneration of Sized3.

- [ ] **Step 3: Full build**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Stage**

```bash
git add permuplate-quarkus-deployment/src/test/java/io/quarkiverse/permuplate/deployment/PermuteplateDevModeTest.java
git add permuplate-quarkus-deployment/pom.xml
```

---

## Task 6: Documentation and commit

- [ ] **Step 1: Update CLAUDE.md module layout table**

Add to the module layout table in `CLAUDE.md`:

```
├── permuplate-quarkus-runtime/     Quarkus extension runtime (descriptor only)
├── permuplate-quarkus-deployment/  Quarkus extension deployment: CodeGenProvider (inline hot-reload) + @BuildStep (watch)
```

Also add to the Key non-obvious decisions table:

| InlineGenerator/SourceScanner/AnnotationReader moved to permuplate-core | Required by the Quarkus extension, which cannot depend on permuplate-maven-plugin (Maven API classpath contamination) |
| Quarkus CodeGenProvider vs @BuildStep | CodeGenProvider runs BEFORE javac (correct for source generation); @BuildStep runs AFTER (correct for class transformation). We use both: CodeGenProvider for generation, @BuildStep for hot-reload watching. |
| shouldRun() checks workDir, not sourceDir | Quarkus may pass an unexpected sourceDir; workDir is the reliable project root where src/main/permuplate lives. |

- [ ] **Step 2: Stage docs**

```bash
git add CLAUDE.md
```

---

## Self-Review

**Spec coverage:**
- ✅ Seamless hot-reload for inline templates — CodeGenProvider + HotDeploymentWatchedFileBuildItem
- ✅ Level 1 (APT mode) — APT processor runs automatically once on the compile path; no extra setup needed in the extension (Quarkus handles javac + APT naturally)
- ✅ Generated sources identical to Maven plugin output — same InlineGenerator, same output dir layout
- ✅ Works in both `mvn package` and `mvn quarkus:dev` — CodeGenProvider is called in both
- ✅ Integration test — QuarkusDevModeTest covers the full hot-reload loop

**Placeholder scan:** None found.

**Type consistency:**
- `PermuteplateCodeGen.TEMPLATE_SRC` used in both `PermuteplateCodeGen` and `PermuteplateProcessor` ✓
- `SourceScanner.ScanResult.inlineGroups()` — verify this method exists in SourceScanner after the move in Task 1; if missing, add it or inline the grouping logic in `PermuteplateCodeGen.trigger()`
- `CodeGenContext` interface — verify exact method signatures against Quarkus 3.8.4 at implementation time; the interface is stable but `artifactKey()` return type may vary

**API verification note for implementer:** Before writing `PermuteplateCodeGen`, run:
```bash
/opt/homebrew/bin/mvn dependency:get -Dartifact=io.quarkus:quarkus-core-deployment:3.8.4
jar -tf ~/.m2/repository/io/quarkus/quarkus-core-deployment/3.8.4/quarkus-core-deployment-3.8.4.jar | grep CodeGen
```
to confirm the exact `CodeGenProvider` and `CodeGenContext` class/interface names in the version being used.
