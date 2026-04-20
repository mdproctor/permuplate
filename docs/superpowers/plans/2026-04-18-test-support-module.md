# permuplate-test-support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `permuplate-test-support` Maven module with a fluent assertion API that replaces raw `assertThat(src).contains(...)` chains in Permuplate tests.

**Architecture:** New Maven module `permuplate-test-support` added to the parent aggregator. Provides `PermuplateAssertions.assertGenerated(compilation, className)` returning `GeneratedClassAssert`, a fluent wrapper around the source string extracted from the compilation result. The existing `permuplate-tests` module is updated to depend on it.

**Tech Stack:** Java 17, Google `compile-testing` 0.21.0 (already in parent BOM), Google `truth` 1.1.5 (already in parent BOM), Maven.

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Create | `permuplate-test-support/pom.xml` | Module build config |
| Create | `permuplate-test-support/src/main/java/io/quarkiverse/permuplate/testing/PermuplateAssertions.java` | Entry point: `assertGenerated(Compilation, String)` |
| Create | `permuplate-test-support/src/main/java/io/quarkiverse/permuplate/testing/GeneratedClassAssert.java` | Fluent assertion type |
| Modify | `pom.xml` | Add `permuplate-test-support` to `<modules>` |
| Modify | `permuplate-tests/pom.xml` | Add dependency on `permuplate-test-support` |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseTest.java` | Migrate one test as a usage example |

---

### Task 1: Create the Maven module skeleton

**Files:**
- Create: `permuplate-test-support/pom.xml`
- Modify: `pom.xml` (parent)

- [ ] **Step 1: Write the module pom.xml**

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

    <artifactId>quarkus-permuplate-test-support</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.google.testing.compile</groupId>
            <artifactId>compile-testing</artifactId>
            <scope>compile</scope>
        </dependency>
        <dependency>
            <groupId>com.google.truth</groupId>
            <artifactId>truth</artifactId>
            <scope>compile</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Add the module to the parent pom.xml `<modules>` list**

In `/Users/mdproctor/claude/permuplate/pom.xml`, add `<module>permuplate-test-support</module>` after the `permuplate-ide-support` line (before `permuplate-processor`).

- [ ] **Step 3: Verify the module is discovered**

```bash
/opt/homebrew/bin/mvn validate -pl permuplate-test-support -q
```

Expected: BUILD SUCCESS

---

### Task 2: Implement `GeneratedClassAssert`

**Files:**
- Create: `permuplate-test-support/src/main/java/io/quarkiverse/permuplate/testing/GeneratedClassAssert.java`

- [ ] **Step 1: Write the failing test for the assertion type (inline in `PermulateAssertionsTest`)**

Skip — the assertion type is itself a test utility. Validate via integration in Task 4.

- [ ] **Step 2: Implement `GeneratedClassAssert`**

```java
package io.quarkiverse.permuplate.testing;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Fluent assertions over a single generated class's source text.
 *
 * <p>Obtain via {@link PermuplateAssertions#assertGenerated(com.google.testing.compile.Compilation, String)}.
 */
public final class GeneratedClassAssert {

    private final String className;
    private final String source;

    GeneratedClassAssert(String className, String source) {
        this.className = className;
        this.source = source;
    }

    /** Asserts the generated source contains {@code snippet}. */
    public GeneratedClassAssert contains(String snippet) {
        assertWithMessage("generated class %s", className)
                .that(source).contains(snippet);
        return this;
    }

    /** Asserts the generated source does NOT contain {@code snippet}. */
    public GeneratedClassAssert doesNotContain(String snippet) {
        assertWithMessage("generated class %s", className)
                .that(source).doesNotContain(snippet);
        return this;
    }

    /**
     * Asserts the generated source contains a field declaration matching {@code typeAndName}.
     * Example: {@code hasField("Callable3 c3")} checks for {@code "Callable3 c3"}.
     */
    public GeneratedClassAssert hasField(String typeAndName) {
        assertWithMessage("generated class %s should have field [%s]", className, typeAndName)
                .that(source).contains(typeAndName);
        return this;
    }

    /**
     * Asserts the generated source contains a method whose signature starts with {@code methodPrefix}.
     * Example: {@code hasMethod("join(")} checks for any method named {@code join}.
     */
    public GeneratedClassAssert hasMethod(String methodPrefix) {
        assertWithMessage("generated class %s should have method starting with [%s]", className, methodPrefix)
                .that(source).contains(methodPrefix);
        return this;
    }

    /**
     * Asserts the generated source contains a switch case for integer {@code label}.
     * Example: {@code hasCase(3)} checks for {@code "case 3:"}.
     */
    public GeneratedClassAssert hasCase(int label) {
        String caseLabel = "case " + label + ":";
        assertWithMessage("generated class %s should have [%s]", className, caseLabel)
                .that(source).contains(caseLabel);
        return this;
    }

    /**
     * Asserts the generated source contains an import for {@code fqn}.
     * Example: {@code hasImport("java.util.List")} checks for {@code "import java.util.List;"}.
     */
    public GeneratedClassAssert hasImport(String fqn) {
        String importStmt = "import " + fqn + ";";
        assertWithMessage("generated class %s should have [%s]", className, importStmt)
                .that(source).contains(importStmt);
        return this;
    }

    /**
     * Asserts that no Permuplate annotation appears in the generated source.
     * Checks for common annotation simple names.
     */
    public GeneratedClassAssert hasNoPermuplateAnnotations() {
        for (String ann : new String[]{
                "@Permute", "@PermuteCase", "@PermuteStatements", "@PermuteDeclr",
                "@PermuteParam", "@PermuteMethod", "@PermuteReturn", "@PermuteBody"}) {
            assertWithMessage("generated class %s should not contain [%s]", className, ann)
                    .that(source).doesNotContain(ann);
        }
        return this;
    }

    /** Returns the raw generated source text for custom assertions. */
    public String source() {
        return source;
    }
}
```

---

### Task 3: Implement `PermuplateAssertions`

**Files:**
- Create: `permuplate-test-support/src/main/java/io/quarkiverse/permuplate/testing/PermuplateAssertions.java`

- [ ] **Step 1: Implement the entry point**

```java
package io.quarkiverse.permuplate.testing;

import java.io.IOException;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;

/**
 * Static entry point for fluent assertions over Permuplate-generated sources.
 *
 * <p>Usage:
 * <pre>
 * PermuplateAssertions.assertGenerated(compilation, "io.example.Join3")
 *     .hasField("Callable3 c3")
 *     .hasMethod("join(")
 *     .hasCase(3)
 *     .doesNotContain("@PermuteCase");
 * </pre>
 */
public final class PermuplateAssertions {

    private PermuplateAssertions() {}

    /**
     * Entry point. Extracts the generated source for {@code className} from the compilation
     * result and returns a fluent assertion object.
     *
     * @param compilation the result of a Permuplate-annotated compilation
     * @param className the fully-qualified name of the generated class to assert on
     * @throws AssertionError if no generated source file exists for {@code className}
     */
    public static GeneratedClassAssert assertGenerated(Compilation compilation, String className) {
        JavaFileObject file = compilation.generatedSourceFile(className)
                .orElseThrow(() -> new AssertionError(
                        "No generated source file found for class: " + className
                        + "\nGenerated files: " + compilation.generatedSourceFiles()
                                .stream().map(f -> f.getName()).toList()));
        String source = sourceOf(file);
        return new GeneratedClassAssert(className, source);
    }

    private static String sourceOf(JavaFileObject file) {
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new AssertionError("Failed to read generated source for " + file.getName(), e);
        }
    }
}
```

- [ ] **Step 2: Build the module**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-test-support -q
```

