# Typed join() and Dual filter() Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `join()` fully type-safe at all arities and add a single-fact `filter()` overload matching real Drools ergonomics, by extending `PermuteTypeParamTransformer` to handle standalone method-level `@PermuteTypeParam` with automatic parameter-type propagation.

**Architecture:** Three layers of change — (1) transformer core: `transformMethod()` gains rename propagation into parameter types, `transform()` gains Step 5 for non-`@PermuteMethod` methods; (2) template: `JoinBuilder.java` uses the new standalone `@PermuteTypeParam` on `join()` and a `@PermuteMethod` ternary sentinel for the single-fact filter; (3) example tests: `RuleBuilderTest` is cleaned of raw types and extended with typed-join and dual-filter tests.

**Tech Stack:** JavaParser (AST manipulation), Apache Commons JEXL3 (expression evaluation), Google compile-testing (APT unit tests), JUnit 4, Maven (`/opt/homebrew/bin/mvn`).

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java` | Modify | Add propagation to `transformMethod()`; add Step 5 to `transform()`; add 3 private helpers |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTypeParamTest.java` | Modify | Add 5 tests for standalone method `@PermuteTypeParam`: rename, propagation, `@PermuteDeclr` override, guard, APT e2e |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java` | Modify | Add 1 test for `@PermuteMethod` ternary `from` expression suppression |
| `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java` | Modify | Typed `join()` with `@PermuteTypeParam` + `<B>` + `DataSource<B>`; add `filterLatest` sentinel |
| `permuplate-mvn-examples/src/test/java/.../drools/RuleBuilderTest.java` | Modify | Remove raw-type constants/suppressions/casts; add 5 new tests |
| `permuplate-mvn-examples/DROOLS-DSL.md` | Modify | Document dual filter pattern, Drools comparison, ternary mechanism |
| `CLAUDE.md` | Modify | Add 4 entries to non-obvious decisions table |

---

## Task 1: Write Failing Tests for Standalone Method @PermuteTypeParam

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTypeParamTest.java`

These tests will fail until Task 2 implements the transformer changes.

- [ ] **Step 1: Add 5 new tests to PermuteTypeParamTest**

Append the following tests to the existing `PermuteTypeParamTest` class (before the closing `}`):

