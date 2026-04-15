# Record Template Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make records first-class `@Permute` templates with full annotation parity — `@PermuteDeclr`, `@PermuteParam`, `@PermuteTypeParam`, and all other transformers work on records in both APT and Maven plugin inline mode.

**Architecture:** Generalize every transformer method signature in `permuplate-core` from `ClassOrInterfaceDeclaration` to the common supertype `TypeDeclaration<?>`. Configure `StaticJavaParser` for Java 17 so record syntax parses. Update `PermuteProcessor` and `InlineGenerator` to find and process `RecordDeclaration` alongside `ClassOrInterfaceDeclaration`. Add `transformRecordComponents()` to `PermuteParamTransformer` to handle `@PermuteParam` on record components (the `RecordDeclaration.getParameters()` list).

**Tech Stack:** Java 17, JavaParser, JUnit 4, Google compile-testing.

**GitHub:** Refs #29 (created in Task 1), epic TBD.

---

## File Map

| File | Change |
|---|---|
| `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | Language level in `init()`; `findFirst` handles `RecordDeclaration`; internal methods generalized to `TypeDeclaration<?>`; record-specific branches |
| `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | Language level static init; `generate()` accepts `TypeDeclaration<?>`; record-specific branches |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java` | `transform()` + `validatePrefixes()` signatures → `TypeDeclaration<?>` |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java` | `transform()` + `validatePrefixes()` → `TypeDeclaration<?>`; add `transformRecordComponents()` |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java` | Both `transform()` overloads → `TypeDeclaration<?>` |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java` | `transform()` → `TypeDeclaration<?>` |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java` | `transform()` → `TypeDeclaration<?>` |
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteValueTransformer.java` | `transform()` → `TypeDeclaration<?>` |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/RecordExpansionTest.java` | Replace blocker-documenting tests with 4 working tests |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java` | Add record inline mode test |

---

## Task 1: Create issue + fix Blocker 1 + write failing tests

**Files:**
- Test: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/RecordExpansionTest.java`
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

- [ ] **Step 1.1: Create GitHub issue**

```bash
gh issue create --repo mdproctor/permuplate \
  --title "Record template support — full @Permute parity for records" \
  --label "enhancement" \
  --body "$(cat <<'EOF'
## Problem
Records cannot be used as @Permute templates. Two blockers:
1. StaticJavaParser defaults to Java 11 (predates records)
2. All transformers use ClassOrInterfaceDeclaration; RecordDeclaration is a sibling type

## Solution
- Configure StaticJavaParser for Java 17+ in PermuteProcessor.init()
- Generalize all transformer signatures from ClassOrInterfaceDeclaration to TypeDeclaration<?>
- Update PermuteProcessor and InlineGenerator to find RecordDeclaration alongside ClassOrInterfaceDeclaration
- Add transformRecordComponents() to PermuteParamTransformer for @PermuteParam on record components

## Acceptance criteria
- Basic record permutation (@Permute on record) generates correctly named records
- @PermuteDeclr on record component renames its type
- @PermuteTypeParam on record type parameter expands correctly
- @PermuteParam on record component expands the component list (Tuple pattern)
- All above work in inline mode (Maven plugin)
- All 181 existing tests continue passing
EOF
)"
```

Note the issue number output — use it as `#N` in all subsequent commits.

- [ ] **Step 1.2: Fix Blocker 1 — configure Java 17 language level**

Read `PermuteProcessor.java`. Find the `init(ProcessingEnvironment processingEnv)` method. Add at the start of `init()`, before any existing code:

```java
// Enable Java 17 syntax (records, sealed classes, etc.) in JavaParser
com.github.javaparser.StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
```

- [ ] **Step 1.3: Rewrite RecordExpansionTest.java with failing tests**

Replace the entire content of `RecordExpansionTest.java` with:

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.quarkiverse.permuplate.processor.PermuteProcessor;
import org.junit.Test;

/**
 * Verifies that record declarations work as Permuplate templates with full parity.
 * Two blockers were fixed: (1) StaticJavaParser language level set to JAVA_17
 * in PermuteProcessor.init(); (2) transformer signatures generalized to TypeDeclaration<?>.
 */
