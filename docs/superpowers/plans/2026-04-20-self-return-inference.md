# Self-Return Inference (Maven Plugin) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In Maven plugin inline generation, after all transformers run, detect methods with `Object` sentinel return type, no explicit `@PermuteReturn` or `@PermuteSelf`, and a body where every `return` statement returns `this` or `cast(this)`. Auto-set return type to the current generated class. Zero annotations needed on the template.

**Architecture:** Post-processing pass in `InlineGenerator.generate()` after the main transformer pipeline. Uses JavaParser AST to detect the `return this;` / `return cast(this);` pattern. Runs after `PermuteSelfTransformer` (which handles the explicit-annotation case) so there is no double-processing. APT is deliberately excluded — the APT template must compile with valid types, so inference would require the return type to be `Object` in the compilable template, which is valid.

**Epic:** #79

**Tech Stack:** Java 17, JavaParser 3.28.0.

---

## File Structure

| Action | Path | What changes |
|---|---|---|
| Modify | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | Add `isSelfReturning()` helper and `applySelfReturnInference()` post-pass |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java` | New tests |

---

### Task 1: Tests (TDD — write before implementing)

- [ ] **Step 1: Read `InlineGenerationTest.java` header and imports**

```bash
head -50 /Users/mdproctor/claude/permuplate/permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java
```

Use the same imports and helper pattern in the new tests below.

- [ ] **Step 2: Add tests to `InlineGenerationTest.java`**

Add the following tests to `InlineGenerationTest.java`:

```java
@Test
public void testSelfReturnInferenceSetsReturnTypeAutomatically() throws Exception {
    // Methods returning 'this' with Object sentinel return type are auto-updated
    // to return the generated class — no annotation needed.
    CompilationUnit parentCu = StaticJavaParser.parse("""
            package io.permuplate.test;
            public class Host {}
            """);
    CompilationUnit templateCu = StaticJavaParser.parse("""
            package io.permuplate.test;
            import io.quarkiverse.permuplate.Permute;
            public class Host {
                @Permute(varName="i", from="2", to="3", className="Fluent${i}", inline=true)
                static class FluentTemplate {
                    // No @PermuteSelf, no @PermuteReturn — inference from 'return this;'
                    public Object step() { return this; }
                    // This method returns a string literal — must NOT be inferred
                    public String name() { return "hello"; }
                }
            }
            """);
    ClassOrInterfaceDeclaration template = templateCu.findFirst(
            ClassOrInterfaceDeclaration.class,
            c -> "FluentTemplate".equals(c.getNameAsString())).orElseThrow();
    var ann = template.getAnnotationByName("Permute").orElseThrow();
    PermuteConfig config = AnnotationReader.readPermute(ann);
    CompilationUnit result = InlineGenerator.generate(parentCu, template, config,
            PermuteConfig.buildAllCombinations(config));
    String src = result.toString();
    assertThat(src).contains("Fluent2 step()");
    assertThat(src).contains("Fluent3 step()");
    // name() returns a String literal — must stay as String
    assertThat(src).contains("String name()");
}

@Test
public void testSelfReturnInferenceWithCastThis() throws Exception {
    // The cast(this) pattern — used with @SuppressWarnings("unchecked") — also triggers
    // inference. The cast() method helper is idiomatic in fluent DSLs.
    CompilationUnit parentCu = StaticJavaParser.parse(
            "package io.p.t; public class H {}");
    CompilationUnit templateCu = StaticJavaParser.parse("""
            package io.p.t;
            import io.quarkiverse.permuplate.Permute;
            public class H {
                @Permute(varName="i", from="2", to="2", className="R${i}", inline=true)
                static class RTemplate {
                    @SuppressWarnings("unchecked")
                    private static <T> T cast(Object o) { return (T) o; }
                    public Object fluent() { return cast(this); }
                }
            }
            """);
    ClassOrInterfaceDeclaration template = templateCu.findFirst(
            ClassOrInterfaceDeclaration.class,
            c -> "RTemplate".equals(c.getNameAsString())).orElseThrow();
    var ann = template.getAnnotationByName("Permute").orElseThrow();
    PermuteConfig config = AnnotationReader.readPermute(ann);
    CompilationUnit result = InlineGenerator.generate(parentCu, template, config,
            PermuteConfig.buildAllCombinations(config));
    assertThat(result.toString()).contains("R2 fluent()");
}

