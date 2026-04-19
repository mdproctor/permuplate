# Permuplate Engine Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend Permuplate's annotation engine with four improvements identified during the Drools DSL review: inner-variable access in @PermuteBody/@PermuteMethod, qualified-name TYPE_USE, `alwaysEmit` on @PermuteReturn, and `capitalize`/`decapitalize` JEXL functions.

**Architecture:** All four items are independent. C1 and C2 modify existing transformers. C3 adds a new annotation attribute. C4 adds new JEXL built-ins. Each item has its own test, commit, and zero dependency on the others.

**Tech Stack:** Java 17, JavaParser 3.28.0, Apache Commons JEXL3, APT, Maven.

---

## File Structure

| Action | Path | What changes |
|---|---|---|
| Modify | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` | C1: add PermuteBodyTransformer call in applyPermuteMethod; C2: verify/extend transformNewExpressions |
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` | C1: add PermuteBodyTransformer call in applyPermuteMethodApt; C3: alwaysEmit check |
| Modify | `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturn.java` | C3: add alwaysEmit() attribute |
| Modify | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java` | C4: capitalize, decapitalize JEXL functions |
| Modify | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java` | C2: fix transformNewExpressions if test reveals gap |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java` | C1 test |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java` | C1 inline test, C2 test |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDeclrTest.java` | C2 test |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteReturnTest.java` or `DegenerateInputTest.java` | C3 test |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTest.java` | C4 test |

---

### Task 1 (C1): `@PermuteBody` inner-variable access in `@PermuteMethod` context

**Problem:** `@PermuteMethod` processes clones with an inner context containing the method variable (e.g. `n`). `@PermuteBody` runs later with the outer context only — so `${n}` in a body template is unresolved when used alongside `@PermuteMethod`.

**Fix:** In `applyPermuteMethod` (InlineGenerator) and `applyPermuteMethodApt` (PermuteProcessor), after `transformNewExpressions(clone, innerCtx)`, run `PermuteBodyTransformer` on the clone with `innerCtx`. Uses the same temp-class wrapper pattern already used for `PermuteParamTransformer`.

**Files:**
- Modify: `permuplate-maven-plugin/.../InlineGenerator.java` (around line 527)
- Modify: `permuplate-processor/.../PermuteProcessor.java` (around line 1051)
- Test: `permuplate-tests/.../PermuteMethodTest.java`
- Test: `permuplate-tests/.../InlineGenerationTest.java`

- [ ] **Step 1: Write the APT failing test**

Add to `PermuteMethodTest.java`:

```java
@Test
public void testPermuteBodyAccessesInnerMethodVariable() {
    // @PermuteBody on a @PermuteMethod template must evaluate the body with the
    // inner method variable (n) available, not just the outer permutation variable (i).
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.Counter1",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteMethod;
                    import io.quarkiverse.permuplate.PermuteBody;
                    @Permute(varName="i", from="1", to="1", className="Counter")
                    public class Counter1 {
                        @PermuteMethod(varName="n", from="2", to="3", name="count${n}")
                        @PermuteBody(body="{ return ${n}; }")
                        public int countTemplate() { return 0; }
                    }
                    """);
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);
    assertThat(compilation).succeeded();
    String src = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.Counter").orElseThrow());
    assertThat(src).contains("int count2()");
    assertThat(src).contains("return 2");
    assertThat(src).contains("int count3()");
    assertThat(src).contains("return 3");
    assertThat(src).doesNotContain("return 0");
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteMethodTest#testPermuteBodyAccessesInnerMethodVariable -q 2>&1 | tail -10
```

Expected: FAIL — generated source contains `return 0` instead of `return 2`/`return 3`.

- [ ] **Step 3: Fix `applyPermuteMethod` in InlineGenerator**

In `InlineGenerator.java`, find the block ending with `transformNewExpressions(clone, innerCtx)` (around line 527). Insert immediately after it, before the name-setting block:

```java
                // Apply @PermuteBody with innerCtx so body templates can reference
                // the @PermuteMethod inner variable (e.g. ${n}).
                // Uses the same temp-class wrapper pattern as PermuteParamTransformer.
                {
                    ClassOrInterfaceDeclaration tmpBody = new ClassOrInterfaceDeclaration();
                    tmpBody.addMember(clone);
                    PermuteBodyTransformer.transform(tmpBody, innerCtx);
                    if (!tmpBody.getMethods().isEmpty())
                        clone = tmpBody.getMethods().get(0);
                }
```

- [ ] **Step 4: Fix `applyPermuteMethodApt` in PermuteProcessor**

In `PermuteProcessor.java`, find the line `io.quarkiverse.permuplate.core.PermuteDeclrTransformer.processMethodParamDeclr(clone, innerCtx)` (around line 1051). Insert immediately after it:

```java
                // Apply @PermuteBody with innerCtx so body templates can reference
                // the @PermuteMethod inner variable.
                {
                    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration tmpBody =
                            new com.github.javaparser.ast.body.ClassOrInterfaceDeclaration();
                    tmpBody.addMember(clone);
                    io.quarkiverse.permuplate.core.PermuteBodyTransformer.transform(tmpBody, innerCtx);
                    if (!tmpBody.getMethods().isEmpty())
                        clone = tmpBody.getMethods().get(0);
                }
```

- [ ] **Step 5: Build and run the APT test**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-maven-plugin,permuplate-processor -am -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteMethodTest#testPermuteBodyAccessesInnerMethodVariable -q 2>&1 | tail -5
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 6: Write the InlineGenerator failing test**

Add to `InlineGenerationTest.java`:

```java
@Test
public void testPermuteBodyInPermuteMethodUsesInnerVariable() throws Exception {
    // InlineGenerator path: @PermuteBody on a @PermuteMethod clone must see the
    // inner variable (n), not just the outer (i).
    CompilationUnit parentCu = StaticJavaParser.parse("""
            package io.permuplate.test;
            public class Host {
            }
            """);

    CompilationUnit templateCu = StaticJavaParser.parse("""
            package io.permuplate.test;
            import io.quarkiverse.permuplate.Permute;
            import io.quarkiverse.permuplate.PermuteMethod;
            import io.quarkiverse.permuplate.PermuteBody;
            public class Host {
                @Permute(varName="i", from="1", to="1", className="Gauge", inline=true)
                static class GaugeTemplate {
                    @PermuteMethod(varName="n", from="2", to="3", name="level${n}")
                    @PermuteBody(body="{ return ${n}; }")
                    public int levelTemplate() { return 0; }
                }
            }
            """);

    ClassOrInterfaceDeclaration templateClass = templateCu
            .findFirst(ClassOrInterfaceDeclaration.class,
                    c -> "GaugeTemplate".equals(c.getNameAsString()))
            .orElseThrow();

    var ann = templateClass.getAnnotationByName("Permute").orElseThrow();
    PermuteConfig config = AnnotationReader.readPermute(ann);
    List<Map<String, Object>> combinations = PermuteConfig.buildAllCombinations(config);

    CompilationUnit result = InlineGenerator.generate(parentCu, templateClass, config, combinations);
    String src = result.toString();

    assertThat(src).contains("int level2()");
    assertThat(src).contains("return 2");
    assertThat(src).contains("int level3()");
    assertThat(src).contains("return 3");
    assertThat(src).doesNotContain("return 0");
}
```

- [ ] **Step 7: Run inline test**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=InlineGenerationTest#testPermuteBodyInPermuteMethodUsesInnerVariable -q 2>&1 | tail -5
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 8: Full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add \
    permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/InlineGenerationTest.java
git commit -m "feat: @PermuteBody on @PermuteMethod clones evaluates body with inner method variable"
```

---

### Task 2 (C2): Verify and fix `@PermuteDeclr TYPE_USE` on qualified names

**Problem:** `@PermuteDeclr TYPE_USE` on a qualified constructor like `new @PermuteDeclr(type="Outer.Inner${i}") Outer.Inner1<>()` may not correctly replace the name. JavaParser places the annotation on the scope type (`Outer`), and the existing `transformNewExpressions` code has a fallback to read from the scope — but whether the replacement produces the correct full qualified type has not been tested.

**Strategy:** Write a test first. If it passes, the engine already handles this and we skip to B2 (DSL cleanup). If it fails, fix `transformNewExpressions`.

**Files:**
- Test: `permuplate-tests/.../PermuteDeclrTest.java`
- Potentially: `permuplate-core/.../PermuteDeclrTransformer.java`

- [ ] **Step 1: Write the failing test**

Add to `PermuteDeclrTest.java`. Find the file first:
```bash
find /Users/mdproctor/claude/permuplate/permuplate-tests -name "PermuteDeclrTest.java"
```

Add this test:

```java
@Test
public void testPermuteDeclrTypeUseOnQualifiedConstructor() {
    // Regression guard: @PermuteDeclr TYPE_USE on a qualified new expression
    // (e.g. new @PermuteDeclr(type="Outer.Inner${i}") Outer.Inner1<>())
    // must correctly replace the full qualified type name, not just the simple name.
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.Outer1",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteDeclr;
                    public class Outer1 {
                        public static class Box1 {}
                        public static class Box2 {}
                        @Permute(varName="i", from="2", to="2", className="Outer${i}")
                        public static class Outer1Template {
                            public Object make() {
                                return new @PermuteDeclr(type="Outer1.Box${i}") Outer1.Box1<>();
                            }
                        }
                    }
                    """);
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);
    assertThat(compilation).succeeded();
    String src = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.Outer2").orElseThrow());
    assertThat(src).contains("new Outer1.Box2()");
    assertThat(src).doesNotContain("new Outer1.Box1()");
}
```

- [ ] **Step 2: Run the test**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteDeclrTest#testPermuteDeclrTypeUseOnQualifiedConstructor -q 2>&1 | tail -10
```