```java
// =========================================================================
// Standalone method-level @PermuteTypeParam (non-@PermuteMethod methods)
// =========================================================================

/**
 * @PermuteTypeParam on a non-@PermuteMethod method renames the type parameter
 * declaration AND propagates the rename into parameter types that reference it.
 * Here B is renamed to T2 (i=2) and T3 (i=3); List<B> propagates to List<T2>/List<T3>.
 */
@Test
public void testStandaloneMethodTypeParamRenameAndPropagate() {
    JavaFileObject source = JavaFileObjects.forSourceString("io.example.Gatherer1",
            """
            package io.example;
            import io.quarkiverse.permuplate.Permute;
            import io.quarkiverse.permuplate.PermuteTypeParam;
            @Permute(varName = "i", from = 2, to = 3, className = "Gatherer${i}")
            public class Gatherer1 {
                @PermuteTypeParam(varName = "m", from = "${i}", to = "${i}", name = "T${m}")
                public <B> void collect(java.util.List<B> items) {}
            }
            """);
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);
    assertThat(compilation).succeeded();

    String src2 = sourceOf(compilation.generatedSourceFile("io.example.Gatherer2").orElseThrow());
    // B renamed to T2; List<B> propagated to List<T2>
    assertThat(src2).contains("<T2> void collect(java.util.List<T2> items)");

    String src3 = sourceOf(compilation.generatedSourceFile("io.example.Gatherer3").orElseThrow());
    // B renamed to T3; List<B> propagated to List<T3>
    assertThat(src3).contains("<T3> void collect(java.util.List<T3> items)");
}

/**
 * Propagation works when the type parameter appears nested multiple levels deep
 * in the parameter type — e.g. Function<DS, DataSource<B>> where B is two levels deep.
 */
@Test
public void testPropagationIntoNestedGeneric() {
    JavaFileObject source = JavaFileObjects.forSourceString("io.example.Fetcher1",
            """
            package io.example;
            import io.quarkiverse.permuplate.Permute;
            import io.quarkiverse.permuplate.PermuteTypeParam;
            @Permute(varName = "i", from = 2, to = 3, className = "Fetcher${i}")
            public class Fetcher1 {
                @PermuteTypeParam(varName = "m", from = "${i}", to = "${i}", name = "T${m}")
                public <B> void fetch(
                        java.util.function.Function<Object, java.util.List<B>> supplier) {}
            }
            """);
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);
    assertThat(compilation).succeeded();

    String src2 = sourceOf(compilation.generatedSourceFile("io.example.Fetcher2").orElseThrow());
    // B nested inside Function<Object, List<B>> — must propagate to T2
    assertThat(src2).contains("java.util.function.Function<Object, java.util.List<T2>>");
}

/**
 * @PermuteDeclr on a parameter takes explicit precedence over propagated renames.
 * The first param (no @PermuteDeclr) is propagated; the second (@PermuteDeclr) uses its
 * explicit type, which also happens to reference the new name via ${alpha(i)} evaluation.
 */
@Test
public void testPermuteDeclrOverridesPropagation() {
    JavaFileObject source = JavaFileObjects.forSourceString("io.example.Dual1",
            """
            package io.example;
            import io.quarkiverse.permuplate.Permute;
            import io.quarkiverse.permuplate.PermuteDeclr;
            import io.quarkiverse.permuplate.PermuteTypeParam;
            @Permute(varName = "i", from = 2, to = 3, className = "Dual${i}")
            public class Dual1 {
                @PermuteTypeParam(varName = "m", from = "${i}", to = "${i}", name = "T${m}")
                public <B> void handle(
                        java.util.List<B> propagated,
                        @PermuteDeclr(type = "java.util.Set<T${i}>") Object explicit) {}
            }
            """);
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);
    assertThat(compilation).succeeded();

    String src2 = sourceOf(compilation.generatedSourceFile("io.example.Dual2").orElseThrow());
    // First param: B propagated to T2
    assertThat(src2).contains("java.util.List<T2> propagated");
    // Second param: @PermuteDeclr wins — Set<T2> not List<T2>
    assertThat(src2).contains("java.util.Set<T2> explicit");
}

/**
 * @PermuteMethod methods must be skipped by Step 5. They are processed later in
 * applyPermuteMethod() with the inner (i,j) context; double-processing would use
 * only the outer context and produce incorrect output or corrupt the type parameters.
 * Verifies standalone method IS processed while @PermuteMethod method is NOT corrupted.
 */
@Test
public void testPermuteMethodGuardPreventsDoubleProcessing() {
    JavaFileObject source = JavaFileObjects.forSourceString("io.example.Guarded1",
            """
            package io.example;
            import io.quarkiverse.permuplate.Permute;
            import io.quarkiverse.permuplate.PermuteMethod;
            import io.quarkiverse.permuplate.PermuteReturn;
            import io.quarkiverse.permuplate.PermuteTypeParam;
            @Permute(varName = "i", from = 2, to = 2, className = "Guarded${i}")
            public class Guarded1 {
                // Standalone: Step 5 should process this
                @PermuteTypeParam(varName = "m", from = "${i}", to = "${i}", name = "X${m}")
                public <B> void standalone(java.util.List<B> items) {}
                // @PermuteMethod: Step 5 must skip this; applyPermuteMethod() handles it
                @PermuteMethod(varName = "j", from = "1", to = "1")
                @PermuteReturn(className = "Guarded${i}", when = "true")
                public Object overloaded() { return this; }
            }
            """);
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);
    assertThat(compilation).succeeded();

    String src = sourceOf(compilation.generatedSourceFile("io.example.Guarded2").orElseThrow());
    // Standalone: B renamed to X2, propagated to List<X2>
    assertThat(src).contains("<X2> void standalone(java.util.List<X2> items)");
    // @PermuteMethod: one overload generated correctly (not double-processed)
    assertThat(src).contains("Object overloaded()");
}

/**
 * Full end-to-end APT test of the typed join() pattern:
 * class-level @PermuteTypeParam (expanding) + method-level @PermuteTypeParam (single-value,
 * standalone) + @PermuteReturn. Verifies that B is renamed to the next alpha letter,
 * propagates into Function<Object, List<B>>, and the return type is fully parameterised.
 */
@Test
public void testTypedJoinPatternEndToEnd() {
    JavaFileObject source = JavaFileObjects.forSourceString("io.example.Chain0",
            """
            package io.example;
            import io.quarkiverse.permuplate.Permute;
            import io.quarkiverse.permuplate.PermuteReturn;
            import io.quarkiverse.permuplate.PermuteTypeParam;
            @Permute(varName = "i", from = 1, to = 3, className = "Chain${i}")
            public class Chain0<
                    @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "T${k}") T1> {
                @PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "T${m}")
                @PermuteReturn(className = "Chain${i+1}",
                               typeArgs = "typeArgList(1, i+1, 'T')")
                public <B> Object join(
                        java.util.function.Function<Object, java.util.List<B>> source) {
                    return null;
                }
            }
            """);
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);
    assertThat(compilation).succeeded();

    // Chain1<T1>: join() has <T2>, parameter List<T2>, return Chain2<T1, T2>
    String src1 = sourceOf(compilation.generatedSourceFile("io.example.Chain1").orElseThrow());
    assertThat(src1).contains("public <T2> Chain2<T1, T2> join(" +
            "java.util.function.Function<Object, java.util.List<T2>> source)");

    // Chain2<T1, T2>: join() has <T3>, parameter List<T3>, return Chain3<T1, T2, T3>
    String src2 = sourceOf(compilation.generatedSourceFile("io.example.Chain2").orElseThrow());
    assertThat(src2).contains("public <T3> Chain3<T1, T2, T3> join(" +
            "java.util.function.Function<Object, java.util.List<T3>> source)");

    // Chain3 is leaf: Chain4 not in generated set → join() omitted by boundary omission
    String src3 = sourceOf(compilation.generatedSourceFile("io.example.Chain3").orElseThrow());
    assertThat(src3).doesNotContain("join(");
}
```

