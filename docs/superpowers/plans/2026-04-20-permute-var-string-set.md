# @PermuteVar String-Set Axis — Verification and Documentation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify `@PermuteVar(values={...})` string-set cross-product generation works end-to-end (it is already implemented), add a happy-path test, a `capitalize()` integration test, an apt-example, and update README and CLAUDE.md.

**Architecture:** No engine changes — implementation already exists. Tasks are: confirm tests pass, add missing test coverage, add example, update docs. Closes GitHub issue #72, part of epic #71.

**Tech Stack:** Java 17, Google compile-testing, Maven.

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java` | Add 2 new tests |
| Create | `permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/WidgetFactory1.java` | APT example using @PermuteVar(values=) |
| Modify | `README.md` | Add string-set @PermuteVar section |
| Modify | `CLAUDE.md` | Add key decision entry |

---

### Task 1: Happy-path cross-product test

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java`

- [ ] **Step 1: Write the test (TDD — expect it to pass immediately)**

Add to `StringSetIterationTest.java`:

```java
/**
 * @PermuteVar(values={...}) produces a cross-product of classes.
 * i ∈ [2,3], T ∈ {"Sync","Async"} → 4 generated classes.
 */
@Test
public void testPermuteVarStringValuesProducesCrossProduct() {
    Compilation compilation = compile("io.example.SyncPair2",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.Permute;\n" +
            "import io.quarkiverse.permuplate.PermuteVar;\n" +
            "@Permute(varName=\"i\", from=\"2\", to=\"3\",\n" +
            "         className=\"${T}Pair${i}\",\n" +
            "         extraVars={@PermuteVar(varName=\"T\", values={\"Sync\",\"Async\"})})\n" +
            "public class SyncPair2 {}");

    assertThat(compilation).succeeded();

    // All four cross-product classes must be generated
    assertThat(compilation.generatedSourceFile("io.example.SyncPair2")).isPresent();
    assertThat(compilation.generatedSourceFile("io.example.SyncPair3")).isPresent();
    assertThat(compilation.generatedSourceFile("io.example.AsyncPair2")).isPresent();
    assertThat(compilation.generatedSourceFile("io.example.AsyncPair3")).isPresent();

    // Template class name SyncPair2 is re-generated (it IS in the generated set)
    String src = ProcessorTestSupport.sourceOf(
            compilation.generatedSourceFile("io.example.SyncPair2").get());
    assertThat(src).contains("class SyncPair2");
    assertThat(src).doesNotContain("@Permute");
    assertThat(src).doesNotContain("@PermuteVar");
}
```

- [ ] **Step 2: Run to confirm it passes**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=StringSetIterationTest#testPermuteVarStringValuesProducesCrossProduct -q 2>&1 | tail -5
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

If it fails, the engine has a bug — investigate `PermuteConfig.buildAllCombinations()` lines around `extra.values.length > 0`.

---

### Task 2: `capitalize()` integration test

**Files:**
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java`

- [ ] **Step 1: Write the test**

Add to `StringSetIterationTest.java`:

```java
/**
 * String variable from @PermuteVar(values=) works with capitalize() JEXL function.
 * Values are lowercase; capitalize() produces proper class names.
 */
@Test
public void testPermuteVarStringValuesWithCapitalize() {
    Compilation compilation = compile("io.example.WidgetTemplate",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.Permute;\n" +
            "import io.quarkiverse.permuplate.PermuteVar;\n" +
            "@Permute(varName=\"i\", from=\"2\", to=\"2\",\n" +
            "         className=\"${capitalize(T)}Widget${i}\",\n" +
            "         extraVars={@PermuteVar(varName=\"T\", values={\"fancy\",\"plain\"})})\n" +
            "public class WidgetTemplate {}");

    assertThat(compilation).succeeded();
    assertThat(compilation.generatedSourceFile("io.example.FancyWidget2")).isPresent();
    assertThat(compilation.generatedSourceFile("io.example.PlainWidget2")).isPresent();

    String fancySrc = ProcessorTestSupport.sourceOf(
            compilation.generatedSourceFile("io.example.FancyWidget2").get());
    assertThat(fancySrc).contains("class FancyWidget2");
}
```

- [ ] **Step 2: Run**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=StringSetIterationTest#testPermuteVarStringValuesWithCapitalize -q 2>&1 | tail -5
```