public class RecordExpansionTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    /**
     * Basic record permutation: @Permute on a record with fixed components
     * generates correctly named records with all components preserved.
     *
     * Template: Point2D (sentinel) generates Point3D and Point4D.
     * from=3 avoids collision between template name Point2D and generated Point2D.
     */
    @Test
    public void testBasicRecordPermutation() {
        Compilation compilation = compile("io.example.Point2D",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Point${i}D\")\n" +
                "public record Point2D(double x, double y) {}");

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("io.example.Point3D").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.Point4D").isPresent()).isTrue();
        String p3 = sourceOf(compilation.generatedSourceFile("io.example.Point3D").get());
        assertThat(p3).contains("record Point3D");
        assertThat(p3).contains("double x");
        assertThat(p3).contains("double y");
    }

    /**
     * @PermuteDeclr(type="${T}") on a record component renames the component's type
     * per permutation. Uses string-set iteration (values={"String","Integer"}).
     *
     * StringBox has component: String value
     * IntegerBox has component: Integer value
     */
    @Test
    public void testRecordWithPermuteDeclrOnComponent() {
        Compilation compilation = compile("io.example.Box2",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteDeclr;\n" +
                "@Permute(varName=\"T\", values={\"String\",\"Integer\"}, className=\"${T}Box\")\n" +
                "public record Box2(\n" +
                "    @PermuteDeclr(type=\"${T}\") Object value\n" +
                ") {}");

        assertThat(compilation).succeeded();
        String strSrc = sourceOf(compilation.generatedSourceFile("io.example.StringBox").get());
        assertThat(strSrc).contains("record StringBox");
        assertThat(strSrc).contains("String value");
        String intSrc = sourceOf(compilation.generatedSourceFile("io.example.IntegerBox").get());
        assertThat(intSrc).contains("record IntegerBox");
        assertThat(intSrc).contains("Integer value");
    }

    /**
     * @PermuteTypeParam on a record's type parameter expands correctly.
     * Template: Wrapper2<A>(A item) with @PermuteTypeParam(from=1,to="${i}").
     * i=3: Wrapper3<A,B,C>(A item)
     */
    @Test
    public void testRecordWithPermuteTypeParam() {
        Compilation compilation = compile("io.example.Wrapper2",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteTypeParam;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"3\", className=\"Wrapper${i}\")\n" +
                "public record Wrapper2<\n" +
                "    @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n" +
                "                      name=\"${alpha(k)}\") A\n" +
                ">(A item) {}");

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.example.Wrapper3").get());
        assertThat(src).contains("record Wrapper3");
        // Type parameters A, B, C should be present
        assertThat(src).contains("A");
        assertThat(src).contains("B");
        assertThat(src).contains("C");
        assertThat(src).contains("item");
    }

    /**
     * @PermuteParam on a record component expands the component list — the Tuple pattern.
     * Template: Tuple2<A>(A a) → Tuple3<A,B,C>(A a, B b, C c), Tuple4<A,B,C,D>(A a, B b, C c, D d).
     *
     * This requires transformRecordComponents() in PermuteParamTransformer.
     */
    @Test
    public void testRecordWithPermuteParam() {
        Compilation compilation = compile("io.example.Tuple2",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteParam;\n" +
                "import io.quarkiverse.permuplate.PermuteTypeParam;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Tuple${i}\")\n" +
                "public record Tuple2<\n" +
                "    @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n" +
                "                      name=\"${alpha(k)}\") A\n" +
                ">(\n" +
                "    @PermuteParam(varName=\"j\", from=\"1\", to=\"${i}\",\n" +
                "                  type=\"${alpha(j)}\", name=\"${lower(j)}\")\n" +
                "    A a\n" +
                ") {}");

        assertThat(compilation).succeeded();
        String t3 = sourceOf(compilation.generatedSourceFile("io.example.Tuple3").get());
        assertThat(t3).contains("record Tuple3");
        assertThat(t3).contains("A a");
        assertThat(t3).contains("B b");
        assertThat(t3).contains("C c");
        String t4 = sourceOf(compilation.generatedSourceFile("io.example.Tuple4").get());
        assertThat(t4).contains("record Tuple4");
        assertThat(t4).contains("D d");
    }
}
```

- [ ] **Step 1.4: Run tests — expect 4 FAIL (blockers), all other 181 PASS**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="RecordExpansionTest" 2>&1 | tail -20
```

Expected: 4 test errors/failures (records not yet handled in processor despite language level fix).
The 4 tests should fail — some with compile errors (Blocker 2 still present), not with ParseProblemException (Blocker 1 is now fixed).