- [ ] **Step 2: Run failing tests to confirm they fail with the expected reason**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am \
  -Dtest=PermuteTypeParamTest#testStandaloneMethodTypeParamRenameAndPropagate+testPropagationIntoNestedGeneric+testPermuteDeclrOverridesPropagation+testPermuteMethodGuardPreventsDoubleProcessing+testTypedJoinPatternEndToEnd \
  2>&1 | tail -30
```

Expected: **5 tests fail**. The generated source will contain `<B> void collect(java.util.List<B> items)` (not renamed/propagated) because the transformer doesn't yet handle standalone methods.

---

## Task 2: Implement Propagation in transformMethod() and Step 5 in transform()

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java`

- [ ] **Step 1: Add missing imports at the top of the file**

Add after the existing imports (`import java.util.Set;`):

```java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
```

- [ ] **Step 2: Add 3 private helper methods at the bottom of the class (before the final `}`)**

```java
/**
 * Replaces occurrences of {@code oldName} with {@code newName} in a Java type string,
 * respecting word boundaries so that "B" does not match inside "Boolean" or "Builder".
 * Used by propagation to rename type parameter references in parameter type strings.
 */
private static String replaceTypeIdentifier(String type, String oldName, String newName) {
    StringBuilder sb = new StringBuilder();
    int idx = 0;
    while (idx < type.length()) {
        int found = type.indexOf(oldName, idx);
        if (found < 0) {
            sb.append(type.substring(idx));
            break;
        }
        boolean before = found == 0 || !Character.isJavaIdentifierPart(type.charAt(found - 1));
        int end = found + oldName.length();
        boolean after = end >= type.length() || !Character.isJavaIdentifierPart(type.charAt(end));
        if (before && after) {
            sb.append(type, idx, found);
            sb.append(newName);
            idx = end;
        } else {
            sb.append(type.charAt(found));
            idx = found + 1;
        }
    }
    return sb.toString();
}

/**
 * Returns true if the parameter carries a {@code @PermuteDeclr} annotation.
 * Used during propagation: explicit {@code @PermuteDeclr} takes precedence over
 * the automatic rename propagated from a {@code @PermuteTypeParam} expansion.
 */
private static boolean hasPermuteDeclrAnnotation(Parameter param) {
    return param.getAnnotations().stream().anyMatch(a -> {
        String n = a.getNameAsString();
        return n.equals("PermuteDeclr") || n.equals("io.quarkiverse.permuplate.PermuteDeclr");
    });
}

/**
 * Returns true if the method carries a {@code @PermuteMethod} annotation.
 * Used in Step 5 of {@link #transform} to guard against double-processing:
 * {@code @PermuteMethod} methods are handled later in {@code applyPermuteMethod()}
 * with the inner {@code (i,j)} context, so processing them here (outer context only)
 * would produce incorrect output.
 */
private static boolean isPermuteMethodAnnotated(MethodDeclaration method) {
    return method.getAnnotations().stream().anyMatch(a -> {
        String n = a.getNameAsString();
        return n.equals("PermuteMethod") || n.equals("io.quarkiverse.permuplate.PermuteMethod");
    });
}
```

- [ ] **Step 3: Add propagation inside `transformMethod()` — single-value rename tracking and parameter update**

In `transformMethod()`, locate the line `Set<String> expanded = new HashSet<>();` and add the rename map declaration immediately after it:

```java
// Maps sentinel name → new name for single-value expansions (from == to).
// Multi-value expansions (from < to) expand one sentinel to N names — no single
// target to propagate — so they are excluded.
Map<String, String> singleValueRenames = new LinkedHashMap<>();
```

Then inside the expansion `for (int j = fromVal; j <= toVal; j++)` loop, after `String newName = innerCtx.evaluate(nameTemplate);` and before `result.add(buildTypeParam(...))`, add:

```java
if (fromVal == toVal) {
    singleValueRenames.put(sentinelName, newName);
}
```

Then immediately after `method.setTypeParameters(result);`, add the propagation block:

```java
// Propagate single-value renames into parameter types.
// @PermuteDeclr-annotated parameters are skipped — explicit annotation wins.
for (Map.Entry<String, String> entry : singleValueRenames.entrySet()) {
    String oldName = entry.getKey();
    String newName = entry.getValue();
    for (Parameter param : method.getParameters()) {
        if (hasPermuteDeclrAnnotation(param)) continue;
        String current = param.getTypeAsString();
        String updated = replaceTypeIdentifier(current, oldName, newName);
        if (!updated.equals(current)) {
            param.setType(StaticJavaParser.parseType(updated));
        }
    }
}
```

- [ ] **Step 4: Add Step 5 to `transform()` — process standalone method-level @PermuteTypeParam**

In `transform()`, locate the `return expanded;` line at the bottom and insert Step 5 before it:

