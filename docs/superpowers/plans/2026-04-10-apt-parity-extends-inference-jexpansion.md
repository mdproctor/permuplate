# APT Processor Parity — Three Missing Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port three features from the Maven plugin's `InlineGenerator` to the APT `PermuteProcessor` to achieve feature parity: extends clause expansion, implicit return type inference, and j-based `@PermuteMethod` type expansion.

**Architecture:** All changes land in `PermuteProcessor.java`. The shared helper utilities (`firstEmbeddedNumber`, `parseReturnTypeInfo`, etc.) are added as private static methods — same pattern as InlineGenerator. A global generated-names set is built once at the top of `process()` by iterating all @Permute elements before generation begins, then stored as an instance field. The three features are added at their correct positions in the `generatePermutation()` / `applyPermuteMethodApt()` pipeline.

**Tech Stack:** Java 17, JavaParser AST, Google compile-testing (JUnit 4)

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | **Modify** | All three new features + helper utilities |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteExtendsExpansionTest.java` | **Create** | Tests for extends clause expansion (Task 1) |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/ImplicitInferenceTest.java` | **Create** | Tests for implicit return type inference (Task 3) |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java` | **Modify** | Add j-based expansion test (Task 4) |

---

## Task 1: Extends clause expansion

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteExtendsExpansionTest.java`

When Permuplate generates `Step3First` from `Step2First extends Step2Second<T1>`, the extends clause must update to `Step3Second<T1, T2, T3>`. Currently it stays as `Step2Second<T1>` — wrong.

**Key rule:** same-N formula — `Join3First` extends `Join3Second` (not `Join4Second`). The embedded number in the extends base class is replaced by the CURRENT permutation's embedded number.

**Two detection branches (same as InlineGenerator):**
1. All T+number type args → hardcode `T1..T(newNum)`
2. Extends type args are a prefix of the post-G1 type params → use the full post-G1 list (alpha naming)

- [ ] **Step 1: Create the test file**

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for extends clause expansion in APT mode.
 * Verifies that generated classes extend the correct same-N sibling rather than
 * the template's base class.
 */
public class PermuteExtendsExpansionTest {

    // -------------------------------------------------------------------------
    // T+number style — both First and Second are generated
    // -------------------------------------------------------------------------

    @Test
    public void testExtendsExpansionTNumber() {
        // Step2First extends Step2Second → Step3First should extend Step3Second<T1, T2, T3>
        var secondTemplate = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Step2Second",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Step${i}Second")
                        public class Step2Second<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1> {}
                        """);
        var firstTemplate = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Step2First",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Step${i}First")
                        public class Step2First<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1>
                                extends Step2Second<T1> {}
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(secondTemplate, firstTemplate);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Step3First").orElseThrow());
        // Extends clause must be updated from Step2Second to Step3Second with full type args
        assertThat(src).contains("extends Step3Second<T1, T2, T3>");
        assertThat(src).doesNotContain("Step2Second");
    }

    // -------------------------------------------------------------------------
    // Alpha naming style — same-N formula with post-G1 type params
    // -------------------------------------------------------------------------

    @Test
    public void testExtendsExpansionAlpha() {
        // Alpha2First<A, B> extends Alpha2Second<A, B>
        // → Alpha3First<A, B, C> extends Alpha3Second<A, B, C>
        var secondTemplate = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Alpha2Second",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Alpha${i}Second")
                        public class Alpha2Second<A, @PermuteTypeParam(varName="j", from="2", to="${i+1}", name="${alpha(j)}") B>
                                extends Object {}
                        """);
        var firstTemplate = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Alpha2First",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Alpha${i}First")
                        public class Alpha2First<A, @PermuteTypeParam(varName="j", from="2", to="${i+1}", name="${alpha(j)}") B>
                                extends Alpha2Second<A, B> {}
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(secondTemplate, firstTemplate);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Alpha3First").orElseThrow());
        // Extends clause updated to same-N with full post-G1 type params
        assertThat(src).contains("extends Alpha3Second<A, B, C>");
        assertThat(src).doesNotContain("Alpha2Second");
    }

    @Test
    public void testThirdPartyBaseClassNotExpanded() {
        // A base class with a different prefix must NOT be touched
        var template = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Widget2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Widget${i}")
                        public class Widget2<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1>
                                extends java.util.ArrayList<T1> {}
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(template);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Widget3").orElseThrow());
        // java.util.ArrayList must NOT be renamed to ArrayList3 etc.
        assertThat(src).contains("extends java.util.ArrayList");
        assertThat(src).doesNotContain("ArrayList3");
    }
}
```

- [ ] **Step 2: Run to verify all three tests FAIL**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-ide-support,permuplate-core -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteExtendsExpansionTest 2>&1 | tail -15
```
Expected: `testExtendsExpansionTNumber` and `testExtendsExpansionAlpha` FAIL with wrong extends clause; `testThirdPartyBaseClassNotExpanded` may pass.