Also run the full suite to confirm no regressions:
```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS (181 existing tests pass; 4 new ones fail — but maven-failsafe won't stop this unless you use `-Dtest=` scoping above).

- [ ] **Step 1.5: Stage and commit**

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/RecordExpansionTest.java
git commit -m "feat: fix Blocker 1 (Java 17 language level) + add record template failing tests

Refs #29.

StaticJavaParser configured for Java 17 in PermuteProcessor.init() — records
now parse without ParseProblemException. RecordExpansionTest rewritten with 4
target-state tests (all failing until Blocker 2 is resolved in subsequent tasks).

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Generalize transformer signatures in permuplate-core

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java`
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java`
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteValueTransformer.java`
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java`
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java`
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java`

**Important:** Read each file in full before modifying it. The method body may have `ClassOrInterfaceDeclaration`-specific method calls that need casting.

- [ ] **Step 2.1: For each of the 6 transformer files, apply the signature change pattern**

The change for every public method that takes `ClassOrInterfaceDeclaration classDecl`:

```java
// BEFORE:
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

public static void transform(ClassOrInterfaceDeclaration classDecl,
        EvaluationContext ctx, ...) { ... }

// AFTER:
import com.github.javaparser.ast.body.TypeDeclaration;
// (keep ClassOrInterfaceDeclaration import if used in the body for instanceof checks)

public static void transform(TypeDeclaration<?> classDecl,
        EvaluationContext ctx, ...) { ... }
```

For **`PermuteCaseTransformer`**, **`PermuteStatementsTransformer`**, and **`PermuteValueTransformer`**: these use `classDecl.findAll(MethodDeclaration.class)` and `classDecl.findAll(ConstructorDeclaration.class)` — both are on the `Node` base class, so they work with `TypeDeclaration<?>` without casts.

For **`PermuteTypeParamTransformer`**: it has two `transform()` overloads. Both take `ClassOrInterfaceDeclaration`. Read the file — it may use `classDecl.getTypeParameters()` (on `TypeDeclaration<?>`) and `classDecl.isInterface()` (class-specific — wrap in instanceof check). Change both overloads.

For **`PermuteDeclrTransformer`**: `transform()` and `validatePrefixes()` both change. Read the file — it uses `classDecl.getFields()` (on `TypeDeclaration<?>`), `classDecl.walk(FieldDeclaration.class)` (on `Node`), `classDecl.getConstructors()` (on `TypeDeclaration<?>`). All safe. `validatePrefixes` may use methods specific to `ClassOrInterfaceDeclaration` — read carefully and add instanceof casts if needed.

For **`PermuteParamTransformer`**: `transform()` and `validatePrefixes()` both change. Body uses `classDecl.findAll(MethodDeclaration.class)` and `classDecl.findAll(ConstructorDeclaration.class)` — both on `Node`, safe.

- [ ] **Step 2.2: Update import statements**

In each changed file, add `import com.github.javaparser.ast.body.TypeDeclaration;`. Keep `import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;` only if it's still used in the body (for instanceof checks or casts).

- [ ] **Step 2.3: Fix any call sites in the same file that break**

After changing a method signature, the calls to it from within the same file may break if they pass `ClassOrInterfaceDeclaration` to the now-`TypeDeclaration<?>` parameter. Since `ClassOrInterfaceDeclaration extends TypeDeclaration<?>`, callers that pass a `ClassOrInterfaceDeclaration` still compile without changes.

- [ ] **Step 2.4: Run full Maven test suite — all 181 existing tests must still pass**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 181 tests. The 4 new RecordExpansionTest tests are still failing (that's correct — the processor isn't updated yet).

Note: The Maven build will re-compile `permuplate-core` whose callers in `permuplate-processor` and `permuplate-maven-plugin` pass `ClassOrInterfaceDeclaration` (a subtype of `TypeDeclaration<?>`), so the callers still compile.

- [ ] **Step 2.5: Stage and commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java \
        permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java \
        permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java \
        permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java \
        permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteStatementsTransformer.java \
        permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteValueTransformer.java
git commit -m "refactor(core): generalize transformer signatures from ClassOrInterfaceDeclaration to TypeDeclaration<?>

Refs #29.

All 6 transformers now accept TypeDeclaration<?> — the common supertype of
ClassOrInterfaceDeclaration and RecordDeclaration. Existing class/interface
templates are unaffected (ClassOrInterfaceDeclaration is a subtype).

181 tests pass, 4 RecordExpansionTest tests still failing (processor not yet updated).

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Update PermuteProcessor to find and process RecordDeclaration

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

**Read the full file before making changes.** This is the most complex task.

- [ ] **Step 3.1: Add RecordDeclaration import**

Add at the top of `PermuteProcessor.java`:

```java
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
```

- [ ] **Step 3.2: Add private helper to find either type**

Add this private helper method:

```java
    /** Finds a class, interface, or record by simple name in the given compilation unit. */
    private static Optional<TypeDeclaration<?>> findTemplateType(
            com.github.javaparser.ast.CompilationUnit cu, String simpleName) {
        return cu.findFirst(ClassOrInterfaceDeclaration.class,
                        c -> c.getNameAsString().equals(simpleName))
                .<TypeDeclaration<?>>map(c -> c)
                .or(() -> cu.findFirst(RecordDeclaration.class,
                        r -> r.getNameAsString().equals(simpleName)));
    }

    /** Finds a nested type (class, interface, or record) by simple name. */
    private static Optional<TypeDeclaration<?>> findTemplateType(
            com.github.javaparser.ast.CompilationUnit cu, String simpleName,
            String enclosingSimpleName) {
        if (enclosingSimpleName == null) return findTemplateType(cu, simpleName);
        return cu.findFirst(ClassOrInterfaceDeclaration.class,
                        c -> c.getNameAsString().equals(simpleName)
                                && hasEnclosingType(c, enclosingSimpleName))
                .<TypeDeclaration<?>>map(c -> c)
                .or(() -> cu.findFirst(RecordDeclaration.class,
                        r -> r.getNameAsString().equals(simpleName)
                                && hasEnclosingType(r, enclosingSimpleName)));
    }

    private static boolean hasEnclosingType(com.github.javaparser.ast.Node node, String enclosingName) {
        return node.getParentNode()
                .filter(p -> p instanceof TypeDeclaration)
                .map(p -> ((TypeDeclaration<?>) p).getNameAsString().equals(enclosingName))
                .orElse(false);
    }
