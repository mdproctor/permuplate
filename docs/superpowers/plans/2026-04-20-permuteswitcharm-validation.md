# @PermuteSwitchArm APT Source-Level Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When `@PermuteSwitchArm` is used in APT mode and the consuming project's source level is below Java 21, emit a clear compiler error pointing at the template class — preventing the confusing javac "patterns in switch are a preview feature" error that would otherwise appear on the generated file. Closes GitHub issue #77, part of epic #75.

**Architecture:** In `PermuteProcessor.processTypePermutation()`, after parsing the source and finding the template class, scan for `@PermuteSwitchArm` on any method. If found and `processingEnv.getSourceVersion().ordinal() < 21`, emit an error at the `typeElement` (class level) with a message that names the annotation and the required source level. Use ordinal comparison to avoid a compile-time reference to `SourceVersion.RELEASE_21` (unavailable when the processor itself compiles with Java 17 tools).

**Tech Stack:** Java 17 (processor compile target), APT `Messager`, `javax.lang.model.SourceVersion`.

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | Add `hasPermuteSwitchArm()` helper + source-level check |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java` | 2 new tests: below-21 error, at-21 success |
| Modify | `CLAUDE.md` | Key decision entry |

---

### Task 1: Write failing tests (TDD first)

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java`

- [ ] **Step 1: Add two tests to `DegenerateInputTest.java`**

Read the existing file first to understand the `compile(Class, String, String)` helper and `PKG` / `PERMUTE_FQN` constants. The two tests use `Compiler.javac().withOptions(...)` to control the source level.

Add in the JEXL expression section (or create a new section `// @PermuteSwitchArm source-level validation`):

```java
// -------------------------------------------------------------------------
// @PermuteSwitchArm: requires Java 21+ source level
// -------------------------------------------------------------------------

@Test
public void testPermuteSwitchArmBelowJava21EmitsError() {
    // Correctness: when compiled with -source 17, @PermuteSwitchArm on a method
    // must emit a clear error pointing at the annotated class (not a confusing
    // javac error on the generated file).
    //
    // Template uses a plain arrow-switch on int — valid from Java 14, so javac
    // itself accepts the source. The error must come from the processor.
    Compilation compilation = Compiler.javac()
            .withOptions("-source", "17")
            .withProcessors(new PermuteProcessor())
            .compile(JavaFileObjects.forSourceString(
                    PKG + ".SwitchArmSource2",
                    """
                    package %s;
                    import %s;
                    import io.quarkiverse.permuplate.PermuteSwitchArm;
                    @Permute(varName = "i", from = "3", to = "3", className = "SwitchArmSource${i}")
                    public class SwitchArmSource2 {
                        @PermuteSwitchArm(varName = "k", from = "1", to = "1",
                                         pattern = "Number n",
                                         body = "yield 0;")
                        public int dispatch(int x) {
                            return switch (x) {
                                default -> -1;
                            };
                        }
                    }
                    """.formatted(PKG, PERMUTE_FQN)));

    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("@PermuteSwitchArm");
    assertThat(compilation).hadErrorContaining("21");
}

@Test
public void testPermuteSwitchArmAtJava21DoesNotError() {
    // Happy path: without -source 17 override (default is 21+ in this project),
    // @PermuteSwitchArm compiles without the source-level error.
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(JavaFileObjects.forSourceString(
                    PKG + ".SwitchArmOk2",
                    """
                    package %s;
                    import %s;
                    import io.quarkiverse.permuplate.PermuteSwitchArm;
                    @Permute(varName = "i", from = "3", to = "3", className = "SwitchArmOk${i}")
                    public class SwitchArmOk2 {
                        @PermuteSwitchArm(varName = "k", from = "1", to = "1",
                                         pattern = "Number n",
                                         body = "yield 0;")
                        public int dispatch(int x) {
                            return switch (x) {
                                default -> -1;
                            };
                        }
                    }
                    """.formatted(PKG, PERMUTE_FQN)));

    assertThat(compilation).succeeded();
    assertThat(compilation.generatedSourceFile(PKG + ".SwitchArmOk3")).isPresent();
}
```

Note: The `withOptions("-source", "17")` test uses a template method body of `switch (x) { default -> -1; }` on `int x` — arrow switch on int is valid since Java 14 and compiles fine with `-source 17`. The processor sees `@PermuteSwitchArm` and should check the source level.