- [ ] **Step 3: Add helper methods to PermuteProcessor**

Add these private static methods to `PermuteProcessor.java` (near the bottom, before the closing `}`):

```java
// =========================================================================
// Extends expansion helpers (ported from InlineGenerator)
// =========================================================================

/** Returns the substring of name up to (but not including) its first digit. */
private static String prefixBeforeFirstDigit(String name) {
    for (int i = 0; i < name.length(); i++) {
        if (Character.isDigit(name.charAt(i)))
            return name.substring(0, i);
    }
    return name;
}

/** Returns the first contiguous sequence of digits in name as an int, or -1 if none. */
private static int firstEmbeddedNumber(String name) {
    int start = -1;
    for (int i = 0; i < name.length(); i++) {
        if (Character.isDigit(name.charAt(i))) {
            start = i;
            break;
        }
    }
    if (start < 0) return -1;
    int end = start;
    while (end < name.length() && Character.isDigit(name.charAt(end))) end++;
    try { return Integer.parseInt(name.substring(start, end)); }
    catch (NumberFormatException e) { return -1; }
}

/** Replaces the first contiguous digit sequence in name with newNum. */
private static String replaceFirstEmbeddedNumber(String name, int newNum) {
    int start = -1;
    for (int i = 0; i < name.length(); i++) {
        if (Character.isDigit(name.charAt(i))) { start = i; break; }
    }
    if (start < 0) return name;
    int end = start;
    while (end < name.length() && Character.isDigit(name.charAt(end))) end++;
    return name.substring(0, start) + newNum + name.substring(end);
}

/** Returns true if s matches the T+number pattern (e.g. "T1", "T23"). */
private static boolean isTNumberVar(String s) {
    if (s == null || s.length() < 2 || s.charAt(0) != 'T') return false;
    for (int i = 1; i < s.length(); i++) {
        if (!Character.isDigit(s.charAt(i))) return false;
    }
    return true;
}
```

- [ ] **Step 4: Add `applyExtendsExpansion` to PermuteProcessor**