```

(Check the existing `hasEnclosingClass` helper — if it exists and does the same job, reuse it. If it uses `ClassOrInterfaceDeclaration` specifically, either generalize it or add the overload above alongside it.)

- [ ] **Step 3.3: Update processTypePermutation() to use findTemplateType()**

Find the two `findFirst(ClassOrInterfaceDeclaration.class, ...)` calls in `processTypePermutation()`. Replace both with `findTemplateType(...)`.

The first call (for validation):
```java
// BEFORE:
Optional<ClassOrInterfaceDeclaration> foundForValidation = templateCu.findFirst(
        ClassOrInterfaceDeclaration.class,
        c -> c.getNameAsString().equals(templateSimpleName));
if (foundForValidation.isEmpty()) { ... return; }
ClassOrInterfaceDeclaration templateClassDecl = foundForValidation.get();

// AFTER:
Optional<TypeDeclaration<?>> foundForValidation = findTemplateType(templateCu, templateSimpleName);
if (foundForValidation.isEmpty()) { ... return; }
TypeDeclaration<?> templateClassDeclForValidation = foundForValidation.get();
```

The second call (for generation):
```java
// BEFORE:
Optional<ClassOrInterfaceDeclaration> found = templateCu.findFirst(
        ClassOrInterfaceDeclaration.class,
        c -> c.getNameAsString().equals(templateSimpleName) && ...);
// clones and processes it