- [ ] **Step 2: Run tests to confirm they fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=DegenerateInputTest#testPermuteSwitchArmBelowJava21EmitsError+testPermuteSwitchArmAtJava21DoesNotError \
    -q 2>&1 | tail -10
```

Expected: `testPermuteSwitchArmBelowJava21EmitsError` fails (compilation currently SUCCEEDS without the check). `testPermuteSwitchArmAtJava21DoesNotError` should already pass.

---

### Task 2: Implement the source-level check in `PermuteProcessor`

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

- [ ] **Step 1: Find the insertion point**

In `PermuteProcessor.java`, find `processTypePermutation()` (around line 164). The template class is parsed and found around lines 331–335:

```java
CompilationUnit templateCu = parseSource(typeElement);
...
Optional<TypeDeclaration<?>> foundForValidation = findTemplateType(templateCu, templateSimpleName);
```

The check must go AFTER `foundForValidation` is established (so we have the AST) and BEFORE the generation loop. Find the line:

```java
List<String> filterExprs = readFilterExpressions(typeElement);
```

Insert the check immediately BEFORE this line.

- [ ] **Step 2: Add the `hasPermuteSwitchArm` helper method**

Add this private static method near the other validation helpers (search for `private static boolean hasPermuteSwitchArm` — doesn't exist yet; add after `validateExtraVars` or similar):

```java
/**
 * Returns true if any method in {@code classDecl} carries a {@code @PermuteSwitchArm}
 * annotation (by simple name or fully-qualified name).
 */
private static boolean hasPermuteSwitchArm(TypeDeclaration<?> classDecl) {
    return classDecl.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
            .stream()
            .anyMatch(m -> m.getAnnotations().stream()
                    .anyMatch(a -> {
                        String n = a.getNameAsString();
                        return n.equals("PermuteSwitchArm")
                            || n.equals("io.quarkiverse.permuplate.PermuteSwitchArm");
                    }));
}
```

- [ ] **Step 3: Add the source-level check in `processTypePermutation`**

Find the line:
```java
List<String> filterExprs = readFilterExpressions(typeElement);
```

Immediately BEFORE it, insert:

```java
        // Validate @PermuteSwitchArm requires Java 21+.
        // Use ordinal comparison (< 21) to avoid a compile-time reference to
        // SourceVersion.RELEASE_21, which is unavailable when building the
        // processor with Java 17 tools.
        Optional<TypeDeclaration<?>> templateDeclOpt = findTemplateType(templateCu, templateSimpleName);
        if (templateDeclOpt.isPresent()
                && hasPermuteSwitchArm(templateDeclOpt.get())
                && processingEnv.getSourceVersion().ordinal() < 21) {
            error("@PermuteSwitchArm generates Java 21 pattern matching syntax. "
                    + "Set --release 21 (or later) in your maven-compiler-plugin "
                    + "configuration (current source level: "
                    + processingEnv.getSourceVersion() + ").",
                    typeElement);
            return;
        }
```

Note: `findTemplateType(templateCu, templateSimpleName)` is already called earlier in the method (for validation purposes, around line 335). Check if the variable `foundForValidation` already holds this result — if so, use it instead of calling `findTemplateType` again. Look for `foundForValidation` in scope.

- [ ] **Step 4: Build the processor**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-core,permuplate-processor -am -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Run the two new tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=DegenerateInputTest#testPermuteSwitchArmBelowJava21EmitsError+testPermuteSwitchArmAtJava21DoesNotError \
    -q 2>&1 | tail -5
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Run the full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

Expected: all tests pass.

- [ ] **Step 7: Stage and commit**

```bash
git add \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java
git commit -m "feat: @PermuteSwitchArm emits clear error when APT source level is below Java 21 (closes #77)"
```

---

### Task 3: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add key decision entry**

In the key decisions table, add:

```
| `@PermuteSwitchArm` APT source-level validation | `PermuteProcessor` checks `processingEnv.getSourceVersion().ordinal() < 21` after parsing the template class and detecting `@PermuteSwitchArm` on any method. Uses ordinal (not `SourceVersion.RELEASE_21` by name) to avoid a compile-time dependency on Java 21 tools when building the processor with Java 17. Error is at element-level (the annotated class). Returns early to skip generating source that javac would reject. |
```

- [ ] **Step 2: Full clean build**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Stage and commit**

```bash
git add CLAUDE.md
git commit -m "docs: @PermuteSwitchArm Java 21 source-level validation documented in CLAUDE.md"
```
