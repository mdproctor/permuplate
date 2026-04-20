# Batch 8 — DSL Deep Polish: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply ten targeted improvements to Permuplate core and the Drools DSL, derived from a
systematic audit of every remaining annotation verbosity, hand-coded method, and structural limit.

**Architecture:** Items 1–5 are DSL-only or tiny core extensions needing no new annotations.
Items 6–10 add new annotations and InlineGenerator logic. Each item is committed against its own
GitHub issue under a single batch-8 epic. All items use TDD: failing test first, minimal
implementation, then DSL integration.

**Tech Stack:** Java 17, JavaParser 3.28.0, Apache Commons JEXL3, Google compile-testing,
JUnit 4, Maven 3.x. All Maven commands use `/opt/homebrew/bin/mvn`.

---

## File Map

| Item | Core files changed | DSL files changed | Test files |
|---|---|---|---|
| 1 | — | `NegationScope.java` (rename→`NotScope.java`), `JoinBuilder.java`, `RuleDefinition.java` | `RuleBuilderTest.java` (existing, must pass) |
| 2 | `EvaluationContext.java` | `JoinBuilder.java` | `EvaluationContextTest.java` (new) |
| 3 | `EvaluationContext.java` | — | `EvaluationContextTest.java` |
| 4 | — | `JoinBuilder.java` | `RuleBuilderTest.java` (existing + new variable-filter tests) |
| 5 | — | `JoinBuilder.java`, `RuleBuilder.java`, `ParametersFirst.java` | `RuleBuilderTest.java` (existing, must pass) |
| 6 | `PermuteReturn.java` (annotation), `AnnotationReader.java`, `InlineGenerator.java` | `JoinBuilder.java` | `PermuteReturnTypeParamTest.java` (new), `RuleBuilderTest.java` |
| 7 | `PermuteMacros.java` (new annotation), `AnnotationReader.java`, `InlineGenerator.java` | `JoinBuilder.java` | `PermuteMacrosTest.java` (new), `RuleBuilderTest.java` |
| 8 | `PermuteMixin.java` (new annotation), `AnnotationReader.java`, `InlineGenerator.java` | `ExtendsRuleMixin.java` (new), `RuleBuilder.java`, `ParametersFirst.java` | `PermuteMixinTest.java` (new), `ExtensionPointTest.java` (existing) |
| 9 | `InlineGenerator.java` | `BaseTuple.java` | `SuperCallInferenceTest.java` (new), `TupleAsTest.java` (existing) |
| 10 | `PermuteExtendsChain.java` (new annotation), `AnnotationReader.java`, `InlineGenerator.java` | `BaseTuple.java` | `PermuteExtendsChainTest.java` (new), `TupleAsTest.java` (existing) |

### Key absolute paths
- Annotations: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/`
- Core: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/`
- Maven plugin: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/`
- DSL templates: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/`
- DSL tests: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/`
- Unit/integration tests: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/`

---

## Task 0: Epic and Issues

**Files:** none (GitHub only)

- [ ] **Step 1: Create the batch-8 epic**

```bash
gh issue create \
  --title "batch 8: DSL deep polish — 10 items" \
  --body "$(cat <<'EOF'
Systematic audit of every remaining verbosity, hand-coded method, and structural limit in the
Drools DSL after batches 6–7. Ten targeted items; see
docs/superpowers/specs/2026-04-20-dsl-batch8-design.md for the full spec.

Items:
1. not()/exists() string-set conversion (DSL rename + existing @PermuteMethod values=)
2. max()/min() JEXL built-in functions
3. typeArgList custom-prefix support
4. Variable filter overloads templated
5. Method macros for bilinear join / extendsRule / OOPath
6. @PermuteReturn(typeParam=) new attribute — unify Path2
7. @PermuteMacros — file-level shared macros
8. @PermuteMixin — solve ADR-0006 extendsRule duplication
9. Constructor super-call inference (eliminate @PermuteStatements on Tuple chain)
10. @PermuteExtendsChain — extends-previous shorthand
EOF
)" \
  --label "epic"
```

Expected: issue #91 created.

- [ ] **Step 2: Create child issues #92–#101**

```bash
gh issue create --title "item 1: not()/exists() string-set conversion" \
  --body "Rename NegationScope→NotScope, ExistenceScope→ExistsScope. Replace k=1..2 ternaries with @PermuteMethod(values={\"not\",\"exists\"}) + capitalize(). Epic: #91" \
  --label "enhancement"

gh issue create --title "item 2: max()/min() JEXL built-in functions" \
  --body "Add max(a,b) and min(a,b) to EvaluationContext. Eliminates filterLatest ternary. Epic: #91" \
  --label "enhancement"

gh issue create --title "item 3: typeArgList custom-prefix support" \
  --body "Unknown styles treated as literal prefix+index (V→V1,V2). Enables item 4. Epic: #91" \
  --label "enhancement"

gh issue create --title "item 4: template variable filter overloads" \
  --body "Replace hand-coded filter(Variable<V1>,Variable<V2>,Pred3) overloads with @PermuteMethod(m=2..3) template. Depends on #94. Epic: #91" \
  --label "enhancement"

gh issue create --title "item 5: method macros for bilinear join / extendsRule / OOPath" \
  --body "Add @PermuteMethod macros= to three annotation sites to name repeated typeArgList(...) expressions. Epic: #91" \
  --label "enhancement"

gh issue create --title "item 6: @PermuteReturn(typeParam=) — unify Path2 into template" \
  --body "New typeParam attribute on @PermuteReturn sets return type to a type parameter (e.g. END). Eliminates hand-coded Path2 class. Epic: #91" \
  --label "enhancement"

gh issue create --title "item 7: @PermuteMacros — shared file-level macros" \
  --body "New annotation on outer class; macros apply to all nested @Permute templates. Eliminates alphaList duplication across Join0Second and Join0First. Epic: #91" \
  --label "enhancement"

gh issue create --title "item 8: @PermuteMixin — solve ADR-0006 extendsRule duplication" \
  --body "New annotation injects methods from a mixin class into template before processing. Eliminates extendsRule() duplication from RuleBuilderTemplate + ParametersFirstTemplate. Epic: #91" \
  --label "enhancement"

gh issue create --title "item 9: constructor super-call inference" \
  --body "When template extends previous-in-family via @PermuteExtendsChain/@PermuteExtends and constructor params extend parent's by one, auto-generate super() call. Eliminates @PermuteStatements on Tuple chain. Epic: #91" \
  --label "enhancement"

gh issue create --title "item 10: @PermuteExtendsChain shorthand" \
  --body "New annotation equivalent to @PermuteExtends(className=\"X\${i-1}\", typeArgs=\"typeArgList(1,i-1,'alpha')\") with family inferred from className pattern. Epic: #91" \
  --label "enhancement"
```

Verify all 11 issues created (epic + 10 items). Record the actual issue numbers returned — they may differ from #91–#101 if issues were created between sessions.

---

## Task 1: not()/exists() string-set conversion (closes #92)

**Files:**
- Rename: `DSL-TEMPLATES/NegationScope.java` → `NotScope.java`
- Modify: `DSL-TEMPLATES/JoinBuilder.java`
- Modify: `DSL-JAVA/RuleDefinition.java`
- Test: `DSL-TESTS/RuleBuilderTest.java` (existing; must pass without change)

Where `DSL-TEMPLATES` = `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/`
and `DSL-JAVA` = `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/`
and `DSL-TESTS` = `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/`

- [ ] **Step 1: Rename NegationScope.java → NotScope.java**

Move the file and update its content:

```java
// permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/NotScope.java
package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;

/**
 * Scope builder returned by {@code JoinNSecond.not()}.
 * Template (keepTemplate=true) generates {@link ExistsScope} via string-set permutation.
 */
@Permute(varName = "T", values = {"Exists"}, className = "${T}Scope",
         inline = false, keepTemplate = true)
public class NotScope<OUTER, DS> {

    private final OUTER outer;
    private final RuleDefinition<DS> scopeRd;

    public NotScope(OUTER outer, RuleDefinition<DS> scopeRd) {
        this.outer = outer;
        this.scopeRd = scopeRd;
    }

    @PermuteDeclr(type = "${T}Scope<OUTER, DS>")
    public NotScope<OUTER, DS> join(java.util.function.Function<DS, DataSource<?>> source) {
        scopeRd.addSource(source);
        return this;
    }

    @PermuteDeclr(type = "${T}Scope<OUTER, DS>")
    public NotScope<OUTER, DS> filter(Object predicate) {
        scopeRd.addFilter(predicate);
        return this;
    }

    public OUTER end() {
        return outer;
    }
}
```

Delete the old `NegationScope.java`.

- [ ] **Step 2: Rename addNegation → addNot, addExistence → addExists in RuleDefinition.java**

In `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java`:

```java
// Before:
public void addNegation(RuleDefinition<DS> notScope) {
    negations.add(notScope);
}
public void addExistence(RuleDefinition<DS> existsScope) {
    existences.add(existsScope);
}

// After:
public void addNot(RuleDefinition<DS> notScope) {
    negations.add(notScope);
}
public void addExists(RuleDefinition<DS> existsScope) {
    existences.add(existsScope);
}
```

Also update the Javadoc references (search for "NegationScope" and "ExistenceScope" in the Javadoc block above these methods and replace with "NotScope" and "ExistsScope").

- [ ] **Step 3: Update JoinBuilder.java — replace the not()/exists() method**

In `DSL-TEMPLATES/JoinBuilder.java`, replace the `scopeTemplate()` method (annotated with `@PermuteMethod(varName="k", from="1", to="2", ...)`) with:

