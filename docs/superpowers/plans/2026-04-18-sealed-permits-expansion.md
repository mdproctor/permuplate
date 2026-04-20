# Sealed Class `permits` Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In inline generation mode, when the parent CU contains a sealed class or interface whose `permits` clause lists the template class name, automatically replace that single entry with the full list of generated class names.

**Architecture:** Post-generation step in `InlineGenerator.generate()`. After all classes from a template are generated and appended to the output CU, scan the output CU for any `sealed` class/interface containing the template class name in its `permits` list, and expand it to the full generated-name list. No new annotation is needed — the `permits` clause itself serves as the declaration.

**Tech Stack:** Java 17, JavaParser 3.28.0 (`ClassOrInterfaceDeclaration.getPermittedTypes()` / `setPermittedTypes()`), Maven plugin only (APT cannot modify source files).

---

## Design

```java
// src/main/java/io/example/Shape.java — parent class
public class Shape {
    public sealed interface Expr permits ExprTemplate {}  // "ExprTemplate" is the placeholder

    // src/main/permuplate/Shape.java — template
    @Permute(varName="i", from="1", to="3", className="Expr${i}", inline=true)
    static final class ExprTemplate implements Expr {
        public int rank() { return ${i}; }
    }
}
// After generation, target/generated-sources/permuplate/Shape.java contains:
//   sealed interface Expr permits Expr1, Expr2, Expr3 {}
//   static final class Expr1 implements Expr { ... }
//   static final class Expr2 implements Expr { ... }
//   static final class Expr3 implements Expr { ... }
```

The template class name (`ExprTemplate`) is used as the placeholder in `permits`. After all generated names are known (`Expr1`, `Expr2`, `Expr3`), the `permits` entry for `ExprTemplate` is replaced with one entry per generated name.

**Constraint:** This is a Maven plugin (inline) feature only. APT generates new files — it cannot modify existing sealed parent class files. The plan does not touch `PermuteProcessor.java`.

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Modify | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | Add `expandSealedPermits()` call after class generation |
| Create | `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/SealedExprTemplate.java` | Working example (in permuplate-mvn-examples) |
| Create | `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/SealedExprParent.java` | Parent class with sealed interface |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java` | Add regression test |

---

### Task 1: Write the failing test

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java`

- [ ] **Step 1: Read the existing `InlineGenerationTest` to understand the test pattern**

```bash
cat permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java
```

Tests here use `InlineGenerator.generate()` directly. Look at how `parentCu` and template source are constructed.

- [ ] **Step 2: Add the failing test**

Add to `InlineGenerationTest.java`:

```java
@Test
public void testSealedPermitsExpandedForTemplate() {
    // Parent CU has: sealed interface Expr permits ExprTemplate {}
    // Template generates Expr1..Expr3.
    // After generation, permits should be: Expr1, Expr2, Expr3 (not ExprTemplate).
    CompilationUnit parentCu = StaticJavaParser.parse("""
            package io.permuplate.test;
            public class Shape {
                public sealed interface Expr permits ExprTemplate {}
            }
            """);

    CompilationUnit templateCu = StaticJavaParser.parse("""
            package io.permuplate.test;
            import io.quarkiverse.permuplate.Permute;
            public class Shape {
                @Permute(varName="i", from="1", to="3", className="Expr${i}", inline=true)
                static final class ExprTemplate implements Expr {
                    public int rank() { return 1; }
                }
            }
            """);

    ClassOrInterfaceDeclaration templateClass = templateCu
            .findFirst(ClassOrInterfaceDeclaration.class,
                       c -> "ExprTemplate".equals(c.getNameAsString()))
            .orElseThrow();

    PermuteConfig config = PermuteConfig.from(templateClass);
    List<Map<String, Object>> combinations = PermuteConfig.buildAllCombinations(config);

    CompilationUnit result = InlineGenerator.generate(parentCu, templateClass, config, combinations);
    String src = result.toString();

    // permits should list all three generated names, not the template
    assertThat(src).contains("permits Expr1, Expr2, Expr3");
    assertThat(src).doesNotContain("ExprTemplate");
    // Each generated class must be present
    assertThat(src).contains("class Expr1");
    assertThat(src).contains("class Expr2");
    assertThat(src).contains("class Expr3");
}
```

(Import `StaticJavaParser`, `ClassOrInterfaceDeclaration`, `CompilationUnit`, `PermuteConfig`, `InlineGenerator` at the top — look at existing imports in the test file for the exact forms.)