**If the test PASSES:** The engine already handles this correctly. Skip to Step 6 (commit the test). The reflection in the DSL will be eliminated in Plan 2 (B2).

**If the test FAILS:** Proceed to Step 3.

- [ ] **Step 3 (if test failed): Read `transformNewExpressions` fully**

```bash
grep -n "transformNewExpressions\|hasPermuteDeclr\|getScope\|setType" \
    /Users/mdproctor/claude/permuplate/permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java \
    | head -30
```

Then read the full method. Identify the exact point where scope-annotation replacement fails to produce the correct qualified output type.

- [ ] **Step 4 (if test failed): Fix `transformNewExpressions`**

The expected fix: when the evaluated `newTypeName` contains a `.` (qualified), `StaticJavaParser.parseType(newTypeName)` already produces a scoped `ClassOrInterfaceType`. Ensure `newExpr.setType(newType)` preserves the original diamond inference type arguments (if `<>` was present on the original). 

The issue is likely that the original `newExpr` has a `<>` (empty type arguments) which needs to be preserved when the type is replaced. Add after `newExpr.setType(newType)`:

```java
// Preserve empty type arguments (diamond inference) from the original if present.
// StaticJavaParser.parseType() produces a type with no type args; the original
// ObjectCreationExpr tracked them separately.
// (No action needed — ObjectCreationExpr type args are separate from the type node.)
```