```java
@PermuteMethod(varName = "scope", values = {"not", "exists"}, name = "${scope}")
@PermuteReturn(className = "${capitalize(scope)}Scope",
               typeArgs = "'Join' + i + 'Second<END, DS, ' + alphaList + '>, DS'",
               alwaysEmit = true)
@PermuteBody(body = "{ RuleDefinition<DS> scopeRd = new RuleDefinition<>(\"${scope}-scope\"); rd.add${capitalize(scope)}(scopeRd); return new ${capitalize(scope)}Scope<>(this, scopeRd); }")
public Object scopeTemplate() {
    return null; // replaced by @PermuteBody
}
```

- [ ] **Step 4: Run the full build to confirm zero regressions**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`. All 277+ tests pass. Generated `not()` still returns `NotScope`; `exists()` returns `ExistsScope`. No test references `NegationScope` or `ExistenceScope` by class name.

- [ ] **Step 5: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/NotScope.java
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git rm permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/NegationScope.java
git commit -m "$(cat <<'EOF'
feat: convert not()/exists() to @PermuteMethod(values=) — eliminate 3 ternaries (closes #92)

Rename NegationScope→NotScope, ExistenceScope→ExistsScope.
addNegation→addNot, addExistence→addExists in RuleDefinition.
@PermuteMethod(values={"not","exists"}) + capitalize() replaces k=1..2 integer axis.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: max()/min() JEXL built-in functions (closes #93)

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/EvaluationContextTest.java`
- Modify: `DSL-TEMPLATES/JoinBuilder.java` (apply to filterLatest)

- [ ] **Step 1: Write failing unit test for max()/min()**

Create `permuplate-tests/src/test/java/io/quarkiverse/permuplate/EvaluationContextTest.java`:

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;

import java.util.Map;

import org.junit.Test;

import io.quarkiverse.permuplate.core.EvaluationContext;

public class EvaluationContextTest {

    private static String eval(String template, Map<String, Object> vars) {
        return new EvaluationContext(vars).evaluate(template);
    }

    // ---- max() ----

    @Test
    public void testMaxFirstArgLarger() {
        assertThat(eval("${max(3, 1)}", Map.of())).isEqualTo("3");
    }

    @Test
    public void testMaxSecondArgLarger() {
        assertThat(eval("${max(1, 3)}", Map.of())).isEqualTo("3");
    }

    @Test
    public void testMaxEqual() {
        assertThat(eval("${max(2, 2)}", Map.of())).isEqualTo("2");
    }

    @Test
    public void testMaxWithVariable() {
        // max(2, i) when i=1 → 2; when i=3 → 3
        assertThat(eval("${max(2, i)}", Map.of("i", 1))).isEqualTo("2");
        assertThat(eval("${max(2, i)}", Map.of("i", 3))).isEqualTo("3");
    }

    // ---- min() ----

    @Test
    public void testMinFirstArgSmaller() {
        assertThat(eval("${min(1, 3)}", Map.of())).isEqualTo("1");
    }

    @Test
    public void testMinSecondArgSmaller() {
        assertThat(eval("${min(3, 1)}", Map.of())).isEqualTo("1");
    }

