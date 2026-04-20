# @Permute macros= Attribute Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `String[] macros() default {}` to `@Permute`. Format: `"name=jexlExpr"`. Each macro is evaluated in the permutation context (with `i` and other loop variables available) and stored as a named variable usable via `${name}` in all other JEXL expressions for that permutation. Eliminates copy-pasting the same complex JEXL expression across multiple annotations.

**Architecture:** Macros are evaluated at context-build time, inside `PermuteConfig.buildAllCombinations()`, immediately after all loop variables are bound and before any annotation processing. The result is added to the combination `Map<String, Object>` just like any other variable, so the existing `EvaluationContext` sees it automatically.

**Epic:** #79

**Tech Stack:** Java 17, Apache Commons JEXL3, APT + Maven plugin, Google compile-testing.

---

## File Structure

| Action | Path | What changes |
|---|---|---|
| Modify | `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/Permute.java` | Add `macros()` attribute |
| Modify | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteConfig.java` | Parse macros; evaluate in `buildAllCombinations()` |
| Modify | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/AnnotationReader.java` | Read `macros` from annotation (if macros not stored in config) |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTest.java` | New macro tests |

---

### Task 1: Annotation change

- [ ] **Step 1: Read current `Permute.java`**

```bash
cat /Users/mdproctor/claude/permuplate/permuplate-annotations/src/main/java/io/quarkiverse/permuplate/Permute.java
```

- [ ] **Step 2: Add `macros()` to `Permute.java`**

Add after the `strings()` attribute:

```java
    /**
     * Named JEXL expression macros, evaluated in the permutation context.
     * Each entry has the format {@code "name=jexlExpression"}.
     *
     * <p>Macros are evaluated after all loop variables (e.g. {@code i}, {@code j}) are
     * bound, so they can reference those variables. The result is stored under {@code name}
     * and is available as {@code ${name}} in any other JEXL expression in this template,
     * including subsequent macros (evaluated in declaration order).
     *
     * <p>Example: a self-type-args shorthand for a Join family:
     * <pre>{@code
     * @Permute(varName="i", from="1", to="6",
     *          className="Join${i}First",
     *          macros={"selfArgs=\"'END, DS, '\" + typeArgList(1, i, 'alpha')"})
     * }</pre>
     * Then use {@code ${selfArgs}} in any {@code typeArgs=} or other JEXL attribute.
     */
    String[] macros() default {};