```java
/**
 * Expands the extends clause of a generated class to match its current arity.
 *
 * Fires when the template class and extends base class share the same name prefix
 * and the same first embedded number. Replaces the base class number with
 * currentEmbeddedNum (same-N formula: Join3First extends Join3Second).
 *
 * Branch 1 — all-T+number type args: builds T1..T(currentEmbeddedNum).
 * Branch 2 — alpha/mixed: extends type args are a prefix of postG1TypeParams →
 * uses the full postG1TypeParams list.
 *
 * Third-party classes (different prefix) are safely skipped.
 */
private static void applyExtendsExpansion(
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl,
        String templateName,
        int templateEmbeddedNum,
        int currentEmbeddedNum,
        java.util.List<String> postG1TypeParams) {

    if (templateEmbeddedNum < 0 || currentEmbeddedNum < 0) return;
    String templatePrefix = prefixBeforeFirstDigit(templateName);
    int newNum = currentEmbeddedNum;

    com.github.javaparser.ast.NodeList<com.github.javaparser.ast.type.ClassOrInterfaceType> extended =
            classDecl.getExtendedTypes();

    for (int idx = 0; idx < extended.size(); idx++) {
        com.github.javaparser.ast.type.ClassOrInterfaceType ext = extended.get(idx);
        String baseName = ext.getNameAsString();

        // Only expand siblings: same prefix-before-digit as template
        if (!prefixBeforeFirstDigit(baseName).equals(templatePrefix)) continue;

        // Only expand when base class has the same embedded number as template
        int extNum = firstEmbeddedNumber(baseName);
        if (extNum < 0 || extNum != templateEmbeddedNum) continue;

        java.util.Optional<com.github.javaparser.ast.NodeList<com.github.javaparser.ast.type.Type>> typeArgsOpt =
                ext.getTypeArguments();
        if (typeArgsOpt.isEmpty() || typeArgsOpt.get().isEmpty()) continue;

        java.util.List<String> extArgNames = typeArgsOpt.get().stream()
                .map(com.github.javaparser.ast.type.Type::asString)
                .collect(java.util.stream.Collectors.toList());

        // Determine new type args
        java.util.List<String> newTypeArgNames;
        boolean allTNumber = extArgNames.stream().allMatch(PermuteProcessor::isTNumberVar);
        if (allTNumber) {
            newTypeArgNames = new java.util.ArrayList<>();
            for (int t = 1; t <= newNum; t++) newTypeArgNames.add("T" + t);
        } else {
            // Alpha/mixed: extends args must be a prefix of postG1TypeParams
            boolean isPrefix = extArgNames.size() <= postG1TypeParams.size()
                    && java.util.stream.IntStream.range(0, extArgNames.size())
                            .allMatch(k -> extArgNames.get(k).equals(postG1TypeParams.get(k)));
            if (!isPrefix) continue;
            newTypeArgNames = postG1TypeParams;
        }

        String newBaseName = replaceFirstEmbeddedNumber(baseName, newNum);
        String newExtStr = newBaseName + "<" + String.join(", ", newTypeArgNames) + ">";
        try {
            extended.set(idx, com.github.javaparser.StaticJavaParser.parseClassOrInterfaceType(newExtStr));
        } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 5: Call `applyExtendsExpansion` from `generatePermutation()`**

In `generatePermutation()`, after step 1b (after the call to `PermuteTypeParamTransformer.transform()`), add:

```java
        // 1c. Extends clause expansion — update base class name to same-N sibling
        // Must run after @PermuteTypeParam so postG1TypeParams reflect the expanded list.
        List<String> postG1TypeParams = classDecl.getTypeParameters().stream()
                .map(tp -> tp.getNameAsString())
                .collect(java.util.stream.Collectors.toList());
        int templateEmbeddedNum = firstEmbeddedNumber(templateClassName);
        int currentEmbeddedNum = firstEmbeddedNumber(newClassName);
        applyExtendsExpansion(classDecl, templateClassName, templateEmbeddedNum,
                currentEmbeddedNum, postG1TypeParams);
```

The exact location: immediately after the `PermuteTypeParamTransformer.transform(classDecl, ctx, processingEnv.getMessager(), typeElement);` call (line ~292) and before step 2 (`PermuteDeclrTransformer.transform(...)`).

- [ ] **Step 6: Run the tests to verify they pass**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-ide-support,permuplate-core,permuplate-processor -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteExtendsExpansionTest 2>&1 | tail -10
```
Expected: 3/3 tests pass, BUILD SUCCESS

- [ ] **Step 7: Run full suite for regressions**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | tail -5
```
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 8: Commit**

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteExtendsExpansionTest.java
git commit -m "feat(processor): port extends clause expansion from InlineGenerator to APT"
```

---

## Task 2: Global generated-names set

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

The implicit inference (Task 3) needs to know ALL class names generated across ALL @Permute templates in the current round — not just the current template's set. Build this once at the start of `process()`.

- [ ] **Step 1: Add the instance field and build it in `process()`**

Add an instance field to `PermuteProcessor`:

```java
/** All class names generated by ALL @Permute templates in the current round. Built once per round. */
private Set<String> globalGeneratedNames = new java.util.HashSet<>();
```

In `process()`, immediately after collecting `annotated` elements (after the `processingEnv.getMessager().printMessage(...)` line), add:

```java
        // Build global generated-names set across all @Permute templates — used by implicit inference.
        globalGeneratedNames = new java.util.HashSet<>();
        for (Element elem : annotated) {
            if (elem instanceof TypeElement) {
                Permute p = ((TypeElement) elem).getAnnotation(Permute.class);
                if (p != null) globalGeneratedNames.addAll(buildGeneratedSet(p));
            }
        }
```