    @Test
    public void testMinEqual() {
        assertThat(eval("${min(2, 2)}", Map.of())).isEqualTo("2");
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest=EvaluationContextTest -q
```

Expected: FAIL — `max` and `min` not defined in JEXL context.

- [ ] **Step 3: Add max() and min() to EvaluationContext**

In `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java`:

After the existing `JEXL_DECAPITALIZE` declaration, add:

```java
/** JEXL lambda implementing {@code max(a, b)}: returns the larger of two values. */
private static final JexlScript JEXL_MAX = JEXL.createScript(
        "function(a, b) { a >= b ? a : b; }");

/** JEXL lambda implementing {@code min(a, b)}: returns the smaller of two values. */
private static final JexlScript JEXL_MIN = JEXL.createScript(
        "function(a, b) { a <= b ? a : b; }");
```

Update `JEXL_FUNCTIONS` to include the new entries (note: `Map.of` only accepts up to 10 entries — if it's already at 10, switch to `Map.ofEntries` or use a static initializer block):

```java
// If JEXL_FUNCTIONS currently uses Map.of(...) with 6 entries, add:
private static final Map<String, Object> JEXL_FUNCTIONS = Map.of(
        "alpha", JEXL_ALPHA,
        "lower", JEXL_LOWER,
        "typeArgList", JEXL_TYPE_ARG_LIST,
        "__throwHelper", JEXL_THROW_HELPER,
        "capitalize", JEXL_CAPITALIZE,
        "decapitalize", JEXL_DECAPITALIZE,
        "max", JEXL_MAX,
        "min", JEXL_MIN);
```

Also add the Java-side static methods to `PermuplateStringFunctions` (if that class exists as a parallel implementation):

```java
public static int max(int a, int b) { return Math.max(a, b); }
public static int min(int a, int b) { return Math.min(a, b); }
```

Update `EvaluationContext.throwFor(String style)` Javadoc to mention `max` and `min` are NOT reserved names (they're separate functions, not styles).

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest=EvaluationContextTest -q
```

Expected: all 7 tests pass.

- [ ] **Step 5: Apply max() to filterLatest in JoinBuilder.java**

In `DSL-TEMPLATES/JoinBuilder.java`, update the `filterLatest` method annotation:

```java
// Before:
@PermuteMethod(varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}", name = "filter")

// After:
@PermuteMethod(varName = "x", from = "${max(2, i)}", to = "${i}", name = "filter")
```

- [ ] **Step 6: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`. All tests pass.

- [ ] **Step 7: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/EvaluationContextTest.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "$(cat <<'EOF'
feat: add max()/min() JEXL built-ins; apply max(2,i) in filterLatest (closes #93)

Eliminates ternary ${i > 1 ? i : i+1} → ${max(2,i)}.
EvaluationContextTest covers both functions with variable-bound and constant args.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: typeArgList custom-prefix support (closes #94)

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java`
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/EvaluationContextTest.java` (add tests)

- [ ] **Step 1: Write failing tests in EvaluationContextTest**

Add to `EvaluationContextTest.java`:

```java
// ---- typeArgList custom prefix ----

@Test
public void testTypeArgListVPrefix() {
    assertThat(eval("${typeArgList(1, 3, 'V')}", Map.of())).isEqualTo("V1, V2, V3");
}

@Test
public void testTypeArgListLowercaseVPrefix() {
    assertThat(eval("${typeArgList(1, 2, 'v')}", Map.of())).isEqualTo("v1, v2");
}

@Test
public void testTypeArgListMultiCharPrefix() {
    assertThat(eval("${typeArgList(2, 4, 'Param')}", Map.of())).isEqualTo("Param2, Param3, Param4");
}

@Test
public void testTypeArgListTStyleStillWorks() {
    assertThat(eval("${typeArgList(1, 3, 'T')}", Map.of())).isEqualTo("T1, T2, T3");
}

@Test
public void testTypeArgListAlphaStillWorks() {
    assertThat(eval("${typeArgList(1, 3, 'alpha')}", Map.of())).isEqualTo("A, B, C");
}

@Test
public void testTypeArgListLowerStillWorks() {
    assertThat(eval("${typeArgList(1, 3, 'lower')}", Map.of())).isEqualTo("a, b, c");
}

@Test
public void testTypeArgListEmptyRange() {
    // from > to → empty string (existing behaviour)
    assertThat(eval("${typeArgList(3, 2, 'V')}", Map.of())).isEqualTo("");
}
```

- [ ] **Step 2: Run to confirm V/v/Param tests fail, T/alpha/lower pass**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest=EvaluationContextTest -q
```

Expected: V, v, Param tests fail with unknown-style error; T, alpha, lower tests pass.

- [ ] **Step 3: Update JEXL lambda in EvaluationContext**

In `EvaluationContext.java`, find `JEXL_TYPE_ARG_LIST`. Change the `else __throwHelper.throwFor(style)` branch to treat unknown styles as literal prefix + index:

```java
private static final JexlScript JEXL_TYPE_ARG_LIST = JEXL.createScript(
        "function(from, to, style) {" +
        "  var result = '';" +
        "  var first = true;" +
        "  for (var k = from; k <= to; k++) {" +
        "    if (!first) result = result + ', ';" +
        "    first = false;" +
        "    if (style == 'alpha') result = result + 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.substring(k - 1, k);" +
        "    else if (style == 'lower') result = result + 'abcdefghijklmnopqrstuvwxyz'.substring(k - 1, k);" +
        "    else result = result + style + k;" +
        "  }" +
        "  result;" +
        "}");
```

Note: `'T'` now falls through to `else result = result + style + k` which produces `T1, T2, T3` — identical to before. No separate `case 'T'` needed.

Also update the Java-side `typeArgList` method in `PermuplateStringFunctions` (if present) to match:

```java
public static String typeArgList(int from, int to, String style) {
    if (from > to) return "";
    StringBuilder sb = new StringBuilder();
    for (int k = from; k <= to; k++) {
        if (k > from) sb.append(", ");
        switch (style) {
            case "alpha" -> sb.append((char) ('A' + k - 1));
            case "lower" -> sb.append((char) ('a' + k - 1));
            default -> sb.append(style).append(k);  // custom prefix
        }
    }
    return sb.toString();
}
```

Update `throwFor(String style)` in `JexlThrowHelper` — remove it or keep for other callers; the JEXL script no longer calls it. If it's only called from the old JEXL script, it can remain but becomes unreachable (safe). Update Javadoc of `typeArgList` to document the new behaviour.

- [ ] **Step 4: Run all EvaluationContext tests**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest=EvaluationContextTest -q
```

Expected: all tests pass including new V/v/Param tests.

- [ ] **Step 5: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/EvaluationContextTest.java
git commit -m "$(cat <<'EOF'
feat: typeArgList treats unknown style as literal prefix+index (closes #94)

V→V1,V2,V3; v→v1,v2,v3; Param→Param1,Param2.
T/alpha/lower unchanged. Enables item 4 variable-filter templating.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Template variable filter overloads (closes #95)

**Files:**
- Modify: `DSL-TEMPLATES/JoinBuilder.java`
- Modify: `DSL-TESTS/RuleBuilderTest.java` (add variable-filter tests; existing ones must pass)

- [ ] **Step 1: Write failing correctness tests for variable filter at multiple arities**

Add these tests to `RuleBuilderTest.java` (they will fail if `filterVar` is missing):

```java
@Test
public void testVariableFilter2BoundVars() {
    Variable<Person> v1 = new Variable<>();
    Variable<Account> v2 = new Variable<>();

    var rule = builder.from(ctx -> ctx.persons())
            .var(v1)
            .join(ctx -> ctx.accounts())
            .var(v2)
            .filter(v1, v2, (ctx, p, a) -> p.name().startsWith("A") && a.balance() > 500)
            .fn((ctx, a, b) -> {
            });

    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(1); // Alice + ACC1
}

@Test
public void testVariableFilter3BoundVars() {
    Variable<Person> v1 = new Variable<>();
    Variable<Account> v2 = new Variable<>();
    Variable<Order> v3 = new Variable<>();

    var rule = builder.from(ctx -> ctx.persons())
            .var(v1)
            .join(ctx -> ctx.accounts())
            .var(v2)
            .join(ctx -> ctx.orders())
            .var(v3)
            .filter(v1, v2, v3, (ctx, p, a, o) -> p.name().startsWith("A") && a.balance() > 500 && o.amount() > 100)
            .fn((ctx, a, b, c) -> {
            });

    rule.run(ctx);
    assertThat(rule.executionCount()).isEqualTo(1); // Alice + ACC1 + ORD1
}
```

- [ ] **Step 2: Confirm these pass already (hand-coded methods exist)**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples \
  -Dtest=RuleBuilderTest#testVariableFilter2BoundVars+testVariableFilter3BoundVars -q
```

Expected: both pass (hand-coded methods exist). These tests verify correctness before and after.

- [ ] **Step 3: Replace hand-coded methods with @PermuteMethod template in JoinBuilder.java**

In `DSL-TEMPLATES/JoinBuilder.java`, inside `Join0First`, replace:

```java
// DELETE BOTH of these:
@PermuteSelf
public <V1, V2> Object filter(Variable<V1> v1, Variable<V2> v2,
                              Predicate3<DS, V1, V2> predicate) {
    rd.addVariableFilter(v1, v2, predicate);
    return this;
}

@PermuteSelf
public <V1, V2, V3> Object filter(Variable<V1> v1, Variable<V2> v2, Variable<V3> v3,
                                   Predicate4<DS, V1, V2, V3> predicate) {
    rd.addVariableFilter(v1, v2, v3, predicate);
    return this;
}
```

Replace with the single template:

```java
/**
 * Cross-fact filter using m bound variable(s).
 * All variables must have been bound via var() before this call.
 * Templated for m=2 and m=3 via @PermuteMethod.
 */
@PermuteMethod(varName = "m", from = "2", to = "3", name = "filter")
@PermuteSelf
@PermuteBody(body = "{ rd.addVariableFilter(${typeArgList(1, m, 'v')}, predicate); return this; }")
public <@PermuteTypeParam(varName = "k", from = "1", to = "${m}", name = "V${k}") V1>
        Object filterVar(
        @PermuteParam(varName = "k", from = "1", to = "${m}",
                      type = "Variable<V${k}>", name = "v${k}") Variable<V1> v1,
        @PermuteDeclr(type = "Predicate${m+1}<DS, ${typeArgList(1, m, 'V')}>")
        Object predicate) {
    return null; // replaced by @PermuteBody
}
```

Also add the missing imports to JoinBuilder.java (if not already present):
```java
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;
```

- [ ] **Step 4: Run the full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`. All variable filter tests pass.

- [ ] **Step 5: Run specific variable filter tests to confirm identical behaviour**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples \
  -Dtest=RuleBuilderTest#testVariableFilter2BoundVars+testVariableFilter3BoundVars -q
```

Expected: both pass.

- [ ] **Step 6: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java
git commit -m "$(cat <<'EOF'
feat: template variable filter overloads via @PermuteMethod(m=2..3) (closes #95)

Replaces two hand-coded filter(Variable<V1>,...) methods with a single @PermuteMethod
template using G4 @PermuteTypeParam and custom-prefix typeArgList('V'/'v').
Depends on #94 (typeArgList prefix). ~14 lines removed.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Method macros for bilinear join / extendsRule / OOPath (closes #96)

**Files:**
- Modify: `DSL-TEMPLATES/JoinBuilder.java`
- Modify: `DSL-TEMPLATES/RuleBuilder.java`
- Modify: `DSL-TEMPLATES/ParametersFirst.java`

This task is a pure refactoring: generated output is identical, only annotation expressions change.

- [ ] **Step 1: Update bilinear join in JoinBuilder.java**

Find the `joinBilinear` method template (annotated with `@PermuteMethod(varName="j", from="1", name="join")`). Add macros:

```java
@PermuteMethod(varName = "j", from = "1", name = "join",
               macros = {"joinAll=typeArgList(1,i+j,'alpha')",
                         "joinRight=typeArgList(i+1,i+j,'alpha')"})
@PermuteReturn(className = "Join${i+j}First",
               typeArgs = "'END, DS, ' + joinAll")
public <@PermuteTypeParam(varName = "k", from = "${i+1}", to = "${i+j}",
                           name = "${alpha(k)}") C> Object joinBilinear(
        @PermuteDeclr(type = "Join${j}Second<Void, DS, ${joinRight}>")
        Object secondChain) {
```

- [ ] **Step 2: Update OOPath pathTemplate in JoinBuilder.java**

Find `pathTemplate()` (annotated with `@PermuteMethod(varName="n", from="2", to="6", ...)`). Extend the existing macros and simplify typeArgs:

```java
@PermuteMethod(varName = "n", from = "2", to = "6", name = "path${n}",
               macros = {"tail=typeArgList(i,i+n-1,'alpha')",
                         "prev=typeArgList(i,i+n-2,'alpha')",
                         "outerJoin='Join'+(i+1)+'First<END, DS, '+alphaList+', BaseTuple.Tuple'+n+'<'+tail+'>>'",
                         "prevTuple='BaseTuple.Tuple'+(n-1)+'<'+prev+'>'"})
@PermuteReturn(
        className = "RuleOOPathBuilder.Path${n}",
        typeArgs = "outerJoin + ', ' + prevTuple + ', ' + tail",
        when = "i < 6")
```

- [ ] **Step 3: Update extendsRule in RuleBuilder.java**

Find `extendsRule()` method. Add method macro:

```java
@PermuteMethod(varName = "j", from = "2", to = "7", name = "extendsRule",
               macros = {"prevAlpha=typeArgList(1,j-1,'alpha')"})
@PermuteReturn(className = "JoinBuilder.Join${j-1}First",
               typeArgs = "'Void, DS, ' + prevAlpha",
               alwaysEmit = true)
public <@PermuteTypeParam(varName = "k", from = "1", to = "${j-1}", name = "${alpha(k)}") A>
        Object extendsRule(
        @PermuteDeclr(type = "RuleExtendsPoint.RuleExtendsPoint${j}<DS, ${prevAlpha}>")
        ExtendsPoint<DS> ep) {
```

- [ ] **Step 4: Update extendsRule in ParametersFirst.java (identical change)**

Same change as Step 3, applied to the `extendsRule()` in `ParametersFirstTemplate`.

- [ ] **Step 5: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`. Output is identical — this is a pure annotation-expression refactoring.

- [ ] **Step 6: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleBuilder.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/ParametersFirst.java
git commit -m "$(cat <<'EOF'
refactor: name repeated typeArgList(...) via @PermuteMethod macros= (closes #96)

bilinear join: joinAll/joinRight macros.
OOPath pathTemplate: outerJoin/prevTuple macros (on top of tail/prev).
extendsRule (both templates): prevAlpha macro.
Generated output unchanged — annotation expressions only.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: @PermuteReturn(typeParam=) — unify Path2 (closes #97)

**Files:**
- Modify: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturn.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Modify: `DSL-TEMPLATES/RuleOOPathBuilder.java` (unify Path2 into template)
- Create: `DSL-TESTS/PermuteReturnTypeParamTest.java` (new)

- [ ] **Step 1: Write failing test for @PermuteReturn(typeParam=)**

Create `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/PermuteReturnTypeParamTest.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class PermuteReturnTypeParamTest {

    /**
     * Verifies that Path2.path() still returns the END type (Void in top-level rules).
     * Before this item, Path2 was hand-coded. After, it's the i=2 case of the template.
     */
    @Test
    public void testPath2PathMethodExists() throws Exception {
        // Confirm Path2 class exists and has a path() method
        Class<?> path2Class = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleOOPathBuilder$Path2");
        assertThat(path2Class).isNotNull();

        java.lang.reflect.Method pathMethod = java.util.Arrays.stream(path2Class.getMethods())
                .filter(m -> m.getName().equals("path"))
                .findFirst().orElse(null);
        assertThat(pathMethod).isNotNull();

        // Return type should be Object (erased from generic END) — not Path1
        assertThat(pathMethod.getReturnType()).isEqualTo(Object.class);
    }

    /**
     * End-to-end: a 2-step OOPath chain using path2().path() returns the END value.
     * This is the key behavioural invariant — the terminal node returns the outer JOIN END.
     */
    @Test
    public void testOOPath2StepChainCompletes() {
        var ctx = buildCtx();
        var builder = new RuleBuilder<Ctx>();

        var rule = builder.from(c -> c.libraries())
                .path2(
                        (pc, lib) -> lib.rooms(),
                        (pc, room) -> true)
                .path(
                        (pc, room) -> room.books(),
                        (pc, book) -> book.published())
                .fn((c, lib, tuple) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isGreaterThan(0);
    }

    private Ctx buildCtx() {
        return new Ctx(
                DataSource.of(new Person("Alice", 30)),
                DataSource.of(new Account("ACC1", 1000.0)),
                DataSource.of(new Order("ORD1", 150.0)),
                DataSource.of(new Product("PRD1", 99.0)),
                DataSource.of(new Transaction("TXN1", 200.0)),
                DataSource.of(new Library("Lib", java.util.List.of(
                        new Room("Room1", java.util.List.of(
                                new Book("Book1", true, java.util.List.of(new Page("p1")))),
                                java.util.List.of())))));
    }
}
```

- [ ] **Step 2: Run to confirm tests pass (pre-change baseline)**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=PermuteReturnTypeParamTest -q
```

Expected: both tests pass (Path2 hand-coded currently). Record passing state.

- [ ] **Step 3: Add typeParam() attribute to PermuteReturn annotation**

In `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturn.java`:

Change `String className()` (currently required/no default) to have a default of `""`:

```java
/**
 * Template expression for the return type class name (e.g., {@code "Step${i+1}"}).
 * Mutually exclusive with {@code typeParam}. At least one of {@code className} or
 * {@code typeParam} must be non-empty; if both are non-empty, a compile error is reported.
 */
String className() default "";
```

Add the new attribute after `replaceLastTypeArgWith()`:

```java
/**
 * When non-empty, the return type is set to the named type parameter declared on
 * this class (e.g., {@code "END"}). Use at boundary cases where the method returns
 * the outer scope type rather than a generated class.
 *
 * <p>Mutually exclusive with {@code className}, {@code typeArgs}, {@code typeArgVarName},
 * and {@code replaceLastTypeArgWith}. When {@code typeParam} is set, boundary omission
 * does not apply — the method is always generated.
 */
String typeParam() default "";
```

- [ ] **Step 4: Update AnnotationReader to read typeParam**

In `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java`:

Add `typeParam` to `PermuteReturnConfig` record:

```java
public record PermuteReturnConfig(
        String className,
        String typeArgVarName,
        String typeArgFrom,
        String typeArgTo,
        String typeArgName,
        String typeArgs,
        String when,
        boolean alwaysEmit,
        String replaceLastTypeArgWith,
        String typeParam) {               // NEW

    public boolean hasTypeArgLoop() { ... }
    public boolean hasTypeArgsExpr() { ... }
    public boolean hasReplaceLastTypeArgWith() { ... }
    public boolean hasTypeParam() {       // NEW
        return typeParam != null && !typeParam.isEmpty();
    }

    public PermuteReturnConfig withTypeArgs(String newTypeArgs) {
        return new PermuteReturnConfig(className, typeArgVarName, typeArgFrom,
                typeArgTo, typeArgName, newTypeArgs, when, alwaysEmit,
                replaceLastTypeArgWith, typeParam);
    }
}
```

In `readPermuteReturn()`:
- Initialise `String typeParam = ""` alongside other locals
- Add `case "typeParam" -> typeParam = val;` to the switch
- Change the null-check: `if (className == null && typeParam.isEmpty()) return null;`
  (allow null className when typeParam is set)
- Update the constructor call to include `typeParam`

```java
public static PermuteReturnConfig readPermuteReturn(AnnotationExpr ann) {
    if (!(ann instanceof NormalAnnotationExpr normal))
        return null;

    String className = null, typeArgVarName = "", typeArgFrom = "1",
            typeArgTo = "", typeArgName = "", typeArgs = "", when = "",
            replaceLastTypeArgWith = "", typeParam = "";
    boolean alwaysEmit = false;

    for (MemberValuePair pair : normal.getPairs()) {
        String name = pair.getNameAsString();
        if (name.equals("alwaysEmit")) {
            alwaysEmit = pair.getValue() instanceof BooleanLiteralExpr b && b.getValue();
        } else {
            String val = PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            switch (name) {
                case "className" -> className = val;
                case "typeArgVarName" -> typeArgVarName = val;
                case "typeArgFrom" -> typeArgFrom = val;
                case "typeArgTo" -> typeArgTo = val;
                case "typeArgName" -> typeArgName = val;
                case "typeArgs" -> typeArgs = val;
                case "when" -> when = val;
                case "replaceLastTypeArgWith" -> replaceLastTypeArgWith = val;
                case "typeParam" -> typeParam = val;
            }
        }
    }
    // Allow: className present, typeParam absent — classic use
    // Allow: className absent, typeParam present — new use
    // Reject: both absent
    if ((className == null || className.isEmpty()) && typeParam.isEmpty())
        return null;
    if (className == null) className = "";
    return new PermuteReturnConfig(className, typeArgVarName, typeArgFrom,
            typeArgTo, typeArgName, typeArgs, when, alwaysEmit,
            replaceLastTypeArgWith, typeParam);
}
```

- [ ] **Step 5: Update applyPermuteReturn in InlineGenerator to handle typeParam**

In `permuplate-maven-plugin/.../InlineGenerator.java`, in the `applyPermuteReturn` method, add a new branch immediately after `cfg == null` check and before the `className` evaluation:

```java
// typeParam= path: set return type to the named type parameter, always emit
if (cfg.hasTypeParam()) {
    String evaluatedTypeParam = ctx.evaluate(cfg.typeParam());
    try {
        method.setType(StaticJavaParser.parseType(evaluatedTypeParam));
    } catch (Exception ignored) {
    }
    method.getAnnotations().removeIf(a -> a == annOpt.get());
    return;
}
```

This branch fires before the `className` evaluation block, so any other attributes on the same
`@PermuteReturn` (like `when=`) are intentionally ignored when `typeParam` is set. If conditional
`typeParam` is needed in future, add `when=` evaluation before this branch.

Also: in the `withTypeArgs` copy helper and anywhere `PermuteReturnConfig` is constructed
manually, add the `typeParam` field (pass `""` for all existing call sites that don't use it).

- [ ] **Step 6: Unify Path2 into Path3 template in RuleOOPathBuilder.java**

Replace the hand-coded `Path2` class and the `Path3` template with a single unified template:

```java
// DELETE the hand-coded Path2 class entirely.
// UPDATE Path3 template to start from i=2:

@Permute(varName = "i", from = "2", to = "6", className = "Path${i}",
         inline = true, keepTemplate = true)
public static class Path2<END, T extends BaseTuple, A, B,
        @PermuteTypeParam(varName = "k", from = "3", to = "${i}", name = "${alpha(k)}") C> {
    private final END end;
    private final RuleDefinition<?> rd;
    private final List<OOPathStep> steps;
    private final int rootIndex;

    public Path2(END end, RuleDefinition<?> rd, List<OOPathStep> steps, int rootIndex) {
        this.end = end;
        this.rd = rd;
        this.steps = steps;
        this.rootIndex = rootIndex;
    }

    @SuppressWarnings("unchecked")
    @PermuteReturn(when = "${i == 2}", typeParam = "END")
    @PermuteReturn(when = "${i > 2}", className = "RuleOOPathBuilder.Path${i-1}",
                   typeArgs = "'END, T, ' + typeArgList(2, i, 'alpha')",
                   alwaysEmit = true)
    @PermuteBody(when = "${i == 2}", body = "{ steps.add(new OOPathStep((ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact), (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child))); rd.addOOPathPipeline(rootIndex, steps); return end; }")
    @PermuteBody(when = "${i > 2}", body = "{ steps.add(new OOPathStep((ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact), (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child))); return new @PermuteDeclr(type = \"RuleOOPathBuilder.Path${i-1}\") Path2<>(end, rd, steps, rootIndex); }")
    public Object path(Function2<PathContext<T>, A, Iterable<B>> fn2,
            Predicate2<PathContext<T>, B> flt2) {
        return null; // replaced by @PermuteBody
    }
}
```

Note: `@PermuteReturn` is `@Repeatable` via `@PermuteReturns` container (check if this container
exists; if not, it may need to be added — see Step 6b below). `@PermuteBody` is already
`@Repeatable`.

- [ ] **Step 6b: Verify @PermuteReturn repeatability**

Check if `@PermuteReturn` has `@Repeatable` and a container annotation:

```bash
grep -n "Repeatable\|PermuteReturns" \
  /Users/mdproctor/claude/permuplate/permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturn.java
ls /Users/mdproctor/claude/permuplate/permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturns.java 2>/dev/null || echo "NOT FOUND"
```

If `@Repeatable` is not present:
1. Add `@Repeatable(PermuteReturns.class)` to `PermuteReturn`
2. Create `PermuteReturns.java`:

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteReturns {
    PermuteReturn[] value();
}
```

3. Update `InlineGenerator.applyPermuteReturn` to also detect `@PermuteReturns` container
   (same pattern as `@PermuteBody` / `@PermuteBodies`):

```java
// In applyPermuteReturn, find ALL @PermuteReturn annotations on the method:
List<AnnotationExpr> returnAnns = method.getAnnotations().stream()
        .filter(a -> {
            String n = a.getNameAsString();
            return n.equals("PermuteReturn") || n.equals("io.quarkiverse.permuplate.PermuteReturn")
                    || n.equals("PermuteReturns") || n.equals("io.quarkiverse.permuplate.PermuteReturns");
        })
        .toList();
```

Then unwrap the container if it's `@PermuteReturns`, and iterate all `@PermuteReturn` values,
picking the first one whose `when=` evaluates to true (or the only one if there's just one).

- [ ] **Step 7: Run full build and confirm OOPath tests still pass**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`. Run specifically:

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=RuleBuilderTest#testOOPath* -q
```

Expected: all OOPath tests pass. `PermuteReturnTypeParamTest` also passes.

- [ ] **Step 8: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturn.java
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturns.java 2>/dev/null; true
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleOOPathBuilder.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/PermuteReturnTypeParamTest.java
git commit -m "$(cat <<'EOF'
feat: @PermuteReturn(typeParam=) + unify Path2 into template (closes #97)

New typeParam= attribute sets return type to a named type param (e.g. END).
@PermuteReturn now @Repeatable (PermuteReturns container); first matching when= wins.
Path2 is now the i=2 case of the Path2..6 unified template; hand-coded class removed (~22 lines).

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: @PermuteMacros — shared file-level macros (closes #98)

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteMacros.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Modify: `DSL-TEMPLATES/JoinBuilder.java`
- Create: `DSL-TESTS/PermuteMacrosTest.java` (new)

- [ ] **Step 1: Write failing integration test**

Create `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/PermuteMacrosTest.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Verifies that @PermuteMacros on the outer class is accessible inside nested templates.
 * The DSL uses this for alphaList= shared between Join0Second and Join0First in JoinBuilder.
 */
public class PermuteMacrosTest {

    @Test
    public void testJoinBuilderCompilesWithSharedAlphaListMacro() {
        // If @PermuteMacros works, JoinBuilder compiles (the whole DSL build succeeds).
        // The existence of Join3Second with correct fn() signature proves alphaList was available.
        try {
            Class<?> join3Second = Class.forName(
                    "io.quarkiverse.permuplate.example.drools.JoinBuilder$Join3Second");
            java.lang.reflect.Method fn = java.util.Arrays.stream(join3Second.getMethods())
                    .filter(m -> m.getName().equals("fn"))
                    .findFirst().orElse(null);
            assertThat(fn).isNotNull();
            // fn(Consumer4<DS, A, B, C>) — type erasure gives Object param
            assertThat(fn.getParameterCount()).isEqualTo(1);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Join3Second should exist", e);
        }
    }
}
```

- [ ] **Step 2: Run to confirm passes before the change (alphaList is still in template macros=)**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=PermuteMacrosTest -q
```

Expected: passes (alphaList defined in each template). This is the baseline.

- [ ] **Step 3: Create PermuteMacros annotation**

Create `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteMacros.java`:

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares JEXL macro expressions that are available to all {@code @Permute} templates
 * nested within the annotated type. Macros are evaluated per-permutation with the current
 * loop variables in scope, in declaration order (later macros may reference earlier ones).
 *
 * <p>Format: same as {@link Permute#macros()} — each entry is {@code "name=jexlExpr"}.
 *
 * <p>Innermost macros take precedence: a template's own {@code macros=} attribute can shadow
 * a container macro with the same name.
 *
 * <p>Nesting: if multiple enclosing types have {@code @PermuteMacros}, all are collected
 * from outermost to innermost, then the template's own macros are appended last. Duplicate
 * names resolve to the last definition (innermost wins).
 *
 * <p>Example:
 * <pre>{@code
 * @PermuteMacros({"alphaList=typeArgList(1,i,'alpha')"})
 * public class MyContainer {
 *     @Permute(varName="i", from="1", to="6", className="MyClass${i}", inline=true)
 *     public static class MyClass1<A> {
 *         // alphaList is available here without repeating macros= on @Permute
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteMacros {
    /** Macro definitions in {@code "name=jexlExpr"} format. */
    String[] value();
}
```

- [ ] **Step 4: Add container-macro collection to InlineGenerator**

In `InlineGenerator.java`, add a private static helper that walks the enclosing type hierarchy
to collect `@PermuteMacros` entries. Add it near the other `applyPermute*` helpers:

```java
/**
 * Collects macro entries from all @PermuteMacros annotations on enclosing types of
 * the given template class, from outermost to innermost. The template's own
 * @Permute.macros= is NOT included here — call site appends those after.
 */
private static List<String> collectContainerMacros(TypeDeclaration<?> templateDecl) {
    List<String> result = new ArrayList<>();
    com.github.javaparser.ast.Node current = templateDecl.getParentNode().orElse(null);
    // Walk up: collect in outer-to-inner order, then reverse so innermost is last
    List<String[]> layers = new ArrayList<>();
    while (current instanceof TypeDeclaration<?> enclosing) {
        enclosing.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("PermuteMacros")
                        || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteMacros"))
                .findFirst()
                .ifPresent(ann -> {
                    // Read the String[] value attribute
                    if (ann instanceof NormalAnnotationExpr normal) {
                        normal.getPairs().stream()
                                .filter(p -> p.getNameAsString().equals("value"))
                                .findFirst()
                                .ifPresent(p -> {
                                    // value is either a single string or an ArrayInitializerExpr
                                    layers.add(readStringArrayAttr(p.getValue()));
                                });
                    } else if (ann instanceof SingleMemberAnnotationExpr single) {
                        layers.add(readStringArrayAttr(single.getMemberValue()));
                    }
                });
        current = enclosing.getParentNode().orElse(null);
    }
    // Reverse so innermost is last (will overwrite outer macros with same name)
    java.util.Collections.reverse(layers);
    layers.forEach(arr -> result.addAll(java.util.Arrays.asList(arr)));
    return result;
}

/** Reads a String or ArrayInitializerExpr into a String[]. */
private static String[] readStringArrayAttr(com.github.javaparser.ast.expr.Expression expr) {
    if (expr instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr) {
        return arr.getValues().stream()
                .map(v -> PermuteDeclrTransformer.stripQuotes(v.toString()))
                .toArray(String[]::new);
    }
    return new String[]{PermuteDeclrTransformer.stripQuotes(expr.toString())};
}
```

In `generate()`, where `EvaluationContext ctx = new EvaluationContext(vars)` is created for each
combination, prepend container macros to the `vars` map before constructing the context.
Specifically, find where `config.macros` are applied (in `buildAllCombinations` or just before
the per-combination loop) and prepend container macros there.

The cleanest insertion point: in `InlineGenerator.generate()`, just before the
`for (Map<String, Object> vars : filteredCombinations)` loop, collect container macros once
and build a prefix list:

```java
// Collect @PermuteMacros from enclosing types — evaluated per-permutation, prepended before template's own macros
List<String> containerMacros = collectContainerMacros(templateClassDecl);
```

Then, in `PermuteConfig.buildAllCombinations()` (or wherever macros are evaluated into the
`vars` map), prepend the container macros before the template's own macros. The exact mechanism
depends on how `config.macros` is currently injected into each combination's `vars` map.

Look for the code that calls `ctx.evaluate(macroExpr)` and stores the result. Add the container
macros first (using the same evaluation-and-store pattern), then the template's own macros.

If `buildAllCombinations` handles macros inside `PermuteConfig`, pass `containerMacros` as a
parameter or add it to `PermuteConfig` before calling `buildAllCombinations`.

- [ ] **Step 5: Update JoinBuilder.java — move alphaList to @PermuteMacros on JoinBuilder**

Remove `macros = {"alphaList=typeArgList(1,i,'alpha')"}` from both `Join0Second` and
`Join0First` `@Permute` annotations. Add `@PermuteMacros` to the outer `JoinBuilder` class:

```java
@io.quarkiverse.permuplate.PermuteMacros({"alphaList=typeArgList(1,i,'alpha')"})
public class JoinBuilder {
    ...
    @Permute(varName = "i", from = "1", to = "6", className = "Join${i}Second",
             inline = true, keepTemplate = false)          // no macros= here
    public static non-sealed class Join0Second<...> { ... }

    @Permute(varName = "i", from = "1", to = "6", className = "Join${i}First",
             inline = true, keepTemplate = false)          // no macros= here
    public static non-sealed class Join0First<...> { ... }
}
```

- [ ] **Step 6: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`. `PermuteMacrosTest` passes.

- [ ] **Step 7: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteMacros.java
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/PermuteMacrosTest.java
git commit -m "$(cat <<'EOF'
feat: @PermuteMacros — shared file-level macros across nested @Permute templates (closes #98)

New annotation on outer class; macros available to all nested templates, innermost wins.
Applied to JoinBuilder: alphaList defined once on JoinBuilder, removed from Join0Second + Join0First.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: @PermuteMixin — solve ADR-0006 extendsRule duplication (closes #99)

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteMixin.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Create: `DSL-TEMPLATES/ExtendsRuleMixin.java` (new mixin class)
- Modify: `DSL-TEMPLATES/RuleBuilder.java` (remove duplicated extendsRule)
- Modify: `DSL-TEMPLATES/ParametersFirst.java` (remove duplicated extendsRule)
- Create: `DSL-TESTS/PermuteMixinTest.java` (new)

- [ ] **Step 1: Write failing integration test**

Create `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/PermuteMixinTest.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

/**
 * Verifies that @PermuteMixin correctly injects extendsRule() overloads into both
 * RuleBuilder and ParametersFirst from a single shared mixin class.
 */
public class PermuteMixinTest {

    @Test
    public void testRuleBuilderHasExtendsRuleForAllArities() throws Exception {
        Class<?> ruleBuilderClass = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleBuilder");
        long extendsRuleCount = Arrays.stream(ruleBuilderClass.getMethods())
                .filter(m -> m.getName().equals("extendsRule"))
                .count();
        assertThat(extendsRuleCount).isEqualTo(6); // j=2..7 → 6 overloads
    }

    @Test
    public void testParametersFirstHasExtendsRuleForAllArities() throws Exception {
        Class<?> pfClass = Class.forName(
                "io.quarkiverse.permuplate.example.drools.ParametersFirst");
        long extendsRuleCount = Arrays.stream(pfClass.getMethods())
                .filter(m -> m.getName().equals("extendsRule"))
                .count();
        assertThat(extendsRuleCount).isEqualTo(6);
    }
}
```

- [ ] **Step 2: Run to confirm passes before the change (extendsRule hand-written in both)**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=PermuteMixinTest -q
```

Expected: passes. Baseline established. The existing `ExtensionPointTest` must also pass.

- [ ] **Step 3: Create PermuteMixin annotation**

Create `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteMixin.java`:

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects methods from the listed mixin class(es) into this template class before
 * the Permuplate transform pipeline runs. Injected methods participate fully in
 * {@code @PermuteMethod}, {@code @PermuteReturn}, and all other transformers.
 *
 * <p>The mixin class itself is not added to generated output — it is a source-only
 * helper. It must be in the same Maven source root as the template.
 *
 * <p>Constraint: only methods are injected (not fields or constructors). The mixin
 * class name is resolved by simple name within the same compilation unit's source set.
 *
 * <p>Example:
 * <pre>{@code
 * @Permute(...)
 * @PermuteMixin(ExtendsRuleMixin.class)
 * public class RuleBuilderTemplate<DS> extends AbstractRuleEntry<DS> { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteMixin {
    /** The mixin class(es) whose annotated methods should be injected into this template. */
    Class<?>[] value();
}
```

- [ ] **Step 4: Create ExtendsRuleMixin.java**

Create `DSL-TEMPLATES/ExtendsRuleMixin.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteMethod;
import io.quarkiverse.permuplate.PermuteReturn;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Mixin providing the six {@code extendsRule()} overloads shared by
 * {@code RuleBuilder} and {@code ParametersFirst}. Injected via {@code @PermuteMixin}.
 * Not generated itself — source-only helper.
 */
class ExtendsRuleMixin<DS> extends AbstractRuleEntry<DS> {

    @Override
    protected String ruleName() { return ""; } // never called — mixin only

    @PermuteMethod(varName = "j", from = "2", to = "7", name = "extendsRule",
                   macros = {"prevAlpha=typeArgList(1,j-1,'alpha')"})
    @PermuteReturn(className = "JoinBuilder.Join${j-1}First",
                   typeArgs = "'Void, DS, ' + prevAlpha",
                   alwaysEmit = true)
    public <@PermuteTypeParam(varName = "k", from = "1", to = "${j-1}",
                               name = "${alpha(k)}") A>
            Object extendsRule(
            @PermuteDeclr(type = "RuleExtendsPoint.RuleExtendsPoint${j}<DS, ${prevAlpha}>")
            ExtendsPoint<DS> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(ruleName());
        ep.baseRd().copyInto(child);
        return cast(new JoinBuilder.@PermuteDeclr(type = "JoinBuilder.Join${j-1}First")
                Join1First<>(null, child));
    }
}
```

- [ ] **Step 5: Add @PermuteMixin injection to InlineGenerator**

In `InlineGenerator.generate()`, before the transform pipeline runs on each generated class,
detect `@PermuteMixin` on the template class and inject its methods:

```java
/**
 * Injects methods from @PermuteMixin classes into the template class AST before processing.
 * Mixin class is located by simple name in the parent CU's source set.
 */
private static void injectMixinMethods(TypeDeclaration<?> templateDecl,
        CompilationUnit parentCu,
        List<CompilationUnit> allSourceCus) {  // allSourceCus = all parsed CUs in source root

    templateDecl.getAnnotations().stream()
            .filter(a -> a.getNameAsString().equals("PermuteMixin")
                    || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteMixin"))
            .findFirst()
            .ifPresent(ann -> {
                // Read the Class<?>[] value — each ClassExpr gives us a class simple name
                List<String> mixinNames = new ArrayList<>();
                if (ann instanceof NormalAnnotationExpr normal) {
                    normal.getPairs().stream()
                            .filter(p -> p.getNameAsString().equals("value"))
                            .findFirst()
                            .ifPresent(p -> {
                                Expression val = p.getValue();
                                if (val instanceof ArrayInitializerExpr arr) {
                                    arr.getValues().forEach(v -> {
                                        if (v instanceof ClassExpr ce)
                                            mixinNames.add(ce.getTypeAsString());
                                    });
                                } else if (val instanceof ClassExpr ce) {
                                    mixinNames.add(ce.getTypeAsString());
                                }
                            });
                } else if (ann instanceof SingleMemberAnnotationExpr single) {
                    Expression val = single.getMemberValue();
                    if (val instanceof ArrayInitializerExpr arr) {
                        arr.getValues().forEach(v -> {
                            if (v instanceof ClassExpr ce)
                                mixinNames.add(ce.getTypeAsString());
                        });
                    } else if (val instanceof ClassExpr ce) {
                        mixinNames.add(ce.getTypeAsString());
                    }
                }

                // For each mixin name, find its class declaration in all parsed CUs
                for (String mixinSimpleName : mixinNames) {
                    allSourceCus.stream()
                            .flatMap(cu -> cu.findAll(TypeDeclaration.class).stream())
                            .filter(td -> td.getNameAsString().equals(mixinSimpleName))
                            .findFirst()
                            .ifPresent(mixinDecl -> {
                                // Clone and inject each method from the mixin
                                mixinDecl.getMethods().forEach(m -> {
                                    MethodDeclaration clone = m.clone();
                                    if (templateDecl instanceof ClassOrInterfaceDeclaration coid) {
                                        coid.addMember(clone);
                                    }
                                });
                            });
                }
            });
}
```

Call this method in `generate()` just before the per-combination loop starts (so the same
injected methods are present for all combinations). The `allSourceCus` parameter needs to come
from `PermuteMojo` — pass all parsed CUs from the source root to `generate()`.

If `InlineGenerator.generate()` does not currently receive `allSourceCus`, you need to thread it
through from `PermuteMojo.execute()` where all source files are parsed. Check `PermuteMojo` and
`SourceScanner` for how CUs are currently collected, and pass the full list to `generate()`.

- [ ] **Step 6: Remove extendsRule from RuleBuilder.java and apply @PermuteMixin**

In `RuleBuilder.java` (the `RuleBuilderTemplate` class):
- Remove the `extendsRule()` method and its annotations (all 6+ lines)
- Add `@PermuteMixin(ExtendsRuleMixin.class)` to `RuleBuilderTemplate`

```java
@Permute(varName = "i", from = "1", to = "1", className = "RuleBuilder",
         inline = true, keepTemplate = false)
@io.quarkiverse.permuplate.PermuteMixin(ExtendsRuleMixin.class)
public class RuleBuilderTemplate<DS> extends AbstractRuleEntry<DS> {
    // from() and rule() only — no extendsRule() here
    ...
}
```

- [ ] **Step 7: Remove extendsRule from ParametersFirst.java and apply @PermuteMixin**

Same change in `ParametersFirstTemplate`:
- Remove the `extendsRule()` method
- Add `@PermuteMixin(ExtendsRuleMixin.class)` to `ParametersFirstTemplate`

- [ ] **Step 8: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`. `PermuteMixinTest` passes (both classes still have 6 overloads).
`ExtensionPointTest` and `NamedRuleTest` pass unchanged.

- [ ] **Step 9: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteMixin.java
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/ExtendsRuleMixin.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleBuilder.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/ParametersFirst.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/PermuteMixinTest.java
git commit -m "$(cat <<'EOF'
feat: @PermuteMixin — inject mixin methods into template; solve ADR-0006 (closes #99)

New annotation injects methods from listed class before transform pipeline.
ExtendsRuleMixin holds shared extendsRule() template; both RuleBuilder and
ParametersFirst reference it. Eliminates 26-line duplication. ADR-0006 resolved.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Constructor super-call inference (closes #100)

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Modify: `DSL-TEMPLATES/BaseTuple.java` (remove @PermuteStatements from constructor)
- Create: `DSL-TESTS/SuperCallInferenceTest.java` (new)

- [ ] **Step 1: Write failing test that verifies super-call inference**

Create `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/SuperCallInferenceTest.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Verifies that the generated Tuple classes have correct super() calls
 * even when @PermuteStatements is absent from the constructor.
 */
public class SuperCallInferenceTest {

    @Test
    public void testTuple2HasCorrectFields() {
        var t = new BaseTuple.Tuple2<>("hello", 42);
        assertThat((String) t.get(0)).isEqualTo("hello");
        assertThat((Integer) t.get(1)).isEqualTo(42);
        assertThat(t.size()).isEqualTo(2);
    }

    @Test
    public void testTuple3HasCorrectFields() {
        var t = new BaseTuple.Tuple3<>("hello", 42, 3.14);
        assertThat((String) t.get(0)).isEqualTo("hello");
        assertThat((Integer) t.get(1)).isEqualTo(42);
        assertThat((Double) t.get(2)).isEqualTo(3.14);
        assertThat(t.size()).isEqualTo(3);
    }

    @Test
    public void testTuple4HasCorrectFields() {
        var t = new BaseTuple.Tuple4<>("a", "b", "c", "d");
        assertThat((String) t.get(0)).isEqualTo("a");
        assertThat((String) t.get(3)).isEqualTo("d");
        assertThat(t.size()).isEqualTo(4);
    }
}
```

- [ ] **Step 2: Run to confirm pass (existing @PermuteStatements still present)**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=SuperCallInferenceTest -q
```

Expected: all pass. Baseline.

- [ ] **Step 3: Add inferSuperCall() to InlineGenerator**

In `InlineGenerator.java`, add a new private static method:

```java
/**
 * Infers and inserts a {@code super(param1, param2, ...)} call as the first statement of
 * constructors that follow the "parent-chain extension" pattern:
 *
 * <ol>
 *   <li>The template class has @PermuteExtends or @PermuteExtendsChain (applied before this).</li>
 *   <li>The constructor has {@code N} parameters (N ≥ 2).</li>
 *   <li>The constructor does NOT already have a super() call as its first statement.</li>
 *   <li>The constructor does NOT have @PermuteStatements (explicit annotation wins).</li>
 * </ol>
 *
 * When all conditions hold, inserts {@code super(p1, p2, ..., p_{N-1});} — all params
 * except the last — as the first statement. This matches the tuple-chain pattern where
 * each level adds one field and delegates the rest to the parent constructor.
 */
private static void inferSuperCall(ClassOrInterfaceDeclaration classDecl,
        boolean hasExplicitExtends) {
    if (!hasExplicitExtends)
        return;

    classDecl.getConstructors().forEach(ctor -> {
        // Skip if @PermuteStatements is present — explicit annotation always wins
        boolean hasPermuteStatements = ctor.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("PermuteStatements")
                        || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteStatements"));
        if (hasPermuteStatements)
            return;

        // Skip single-param constructors (no parent params to delegate)
        if (ctor.getParameters().size() < 2)
            return;

        // Skip if already has super() as first statement
        if (!ctor.getBody().getStatements().isEmpty()) {
            com.github.javaparser.ast.stmt.Statement first = ctor.getBody().getStatements().get(0);
            if (first instanceof com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt e
                    && !e.isThis()) {
                return; // already has super(...)
            }
        }

        // Build super(p1, p2, ..., p_{N-1})
        List<com.github.javaparser.ast.expr.Expression> superArgs = new ArrayList<>();
        List<com.github.javaparser.ast.body.Parameter> params = ctor.getParameters();
        for (int idx = 0; idx < params.size() - 1; idx++) {
            superArgs.add(new com.github.javaparser.ast.expr.NameExpr(params.get(idx).getNameAsString()));
        }
        var superCall = new com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt(
                false, null, new com.github.javaparser.ast.NodeList<>(superArgs));

        ctor.getBody().getStatements().addFirst(superCall);
    });
}
```

Call `inferSuperCall()` in the main pipeline in `generate()`, just after `@PermuteStatements`
is applied (line 224 area), with `hasExplicitExtends` = the `permuteExtendsApplied` flag already
computed at line 253:

```java
// After PermuteStatementsTransformer.transform at line 224:
// Constructor super-call inference: runs after @PermuteStatements (explicit wins)
// and after @PermuteExtends/extends expansion (so we know if it extends a parent)
```

Wait — the extends check happens AFTER @PermuteStatements in the pipeline. To know if the
template has `@PermuteExtends` or `@PermuteExtendsChain`, check the TEMPLATE (before transformation),
not the generated class. Add a pre-check on the template class:

```java
// Check once before the per-combination loop:
boolean templateHasExtendsAnnotation = templateClassDecl instanceof ClassOrInterfaceDeclaration tcoid
        && (hasPermuteExtendsAnnotation(tcoid)
            || tcoid.isAnnotationPresent("PermuteExtendsChain")
            || tcoid.isAnnotationPresent("io.quarkiverse.permuplate.PermuteExtendsChain"));
```

Then call `inferSuperCall(coid, templateHasExtendsAnnotation)` just after `PermuteStatementsTransformer.transform` at line 224.

- [ ] **Step 4: Remove @PermuteStatements from BaseTuple.Tuple1 constructor**

In `DSL-TEMPLATES/BaseTuple.java`, find the full-args constructor of `Tuple1` and remove the
`@PermuteStatements` annotation:

```java
// Before:
@PermuteStatements(position = "first", body = "super(${typeArgList(1, i-1, 'lower')});")
@PermuteValue(index = 1, value = "${i}")
public Tuple1(
        @PermuteParam(varName = "k", from = "1", to = "${i}", type = "${alpha(k)}", name = "${lower(k)}") A a) {
    this.a = a;
    this.size = 1;
}

// After (just remove the @PermuteStatements line):
@PermuteValue(index = 1, value = "${i}")
public Tuple1(
        @PermuteParam(varName = "k", from = "1", to = "${i}", type = "${alpha(k)}", name = "${lower(k)}") A a) {
    this.a = a;
    this.size = 1;
}
```

- [ ] **Step 5: Run full build and confirm tuple tests pass**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`. `SuperCallInferenceTest` passes. `TupleAsTest` passes.

- [ ] **Step 6: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/BaseTuple.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/SuperCallInferenceTest.java
git commit -m "$(cat <<'EOF'
feat: infer super() call for parent-chain constructors (closes #100)

When template has @PermuteExtends/@PermuteExtendsChain and constructor has N>=2 params,
auto-insert super(p1..p_{N-1}) as first statement (unless @PermuteStatements present).
Removes @PermuteStatements from BaseTuple.Tuple1 full-args constructor.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: @PermuteExtendsChain shorthand (closes #101)

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteExtendsChain.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Modify: `DSL-TEMPLATES/BaseTuple.java` (use @PermuteExtendsChain)
- Create: `DSL-TESTS/PermuteExtendsChainTest.java` (new)

- [ ] **Step 1: Write failing test for @PermuteExtendsChain**

Create `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/PermuteExtendsChainTest.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Verifies that @PermuteExtendsChain correctly generates extends clauses.
 * The key invariant: Tuple3 extends Tuple2<A,B>, Tuple2 extends Tuple1<A>.
 */
public class PermuteExtendsChainTest {

    @Test
    public void testTuple2ExtendsTuple1() {
        assertThat(BaseTuple.Tuple2.class.getSuperclass()).isEqualTo(BaseTuple.Tuple1.class);
    }

    @Test
    public void testTuple3ExtendsTuple2() {
        assertThat(BaseTuple.Tuple3.class.getSuperclass()).isEqualTo(BaseTuple.Tuple2.class);
    }

    @Test
    public void testTuple6ExtendsTuple5() {
        assertThat(BaseTuple.Tuple6.class.getSuperclass()).isEqualTo(BaseTuple.Tuple5.class);
    }

    @Test
    public void testInheritedGetFromParent() {
        // Tuple3.get(0) and get(1) delegate to Tuple2 and Tuple1 via super chain
        var t = new BaseTuple.Tuple3<>("x", "y", "z");
        assertThat((String) t.get(0)).isEqualTo("x");
        assertThat((String) t.get(1)).isEqualTo("y");
        assertThat((String) t.get(2)).isEqualTo("z");
    }
}
```

- [ ] **Step 2: Run to confirm passes (existing @PermuteExtends is working)**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=PermuteExtendsChainTest -q
```

Expected: all pass. Baseline.

- [ ] **Step 3: Create @PermuteExtendsChain annotation**

Create `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteExtendsChain.java`:

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shorthand for extending the previous class in a generated family with a shrinking
 * alpha type-argument list.
 *
 * <p>Equivalent to:
 * <pre>{@code
 * @PermuteExtends(className = "${familyBase}${i-1}",
 *                 typeArgs  = "typeArgList(1, i-1, 'alpha')")
 * }</pre>
 * where {@code familyBase} is inferred from the template's {@code @Permute.className}
 * by taking the substring before the first {@code ${}.
 *
 * <p>If {@code @PermuteExtends} is also present, that annotation takes precedence
 * and {@code @PermuteExtendsChain} is silently ignored.
 *
 * <p>Example — replace the verbose form:
 * <pre>{@code
 * @Permute(varName="i", from="2", to="6", className="Tuple${i}", ...)
 * @PermuteExtends(className="Tuple${i-1}", typeArgs="typeArgList(1, i-1, 'alpha')")
 * public static class Tuple1<A> extends BaseTuple { ... }
 * }</pre>
 *
 * With the shorthand:
 * <pre>{@code
 * @Permute(varName="i", from="2", to="6", className="Tuple${i}", ...)
 * @PermuteExtendsChain
 * public static class Tuple1<A> extends BaseTuple { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteExtendsChain {
}
```

- [ ] **Step 4: Add @PermuteExtendsChain handling to InlineGenerator**

In `InlineGenerator.java`, in the extends-handling block (around line 252–264), add detection
for `@PermuteExtendsChain` immediately before `applyPermuteExtendsAnnotation`:

```java
// @PermuteExtendsChain — shorthand for "extend previous in family with alpha type args"
// Fires only when @PermuteExtends is NOT also present (explicit @PermuteExtends wins).
boolean hasExplicitPermuteExtends = hasPermuteExtendsAnnotation(coid);
if (!hasExplicitPermuteExtends && hasPermuteExtendsChainAnnotation(coid)) {
    applyPermuteExtendsChain(coid, config, ctx);
}

// @PermuteExtends — explicit override of extends/implements clause
boolean permuteExtendsApplied = hasExplicitPermuteExtends;
applyPermuteExtendsAnnotation(coid, ctx);
```

Add the two helper methods:

```java
private static boolean hasPermuteExtendsChainAnnotation(ClassOrInterfaceDeclaration classDecl) {
    return classDecl.getAnnotations().stream().anyMatch(a -> {
        String n = a.getNameAsString();
        return n.equals("PermuteExtendsChain")
                || n.equals("io.quarkiverse.permuplate.PermuteExtendsChain");
    });
}

/**
 * Applies @PermuteExtendsChain semantics: extends ${familyBase}${i-1} with alpha type args
 * typeArgList(1, i-1, 'alpha'). Family base is the className pattern prefix before first ${.
 */
private static void applyPermuteExtendsChain(ClassOrInterfaceDeclaration classDecl,
        PermuteConfig config, EvaluationContext ctx) {

    // Extract family base from className pattern (everything before first "${")
    String classNamePattern = config.className;
    int dollarIdx = classNamePattern.indexOf("${");
    if (dollarIdx <= 0)
        return; // cannot infer family base
    String familyBase = classNamePattern.substring(0, dollarIdx);

    // Build className = "${familyBase}${i-1}"
    // Build typeArgs  = "typeArgList(1, i-1, 'alpha')"
    String parentClassNameExpr = familyBase + "${i-1}";
    String typeArgsExpr = "typeArgList(1, i-1, 'alpha')";

    String evaluatedClass;
    try {
        evaluatedClass = ctx.evaluate(parentClassNameExpr);
    } catch (Exception ignored) {
        return;
    }

    String typeArgStr;
    try {
        typeArgStr = ctx.evaluate("${" + typeArgsExpr + "}");
    } catch (Exception ignored) {
        typeArgStr = "";
    }

    String newTypeStr = typeArgStr.isEmpty()
            ? evaluatedClass
            : evaluatedClass + "<" + typeArgStr + ">";

    try {
        com.github.javaparser.ast.type.ClassOrInterfaceType newType =
                (com.github.javaparser.ast.type.ClassOrInterfaceType)
                StaticJavaParser.parseType(newTypeStr);
        classDecl.getExtendedTypes().clear();
        classDecl.addExtendedType(newType);
    } catch (Exception ignored) {
    }

    // Remove @PermuteExtendsChain annotation from generated class
    classDecl.getAnnotations().removeIf(a -> {
        String n = a.getNameAsString();
        return n.equals("PermuteExtendsChain")
                || n.equals("io.quarkiverse.permuplate.PermuteExtendsChain");
    });

    // Set permuteExtendsApplied flag so implicit expansion is suppressed:
    // Handled by caller checking hasPermuteExtendsChainAnnotation before the expansion block.
}
```

Also update the `templateHasExtendsAnnotation` check in Task 9's `inferSuperCall` setup to
include `@PermuteExtendsChain`:

```java
boolean templateHasExtendsAnnotation = templateClassDecl instanceof ClassOrInterfaceDeclaration tcoid
        && (hasPermuteExtendsAnnotation(tcoid) || hasPermuteExtendsChainAnnotation(tcoid));
```

- [ ] **Step 5: Update BaseTuple.java — replace @PermuteExtends with @PermuteExtendsChain**

In `DSL-TEMPLATES/BaseTuple.java`:

```java
// Before:
@Permute(varName = "i", from = "2", to = "6", className = "Tuple${i}",
         inline = true, keepTemplate = true)
@PermuteExtends(className = "Tuple${i-1}", typeArgs = "typeArgList(1, i-1, 'alpha')")
public static class Tuple1<
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
        extends BaseTuple {

// After:
@Permute(varName = "i", from = "2", to = "6", className = "Tuple${i}",
         inline = true, keepTemplate = true)
@io.quarkiverse.permuplate.PermuteExtendsChain
public static class Tuple1<
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
        extends BaseTuple {
```

Also update the import list at the top of BaseTuple.java — remove `import io.quarkiverse.permuplate.PermuteExtends;` and add `import io.quarkiverse.permuplate.PermuteExtendsChain;` (or use the fully-qualified form as shown above).

- [ ] **Step 6: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`. All `PermuteExtendsChainTest`, `SuperCallInferenceTest`, `TupleAsTest` pass.

- [ ] **Step 7: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteExtendsChain.java
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/BaseTuple.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/PermuteExtendsChainTest.java
git commit -m "$(cat <<'EOF'
feat: @PermuteExtendsChain shorthand for extends-previous-in-family (closes #101)

Infers family base from className pattern; generates extends clause with alpha typeArgList.
Applied to BaseTuple.Tuple1: replaces explicit @PermuteExtends with one-word annotation.
Explicit @PermuteExtends always wins; @PermuteExtendsChain suppresses implicit expansion.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Documentation sync (no issue — part of each item's definition of done)

**Files:**
- Modify: `CLAUDE.md` (non-obvious decisions table, annotation table)
- Modify: `OVERVIEW.md` (annotation API detail section)
- Modify: `docs/ROADMAP.md` (move batch 8 items to Completed)
- Modify: `docs/adr/0006-extendsrule-duplication.md` (mark resolved by @PermuteMixin)
- Modify: `docs/superpowers/specs/2026-04-20-dsl-batch8-design.md` (final state notes)

This task runs after all 10 items are committed.

- [ ] **Step 1: Update CLAUDE.md annotation table**

Add entries for the three new annotations and one new attribute:

```markdown
| `@PermuteMacros` | outer class | Declares JEXL macros available to all nested @Permute templates. Format: "name=jexlExpr". Innermost wins. |
| `@PermuteMixin` | class | Injects methods from listed mixin class(es) before transform pipeline; mixin must be in same source root. |
| `@PermuteExtendsChain` | class | Shorthand: extends ${familyBase}${i-1} with alpha typeArgList(1,i-1,'alpha'). Family base inferred from className pattern. @PermuteExtends takes precedence. |
```

For `@PermuteReturn`, add to its row:
```
typeParam= attribute: return type = named type parameter (e.g. "END"). Mutually exclusive with className.
```

- [ ] **Step 2: Update CLAUDE.md non-obvious decisions table**

Add entries:

```markdown
| `max()/min()` JEXL built-ins | Built-in functions registered in EvaluationContext alongside alpha, lower, typeArgList. `max(2,i)` replaces `${i > 1 ? i : i+1}` ternary pattern. |
| `typeArgList` custom prefix | Unknown styles now treated as literal prefix+index (e.g. 'V'→V1,V2,V3). Previously threw IllegalArgumentException. |
| `@PermuteMixin` mixin resolution | Mixin class located by simple name across all CUs in source root; must be in same Maven source root. Methods are cloned and injected before the per-combination loop. |
| `@PermuteExtendsChain` suppresses implicit expansion | When @PermuteExtendsChain applies, the standard `applyExtendsExpansion()` is NOT called (same semantics as explicit @PermuteExtends). |
| `@PermuteReturn typeParam=` and `className=` mutual exclusion | If both are non-empty on the same annotation, a compile error is reported. typeParam= path bypasses boundary omission entirely — always generates the method. |
| Constructor super-call inference | Fires only when template has @PermuteExtends or @PermuteExtendsChain, constructor has ≥2 params, no existing super(), no @PermuteStatements. |
| `NotScope` / `ExistsScope` rename | NegationScope→NotScope, ExistenceScope→ExistsScope. Shorter names match method names (not()/exists()). addNegation→addNot, addExistence→addExists in RuleDefinition. |
```

- [ ] **Step 3: Update OVERVIEW.md annotation API section**

Add `@PermuteMacros`, `@PermuteMixin`, `@PermuteExtendsChain` rows to the annotation table.
Update `@PermuteReturn` row to mention `typeParam=`.
Update `@Permute` row to mention `max()/min()` are now available JEXL functions.
Update `typeArgList` entry to mention custom prefix support.

- [ ] **Step 4: Update ROADMAP.md**

In the Completed table, add all 10 batch-8 items. Remove any forward references to them in
the Feature ideas section (if present).

- [ ] **Step 5: Update ADR-0006**

In `docs/adr/0006-extendsrule-duplication.md`, add a resolution note:

```markdown
## Resolution (2026-04-21)

Resolved by @PermuteMixin (batch 8, item 8, issue #99). The `ExtendsRuleMixin` class now holds
the single authoritative `extendsRule()` template; both `RuleBuilderTemplate` and
`ParametersFirstTemplate` reference it via `@PermuteMixin(ExtendsRuleMixin.class)`.
```

- [ ] **Step 6: Run the full build one final time**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install
```

Expected: `BUILD SUCCESS`. All tests pass. Total test count ≥ 277 (new tests added in batch 8
increase the count).

- [ ] **Step 7: Commit documentation**

```bash
git add CLAUDE.md OVERVIEW.md docs/ROADMAP.md docs/adr/0006-extendsrule-duplication.md
git add docs/superpowers/specs/2026-04-20-dsl-batch8-design.md
git commit -m "$(cat <<'EOF'
docs: sync batch 8 — new annotations, resolved ADR-0006, ROADMAP updated

CLAUDE.md: @PermuteMacros, @PermuteMixin, @PermuteExtendsChain, @PermuteReturn typeParam=,
  typeArgList prefix, max()/min(), super-call inference, NotScope/ExistsScope rename.
OVERVIEW.md: annotation table updated.
ADR-0006: marked resolved by @PermuteMixin.
ROADMAP: all 10 batch-8 items moved to Completed.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Checklist

**Spec coverage:**
- ✅ Item 1 → Task 1 (not/exists string-set)
- ✅ Item 2 → Task 2 (max/min JEXL)
- ✅ Item 3 → Task 3 (typeArgList prefix)
- ✅ Item 4 → Task 4 (variable filter templating, depends on Task 3)
- ✅ Item 5 → Task 5 (method macros refactoring)
- ✅ Item 6 → Task 6 (@PermuteReturn typeParam= + Path2 unification)
- ✅ Item 7 → Task 7 (@PermuteMacros)
- ✅ Item 8 → Task 8 (@PermuteMixin)
- ✅ Item 9 → Task 9 (constructor super-call inference)
- ✅ Item 10 → Task 10 (@PermuteExtendsChain)
- ✅ Doc sync → Task 11

**Testing coverage:**
- ✅ Unit tests: EvaluationContextTest (Tasks 2, 3)
- ✅ Integration tests: all new *Test.java in permuplate-mvn-examples (Tasks 1, 4, 6, 7, 8, 9, 10)
- ✅ End-to-end: `mvn clean install` after every task
- ✅ Regression: existing 277+ tests verified after every task
- ✅ Happy path: each test exercises the golden-path use case
- ✅ Correctness: variable filter tests, tuple tests, OOPath tests verify runtime behaviour

**Type/name consistency:**
- `NotScope` and `ExistsScope` used consistently throughout (Task 1 → Task 7 references)
- `PermuteReturnConfig.hasTypeParam()` helper referenced correctly in Task 6
- `inferSuperCall(coid, templateHasExtendsAnnotation)` signature consistent between Task 9 definition and Task 10 update
- `hasPermuteExtendsChainAnnotation(coid)` defined in Task 10 Step 4, referenced in Task 9 Step 3

**Dependency order:**
- Task 4 explicitly states it depends on Task 3 (typeArgList prefix for 'V'/'v')
- Task 6 (Path2) depends on @PermuteReturn @Repeatable (handled in Step 6b)
- Task 9 (super-call inference) must run after Task 10 to get `hasPermuteExtendsChainAnnotation`
  in the inference trigger check — BUT Task 9 adds the flag first, Task 10 extends it. The plan
  handles this: Task 9 Step 3 establishes `templateHasExtendsAnnotation` using both
  `hasPermuteExtendsAnnotation` and a placeholder for chain; Task 10 Step 4 finalizes the chain
  helper and updates the check. In practice, implement Tasks 9 and 10 in order and the code
  compiles correctly after both.