// AFTER:
Optional<TypeDeclaration<?>> found = findTemplateType(templateCu, templateSimpleName, enclosingName);
```

(Read the code carefully — the exact structure depends on the enclosing-class handling in place. Generalize the found type to `TypeDeclaration<?>` throughout the scope.)

- [ ] **Step 3.4: Update generatePermutation() internal logic**

Read `generatePermutation()` in full. Change:
- Local variable types from `ClassOrInterfaceDeclaration` to `TypeDeclaration<?>`
- The cloned declaration type
- All internal method calls that pass `ClassOrInterfaceDeclaration` to the now-generalized transformers

Add record-specific branches. Search for these patterns and add the guard:

**Strip `static` (already works on `TypeDeclaration` via `removeModifier`):**
```java
// This already works — removeModifier() is on Node
classDecl.removeModifier(Modifier.Keyword.STATIC);
```

**Ensure public (may use ClassOrInterfaceDeclaration-specific API):**
If there's a call like `classDecl.setInterface(false)` or `classDecl.setAbstract(false)`, wrap it:
```java
if (classDecl instanceof ClassOrInterfaceDeclaration coid) {
    coid.setInterface(false);
    // any other COID-specific modifier setup
}
```

**Constructor rename (works for both — records have explicit constructors):**
```java
// This already works — getConstructors() is on TypeDeclaration<?>
classDecl.getConstructors().forEach(ctor -> ctor.setName(newClassName));
```

**Extends expansion — skip for records:**
```java
if (classDecl instanceof ClassOrInterfaceDeclaration coid) {
    applyExtendsExpansion(coid, ctx, globalGeneratedNames, generatedSet);
}
// (Do NOT call applyExtendsExpansion for records)
```

**Transformer calls — already work with TypeDeclaration<?> after Task 2:**
All the transformer calls (`PermuteDeclrTransformer.transform(classDecl, ctx, ...)` etc.) now accept `TypeDeclaration<?>`, so pass `classDecl` directly.

**Internal processor methods** (like `applyPermuteMethodApt`, `applyPermuteReturn`, `applyImplicitInference`, `buildGeneratedSet`) — read each one. If they take `ClassOrInterfaceDeclaration`, change the parameter to `TypeDeclaration<?>`. Each of these internal methods follows the same pattern as the external transformers.

- [ ] **Step 3.5: Run testBasicRecordPermutation — expect PASS**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="RecordExpansionTest#testBasicRecordPermutation" 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 3.6: Run testRecordWithPermuteDeclrOnComponent and testRecordWithPermuteTypeParam**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest="RecordExpansionTest#testRecordWithPermuteDeclrOnComponent+testRecordWithPermuteTypeParam" \
  2>&1 | tail -10
```

Expected: PASS (both — `@PermuteDeclr` and `@PermuteTypeParam` walk the AST and find parameters/type-params already, so they work once the record is found and cloned).

- [ ] **Step 3.7: Run full suite — all 181 must still pass**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS. The 4th test (testRecordWithPermuteParam) still fails — that's Task 4.

- [ ] **Step 3.8: Stage and commit**

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git commit -m "feat(processor): find and process RecordDeclaration alongside ClassOrInterfaceDeclaration

Refs #29.

- findTemplateType() helper tries COID first then RecordDeclaration
- generatePermutation() accepts TypeDeclaration<?>
- Record-specific branches: skip extends expansion and interface/abstract modifiers
- Internal methods generalized to TypeDeclaration<?>

Tests passing: testBasicRecordPermutation, testRecordWithPermuteDeclrOnComponent,
testRecordWithPermuteTypeParam. One remaining: testRecordWithPermuteParam (Task 4).

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: @PermuteParam on record components

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java`

Read `PermuteParamTransformer.java` in full before making changes. The existing `transform()` method processes `MethodDeclaration` and `ConstructorDeclaration` parameter lists. For records, `RecordDeclaration.getParameters()` contains the components.

- [ ] **Step 4.1: Add the record component processing call to transform()**

In `PermuteParamTransformer.transform(TypeDeclaration<?> classDecl, ...)`, add AFTER the existing constructor processing:

```java
        // Record components — @PermuteParam on RecordDeclaration.getParameters()
        // expands the component list (which is also the canonical constructor's param list).
        if (classDecl instanceof com.github.javaparser.ast.body.RecordDeclaration rec) {
            transformRecordComponents(rec, ctx, messager);
        }