- [ ] **Step 3: Run to confirm it fails**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=InlineGenerationTest#testSealedPermitsExpandedForTemplate -q 2>&1 | tail -10
```

Expected: FAIL — `permits Expr1, Expr2, Expr3` is absent; `ExprTemplate` still appears.

---

### Task 2: Implement `expandSealedPermits()` in `InlineGenerator`

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

- [ ] **Step 1: Find the end of the `generate()` method where generated classes are added**

```bash
grep -n "getTypes\|getMembers\|addType\|addMember\|outputCu\|outputParent\|return output" \
    permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | tail -20
```

Locate where generated class nodes are appended to the output CU (near the end of `generate()`).

- [ ] **Step 2: Add a call to `expandSealedPermits()` after all classes are generated**

Just before the `return` at the end of `generate()`, add:

```java
expandSealedPermits(outputCu, templateClassDecl.getNameAsString(), generatedNames);
```

where `generatedNames` is a `List<String>` of all generated class names (e.g. `["Expr1", "Expr2", "Expr3"]`). Collect this list during the existing generation loop: for each combination, after evaluating `className`, add it to `generatedNames`.

- [ ] **Step 3: Implement `expandSealedPermits()`**

Add as a private static method in `InlineGenerator`:

```java
/**
 * Replaces any {@code permits TemplateName} entry in sealed interfaces/classes within
 * {@code cu} with one entry per generated class name. Called after all classes are generated.
 *
 * @param cu             the output compilation unit
 * @param templateName   the template class simple name (used as placeholder in permits)
 * @param generatedNames all generated class simple names (replacement list)
 */
private static void expandSealedPermits(
        CompilationUnit cu, String templateName, List<String> generatedNames) {

    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
        NodeList<ClassOrInterfaceType> permitted = decl.getPermittedTypes();
        if (permitted.isEmpty())
            return;

        // Find the index of the template placeholder, if present
        int placeholderIdx = -1;
        for (int i = 0; i < permitted.size(); i++) {
            if (templateName.equals(permitted.get(i).getNameAsString())) {
                placeholderIdx = i;
                break;
            }
        }
        if (placeholderIdx < 0)
            return;

        // Remove the placeholder and insert one entry per generated name at that position
        permitted.remove(placeholderIdx);
        for (int j = generatedNames.size() - 1; j >= 0; j--) {
            permitted.add(placeholderIdx,
                    new com.github.javaparser.ast.type.ClassOrInterfaceType(generatedNames.get(j)));
        }
    });
}
```

- [ ] **Step 4: Build the Maven plugin**

```bash
/opt/homebrew/bin/mvn install \
    -pl permuplate-annotations,permuplate-core,permuplate-maven-plugin -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Run the new test**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=InlineGenerationTest#testSealedPermitsExpandedForTemplate -q 2>&1 | tail -5
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 6: Run full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java
git commit -m "feat: auto-expand sealed class permits clause when template name is used as placeholder"
```

---

### Task 3: Add a working example

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/SealedExprParent.java`
- Create: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/SealedExprParent.java`

- [ ] **Step 1: Create the parent class (src/main/java)**

```java
package io.quarkiverse.permuplate.example;

/**
 * Demonstrates sealed interface with auto-expanded permits list.
 * The template generates Expr1, Expr2, Expr3; the permits clause is updated automatically.
 */
public class SealedExprParent {
    public sealed interface Expr permits ExprTemplate {}
}
```

- [ ] **Step 2: Create the template (src/main/permuplate)**

```java
package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;

public class SealedExprParent {
    @Permute(varName = "i", from = "1", to = "3", className = "Expr${i}", inline = true)
    static final class ExprTemplate implements SealedExprParent.Expr {
        private final int rank;
        public ExprTemplate(int rank) { this.rank = rank; }
        public int rank() { return rank; }
    }
}
```

- [ ] **Step 3: Full build including examples**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/SealedExprParent.java \
    "permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/SealedExprParent.java"
git commit -m "example: sealed interface with auto-expanded permits clause"
```

---

### Task 4: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a note to the inline generation section**

Find the `inline = true` section in README and add a paragraph:

```markdown
#### Sealed class `permits` expansion

When a sealed interface or class in the parent uses the template class name as its `permits` placeholder, the Maven plugin automatically expands it to list all generated class names:

```java
// Parent (src/main/java): permits ExprTemplate acts as placeholder
public sealed interface Expr permits ExprTemplate {}

// Template (src/main/permuplate):
@Permute(varName="i", from="1", to="3", className="Expr${i}", inline=true)
static final class ExprTemplate implements Expr { ... }

// Generated (target/generated-sources/permuplate/):
// sealed interface Expr permits Expr1, Expr2, Expr3 {}
```
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document sealed class permits expansion in inline generation section"
```