- [ ] **Step 2: Verify existing tests still pass**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | tail -5
```
Expected: BUILD SUCCESS (no behaviour change yet)

- [ ] **Step 3: Commit**

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git commit -m "feat(processor): build global generated-names set per round for cross-template inference"
```

---

## Task 3: Implicit return type inference

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/ImplicitInferenceTest.java`

Methods with no `@PermuteReturn` whose return type is a generated class containing undeclared T+number type args (the "growing tip") are automatically updated. The tip expands and the embedded class number increments. Uses `globalGeneratedNames` built in Task 2.

Only fires for T+number naming — alpha naming requires explicit `@PermuteReturn`.

- [ ] **Step 1: Write the failing test**

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for implicit return type inference in APT mode.
 * Methods with no @PermuteReturn whose return type is a generated class with
 * an undeclared T+number growing tip are automatically updated.
 */
public class ImplicitInferenceTest {

    @Test
    public void testImplicitReturnTypeInference() {
        // Step2 generates Step3, Step4.
        // Step2.join() returns Step2<T1, T2> where T2 is undeclared — growing tip.
        // Step3.join() should implicitly infer: returns Step3<T1, T2, T3>.
        // Step4.join() should infer: returns Step4<T1, T2, T3, T4> and be omitted at Step4 (boundary).
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Chain2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=4, className="Chain${i}")
                        public class Chain2<T1, @PermuteTypeParam(varName="j", from="2", to="${i}", name="T${j}") T2> {
                            // No @PermuteReturn — should be inferred automatically.
                            // T2 is undeclared (growing tip). Chain2 is in generated set.
                            public Chain2<T1, T2> next() { return null; }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // Chain3: next() should return Chain3<T1, T2, T3>
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Chain3").orElseThrow());
        assertThat(src3).contains("Chain3<T1, T2, T3> next()");
        assertThat(src3).doesNotContain("Chain2<T1, T2> next()");
        assertThat(src3).doesNotContain("Chain4");

        // Chain4: next() would return Chain5 which is NOT in generated set → method omitted
        String src4 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Chain4").orElseThrow());
        assertThat(src4).doesNotContain("next()");
    }

    @Test
    public void testImplicitInferenceSkipsExplicitPermuteReturn() {
        // When @PermuteReturn is explicit, implicit inference must NOT also fire.
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Explicit2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="i", from=3, to=3, className="Explicit${i}")
                        public class Explicit2<T1, @PermuteTypeParam(varName="j", from="2", to="${i}", name="T${j}") T2> {
                            @PermuteReturn(className="Explicit${i}", typeArgVarName="j", typeArgFrom="1", typeArgTo="${i}", typeArgName="T${j}")
                            public Explicit2<T1, T2> typed() { return this; }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Explicit3").orElseThrow());
        // @PermuteReturn was explicit — method is present and correctly typed
        assertThat(src).contains("Explicit3<T1, T2, T3> typed()");
    }
}
```

- [ ] **Step 2: Run to verify tests FAIL**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=ImplicitInferenceTest 2>&1 | tail -15
```
Expected: FAIL — `next()` return type not updated in Chain3.

- [ ] **Step 3: Add additional helper methods to PermuteProcessor**

```java
/** Record holding parsed return type info: base class name and type arguments. */
private record ReturnTypeInfo(String baseClass, java.util.List<String> typeArgs) {}

/**
 * Parses "Step2<T1, T2>" into ReturnTypeInfo("Step2", ["T1","T2"]).
 * Handles nested generics (depth-aware comma splitting).
 * Returns null if the string cannot be parsed as a parametrized class name.
 */
private static ReturnTypeInfo parseReturnTypeInfo(String returnType) {
    int lt = returnType.indexOf('<');
    if (lt < 0) return new ReturnTypeInfo(returnType.trim(), java.util.List.of());
    String base = returnType.substring(0, lt).trim();
    String argsStr = returnType.substring(lt + 1, returnType.lastIndexOf('>')).trim();
    if (argsStr.isEmpty()) return new ReturnTypeInfo(base, java.util.List.of());
    java.util.List<String> args = new java.util.ArrayList<>();
    int depth = 0, start = 0;
    for (int i = 0; i < argsStr.length(); i++) {
        char c = argsStr.charAt(i);
        if (c == '<') depth++;
        else if (c == '>') depth--;
        else if (c == ',' && depth == 0) {
            args.add(argsStr.substring(start, i).trim());
            start = i + 1;
        }
    }
    args.add(argsStr.substring(start).trim());
    return new ReturnTypeInfo(base, args);
}