```

- [ ] **Step 4.2: Implement transformRecordComponents()**

Add this private static method. Study `transformConstructor()` carefully — `transformRecordComponents` follows the same pattern but operates on `RecordDeclaration.getParameters()` instead of a constructor's parameter list:

```java
    /**
     * Processes @PermuteParam on record components (RecordDeclaration.getParameters()).
     * Expands the sentinel component into the generated sequence and expands
     * anchor call sites in all constructor bodies and method bodies within the record.
     */
    private static void transformRecordComponents(
            com.github.javaparser.ast.body.RecordDeclaration rec,
            EvaluationContext ctx,
            Messager messager) {
        // Process sentinels in the component list — same while-loop pattern as transformMethod
        Parameter sentinel;
        while ((sentinel = findNextRecordComponentSentinel(rec)) != null) {
            // Read the @PermuteParam annotation from the sentinel
            com.github.javaparser.ast.expr.AnnotationExpr ann = getPermuteParamAnnotation(sentinel);
            if (ann == null) break;

            String innerVarName = extractAttr(ann, "varName");
            String fromExpr     = extractAttr(ann, "from");
            String toExpr       = extractAttr(ann, "to");
            String typeTemplate = extractAttr(ann, "type");
            String nameTemplate = extractAttr(ann, "name");
            if (innerVarName == null || fromExpr == null || toExpr == null
                    || typeTemplate == null || nameTemplate == null) break;

            int fromVal = ctx.evaluateInt(fromExpr);
            int toVal   = ctx.evaluateInt(toExpr);

            // Build the expanded component list
            com.github.javaparser.ast.NodeList<Parameter> expanded =
                    new com.github.javaparser.ast.NodeList<>();
            java.util.List<String> expandedNames = new java.util.ArrayList<>();
            for (int v = fromVal; v <= toVal; v++) {
                EvaluationContext inner = ctx.withVariable(innerVarName, v);
                String newType = inner.evaluate(typeTemplate);
                String newName = inner.evaluate(nameTemplate);
                expandedNames.add(newName);
                Parameter newParam = new Parameter(
                        com.github.javaparser.StaticJavaParser.parseType(newType),
                        newName);
                expanded.add(newParam);
            }

            // Find position of sentinel and replace it with the expanded list
            int sentinelIdx = rec.getParameters().indexOf(sentinel);
            rec.getParameters().remove(sentinelIdx);
            for (int k = expanded.size() - 1; k >= 0; k--) {
                rec.getParameters().add(sentinelIdx, expanded.get(k));
            }

            // Expand anchor call sites in record body
            String anchor = sentinel.getNameAsString();
            // Compact constructors and explicit constructors
            rec.getConstructors().forEach(ctor ->
                    expandAnchorInStatement(ctor.getBody(), anchor, expandedNames));
            // Instance methods
            rec.getMethods().forEach(method ->
                    method.getBody().ifPresent(body ->
                            expandAnchorInStatement(body, anchor, expandedNames)));
        }
    }

    /** Finds the next record component annotated with @PermuteParam. */
    private static Parameter findNextRecordComponentSentinel(
            com.github.javaparser.ast.body.RecordDeclaration rec) {
        for (Parameter p : rec.getParameters()) {
            if (getPermuteParamAnnotation(p) != null) return p;
        }
        return null;
    }

    /** Returns the @PermuteParam annotation on the given parameter, or null if absent. */
    private static com.github.javaparser.ast.expr.AnnotationExpr getPermuteParamAnnotation(
            Parameter p) {
        for (com.github.javaparser.ast.expr.AnnotationExpr ann : p.getAnnotations()) {
            String name = ann.getNameAsString();
            if (ANNOTATION_SIMPLE.equals(name) || ANNOTATION_FQ.equals(name)) return ann;
        }
        return null;
    }
```

**Note:** The helpers `extractAttr()`, `expandAnchorInStatement()`, and `ANNOTATION_SIMPLE`/`ANNOTATION_FQ` already exist in `PermuteParamTransformer` — use them directly. Read the file to find their exact names and signatures before writing `transformRecordComponents`.

- [ ] **Step 4.3: Run testRecordWithPermuteParam — expect PASS**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="RecordExpansionTest#testRecordWithPermuteParam" 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 4.4: Run all 4 RecordExpansionTest tests — all should pass**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="RecordExpansionTest" 2>&1 | tail -10
```

Expected: 4/4 PASS.

- [ ] **Step 4.5: Run full suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 185+ tests (181 existing + 4 new), 0 failures.

- [ ] **Step 4.6: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java
git commit -m "feat(core): add transformRecordComponents() — @PermuteParam on record components

Refs #29.

RecordDeclaration.getParameters() are Parameter nodes, same as constructor
params. transformRecordComponents() expands the sentinel component into the
generated sequence and expands anchor call sites in constructor/method bodies.