If the actual bug is different, fix accordingly based on what Step 3 reveals.

- [ ] **Step 5 (if test failed): Re-run the test to confirm it passes**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-core -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteDeclrTest#testPermuteDeclrTypeUseOnQualifiedConstructor -q 2>&1 | tail -5
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 6: Full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

- [ ] **Step 7: Commit**

```bash
git add \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDeclrTest.java
# Add transformer only if it was changed:
# git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java
git commit -m "test: verify @PermuteDeclr TYPE_USE works on qualified constructor names"
```

---

### Task 3 (C3): `alwaysEmit` attribute on `@PermuteReturn`

**Problem:** `when="true"` appears 12 times in the DSL to opt out of boundary omission. The intent is non-obvious from the string `"true"`. A boolean `alwaysEmit=true` is self-documenting.

**Files:**
- Modify: `permuplate-annotations/.../PermuteReturn.java`
- Modify: `permuplate-maven-plugin/.../InlineGenerator.java` (boundary omission check)
- Modify: `permuplate-processor/.../PermuteProcessor.java` (boundary omission check)
- Test: `permuplate-tests/.../PermuteReturnTest.java` (find with `find /Users/mdproctor/claude/permuplate/permuplate-tests -name "PermuteReturnTest*"` — use `DegenerateInputTest.java` if not found)

- [ ] **Step 1: Write the failing test**

Find or create the test file:
```bash
find /Users/mdproctor/claude/permuplate/permuplate-tests -name "*.java" | xargs grep -l "PermuteReturn" | head -3
```

Add to the appropriate test file (or `PermuteTest.java` if no dedicated file):