/** Extracts numeric suffix from class name (e.g. "Step3" → 3, "Chain4" → 4). Returns -1 if none. */
private static int classNameSuffix(String name) {
    int i = name.length() - 1;
    if (i < 0 || !Character.isDigit(name.charAt(i))) return -1;
    while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
    try { return Integer.parseInt(name.substring(i)); }
    catch (Exception ignored) { return -1; }
}

/** Strips numeric suffix from class name (e.g. "Step3" → "Step", "Chain4" → "Chain"). */
private static String stripNumericSuffix(String name) {
    int i = name.length() - 1;
    if (i < 0 || !Character.isDigit(name.charAt(i))) return name;
    while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
    return name.substring(0, i);
}

/**
 * Rebuilds the type argument list, expanding the growing tip from T(firstTipNum)
 * to T(newSuffix) while preserving fixed args in their original relative positions.
 *
 * Example: args=[T1, T2, R], declaredTypeParams={T1,R}, firstTipNum=2, newSuffix=4
 * → [T1, T2, T3, T4, R]
 */
private static java.util.List<String> buildExpandedTypeArgs(
        java.util.List<String> originalArgs,
        java.util.Set<String> declaredTypeParams,
        int firstTipNum, int newSuffix) {
    java.util.List<String> result = new java.util.ArrayList<>();
    boolean tipInserted = false;
    for (String arg : originalArgs) {
        boolean isTip = !declaredTypeParams.contains(arg) && isTNumberVar(arg);
        if (isTip && !tipInserted) {
            for (int t = firstTipNum; t <= newSuffix; t++) result.add("T" + t);
            tipInserted = true;
        } else if (!isTip) {
            result.add(arg);
        }
        // Additional tip vars from original are subsumed by the expansion — skip them
    }
    return result;
}