Expected: PASS.

- [ ] **Step 3: Run full test suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -q 2>&1 | tail -3
```

- [ ] **Step 4: Stage and commit**

```bash
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/StringSetIterationTest.java
git commit -m "test: add @PermuteVar string-set cross-product and capitalize integration tests (closes #72)"
```

---

### Task 3: APT example

**Files:**
- Create: `permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/WidgetFactory1.java`

- [ ] **Step 1: Create the example**

```java
package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteVar;

/**
 * Demonstrates {@code @PermuteVar(values={"Sync","Async"})} for string-set cross-product generation.
 *
 * <p>Generates 4 classes: {@code SyncWidget1Factory}, {@code SyncWidget2Factory},
 * {@code AsyncWidget1Factory}, {@code AsyncWidget2Factory}.
 *
 * <p>The {@code T} string variable is available in all JEXL expressions.
 * {@code capitalize(T)} converts the first character to uppercase — useful when
 * the {@code values} list uses lowercase strings to match Java naming conventions.
 */
@Permute(varName = "i", from = "1", to = "2",
         className = "${capitalize(T)}Widget${i}Factory",
         extraVars = { @PermuteVar(varName = "T", values = { "sync", "async" }) })
public class WidgetFactory1 {

    /** Returns the widget type name (e.g. {@code "sync"} or {@code "async"}). */
    public String type() {
        return "sync"; // ${T} — overridden by @PermuteDeclr if needed
    }

    /** Returns the widget arity index. */
    public int index() {
        return 1; // ${i}
    }
}
```

- [ ] **Step 2: Build the apt-examples module**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-apt-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS (4 files generated).

- [ ] **Step 3: Verify generated files exist**

```bash
find /Users/mdproctor/claude/permuplate/permuplate-apt-examples/target \
    -name "SyncWidget*Factory.java" -o -name "AsyncWidget*Factory.java" 2>/dev/null | sort
```

Expected: `SyncWidget1Factory.java`, `SyncWidget2Factory.java`, `AsyncWidget1Factory.java`, `AsyncWidget2Factory.java`.

- [ ] **Step 4: Stage and commit**

```bash
git add permuplate-apt-examples/src/main/java/io/quarkiverse/permuplate/example/WidgetFactory1.java
git commit -m "example: WidgetFactory demonstrates @PermuteVar string-set cross-product generation"
```

---

### Task 4: Update README and CLAUDE.md

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add string-set section to README `@PermuteVar` coverage**

Find the `### @PermuteVar` or `@Permute extraVars` section in README.md. Add after the existing integer-range example:

```markdown
**String-set axis:** Use `values` instead of `from`/`to` to cross-product over named strings:

```java
@Permute(varName="i", from="1", to="2",
         className="${capitalize(T)}Widget${i}Factory",
         extraVars={@PermuteVar(varName="T", values={"sync","async"})})
public class WidgetFactory1 { ... }
// Generates: SyncWidget1Factory, SyncWidget2Factory, AsyncWidget1Factory, AsyncWidget2Factory
```

String variables bind as `String` in JEXL. `capitalize(T)`, `decapitalize(T)`, and string comparison (`${T} == 'sync'`) all work. `values` and `from`/`to` are mutually exclusive — specifying both is a compile error.
```

- [ ] **Step 2: Add CLAUDE.md key decision entry**

In the key decisions table in CLAUDE.md, add:

```
| `@PermuteVar` string-set axis | `values={"A","B"}` produces cross-product with string variable. Already fully wired: annotation has `String[] values() default {}`, `PermuteVarConfig` carries it, `AnnotationReader.readExtraVars()` reads it, `PermuteConfig.buildAllCombinations()` cross-products correctly. Variable binds as `String` in JEXL — arithmetic expressions don't work; use string functions like `capitalize()`. `from`/`to` must be omitted when `values` is used. Tested in `StringSetIterationTest`. |
```

- [ ] **Step 3: Full build**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

- [ ] **Step 4: Stage and commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: document @PermuteVar string-set values axis with capitalize integration example"
```