All 4 RecordExpansionTest tests now pass. 185 total tests, 0 failures.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: InlineGenerator — inline record support

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Test: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java`

Read `InlineGenerator.java` in full before making changes.

- [ ] **Step 5.1: Write the failing inline-mode test**

Add to `InlineGenerationTest.java`:

```java
    @Test
    public void testRecordInlineModeWithPermuteParam() throws Exception {
        // inline=true record template generating Tuple3 and Tuple4 as nested siblings
        String parentSrc =
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteParam;\n" +
                "import io.quarkiverse.permuplate.PermuteTypeParam;\n" +
                "public class Tuples {\n" +
                "    @Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Tuple${i}\",\n" +
                "             inline=true, keepTemplate=false)\n" +
                "    public static record Tuple2<\n" +
                "        @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n" +
                "                          name=\"${alpha(k)}\") A\n" +
                "    >(\n" +
                "        @PermuteParam(varName=\"j\", from=\"1\", to=\"${i}\",\n" +
                "                      type=\"${alpha(j)}\", name=\"${lower(j)}\")\n" +
                "        A a\n" +
                "    ) {}\n" +
                "}";

        com.github.javaparser.ast.CompilationUnit cu =
                com.github.javaparser.StaticJavaParser.parse(parentSrc);
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration parent =
                cu.getClassByName("Tuples").orElseThrow();
        // Find the nested record
        com.github.javaparser.ast.body.RecordDeclaration template =
                parent.getMembers().stream()
                        .filter(m -> m instanceof com.github.javaparser.ast.body.RecordDeclaration)
                        .map(m -> (com.github.javaparser.ast.body.RecordDeclaration) m)
                        .findFirst().orElseThrow();

        io.quarkiverse.permuplate.maven.AnnotationReader reader =
                new io.quarkiverse.permuplate.maven.AnnotationReader();
        io.quarkiverse.permuplate.core.PermuteConfig config = reader.readPermuteConfig(template);
        java.util.List<java.util.Map<String, Object>> allCombinations =
                io.quarkiverse.permuplate.core.PermuteConfig.buildAllCombinations(config);

        com.github.javaparser.ast.CompilationUnit result =
                new io.quarkiverse.permuplate.maven.InlineGenerator()
                        .generate(cu, template, config, allCombinations);

        String output = result.toString();
        assertThat(output).contains("Tuple3");
        assertThat(output).contains("A a");
        assertThat(output).contains("B b");
        assertThat(output).contains("C c");
        assertThat(output).contains("Tuple4");
        assertThat(output).contains("D d");
        assertThat(output).doesNotContain("Tuple2"); // keepTemplate=false
    }
```

- [ ] **Step 5.2: Run — expect FAIL**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest="InlineGenerationTest#testRecordInlineModeWithPermuteParam" 2>&1 | tail -15
```

Expected: FAIL — `InlineGenerator.generate()` takes `ClassOrInterfaceDeclaration`, not `RecordDeclaration`.

- [ ] **Step 5.3: Add Java 17 language level to InlineGenerator**

Find the class declaration in `InlineGenerator.java`. Add a static initializer block:

```java
public class InlineGenerator {

    static {
        com.github.javaparser.StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
    }
    // ...
```

- [ ] **Step 5.4: Update generate() to accept TypeDeclaration<?>**

Change:

```java
// BEFORE:
public static CompilationUnit generate(CompilationUnit parentCu,
        ClassOrInterfaceDeclaration templateClassDecl,
        PermuteConfig config,
        List<Map<String, Object>> allCombinations)

// AFTER:
public static CompilationUnit generate(CompilationUnit parentCu,
        TypeDeclaration<?> templateClassDecl,
        PermuteConfig config,
        List<Map<String, Object>> allCombinations)
```

Add `import com.github.javaparser.ast.body.TypeDeclaration;` and `import com.github.javaparser.ast.body.RecordDeclaration;`.

Inside `generate()`, apply record-specific branches — the same pattern as the processor in Task 3:
- Skip `setInterface(false)` for records: `if (classDecl instanceof ClassOrInterfaceDeclaration coid) { ... }`
- Skip extends expansion for records: same guard
- Transformer calls already accept `TypeDeclaration<?>` after Task 2

Also update `AnnotationReader.readPermuteConfig()` if it currently only accepts `ClassOrInterfaceDeclaration` — check if it needs to handle `RecordDeclaration` too (it uses JavaParser PSI, so it may already work with `TypeDeclaration<?>` or need the same signature generalization).

- [ ] **Step 5.5: Run the inline test — expect PASS**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest="InlineGenerationTest#testRecordInlineModeWithPermuteParam" 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 5.6: Run full suite**