/** Replaces a type variable name in a type string using word-boundary-safe matching. */
private static String replaceTypeVar(String typeStr, String oldVar, String newVar) {
    return typeStr.replaceAll("\\b" + java.util.regex.Pattern.quote(oldVar) + "\\b", newVar);
}
```

- [ ] **Step 4: Add `applyImplicitInference()` and `collectExplicitReturnMethods()` to PermuteProcessor**

```java
/** Collects method keys that have explicit @PermuteReturn. */
private static java.util.Set<String> collectExplicitReturnMethods(
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl) {
    java.util.Set<String> keys = new java.util.HashSet<>();
    classDecl.getMethods().forEach(m -> m.getAnnotations().stream()
            .filter(a -> a.getNameAsString().equals("PermuteReturn")
                    || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteReturn"))
            .findFirst()
            .ifPresent(a -> keys.add(m.getNameAsString() + m.getParameters().toString())));
    return keys;
}

/**
 * Applies implicit return type inference to methods without @PermuteReturn.
 *
 * Fires when BOTH conditions hold:
 * 1. Return type base class is in allGeneratedNames
 * 2. Return type contains undeclared T+number vars (the growing tip)
 *
 * The tip is expanded: template tip starting at T(n) → generated at suffix k gets T(n)..T(k+1).
 * Boundary omission: if the inferred next class is not in allGeneratedNames, method is removed.
 * Parameter inference: old tip var T(n) is replaced by T(k+1) in parameter types.
 */
private static void applyImplicitInference(
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl,
        java.util.Set<String> allGeneratedNames,
        java.util.Set<String> explicitReturnMethods) {

    java.util.Set<String> declaredTypeParams = new java.util.LinkedHashSet<>();
    classDecl.getTypeParameters().forEach(tp -> declaredTypeParams.add(tp.getNameAsString()));

    java.util.List<com.github.javaparser.ast.body.MethodDeclaration> toRemove = new java.util.ArrayList<>();

    classDecl.getMethods().forEach(method -> {
        String methodKey = method.getNameAsString() + method.getParameters().toString();
        if (explicitReturnMethods.contains(methodKey)) return;

        String returnTypeStr = method.getTypeAsString();
        if (returnTypeStr.equals("void") || returnTypeStr.equals("Object")) return;

        ReturnTypeInfo info = parseReturnTypeInfo(returnTypeStr);
        if (info == null) return;

        // Condition 1: return type base class must be in generated set
        if (!allGeneratedNames.contains(info.baseClass())) return;

        // Condition 2: find undeclared T+number vars (growing tip)
        java.util.List<String> growingTip = new java.util.ArrayList<>();
        for (String arg : info.typeArgs()) {
            if (!declaredTypeParams.contains(arg) && isTNumberVar(arg))
                growingTip.add(arg);
        }
        if (growingTip.isEmpty()) return;

        int firstTipNum = Integer.parseInt(growingTip.get(0).substring(1));
        int currentSuffix = classNameSuffix(classDecl.getNameAsString());
        if (currentSuffix < 0) return;

        int newSuffix = currentSuffix + 1;
        String newBaseClass = stripNumericSuffix(info.baseClass()) + newSuffix;

        // Boundary omission: inferred next class not in generated set → omit method
        if (!allGeneratedNames.contains(newBaseClass)) {
            toRemove.add(method);
            return;
        }

        java.util.List<String> newTypeArgs = buildExpandedTypeArgs(
                info.typeArgs(), declaredTypeParams, firstTipNum, newSuffix);
        String newReturnType = newBaseClass + "<" + String.join(", ", newTypeArgs) + ">";
        try {
            method.setType(com.github.javaparser.StaticJavaParser.parseType(newReturnType));
        } catch (Exception ignored) { return; }

        // Parameter inference: T(firstTipNum) → T(newSuffix)
        if (firstTipNum != newSuffix) {
            String oldTip = "T" + firstTipNum;
            String newTip = "T" + newSuffix;
            method.getParameters().forEach(param -> {
                String pt = param.getTypeAsString();
                if (pt.contains(oldTip) && !declaredTypeParams.contains(oldTip)) {
                    try {
                        param.setType(com.github.javaparser.StaticJavaParser.parseType(
                                replaceTypeVar(pt, oldTip, newTip)));
                    } catch (Exception ignored) {}
                }
            });
        }
    });

    toRemove.forEach(m -> classDecl.getMembers().removeIf(member -> member == m));
}
```

- [ ] **Step 5: Call `applyImplicitInference` from `generatePermutation()`**

In `generatePermutation()`, after the call to `applyPermuteReturn(classDecl, ctx, generatedSet, typeElement)` (step 5c), add:

```java
        // 5d. Implicit return type inference — methods with T+number growing tips,
        // no @PermuteReturn required. Must run AFTER applyPermuteReturn so that
        // explicit @PermuteReturn methods are already processed.
        java.util.Set<String> explicitReturnMethods = collectExplicitReturnMethods(classDecl);
        applyImplicitInference(classDecl, globalGeneratedNames, explicitReturnMethods);
```

- [ ] **Step 6: Run the tests**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=ImplicitInferenceTest 2>&1 | tail -10
```
Expected: 2/2 pass

- [ ] **Step 7: Run full suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | tail -5
```
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/ImplicitInferenceTest.java
git commit -m "feat(processor): implicit return type inference for T+number growing tips in APT mode"
```

---

## Task 4: J-based `@PermuteMethod` type expansion

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java`

When `@PermuteMethod` generates overloads for inner variable `j`, methods that don't have explicit `@PermuteReturn` but whose return/param types contain undeclared T+number vars should have those types expanded by `j-1`. For `j=1` this is a no-op. For `j=2` the tip grows by one, etc.

- [ ] **Step 1: Write the failing test**

Add to `PermuteMethodTest.java`:

```java
@Test
public void testPermuteMethodJBasedTypeExpansion() {
    // @PermuteMethod with j=1..3 on a method returning "Step2<T1, T2>" where T2 is undeclared.
    // j=1: no-op → Step2<T1, T2>
    // j=2: tip grows by 1 → Step3<T1, T2, T3>
    // j=3: tip grows by 2 → Step4<T1, T2, T3, T4>
    var source = com.google.testing.compile.JavaFileObjects.forSourceString(
            "io.permuplate.example.JExpand2",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteTypeParam;
                    import io.quarkiverse.permuplate.PermuteMethod;
                    @Permute(varName="i", from=2, to=2, className="JExpand${i}")
                    public class JExpand2<T1, @PermuteTypeParam(varName="k", from="2", to="${i}", name="T${k}") T2> {
                        @PermuteMethod(varName="j", from=1, to=3)
                        public JExpand2<T1, T2> connect() { return null; }
                    }
                    """);

    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);

    assertThat(compilation).succeeded();

    String src = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.JExpand2").orElseThrow());
    // j=1: return type unchanged
    assertThat(src).contains("JExpand2<T1, T2> connect()");
    // j=2: tip expanded by 1 → Step3
    assertThat(src).contains("JExpand3<T1, T2, T3> connect()");
    // j=3: tip expanded by 2 → Step4
    assertThat(src).contains("JExpand4<T1, T2, T3, T4> connect()");
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteMethodTest#testPermuteMethodJBasedTypeExpansion 2>&1 | tail -15
```
Expected: FAIL — j=2 and j=3 overloads have unexpanded types.

- [ ] **Step 3: Add `expandMethodTypesForJ` and `expandTypeStringForJ` helpers**

```java
/**
 * Increments the first contiguous digit sequence in name by offset.
 * E.g. incrementFirstEmbeddedNumber("Step2First", 1) → "Step3First".
 */
private static String incrementFirstEmbeddedNumber(String name, int offset) {
    int start = -1;
    for (int i = 0; i < name.length(); i++) {
        if (Character.isDigit(name.charAt(i))) { start = i; break; }
    }
    if (start < 0) return name;
    int end = start;
    while (end < name.length() && Character.isDigit(name.charAt(end))) end++;
    int num = Integer.parseInt(name.substring(start, end));
    return name.substring(0, start) + (num + offset) + name.substring(end);
}

/**
 * Expands a single type string for the j-based inner loop (offset = j-1).
 * Finds undeclared T+number vars (growing tip), expands from firstTipNum to
 * firstTipNum+offset, and increments the first embedded integer in the base
 * class name by offset. Returns typeStr unchanged if no growing tip found.
 */
private static String expandTypeStringForJ(String typeStr,
        java.util.Set<String> declaredTypeParams, int offset) {
    ReturnTypeInfo info = parseReturnTypeInfo(typeStr);
    if (info == null) return typeStr;

    java.util.List<String> growingTip = new java.util.ArrayList<>();
    for (String arg : info.typeArgs()) {
        if (!declaredTypeParams.contains(arg) && isTNumberVar(arg))
            growingTip.add(arg);
    }
    if (growingTip.isEmpty()) return typeStr;

    int firstTipNum = Integer.parseInt(growingTip.get(0).substring(1));
    int newLastTipNum = firstTipNum + offset;

    java.util.List<String> newTypeArgs = buildExpandedTypeArgs(
            info.typeArgs(), declaredTypeParams, firstTipNum, newLastTipNum);

    String newBase = incrementFirstEmbeddedNumber(info.baseClass(), offset);
    return newTypeArgs.isEmpty() ? newBase : newBase + "<" + String.join(", ", newTypeArgs) + ">";
}

/**
 * Applies j-based implicit type expansion to a method's return type and parameter types.
 * j=1 is a no-op (offset=0). For j>1, undeclared T+number growing tips are expanded
 * by (j-1) and the first embedded class-name number is incremented by (j-1).
 * Only fires on methods NOT already handled by @PermuteReturn.
 */
private static void expandMethodTypesForJ(
        com.github.javaparser.ast.body.MethodDeclaration method,
        java.util.Set<String> declaredTypeParams, int j) {
    if (j <= 1) return; // j=1 is always a no-op
    int offset = j - 1;

    // Expand return type
    String rt = method.getTypeAsString();
    String newRt = expandTypeStringForJ(rt, declaredTypeParams, offset);
    if (!newRt.equals(rt)) {
        try { method.setType(com.github.javaparser.StaticJavaParser.parseType(newRt)); }
        catch (Exception ignored) {}
    }

    // Expand each parameter type
    method.getParameters().forEach(param -> {
        String pt = param.getTypeAsString();
        String newPt = expandTypeStringForJ(pt, declaredTypeParams, offset);
        if (!newPt.equals(pt)) {
            try { param.setType(com.github.javaparser.StaticJavaParser.parseType(newPt)); }
            catch (Exception ignored) {}
        }
    });
}
```

- [ ] **Step 4: Call `expandMethodTypesForJ` from `applyPermuteMethodApt()`**

In `applyPermuteMethodApt()`, the inner j-loop currently does (after `applyPermuteReturnSimple`):

```java
                // Apply @PermuteDeclr on parameters with innerCtx
                io.quarkiverse.permuplate.core.PermuteDeclrTransformer
                        .processMethodParamDeclr(clone, innerCtx);
```

Insert BEFORE that line:

```java
                // J-based implicit type expansion: expand T+number growing tips by (j-1).
                // Only fires on methods without explicit @PermuteReturn.
                // Collect declared class type params ONCE per method (before the j-loop ideally,
                // but since this is in the j-loop, collect from classDecl each time — cheap).
                java.util.Set<String> declaredTPs = new java.util.LinkedHashSet<>();
                classDecl.getTypeParameters().forEach(tp -> declaredTPs.add(tp.getNameAsString()));
                java.util.Set<String> explicitMethods = collectExplicitReturnMethods(classDecl);
                String cloneKey = clone.getNameAsString() + clone.getParameters().toString();
                if (!explicitMethods.contains(cloneKey)) {
                    expandMethodTypesForJ(clone, declaredTPs, j);
                }
```

Note: the `explicitMethods` check uses the original method key before cloning, which may not match after parameter renaming. Use the clone's current key after `applyPermuteReturnSimple` instead:

Replace the above with:
```java
                // J-based implicit type expansion for methods without @PermuteReturn.
                {
                    java.util.Set<String> declaredTPs = new java.util.LinkedHashSet<>();
                    classDecl.getTypeParameters().forEach(tp -> declaredTPs.add(tp.getNameAsString()));
                    // The clone has had @PermuteReturn removed by applyPermuteReturnSimple;
                    // only expand if no @PermuteReturn annotation remains.
                    boolean hadExplicitReturn = clone.getAnnotations().stream().noneMatch(
                            a -> a.getNameAsString().equals("PermuteReturn")
                                    || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteReturn"));
                    if (hadExplicitReturn) {
                        expandMethodTypesForJ(clone, declaredTPs, j);
                    }
                }
```

- [ ] **Step 5: Run the test**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteMethodTest#testPermuteMethodJBasedTypeExpansion 2>&1 | tail -10
```
Expected: PASS

- [ ] **Step 6: Run the full PermuteMethodTest suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteMethodTest 2>&1 | tail -5
```
Expected: BUILD SUCCESS, all existing tests still pass

- [ ] **Step 7: Run full suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | tail -5
```
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java
git commit -m "feat(processor): j-based @PermuteMethod type expansion for T+number growing tips"
```

---

## Task 5: Full build verification

- [ ] **Step 1: Run the complete Maven build**

```bash
cd /Users/mdproctor/claude/permuplate
/opt/homebrew/bin/mvn clean install 2>&1 | grep -E "Tests run:.*Skipped: 0$|BUILD" | tail -5
```
Expected: BUILD SUCCESS, total test count increased from 147 (pre-plan baseline).

---

## Self-Review

**Spec coverage:**
- ✅ Extends clause expansion — Task 1 (T+number and alpha branches, third-party guard)
- ✅ Global generated-names set — Task 2 (prerequisite for Task 3)
- ✅ Implicit return type inference — Task 3 (growing tip, boundary omission, parameter inference)
- ✅ J-based @PermuteMethod expansion — Task 4
- ✅ Full build — Task 5

**Placeholder scan:** No TBDs or vague steps found.

**Type consistency:**
- `ReturnTypeInfo` record defined in Task 3 Step 3 and used in Task 3 Step 4 ✓
- `expandMethodTypesForJ` defined in Task 4 Step 3, called in Task 4 Step 4 ✓
- `parseReturnTypeInfo` defined in Task 3 Step 3, used in both Task 3 and Task 4 ✓
- `firstEmbeddedNumber` defined in Task 1 Step 3, used in Task 1 Steps 4 and 5 ✓
- `collectExplicitReturnMethods` defined in Task 3 Step 4, used in Task 3 Step 5 and Task 4 Step 4 ✓
- `buildExpandedTypeArgs` defined in Task 3 Step 3, used in Task 3 Step 4 and Task 4 Step 3 ✓