Expected: BUILD SUCCESS

---

### Task 4: Add dependency and migrate one test

**Files:**
- Modify: `permuplate-tests/pom.xml`
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseTest.java`

- [ ] **Step 1: Add dependency in permuplate-tests/pom.xml**

Add after the existing `quarkus-permuplate-processor` dependency:

```xml
<dependency>
    <groupId>io.quarkiverse.permuplate</groupId>
    <artifactId>quarkus-permuplate-test-support</artifactId>
    <version>${project.version}</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Add import and migrate `testSwitchCasesAccumulatePerArity` to use fluent API**

At the top of `PermuteCaseTest.java`, add:
```java
import static io.quarkiverse.permuplate.testing.PermuplateAssertions.assertGenerated;
```

Replace the `src2`/`src3` assertion blocks in `testSwitchCasesAccumulatePerArity` with:

```java
assertGenerated(compilation, "io.permuplate.example.Dispatch2")
        .hasCase(0)
        .hasCase(1)
        .contains("return data[1]")
        .doesNotContain("@PermuteCase")
        .doesNotContain("super.get");

assertGenerated(compilation, "io.permuplate.example.Dispatch3")
        .hasCase(0)
        .hasCase(1)
        .hasCase(2)
        .contains("return data[1]")
        .contains("return data[2]")
        .doesNotContain("@PermuteCase");
```

- [ ] **Step 3: Run the migrated test to confirm it passes**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteCaseTest#testSwitchCasesAccumulatePerArity -q 2>&1 | tail -5
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 4: Run full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

Expected: `Tests run: N, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add permuplate-test-support/ pom.xml permuplate-tests/pom.xml \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseTest.java
git commit -m "feat: add permuplate-test-support module with fluent GeneratedClassAssert API"
```

---

### Task 5: Full build verification

- [ ] **Step 1: Full build**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS with all tests passing.