```java
// Step 5: expand method-level @PermuteTypeParam on non-@PermuteMethod methods.
//
// @PermuteMethod methods are guarded here — they are processed in applyPermuteMethod()
// with the combined (i,j) context. Processing them here (outer context only) would
// evaluate expressions like "${j}" or "${i-j}" with j undefined, corrupting output.
//
// Propagation: after expanding the type parameter, parameter types that reference the
// old name are automatically updated (see transformMethod). @PermuteDeclr overrides.
for (MethodDeclaration method : new ArrayList<>(classDecl.getMethods())) {
    if (isPermuteMethodAnnotated(method)) continue;
    boolean hasTypeParamAnn = method.getTypeParameters().stream()
            .anyMatch(tp -> findTypeParamAnnotation(tp).isPresent());
    if (hasTypeParamAnn) {
        transformMethod(method, ctx, messager, element);
    }
}
```

---

## Task 3: Run Transformer Tests — Verify All 5 Pass, Then Commit

**Files:** (no changes)

- [ ] **Step 1: Run the 5 new PermuteTypeParamTest tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am \
  -Dtest=PermuteTypeParamTest#testStandaloneMethodTypeParamRenameAndPropagate+testPropagationIntoNestedGeneric+testPermuteDeclrOverridesPropagation+testPermuteMethodGuardPreventsDoubleProcessing+testTypedJoinPatternEndToEnd \
  2>&1 | tail -20
```

Expected: **5 tests pass**.

- [ ] **Step 2: Run the full permuplate-tests suite to confirm no regressions**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am 2>&1 | tail -15
```

Expected: all existing tests still pass.

- [ ] **Step 3: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteTypeParamTransformer.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTypeParamTest.java
git commit -m "$(cat <<'EOF'
feat(core): standalone method @PermuteTypeParam with parameter-type propagation

Step 5 in transform() processes non-@PermuteMethod methods that carry
@PermuteTypeParam on their type parameters. @PermuteMethod methods are
guarded to prevent double-processing with the wrong context.

transformMethod() now propagates single-value renames (from==to) into
parameter types automatically. @PermuteDeclr on a parameter overrides
the propagated type — explicit always wins.

Helpers: replaceTypeIdentifier (word-boundary-safe), hasPermuteDeclrAnnotation,
isPermuteMethodAnnotated.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Add @PermuteMethod Ternary Suppression Test

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java`

JEXL3 supports ternary `?:` operators. The `@PermuteMethod` `from`/`to` attributes are evaluated via `EvaluationContext.evaluateInt()` which uses JEXL3. The test verifies that `${i > 1 ? i : i+1}` produces an empty range at i=1 (method omitted) and a single clone at i≥2.

- [ ] **Step 1: Add the ternary suppression test to PermuteMethodTest**

Append before the closing `}` of `PermuteMethodTest`:

```java
// =========================================================================
// @PermuteMethod ternary from expression — conditional method generation
// =========================================================================

/**
 * A JEXL ternary expression in @PermuteMethod.from can suppress method generation
 * for specific outer values of i. Here from="${i > 1 ? i : i+1}" with to="${i}"
 * produces an empty range (from > to) at i=1 — the method is silently omitted —
 * and a single-clone range at i≥2.
 *
 * <p>This is the mechanism used by filterLatest in JoinBuilder: at arity 1 the
 * single-fact and all-facts filter have identical signatures, so the single-fact
 * sentinel must be suppressed. At arity 2+ they are distinct overloads.
 */
@Test
public void testTernaryFromExpressionSuppressesAtArity1() {
    // Use inline generation to test across i=1,2,3 easily
    String parentSource = """
            package com.example;
            public class Selector {
                @io.quarkiverse.permuplate.Permute(varName = "i", from = 1, to = 3,
                        className = "Sel${i}", inline = true, keepTemplate = false)
                public static class Sel0 {
                    // Suppressed at i=1 (empty range), one clone at i=2,3
                    @io.quarkiverse.permuplate.PermuteMethod(
                            varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}",
                            name = "extra")
                    @io.quarkiverse.permuplate.PermuteReturn(
                            className = "Sel${i}", when = "true")
                    public Object extraSentinel(
                            @io.quarkiverse.permuplate.PermuteDeclr(type = "String")
                            Object param) { return this; }

                    // Regular method always present
                    public Object regular() { return this; }
                }
            }
            """;
    String src1 = generateInline(parentSource, "Sel0", "i", 1, 3, "Sel${i}", 1);
    // i=1: empty range → extra() suppressed; regular() present
    assertThat(src1).doesNotContain("extra(");
    assertThat(src1).contains("regular()");

    String src2 = generateInline(parentSource, "Sel0", "i", 1, 3, "Sel${i}", 2);
    // i=2: one clone → extra(String param) generated; regular() present
    assertThat(src2).contains("Sel2 extra(String param)");
    assertThat(src2).contains("regular()");

    String src3 = generateInline(parentSource, "Sel0", "i", 1, 3, "Sel${i}", 3);
    // i=3: one clone → extra(String param) generated
    assertThat(src3).contains("Sel3 extra(String param)");
}
```

- [ ] **Step 2: Run the test to verify it passes immediately (JEXL3 already supports ternary)**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -am \
  -Dtest=PermuteMethodTest#testTernaryFromExpressionSuppressesAtArity1 \
  2>&1 | tail -15
```