```bash
/opt/homebrew/bin/mvn test -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 186+ tests, 0 failures.

- [ ] **Step 5.7: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
        permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java
git commit -m "feat(maven-plugin): record template support in InlineGenerator

Refs #29.

generate() accepts TypeDeclaration<?> — both ClassOrInterfaceDeclaration and
RecordDeclaration are handled. Java 17 language level in static initializer.
Record-specific branches skip extends expansion and interface modifiers.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Documentation, example, and close issue

**Files:**
- Modify: `CLAUDE.md`
- Create: `permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/Tuple2Record.java`

- [ ] **Step 6.1: Update annotation-ideas.md and CLAUDE.md**

In `docs/annotation-ideas.md`, update the record entry status:
```
| Record component expansion | High (two blockers) | Medium | **Done** (#29) |
```

In `CLAUDE.md`, update the non-obvious decisions table — add:
```
| Record template support | `StaticJavaParser` configured for Java 17 in `PermuteProcessor.init()` and `InlineGenerator` static initializer. All transformers now take `TypeDeclaration<?>` (common supertype of COID and `RecordDeclaration`). `transformRecordComponents()` in `PermuteParamTransformer` handles `@PermuteParam` on record components. Records skip extends expansion and interface/abstract modifier handling. |
```

- [ ] **Step 6.2: Create example template**

Create `permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/Tuple2Record.java`:

```java
package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Demonstrates @Permute on a record template using @PermuteParam to expand
 * the record component list.
 *
 * <p>Generates Tuple3 through Tuple6 — immutable, type-safe tuples with
 * 3 to 6 typed components:
 * <ul>
 *   <li>Tuple3&lt;A,B,C&gt;(A a, B b, C c)</li>
 *   <li>Tuple4&lt;A,B,C,D&gt;(A a, B b, C c, D d)</li>
 *   <li>Tuple5&lt;A,B,C,D,E&gt;(A a, B b, C c, D d, E e)</li>
 *   <li>Tuple6&lt;A,B,C,D,E,F&gt;(A a, B b, C c, D d, E e, F f)</li>
 * </ul>
 *
 * <p>The sentinel class Tuple2 is the template — it does not appear in the
 * generated output. The range starts at from=3 to avoid a template/generated
 * name collision (Tuple2 would otherwise clash with i=2).
 */
@Permute(varName = "i", from = "3", to = "6", className = "Tuple${i}")
public record Tuple2<
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}",
                          name = "${alpha(k)}") A>(
        @PermuteParam(varName = "j", from = "1", to = "${i}",
                      type = "${alpha(j)}", name = "${lower(j)}")
        A a) {
}
```

- [ ] **Step 6.3: Build examples module to confirm it compiles**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn compile -pl permuplate-apt-examples -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS (generates Tuple3..Tuple6).

- [ ] **Step 6.4: Run full suite one final time**

```bash
/opt/homebrew/bin/mvn test -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, all tests passing.

- [ ] **Step 6.5: Commit and close issue**

```bash
git add docs/annotation-ideas.md CLAUDE.md \
        permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/Tuple2Record.java
git commit -m "docs: record support documented; add Tuple2Record example

Closes #29.

annotation-ideas.md: record expansion marked done.
CLAUDE.md: non-obvious decisions entry added for TypeDeclaration<?> generalization.
Tuple2Record.java: Tuple3..Tuple6 generated from a record template.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

```bash
gh issue close 29 --repo mdproctor/permuplate \
  --comment "Implemented. Records work as @Permute templates with full parity: @PermuteDeclr, @PermuteTypeParam, @PermuteParam on components, inline mode. Both blockers fixed. 5 new tests. Tuple2Record.java demonstrates the Tuple3..Tuple6 pattern."
```

---

## Self-Review

**Spec coverage:**
- ✅ Parser language level (Blocker 1) — Task 1
- ✅ Transformer signatures generalized to TypeDeclaration<?> — Task 2
- ✅ PermuteProcessor finds RecordDeclaration — Task 3
- ✅ Basic record permutation — Task 3 (testBasicRecordPermutation)
- ✅ @PermuteDeclr on record component — Task 3 (testRecordWithPermuteDeclrOnComponent)
- ✅ @PermuteTypeParam on record type param — Task 3 (testRecordWithPermuteTypeParam)
- ✅ @PermuteParam on record components — Task 4 (testRecordWithPermuteParam)
- ✅ Inline mode — Task 5 (testRecordInlineModeWithPermuteParam)
- ✅ Record-specific branches (skip extends, skip interface) — Task 3+5
- ✅ Backward compat (all 181 existing tests still pass) — checked in each task
- ✅ Example template — Task 6

**Not in scope (per design spec):**
- @PermuteExtends on records — skipped (records can't extend)
- @PermuteStatements on compact constructors — deferred
- IntelliJ plugin index updates — separate task

**No placeholders found.**

**Type consistency:** `TypeDeclaration<?>` used consistently across Tasks 2-5. `transformRecordComponents()` helper methods use existing `PermuteParamTransformer` helpers by name (implementer reads file first to confirm exact names).