```java
@Test
public void testAlwaysEmitPreventsOmission() {
    // alwaysEmit=true is equivalent to when="true": method is never omitted
    // even when className evaluates to a class outside the generated set.
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.Pipe1",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    import io.quarkiverse.permuplate.PermuteReturn;
                    @Permute(varName="i", from="2", to="3", className="Pipe${i}")
                    public class Pipe1 {
                        @PermuteReturn(className="Pipe${i+1}", typeArgs="'DS'", alwaysEmit=true)
                        public Object next() { return null; }
                    }
                    """);
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);
    assertThat(compilation).succeeded();
    // Pipe3.next() would normally be omitted (Pipe4 not in generated set).
    // alwaysEmit=true must prevent that.
    String src3 = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.Pipe3").orElseThrow());
    assertThat(src3).contains("next()");
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteTest#testAlwaysEmitPreventsOmission -q 2>&1 | tail -10
```

Expected: compilation error — `alwaysEmit` is not a valid attribute yet.

- [ ] **Step 3: Add `alwaysEmit()` to `PermuteReturn.java`**

In `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturn.java`, add after the `when()` attribute:

```java
    /**
     * When {@code true}, this method is always generated regardless of whether
     * {@code className} evaluates to a class in the generated set. Equivalent to
     * {@code when="true"} but self-documenting. Takes precedence over {@code when}.
     */
    boolean alwaysEmit() default false;
```

- [ ] **Step 4: Add `alwaysEmit` check in InlineGenerator**

In `InlineGenerator.java`, find the boundary omission check (search for `cfg.when().isEmpty()`). The block looks like:

```java
boolean shouldGenerate;
if (cfg.when().isEmpty()) {
    shouldGenerate = allGeneratedNames.contains(evaluatedClass);
} else {
    ...
}
```

Replace with:

```java
boolean shouldGenerate;
if (cfg.alwaysEmit()) {
    shouldGenerate = true;
} else if (cfg.when().isEmpty()) {
    shouldGenerate = allGeneratedNames.contains(evaluatedClass);
} else {
    try {
        shouldGenerate = Boolean.parseBoolean(ctx.evaluate("${" + cfg.when() + "}"));
    } catch (Exception ignored) {
        shouldGenerate = allGeneratedNames.contains(evaluatedClass);
    }
}
```

Note: `cfg` is whatever type holds the parsed `@PermuteReturn` values in InlineGenerator. First run:
```bash
grep -n "class.*PermuteReturnCfg\|record.*PermuteReturn\|cfg\.when\|cfg\.className" \
    /Users/mdproctor/claude/permuplate/permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | head -10
```
to find the config type, then add `boolean alwaysEmit()` to it and wire it to the `alwaysEmit` annotation attribute.

- [ ] **Step 5: Add `alwaysEmit` check in PermuteProcessor**

In `PermuteProcessor.java`, find the boundary omission check (around line 1220):

```java
if (whenExpr == null || whenExpr.isEmpty()) {
    shouldGenerate = generatedSet.contains(evaluatedClassName);
} else {
```

Replace with:

```java
String alwaysEmitAttr = getAnnAttr(ann, "alwaysEmit");
if ("true".equals(alwaysEmitAttr)) {
    shouldGenerate = true;
} else if (whenExpr == null || whenExpr.isEmpty()) {
    shouldGenerate = generatedSet.contains(evaluatedClassName);
} else {
```

- [ ] **Step 6: Build and run the test**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations,permuplate-maven-plugin,permuplate-processor -am -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteTest#testAlwaysEmitPreventsOmission -q 2>&1 | tail -5
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 7: Full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

- [ ] **Step 8: Commit**

```bash
git add \
    permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturn.java \
    permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTest.java
git commit -m "feat: add alwaysEmit=true to @PermuteReturn as self-documenting alternative to when=\"true\""
```

---

### Task 4 (C4): `capitalize()` and `decapitalize()` JEXL functions

**Problem:** String-set permutations (`@Permute(values={"Negation","Existence"})`) need case-manipulation to derive method names and class names from the same variable. No such JEXL function exists.

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java`
- Test: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTest.java`

- [ ] **Step 1: Write the failing test**

Add to `PermuteTest.java`:

```java
@Test
public void testCapitalizeAndDecapitalizeInJexlExpressions() {
    // capitalize() and decapitalize() must be available in all JEXL expressions.
    // Test via @Permute className which is a JEXL-interpolated string.
    var source = JavaFileObjects.forSourceString(
            "io.permuplate.example.Widget1",
            """
                    package io.permuplate.example;
                    import io.quarkiverse.permuplate.Permute;
                    @Permute(varName="T", values={"widget","gadget"}, className="${capitalize(T)}Factory")
                    public class Widget1 {
                    }
                    """);
    Compilation compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(source);
    assertThat(compilation).succeeded();
    assertThat(compilation.generatedSourceFile("io.permuplate.example.WidgetFactory").isPresent()).isTrue();
    assertThat(compilation.generatedSourceFile("io.permuplate.example.GadgetFactory").isPresent()).isTrue();

    String src = sourceOf(compilation
            .generatedSourceFile("io.permuplate.example.WidgetFactory").orElseThrow());
    assertThat(src).contains("class WidgetFactory");
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteTest#testCapitalizeAndDecapitalizeInJexlExpressions -q 2>&1 | tail -10
```

Expected: FAIL — `capitalize` is not a recognized JEXL function.

- [ ] **Step 3: Add `capitalize` and `decapitalize` to `PermuplateStringFunctions`**

In `EvaluationContext.java`, add to `PermuplateStringFunctions` after the existing `typeArgList` method:

```java
        public static String capitalize(String s) {
            if (s == null || s.isEmpty())
                return s;
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        public static String decapitalize(String s) {
            if (s == null || s.isEmpty())
                return s;
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
```

- [ ] **Step 4: Register as JEXL lambdas**

In `EvaluationContext.java`, add two new `JexlScript` fields alongside `JEXL_ALPHA` and `JEXL_LOWER`:

```java
    private static final JexlScript JEXL_CAPITALIZE = JEXL.createScript(
            "function(s) { if (s == null || s.length() == 0) s; else s.substring(0, 1).toUpperCase() + s.substring(1); }");

    private static final JexlScript JEXL_DECAPITALIZE = JEXL.createScript(
            "function(s) { if (s == null || s.length() == 0) s; else s.substring(0, 1).toLowerCase() + s.substring(1); }");
```

Note: JEXL3 lambdas use JavaScript-like syntax. Verify by running a quick test. If JEXL string methods differ, use the Java static method delegation pattern instead (same as `alpha` delegates to `__throwHelper`).

- [ ] **Step 5: Add to `JEXL_FUNCTIONS` map**

`JEXL_FUNCTIONS` uses `Map.of(...)` which has a 10-entry limit. Since there are already 4 entries, add:

```java
    private static final Map<String, Object> JEXL_FUNCTIONS = new java.util.HashMap<>(Map.of(
            "alpha", JEXL_ALPHA,
            "lower", JEXL_LOWER,
            "typeArgList", JEXL_TYPE_ARG_LIST,
            "__throwHelper", JEXL_THROW_HELPER,
            "capitalize", JEXL_CAPITALIZE,
            "decapitalize", JEXL_DECAPITALIZE));
```

(Change from `Map.of` to `new HashMap<>(Map.of(...))` to allow expansion; or use two `Map.of` merged via `Stream.concat`.)

- [ ] **Step 6: Build and run the test**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-core -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteTest#testCapitalizeAndDecapitalizeInJexlExpressions -q 2>&1 | tail -5
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 7: Full test suite**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTest.java
git commit -m "feat: add capitalize() and decapitalize() JEXL built-in functions"
```

---

### Task 5: Update CLAUDE.md

- [ ] **Step 1: Add entries to the key decisions table in CLAUDE.md**

Add these two rows:

```
| `@PermuteBody` in `@PermuteMethod` context | `applyPermuteMethod()` in InlineGenerator and `applyPermuteMethodApt()` in PermuteProcessor now apply `PermuteBodyTransformer` on each clone with the inner `(i,j)` context. Uses the same temp-class wrapper pattern as `PermuteParamTransformer`. |
| `capitalize(s)` / `decapitalize(s)` JEXL functions | Registered as JEXL lambdas alongside `alpha` and `lower`. Available in all JEXL expressions without a prefix. Useful for deriving method names and class names from string-set permutation variables (`values={...}`). |
```

Also add `alwaysEmit` to the `@PermuteReturn` annotation entry in the annotations table (update the Purpose column).

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document @PermuteBody/@PermuteMethod interaction and new JEXL functions"
```