Expected: **1 test passes**.

- [ ] **Step 3: Commit**

```bash
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java
git commit -m "$(cat <<'EOF'
test(core): verify @PermuteMethod ternary from expression suppresses at arity 1

JEXL3 ternary ${i > 1 ? i : i+1} in @PermuteMethod.from produces an empty
range at i=1 (method silently omitted) and a single-clone range at i≥2.
This is the mechanism used by filterLatest in JoinBuilder to avoid duplicate
method signatures at arity 1 where single-fact and all-facts filters coincide.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Update JoinBuilder.java — Typed join() and Dual filter()

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`

- [ ] **Step 1: Add PermuteMethod import**

Add `import io.quarkiverse.permuplate.PermuteMethod;` to the import block. The full import section becomes:

```java
import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteMethod;
import io.quarkiverse.permuplate.PermuteReturn;
import io.quarkiverse.permuplate.PermuteTypeParam;
```

- [ ] **Step 2: Replace the join() method with the typed version**

Replace the entire existing `join()` method (the one with `@PermuteReturn(className = "Join${i+1}First")` and `DataSource<?>`) with:

```java
/**
 * Advances the arity by one fact type. The new source type {@code B} is captured
 * as a method type parameter and flows into both the parameter type and the
 * return type — giving a fully typed chain at every arity.
 *
 * <p>{@code @PermuteTypeParam} renames {@code <B>} to the next alpha letter
 * (B at i=1, C at i=2, …). Propagation automatically renames {@code B} in
 * {@code DataSource<B>} alongside the declaration — no {@code @PermuteDeclr} needed.
 *
 * <p>Boundary omission removes this method from Join6First — Join7First is not
 * in the generated set so {@code @PermuteReturn} silently omits join() there.
 *
 * <p>Uses reflection to instantiate the next JoinFirst class by name — necessary
 * because method bodies are not transformed and we cannot write {@code new Join${i+1}First(rd)}.
 */
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
@PermuteReturn(className = "Join${i+1}First",
               typeArgs = "'DS, ' + typeArgList(1, i+1, 'alpha')")
public <B> Object join(java.util.function.Function<DS, DataSource<B>> source) {
    rd.addSource(source);
    String cn = getClass().getSimpleName();
    int n = Integer.parseInt(cn.replaceAll("[^0-9]", ""));
    String nextName = getClass().getEnclosingClass().getName() + "$Join" + (n + 1) + "First";
    try {
        return cast(Class.forName(nextName).getConstructor(RuleDefinition.class).newInstance(rd));
    } catch (Exception e) {
        throw new RuntimeException("Failed to instantiate " + nextName, e);
    }
}
```

- [ ] **Step 3: Add the filterLatest sentinel after the existing filter() method**

After the closing `}` of the existing `filter()` method, add:

```java
/**
 * Single-fact filter — applies a predicate to the most recently joined fact only.
 * Complement to {@code filter(PredicateN)} which tests all accumulated facts.
 *
 * <p>In the generated output this method is named {@code filter} (via
 * {@code @PermuteMethod name="filter"}), overloading the all-facts variant.
 * The sentinel is named {@code filterLatest} in the template to avoid a
 * duplicate method error — at this point both sentinels have parameter type
 * {@code Object}, so they are distinct; after generation they have distinct
 * parameter types and are valid Java overloads.
 *
 * <p>Suppressed at arity 1 via {@code @PermuteMethod} with a JEXL ternary
 * that produces an empty range ({@code from > to}) when {@code i=1}.
 * At arity 1, {@code filter(Predicate2<DS,A>)} already covers both roles —
 * adding a second copy would be a compile error.
 *
 * <p>Generated signatures per arity:
 * <ul>
 *   <li>i=1 — omitted (single-fact ≡ all-facts at arity 1)</li>
 *   <li>i=2 — {@code filter(Predicate2<DS, B> predicate)}</li>
 *   <li>i=3 — {@code filter(Predicate2<DS, C> predicate)}</li>
 *   <li>…</li>
 *   <li>i=6 — {@code filter(Predicate2<DS, F> predicate)}</li>
 * </ul>
 */
@PermuteMethod(varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}", name = "filter")
@PermuteReturn(className = "Join${i}First",
               typeArgs = "'DS, ' + typeArgList(1, i, 'alpha')",
               when = "true")
public Object filterLatest(
        @PermuteDeclr(type = "Predicate2<DS, ${alpha(i)}>")
        Object predicate) {
    rd.addFilter(predicate);
    return this;
}
```

---

## Task 6: Update RuleBuilderTest — Remove Raw Types and Add New Tests