@Test
public void testSelfReturnInferenceDoesNotOverrideExplicitPermuteReturn() throws Exception {
    // Explicit @PermuteReturn takes precedence — inference must not fire when
    // @PermuteReturn is present on the method.
    CompilationUnit parentCu = StaticJavaParser.parse(
            "package io.p.t; public class P {}");
    CompilationUnit templateCu = StaticJavaParser.parse("""
            package io.p.t;
            import io.quarkiverse.permuplate.*;
            public class P {
                @Permute(varName="i", from="2", to="2", className="Q${i}", inline=true)
                static class QTemplate {
                    @PermuteReturn(className="String", typeArgs="", alwaysEmit=true)
                    public Object explicit() { return this; }
                }
            }
            """);
    ClassOrInterfaceDeclaration template = templateCu.findFirst(
            ClassOrInterfaceDeclaration.class,
            c -> "QTemplate".equals(c.getNameAsString())).orElseThrow();
    var ann = template.getAnnotationByName("Permute").orElseThrow();
    PermuteConfig config = AnnotationReader.readPermute(ann);
    CompilationUnit result = InlineGenerator.generate(parentCu, template, config,
            PermuteConfig.buildAllCombinations(config));
    // @PermuteReturn already ran: return type is String, not Q2
    assertThat(result.toString()).contains("String explicit()");
    assertThat(result.toString()).doesNotContain("Q2 explicit()");
}

@Test
public void testSelfReturnInferenceIgnoresMethodWithMixedReturns() throws Exception {
    // If a method has some 'return this;' and some other returns, it is NOT inferred.
    // All return statements must return this/cast(this) for inference to fire.
    CompilationUnit parentCu = StaticJavaParser.parse(
            "package io.p.t; public class M {}");
    CompilationUnit templateCu = StaticJavaParser.parse("""
            package io.p.t;
            import io.quarkiverse.permuplate.*;
            public class M {
                @Permute(varName="i", from="2", to="2", className="N${i}", inline=true)
                static class NTemplate {
                    // Mixed: one branch returns this, another returns null — not inferred
                    public Object mixed(boolean b) {
                        if (b) return this;
                        return null;
                    }
                }
            }
            """);
    ClassOrInterfaceDeclaration template = templateCu.findFirst(
            ClassOrInterfaceDeclaration.class,
            c -> "NTemplate".equals(c.getNameAsString())).orElseThrow();
    var ann = template.getAnnotationByName("Permute").orElseThrow();
    PermuteConfig config = AnnotationReader.readPermute(ann);
    CompilationUnit result = InlineGenerator.generate(parentCu, template, config,
            PermuteConfig.buildAllCombinations(config));
    // Return type must stay Object — mixed returns prevent inference
    assertThat(result.toString()).contains("Object mixed(");
}
```

- [ ] **Step 3: Run to confirm tests fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=InlineGenerationTest#testSelfReturnInferenceSetsReturnTypeAutomatically+testSelfReturnInferenceWithCastThis \
    -q 2>&1 | tail -10
```

Expected: FAIL — `Fluent2 step()` not found in generated source (return type is still `Object`).

---

### Task 2: Implement `isSelfReturning()` helper

- [ ] **Step 1: Add private helper to `InlineGenerator`**

Find the end of the private helper methods section:
```bash
grep -n "^    private static\|^    private " \
    /Users/mdproctor/claude/permuplate/permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | tail -15
```

Add this method to `InlineGenerator`:

```java
/**
 * Returns {@code true} if every return statement in the method body returns {@code this}
 * or {@code cast(this)}, making this method a fluent self-return candidate for inference.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Return type must be {@code Object} (the sentinel type).</li>
 *   <li>Body must be present and contain at least one return statement.</li>
 *   <li>Every return statement must return {@code this} or a call to a single-arg
 *       method named {@code cast} with {@code this} as the argument.</li>
 * </ul>
 */
private static boolean isSelfReturning(MethodDeclaration method) {
    if (!method.getType().asString().equals("Object")) return false;
    java.util.Optional<com.github.javaparser.ast.stmt.BlockStmt> body = method.getBody();
    if (body.isEmpty()) return false;
    java.util.List<com.github.javaparser.ast.stmt.ReturnStmt> returns =
            body.get().findAll(com.github.javaparser.ast.stmt.ReturnStmt.class);
    if (returns.isEmpty()) return false;
    return returns.stream().allMatch(ret -> {
        if (ret.getExpression().isEmpty()) return false;
        com.github.javaparser.ast.expr.Expression expr = ret.getExpression().get();
        // Direct: return this;
        if (expr instanceof com.github.javaparser.ast.expr.ThisExpr) return true;
        // Wrapped: return cast(this);
        if (expr instanceof com.github.javaparser.ast.expr.MethodCallExpr call) {
            return call.getNameAsString().equals("cast")
                    && call.getArguments().size() == 1
                    && call.getArgument(0) instanceof com.github.javaparser.ast.expr.ThisExpr;
        }
        return false;
    });
}
```

---

### Task 3: Implement `applySelfReturnInference()` post-pass

- [ ] **Step 1: Add the post-pass method to `InlineGenerator`**

```java
/**
 * Post-pass: for methods with {@code Object} sentinel return type and no explicit
 * {@code @PermuteReturn} or {@code @PermuteSelf}, detect whether every return statement
 * returns {@code this} or {@code cast(this)}, and if so, set the return type to the
 * current generated class (including all type parameters).
 *
 * <p>This pass runs after all transformers, so type parameters are fully expanded and
 * {@code @PermuteSelf} / {@code @PermuteReturn} annotations have already been consumed.
 */
private static void applySelfReturnInference(TypeDeclaration<?> classDecl) {
    String className = classDecl.getNameAsString();
    String typeParams = classDecl.getTypeParameters().stream()
            .map(tp -> tp.getNameAsString())
            .collect(java.util.stream.Collectors.joining(", "));
    String returnTypeSrc = typeParams.isEmpty()
            ? className
            : className + "<" + typeParams + ">";

    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
        // Skip if already has a non-Object return type (either inferred or explicit)
        if (!method.getType().asString().equals("Object")) return;
        // Skip if @PermuteReturn is still present (explicit transformer not yet run — should not happen)
        boolean hasExplicitReturn = method.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("PermuteReturn")
                            || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteReturn"));
        if (hasExplicitReturn) return;
        if (!isSelfReturning(method)) return;
        method.setType(StaticJavaParser.parseType(returnTypeSrc));
    });
}
```

- [ ] **Step 2: Register the post-pass in `generate()`**

In `InlineGenerator.generate()`, after the main transformer pipeline (after `PermuteSelfTransformer.transform(...)` if Plan A is already applied, or after `PermuteAnnotationTransformer` which runs last), add:

```java
// Self-return inference: auto-detect methods returning 'this' with Object sentinel
// return type and set return type to the current generated class.
applySelfReturnInference(generated);
```

To find the right location:
```bash
grep -n "PermuteSelfTransformer\|PermuteAnnotationTransformer\|expandSealedPermits\|applySelf" \
    /Users/mdproctor/claude/permuplate/permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | head -15
```

The post-pass must run BEFORE `expandSealedPermits` (which is a structural post-pass on the CU, not the class).

---

### Task 4: Build and verify

- [ ] **Step 1: Build the plugin**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-maven-plugin -am -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run the new tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=InlineGenerationTest#testSelfReturnInferenceSetsReturnTypeAutomatically+testSelfReturnInferenceWithCastThis+testSelfReturnInferenceDoesNotOverrideExplicitPermuteReturn+testSelfReturnInferenceIgnoresMethodWithMixedReturns \
    -q 2>&1 | tail -5
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 3: Full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

Expected: all tests pass.

- [ ] **Step 4: Build the full project including DSL examples**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

---

### Task 5: Commit

- [ ] **Step 1: Commit**

```bash
git add \
    permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java
git commit -m "feat: self-return inference in Maven plugin — auto-detect return this; body (closes #81)"
```