```

- [ ] **Step 3: Build annotations module**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-annotations -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

---

### Task 2: Read existing `PermuteConfig` and `AnnotationReader`

- [ ] **Step 1: Read `PermuteConfig.java`**

```bash
cat /Users/mdproctor/claude/permuplate/permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteConfig.java
```

Identify: the `PermuteConfig` record/class fields, what `buildAllCombinations()` returns and how it builds combination maps, and whether `macros` needs to be a new field or can be read inline.

- [ ] **Step 2: Read `AnnotationReader.java`**

```bash
cat /Users/mdproctor/claude/permuplate/permuplate-core/src/main/java/io/quarkiverse/permuplate/core/AnnotationReader.java
```

Identify: how it reads `@Permute` attributes, where `strings` is parsed, and whether `macros` is read here or in the processor.

---

### Task 3: Tests (TDD — write before implementing)

- [ ] **Step 1: Add tests to `PermuteTest.java`**

Find the file:
```bash
find /Users/mdproctor/claude/permuplate/permuplate-tests -name "PermuteTest.java"
```

Add these tests:

```java
@Test
public void testMacroAvailableInAllJexlExpressions() {
    // A macro defined in macros= is available via ${name} in all other JEXL expressions
    // including className, @PermuteReturn typeArgs, and @PermuteDeclr type.
    var source = JavaFileObjects.forSourceString("io.ex.M2",
            """
                    package io.ex;
                    import io.quarkiverse.permuplate.*;
                    @Permute(varName="i", from="2", to="3",
                             className="M${i}",
                             macros={"half=\"\" + (i / 2)"})
                    public class M2 {
                        // ${half} is evaluated from the macro — 2/2=1, 3/2=1
                        @PermuteDeclr(type="Object", name="field${half}")
                        Object fieldHalf;
                    }
                    """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    // half for i=2: 2/2 = 1  → field1
    String src2 = sourceOf(c.generatedSourceFile("io.ex.M2").orElseThrow());
    assertThat(src2).contains("field1");
    // half for i=3: 3/2 = 1 (integer division) → field1
    String src3 = sourceOf(c.generatedSourceFile("io.ex.M3").orElseThrow());
    assertThat(src3).contains("field1");
}

@Test
public void testMacroComposedFromLoopVariable() {
    // A macro can concatenate a complex expression based on the loop variable i.
    // This is the primary use case: avoiding repetition of typeArgList(1,i,'alpha').
    var source = JavaFileObjects.forSourceString("io.ex.Join2",
            """
                    package io.ex;
                    import io.quarkiverse.permuplate.*;
                    @Permute(varName="i", from="2", to="3",
                             className="Join${i}",
                             macros={"targs=typeArgList(1, i, 'T')"})
                    public class Join2<T1, T2> {
                        @PermuteReturn(className="Join${i}", typeArgs="${targs}", alwaysEmit=true)
                        public Object self() { return this; }
                    }
                    """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    String src2 = sourceOf(c.generatedSourceFile("io.ex.Join2").orElseThrow());
    assertThat(src2).contains("Join2<T1, T2> self()");
    String src3 = sourceOf(c.generatedSourceFile("io.ex.Join3").orElseThrow());
    assertThat(src3).contains("Join3<T1, T2, T3> self()");
}

@Test
public void testMacroEvaluatedPerPermutation() {
    // Macros are evaluated per permutation — each combination gets a fresh evaluation.
    // A macro referencing i should produce different results for each i.
    var source = JavaFileObjects.forSourceString("io.ex.P2",
            """
                    package io.ex;
                    import io.quarkiverse.permuplate.*;
                    @Permute(varName="i", from="2", to="3",
                             className="P${i}",
                             macros={"prev=\"\" + (i - 1)"})
                    public class P2 {
                        @PermuteDeclr(type="String", name="prev${prev}")
                        String prevTemplate;
                    }
                    """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    // i=2: prev = 1 → prev1
    assertThat(sourceOf(c.generatedSourceFile("io.ex.P2").orElseThrow())).contains("prev1");
    // i=3: prev = 2 → prev2
    assertThat(sourceOf(c.generatedSourceFile("io.ex.P3").orElseThrow())).contains("prev2");
}

@Test
public void testMacroCanReferenceEarlierMacroInSameDeclaration() {
    // Macros are evaluated in declaration order, so a later macro can reference an earlier one.
    var source = JavaFileObjects.forSourceString("io.ex.Q2",
            """
                    package io.ex;
                    import io.quarkiverse.permuplate.*;
                    @Permute(varName="i", from="2", to="2",
                             className="Q${i}",
                             macros={"base=\"\" + i", "label=\"\" + \"item\" + base"})
                    public class Q2 {
                        @PermuteDeclr(type="String", name="${label}")
                        String labelField;
                    }
                    """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    // base = "2", label = "item2"
    assertThat(sourceOf(c.generatedSourceFile("io.ex.Q2").orElseThrow())).contains("item2");
}
```

- [ ] **Step 2: Run to confirm tests fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=PermuteTest#testMacroAvailableInAllJexlExpressions+testMacroComposedFromLoopVariable \
    -q 2>&1 | tail -10
```

Expected: FAIL — `macros` attribute not found on `@Permute`.

---

### Task 4: Parse macros in `PermuteConfig`

- [ ] **Step 1: Add `macros` field to `PermuteConfig`**

Add a `List<String> macros` field (or `String[] macros`) to `PermuteConfig`. If `PermuteConfig` is a record, add it as a new component. If it is a class, add a field and update the constructor.

Example (record form):
```java
public record PermuteConfig(
        String varName,
        String from,
        String to,
        String className,
        boolean inline,
        boolean keepTemplate,
        String[] values,
        String[] strings,
        String[] macros,     // NEW
        List<PermuteVarConfig> vars) {
    // ...
}
```

- [ ] **Step 2: Read `macros` in `AnnotationReader.readPermute()`**

In `AnnotationReader.java`, in the `readPermute()` method, read the `macros` attribute and store it in `PermuteConfig`. Use the same pattern as `strings`:

```java
String[] macros = readStringArrayAttr(ann, "macros");
```

(Where `readStringArrayAttr` is the existing helper for string array attributes — or use the inline equivalent if no such helper exists.)

- [ ] **Step 3: Evaluate macros in `buildAllCombinations()`**

In `PermuteConfig.buildAllCombinations()` (or wherever combination maps are built), after all loop variables are bound and before the map is added to the result list, evaluate each macro in order and add its result to the map:

```java
// Evaluate macros in declaration order. Each macro can reference previously bound
// variables including earlier macros in the same list.
if (config.macros() != null) {
    for (String macro : config.macros()) {
        int eq = macro.indexOf('=');
        if (eq < 0) continue; // malformed — skip silently
        String name = macro.substring(0, eq).trim();
        String expr = macro.substring(eq + 1).trim();
        if (name.isEmpty()) continue;
        try {
            // Build a temporary context with the current vars to evaluate the macro
            EvaluationContext tmpCtx = new EvaluationContext(vars);
            Object value = tmpCtx.evaluateRaw(expr);
            vars.put(name, value);
        } catch (Exception ignored) {
            // Macro evaluation failure is silently ignored; downstream JEXL that uses
            // ${name} will then fail with a more meaningful error.
        }
    }
}
```

Note: this requires `EvaluationContext.evaluateRaw(String expr)` — a method that evaluates a raw JEXL expression (not an interpolated template). Check whether this already exists:
```bash
grep -n "evaluateRaw\|evaluate\|createExpression\|createScript" \
    /Users/mdproctor/claude/permuplate/permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java \
    | head -15
```

If not, add it — it's a one-liner wrapping `JEXL.createExpression(expr).evaluate(jexlCtx)`.

---

### Task 5: Build and verify

- [ ] **Step 1: Build core**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-core -am -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Build processor and plugin**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor,permuplate-maven-plugin -am -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run the new tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests \
    -Dtest=PermuteTest#testMacroAvailableInAllJexlExpressions+testMacroComposedFromLoopVariable+testMacroEvaluatedPerPermutation+testMacroCanReferenceEarlierMacroInSameDeclaration \
    -q 2>&1 | tail -5
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 4: Full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

Expected: all tests pass.

---

### Task 6: Apply to Drools DSL and full build

- [ ] **Step 1: Identify the complex repeated typeArgList expression in `JoinBuilder.java`**

```bash
grep -n "typeArgList\|selfArgs\|macros" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    | head -20
```

- [ ] **Step 2: Add `macros=` to the `@Permute` annotation on `Join0First`**

If the same `typeArgList(1, i, 'alpha')` expression appears on many `@PermuteReturn` annotations, extract it to a macro:

```java
@Permute(varName="i", from="1", to="6", className="Join${i}First", inline=true, keepTemplate=false,
         macros={"selfTypeArgs=\"'END, DS, '\" + typeArgList(1, i, 'alpha')"})
```

Then replace `typeArgs = "'END, DS, ' + typeArgList(1, i, 'alpha')"` with `typeArgs = "${selfTypeArgs}"` in each `@PermuteReturn`.

- [ ] **Step 3: Build the full project**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

---

### Task 7: Update CLAUDE.md and commit

- [ ] **Step 1: Update CLAUDE.md**

Add to the key decisions table:

```
| `macros=` on `@Permute` | Evaluated in `buildAllCombinations()` after all loop variables are bound, before any annotation processing. Evaluated in declaration order — later macros can reference earlier ones. Results added to the combination map, so `EvaluationContext` sees them transparently. |
```

- [ ] **Step 2: Commit**

```bash
git add \
    permuplate-annotations/src/main/java/io/quarkiverse/permuplate/Permute.java \
    permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteConfig.java \
    permuplate-core/src/main/java/io/quarkiverse/permuplate/core/AnnotationReader.java \
    permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java \
    permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteTest.java \
    permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    CLAUDE.md
git commit -m "feat: macros= attribute on @Permute for named JEXL expressions (closes #83)"
```