**Files:**
- Modify: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java`

- [ ] **Step 1: Remove the import and the 5 pre-typed source constants**

Remove the import line:
```java
import java.util.function.Function;
```

Remove the entire constants block (5 lines):
```java
private static final Function<Ctx, DataSource<?>> PERSONS = c -> c.persons();
private static final Function<Ctx, DataSource<?>> ACCOUNTS = c -> c.accounts();
private static final Function<Ctx, DataSource<?>> ORDERS = c -> c.orders();
private static final Function<Ctx, DataSource<?>> PRODUCTS = c -> c.products();
private static final Function<Ctx, DataSource<?>> TRANSACTIONS = c -> c.transactions();
```

- [ ] **Step 2: Update all existing join() calls to use inline lambdas and remove @SuppressWarnings**

For each test that previously used a constant or had `@SuppressWarnings`, apply these replacements:

- `.join(ACCOUNTS)` → `.join(ctx -> ctx.accounts())`
- `.join(ORDERS)` → `.join(ctx -> ctx.orders())`
- `.join(PRODUCTS)` → `.join(ctx -> ctx.products())`
- `.join(TRANSACTIONS)` → `.join(ctx -> ctx.transactions())`
- `.join(PERSONS)` → `.join(ctx -> ctx.persons())`
- Remove all `@SuppressWarnings({"unchecked", "rawtypes"})` annotations from the test methods

- [ ] **Step 3: Remove explicit casts from filter and fn lambdas**

With the typed join, fact parameters are now properly typed. Remove casts:
- `((Person) a)` → `a`
- `((Account) b)` → `b`
- `((Order) c)` → `c`

The tests that required casts:
- `testArity2FilterOnBothFacts`: `((Person) a).age()` → `a.age()`, `((Account) b).balance()` → `b.balance()`
- `testArity2MultipleFilters`: same replacements
- `testArity3IntermediateFilter`: `((Person) a)`, `((Account) b)`, `((Order) c)` → direct

- [ ] **Step 4: Add 5 new tests — typed join and dual filter coverage**

Append these tests before the closing `}` of `RuleBuilderTest`:

```java
// =========================================================================
// Typed join() — fully typed chain without casts or pre-typed constants
// =========================================================================

@Test
public void testArity2FullyTyped() {
    // B inferred as Account from ctx -> ctx.accounts() lambda.
    // a is Person, b is Account — no casts needed.
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
            .fn((ctx, a, b) -> {});

    rule.run(ctx);

    assertThat(rule.executionCount()).isEqualTo(1);
    assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
    assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
}

@Test
public void testArity3FullyTyped() {
    // Three-way typed chain — a is Person, b is Account, c is Order.
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .join(ctx -> ctx.orders())
            .filter((ctx, a, b, c) -> a.age() >= 18 && b.balance() > 500.0 && c.amount() > 100.0)
            .fn((ctx, a, b, c) -> {});

    rule.run(ctx);

    assertThat(rule.executionCount()).isEqualTo(1);
    assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
    assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    assertThat(rule.capturedFact(0, 2)).isEqualTo(new Order("ORD1", 150.0));
}

// =========================================================================
// Dual filter() — single-fact and all-facts overloads
// =========================================================================

@Test
public void testArity2SingleFactFilter() {
    // filter(Predicate2<DS, Account>) — tests only the most recently joined fact.
    // 2 persons × 1 high-balance account = 2 executions (person filter not applied here).
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, b) -> b.balance() > 500.0)  // b is Account — latest fact
            .fn((ctx, a, b) -> {});

    assertThat(rule.filterCount()).isEqualTo(1);
    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(2); // Alice+ACC1, Bob+ACC1
}

@Test
public void testArity2BothFilterTypesChained() {
    // Chain single-fact filter (on Account) then cross-fact filter (Person + Account).
    // Only Alice(30) + ACC1(1000) satisfies both.
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, b) -> b.balance() > 500.0)          // single-fact: balance
            .filter((ctx, a, b) -> a.age() >= 18)              // all-facts: age
            .fn((ctx, a, b) -> {});

    assertThat(rule.filterCount()).isEqualTo(2);
    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(1);
    assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
    assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
}

@Test
public void testArity3SingleFactFilterOnLatestFact() {
    // After joining persons + accounts + orders, single-fact filter tests only Order.
    // 2 persons × 2 accounts × 1 qualifying order (ORD1 amount>100) = 4 combos.
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .join(ctx -> ctx.orders())
            .filter((ctx, c) -> c.amount() > 100.0)  // c is Order — only latest fact
            .fn((ctx, a, b, c) -> {});

    assertThat(rule.filterCount()).isEqualTo(1);
    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(4); // 2p × 2a × 1 qualifying order
    assertThat(rule.capturedFact(0, 2)).isInstanceOf(Order.class);
}
```

---

## Task 7: Run Full Build — Verify All Tests Pass

**Files:** (no changes)

- [ ] **Step 1: Run the full build**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | tail -30
```

Expected: **BUILD SUCCESS**. All existing 131 processor tests, the 15 original example tests, and the 5 new example tests pass.

If the build fails due to a compile error in JoinBuilder.java (e.g. `DataSource<B>` type mismatch), check that `RuleDefinition.addSource()` takes `Object` — it does (confirmed from source); no cast is needed. If a test fails with a wrong generated signature, re-read the transformer Task 2 changes and check for typos in the annotation expressions.

- [ ] **Step 2: Commit all template and test changes**

```bash
git add \
  permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
  permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java
git commit -m "$(cat <<'EOF'
feat(drools-example): typed join() and dual filter() overloads

join() now carries a method-level @PermuteTypeParam that captures the new
source type B as the next alpha letter per arity. Propagation (Task 1)
automatically renames B in DataSource<B> — no @PermuteDeclr needed.
Return type is now fully parameterised: Join2First<DS,A,B> not raw.

filterLatest sentinel generates a single-fact filter(Predicate2<DS,alpha(i)>)
at arities 2-6. At arity 1 the ternary from="${i > 1 ? i : i+1}" produces
an empty range — method silently omitted — since the all-facts filter already
has the same signature there.

RuleBuilderTest: pre-typed constants removed, @SuppressWarnings removed,
explicit casts removed. Five new tests cover typed-join and dual-filter.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Update Documentation

**Files:**
- Modify: `permuplate-mvn-examples/DROOLS-DSL.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update DROOLS-DSL.md Phase 1 Architecture section**

Replace the "Phase 1 Architecture" section's limitation table row for `join()`:

Old row:
```
| `join()` parameter is `Function<DS, DataSource<?>>` not typed | Next arity's type param not in scope in template | Wildcard allows lambda target inference at arity 1; arity 2+ needs pre-typed constants |
```

New row:
```
| Method bodies not transformed | `new Join${i+1}First(rd)` not valid Java | Reflective instantiation derives class name from getSimpleName() — brittle but contained |
```

- [ ] **Step 2: Add a new section to DROOLS-DSL.md after the Phase 1 section**

Add after "## Phase 1 Architecture: Single JoinFirst Family":

```markdown
## Typed join() — Method-Level @PermuteTypeParam with Propagation

`join()` uses `@PermuteTypeParam` on its own type parameter `<B>` to rename it to the next alpha letter per arity. The rename automatically propagates into the parameter type `DataSource<B>` — no `@PermuteDeclr` needed.

```java
// Template:
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
@PermuteReturn(className = "Join${i+1}First", typeArgs = "'DS, ' + typeArgList(1, i+1, 'alpha')")
public <B> Object join(java.util.function.Function<DS, DataSource<B>> source) { ... }

// Generated at i=1 (Join1First<DS, A>):
public <B> Join2First<DS, A, B> join(Function<DS, DataSource<B>> source)

// Generated at i=2 (Join2First<DS, A, B>):
public <C> Join3First<DS, A, B, C> join(Function<DS, DataSource<C>> source)
```

This matches the real Drools pattern (`public <C> Join2First<END,DS,B,C> join(Function1<DS,DataSource<C>> fromC)`) exactly. No pre-typed constants, no `@SuppressWarnings`, no explicit casts required in call sites.

## Dual filter() Overloads

Every `JoinNFirst` at arity N≥2 has two `filter()` overloads, matching real Drools:

- **Single-fact** — tests only the most recently joined fact:
  `filter(Predicate2<DS, alpha(N)> predicate)` — ergonomic for post-join checks on one fact
- **All-facts** — tests all accumulated facts:
  `filter(PredicateN+1<DS, A, B, …> predicate)` — cross-fact comparisons

`Join1First` has only the all-facts overload (which IS the single-fact overload at arity 1 — same signature).

### The arity-1 suppression mechanism

At arity 1, both overloads would have `filter(Predicate2<DS, A>)` — identical. Java rejects duplicates. The template uses a `@PermuteMethod` ternary in `from` to suppress the single-fact sentinel when i=1:

```java
// from="${i > 1 ? i : i+1}" produces from=2, to=1 at i=1 → empty range → method omitted.
// At i=2+: from=i, to=i → one clone per arity.
@PermuteMethod(varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}", name = "filter")
public Object filterLatest(@PermuteDeclr(type = "Predicate2<DS, ${alpha(i)}>") Object p) { ... }
```

The inner variable `x` serves only as a loop counter; the annotation uses the outer `i` via `alpha(i)`. The sentinel is named `filterLatest` to distinguish it from the all-facts `filter` sentinel in the template; `name="filter"` renames the generated output correctly.

### Usage comparison: before and after

```java
// Before (Phase 1) — raw types, constants, casts
private static final Function<Ctx, DataSource<?>> ACCOUNTS = c -> c.accounts();

@SuppressWarnings({"unchecked", "rawtypes"})
var rule = builder.from("adults", ctx -> ctx.persons())
        .join(ACCOUNTS)
        .filter((ctx, a, b) -> ((Person) a).age() >= 18 && ((Account) b).balance() > 500.0)
        .fn((ctx, a, b) -> {});

// After — fully typed, inline lambdas, no casts
var rule = builder.from("adults", ctx -> ctx.persons())
        .join(ctx -> ctx.accounts())
        .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)  // a: Person, b: Account
        .fn((ctx, a, b) -> {});

// Single-fact filter — Drools ergonomic pattern
var rule = builder.from("orders", ctx -> ctx.orders())
        .join(ctx -> ctx.accounts())
        .filter((ctx, b) -> b.balance() > 500.0)  // b: Account only — no need to name 'a'
        .fn((ctx, a, b) -> {});
```

## Comparison with Real Drools RuleBuilder

Studied at `droolsvol2/src/main/java/org/drools/core/RuleBuilder.java`:

| Feature | Real Drools | This Example |
|---|---|---|
| Typed `join(Function<DS, DataSource<C>>)` | ✅ `<C> Join2First<...,B,C> join(...)` | ✅ identical pattern |
| Single-fact `filter(Predicate2<DS, C>)` | ✅ per arity | ✅ per arity (arity 2+) |
| All-facts `filter(PredicateN+1<DS,...>)` | ✅ per arity | ✅ per arity |
| First extends Second hierarchy | ✅ | Phase 2 |
| `join(Join2Second)` multi-step | ✅ | Phase 2 |
| `not()` scoped negation | ✅ | Phase 3+ |
| Boundary omission (leaf node) | ❌ manually written | ✅ automatic via `@PermuteReturn` |
| Extend to arity 10 | Requires editing N classes | Change `to=6` → `to=10` |
```

- [ ] **Step 3: Add 4 entries to the CLAUDE.md non-obvious decisions table**

In `CLAUDE.md`, find the `| Topic | Decision / Fix |` table and append these rows:

```markdown
| Standalone method `@PermuteTypeParam` | Step 5 in `PermuteTypeParamTransformer.transform()` scans non-`@PermuteMethod` methods for `@PermuteTypeParam` on their type params and calls `transformMethod()`. `@PermuteMethod` methods are guarded — they are processed later in `applyPermuteMethod()` with the inner `(i,j)` context; processing them here would use only the outer context and corrupt output. |
| Propagation scope for single-value renames | `transformMethod()` propagates renames (old→new) into parameter types when `from==to` (single-value expansion). Word-boundary-safe via `replaceTypeIdentifier`. Multi-value expansions (from<to) have no single propagation target so are excluded. Method bodies are NOT touched — consistent with all other transformations. |
| `@PermuteDeclr` takes precedence over propagation | Parameters with `@PermuteDeclr` are skipped during propagation. Explicit always wins. This allows callers to use `@PermuteDeclr` when the propagated type is insufficient (e.g. a different structure, not just a rename). |
| `@PermuteMethod` ternary `from` for conditional generation | `from="${i > 1 ? i : i+1}"` with `to="${i}"` produces an empty range at `i=1` → method omitted. At `i≥2` it produces `from=to=i` → one clone. Used by `filterLatest` sentinel in `JoinBuilder` to suppress the single-fact filter at arity 1 where it would duplicate the all-facts filter. JEXL3 supports `?:` natively. |
```

- [ ] **Step 4: Run the full build one final time to confirm everything is still green**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | tail -10
```

Expected: **BUILD SUCCESS**.

- [ ] **Step 5: Commit documentation**

```bash
git add CLAUDE.md permuplate-mvn-examples/DROOLS-DSL.md
git commit -m "$(cat <<'EOF'
docs: document typed join, dual filter, propagation, and Drools comparison

DROOLS-DSL.md: typed join() pattern with @PermuteTypeParam propagation,
dual filter overloads, arity-1 suppression mechanism, before/after usage,
Drools comparison table showing parity and improvements.

CLAUDE.md: 4 new non-obvious decision entries covering standalone method
@PermuteTypeParam, propagation scope, @PermuteDeclr precedence, ternary
from expression for conditional @PermuteMethod generation.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Checklist

**Spec coverage:**
- ✅ Transformer: Step 5 in `transform()` — Task 2 Step 4
- ✅ Propagation in `transformMethod()` — Task 2 Steps 2-3
- ✅ `@PermuteDeclr` fallback — Task 2 Step 2 (hasPermuteDeclrAnnotation guard)
- ✅ `@PermuteMethod` guard — Task 2 Step 4 (isPermuteMethodAnnotated)
- ✅ 5 transformer tests — Task 1
- ✅ Ternary `from` test — Task 4
- ✅ `join()` typed with `@PermuteTypeParam` + `<B>` — Task 5 Step 2
- ✅ `filterLatest` sentinel with ternary suppression — Task 5 Step 3
- ✅ `RuleBuilderTest` cleaned of raw types — Task 6 Steps 1-3
- ✅ 5 new example tests — Task 6 Step 4
- ✅ `DROOLS-DSL.md` updated — Task 8 Steps 1-2
- ✅ `CLAUDE.md` updated — Task 8 Step 3

**Type consistency:** `replaceTypeIdentifier` defined in Task 2 Step 2, used in Task 2 Step 3 — same name ✅. `hasPermuteDeclrAnnotation` and `isPermuteMethodAnnotated` defined in Task 2 Step 2, referenced in Task 2 Steps 3-4 ✅.

**No placeholders:** All steps contain complete code. ✅
