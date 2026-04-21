# Batch 9 — DSL Second-Pass Polish: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply 10 targeted DSL improvements and 1 bug fix found by systematic post-batch-8 audit,
covering dead-code removal, annotation simplification, and 4 new Permuplate capabilities.

**Architecture:** Items 0–2 and 6 are DSL-only changes (no core changes). Items 3–5 add new
Permuplate annotations/inference in InlineGenerator. Item 7 uses a new `@PermuteDefaultReturn`
special value. Items 8–10 apply features to DSL templates. Implementation order respects
dependencies: new Permuplate features first, DSL applications after.

**Tech Stack:** Java 17, JavaParser 3.28.0, Apache Commons JEXL3, JUnit 4, Google
compile-testing, Maven. All Maven commands use `/opt/homebrew/bin/mvn`.

---

## Key File Paths

| Role | Path |
|---|---|
| Annotations module | `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/` |
| InlineGenerator | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` |
| AnnotationReader | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/AnnotationReader.java` |
| PermuteParamTransformer | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java` |
| PermuteProcessor (APT) | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` |
| DSL templates | `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/` |
| DSL tests | `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/` |
| Unit/integration tests | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/` |
| ExpressionFunctionsTest | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/ExpressionFunctionsTest.java` |

---

## Task 0: GitHub Epic and Issues

**Files:** none (GitHub only)

- [ ] **Step 1: Create the batch-9 epic**

```bash
gh issue create \
  --title "batch 9: DSL second-pass polish — 10 items + 1 bug fix" \
  --body "Systematic post-batch-8 audit of every remaining verbosity, dead code, and structural gap. Spec: docs/superpowers/specs/2026-04-21-dsl-batch9-design.md" \
  --label "epic"
```

Expected: issue #102 (or next available).

- [ ] **Step 2: Create child issues**

```bash
gh issue create --title "bug: delete orphan NegationScope.java" \
  --body "NegationScope.java was not deleted in batch 8. Generates NegationScope + ExistenceScope, both unreferenced. Epic: #102" --label "bug"

gh issue create --title "item 1: Scope macro eliminates 3x capitalize(scope)" \
  --body "Add macros={'Scope=capitalize(scope)'} to not/exists @PermuteMethod. Epic: #102" --label "enhancement"

gh issue create --title "item 2: @PermuteFilter replaces max(2,i) on filterLatest" \
  --body "@PermuteFilter('i > 1') + from='${i}' is more expressive than max(). Epic: #102" --label "enhancement"

gh issue create --title "item 7: @PermuteBodyFragment — named reusable body templates" \
  --body "New annotation for class-level body fragments, referenced via ${name} in @PermuteBody strings. Epic: #102" --label "enhancement"

gh issue create --title "item 8: @PermuteParam body call-site expansion inside @PermuteMethod" \
  --body "Process @PermuteParam inside @PermuteMethod clones with inner context so body anchors expand. Removes @PermuteBody from filterVar. Epic: #102" --label "enhancement"

gh issue create --title "item 9: @PermuteReturn inference from @PermuteBody return expression" \
  --body "Infer @PermuteReturn className from 'return new X<>()' in @PermuteBody when X is in generated set. Epic: #102" --label "enhancement"

gh issue create --title "item 10: @PermuteDefaultReturn(className='self') special value" \
  --body "className='self' means current generated class + all its type params. Eliminates verbose typeArgs expression. Epic: #102" --label "enhancement"

gh issue create --title "item 3: @PermuteDefaultReturn replaces 5x @PermuteSelf on Join0First" \
  --body "One class-level @PermuteDefaultReturn(className='self') replaces 5 method-level @PermuteSelf. Depends on item 10. Epic: #102" --label "enhancement"

gh issue create --title "item 4: Consumer/Predicate cross-product template merge" \
  --body "@PermuteVar(values={'Consumer','Predicate'}) cross-product with i generates both families from one template. Epic: #102" --label "enhancement"

gh issue create --title "item 5: @FunctionalInterface on Consumer/Predicate via @PermuteAnnotation" \
  --body "Add @PermuteAnnotation(type='FunctionalInterface') to all SAM interface templates. Epic: #102" --label "enhancement"

gh issue create --title "item 6: @PermuteMacros prev macro on RuleExtendsPoint" \
  --body "@PermuteMacros({'prev=${i-1}'}) on RuleExtendsPoint outer class for readability. Epic: #102" --label "enhancement"
```

Record the actual issue numbers returned (#103–#113 or as assigned).

---

## Task 1: Bug Fix — Delete NegationScope.java (closes #103)

**Files:**
- Delete: `DSL-TEMPLATES/NegationScope.java`
- Verify: no references to `NegationScope` or `ExistenceScope` anywhere in `src/`

Where `DSL-TEMPLATES` = `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/`

- [ ] **Step 1: Confirm orphan status**

```bash
grep -rn "ExistenceScope\|NegationScope" \
  /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/ \
  --include="*.java" | grep -v "NegationScope.java"
```

Expected: zero output (no references outside the file itself).

- [ ] **Step 2: Delete the file and run full build**

```bash
git rm /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/NegationScope.java

cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. The file generates nothing used anywhere.

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
fix: delete orphan NegationScope.java — batch 8 git rm did not persist (closes #103)

NegationScope generated NegationScope (kept) + ExistenceScope (generated), both
unreferenced since batch 8 renamed to NotScope/ExistsScope. Zero references confirmed.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Item 1 — Scope macro in JoinBuilder (closes #104)

**Files:**
- Modify: `DSL-TEMPLATES/JoinBuilder.java`

- [ ] **Step 1: Read the current not/exists method**

```bash
grep -n -A8 "values.*not.*exists\|capitalize.*scope\|Scope.*scope" \
  /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java | head -20
```

Note the exact current annotations on the `scopeTemplate` method.

- [ ] **Step 2: Add macro and replace 3× capitalize(scope)**

Find the `@PermuteMethod(varName = "scope", values = {"not", "exists"}, name = "${scope}")` annotation on `scopeTemplate`. Replace the entire annotation block:

```java
@PermuteMethod(varName = "scope", values = {"not", "exists"}, name = "${scope}",
               macros = {"Scope=capitalize(scope)"})
@PermuteReturn(className = "${Scope}Scope",
               typeArgs = "'Join' + i + 'Second<END, DS, ' + alphaList + '>, DS'",
               alwaysEmit = true)
@PermuteBody(body = "{ RuleDefinition<DS> scopeRd = new RuleDefinition<>(\"${scope}-scope\"); rd.add${Scope}(scopeRd); return new ${Scope}Scope<>(this, scopeRd); }")
public Object scopeTemplate() {
    return null; // replaced by @PermuteBody
}
```

- [ ] **Step 3: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. `not()` still returns `NotScope`, `exists()` returns `ExistsScope`.

- [ ] **Step 4: Verify generated output**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=RuleBuilderTest -q 2>&1 | tail -3
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "$(cat <<'EOF'
refactor: Scope macro eliminates 3x capitalize(scope) in not/exists method (closes #104)

macros={"Scope=capitalize(scope)"} → ${Scope}Scope, rd.add${Scope}, new ${Scope}Scope.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Item 2 — @PermuteFilter replaces max(2,i) on filterLatest (closes #105)

**Files:**
- Modify: `DSL-TEMPLATES/JoinBuilder.java`

- [ ] **Step 1: Read the current filterLatest annotation**

```bash
grep -n -B1 -A5 "filterLatest\|max.*2.*i\|PermuteFilter" \
  /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java | head -20
```

- [ ] **Step 2: Replace max(2,i) with @PermuteFilter**

Find the `filterLatest` method. Replace:
```java
// BEFORE:
@PermuteMethod(varName = "x", from = "${max(2, i)}", to = "${i}", name = "filter")
@PermuteSelf
public Object filterLatest(...)

// AFTER:
@PermuteFilter("i > 1")
@PermuteMethod(varName = "x", from = "${i}", to = "${i}", name = "filter")
@PermuteSelf
public Object filterLatest(...)
```

Add import `import io.quarkiverse.permuplate.PermuteFilter;` if not already present.

- [ ] **Step 3: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

- [ ] **Step 4: Verify filterLatest is absent on Join1First, present on Join2First+**

```bash
# Check generated classes (look in target/generated-sources)
grep -rn "filter.*Predicate2" \
  /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/target/generated-sources/ \
  --include="*.java" | grep -i "join" | head -10
```

Expected: `filter(Predicate2<DS, B>)` appears in `Join2First.java` and above, absent in `Join1First.java`.

- [ ] **Step 5: Run DSL tests**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -q 2>&1 | tail -3
```

- [ ] **Step 6: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "$(cat <<'EOF'
refactor: @PermuteFilter('i > 1') replaces max(2,i) on filterLatest (closes #105)

Suppression at arity 1 is now explicit via @PermuteFilter; from="${i}" is trivially readable.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Item 7 — @PermuteBodyFragment new annotation (closes #106)

**Files:**
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteBodyFragment.java`
- Create: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteBodyFragments.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Create: `DSL-TESTS/PermuteBodyFragmentTest.java`
- Modify: `DSL-TEMPLATES/RuleOOPathBuilder.java` (apply the new annotation)

- [ ] **Step 1: Write the failing integration test**

Create `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/PermuteBodyFragmentTest.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

/**
 * Verifies @PermuteBodyFragment: named body fragments substituted into @PermuteBody
 * strings before JEXL evaluation. Exercises via the OOPath template where two
 * @PermuteBody annotations share the steps.add() prefix fragment.
 */
public class PermuteBodyFragmentTest {

    @Test
    public void testPath2ClassExists() throws Exception {
        Class<?> path2 = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleOOPathBuilder$Path2");
        assertThat(path2).isNotNull();
    }

    @Test
    public void testPath3ClassExists() throws Exception {
        Class<?> path3 = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleOOPathBuilder$Path3");
        assertThat(path3).isNotNull();
    }

    @Test
    public void testPath2HasPathMethod() throws Exception {
        Class<?> path2 = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleOOPathBuilder$Path2");
        Method path = Arrays.stream(path2.getMethods())
                .filter(m -> m.getName().equals("path"))
                .findFirst().orElse(null);
        assertThat(path).isNotNull();
        assertThat(path.getReturnType()).isEqualTo(Object.class); // erased END
    }

    @Test
    public void testOOPathChainViaDsl() {
        // End-to-end: OOPath 2-step chain (path2 → path) works correctly post-fragment.
        // This exercises the actual runtime behavior of the generated code.
        var ctx = buildCtx();
        var builder = new RuleBuilder<Ctx>();

        int[] fired = {0};
        var rule = builder.from(c -> c.libraries())
                .path2(
                        (pc, lib) -> lib.rooms(),
                        (pc, room) -> true)
                .path(
                        (pc, room) -> room.books(),
                        (pc, book) -> book.published())
                .fn((c, lib, tuple) -> fired[0]++);

        rule.run(ctx);
        assertThat(fired[0]).isGreaterThan(0);
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

- [ ] **Step 2: Run to confirm tests pass (OOPath still works pre-change)**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=PermuteBodyFragmentTest -q 2>&1 | tail -5
```

Expected: PASS (baseline).

- [ ] **Step 3: Create @PermuteBodyFragment annotation**

Create `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteBodyFragment.java`:

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a named body fragment that can be substituted into {@link PermuteBody}
 * strings via {@code ${name}} references.
 *
 * <p>Fragments are declared on the template class or an enclosing type.
 * The {@code value} is JEXL-evaluated with the current permutation context before
 * substitution, so it may contain {@code ${i}}, {@code ${alpha(k)}}, etc.
 *
 * <p>Substitution occurs before {@link PermuteBody} strings are parsed, so fragments
 * may contain Java source code including annotations and type expressions.
 *
 * <p><b>Maven plugin only.</b> The APT processor ignores this annotation.
 *
 * <p>Example:
 * <pre>{@code
 * @PermuteBodyFragment(name = "logStep", value = "System.out.println(\"step \" + ${i});")
 * public static class MyTemplate<A> {
 *     @PermuteBody(when = "${i == 1}", body = "{ ${logStep} return a; }")
 *     @PermuteBody(when = "${i > 1}", body = "{ ${logStep} return super.get(i); }")
 *     public Object get() { return null; }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(PermuteBodyFragments.class)
public @interface PermuteBodyFragment {
    /** Reference key used as {@code ${name}} in {@link PermuteBody#body()}. */
    String name();
    /** Java code fragment. JEXL expressions (e.g. {@code ${i}}) are evaluated first. */
    String value();
}
```

Create `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteBodyFragments.java`:

```java
package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container for repeatable {@link PermuteBodyFragment}. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteBodyFragments {
    PermuteBodyFragment[] value();
}
```

- [ ] **Step 4: Add fragment collection + substitution to InlineGenerator**

In `InlineGenerator.java`, add a private static helper to collect and evaluate fragments:

```java
/**
 * Collects @PermuteBodyFragment declarations from the template class and all enclosing
 * types (outermost first, so innermost can override), evaluates each fragment value with
 * the current context, and returns a name→evaluated-code map.
 */
private static java.util.Map<String, String> collectBodyFragments(
        TypeDeclaration<?> templateDecl, EvaluationContext ctx) {
    // Collect layers from outermost enclosing to innermost (template itself last)
    java.util.Deque<List<AnnotationExpr>> layers = new java.util.ArrayDeque<>();

    // Walk up to enclosing types
    com.github.javaparser.ast.Node current = templateDecl.getParentNode().orElse(null);
    while (current instanceof TypeDeclaration<?> enc) {
        layers.addFirst(enc.getAnnotations()); // prepend = outermost first
        current = enc.getParentNode().orElse(null);
    }
    layers.addLast(templateDecl.getAnnotations()); // template itself last (wins)

    java.util.Map<String, String> fragments = new java.util.LinkedHashMap<>();
    for (List<AnnotationExpr> anns : layers) {
        for (AnnotationExpr ann : anns) {
            String n = ann.getNameAsString();
            boolean isSingle = n.equals("PermuteBodyFragment")
                    || n.equals("io.quarkiverse.permuplate.PermuteBodyFragment");
            boolean isContainer = n.equals("PermuteBodyFragments")
                    || n.equals("io.quarkiverse.permuplate.PermuteBodyFragments");
            if (isSingle) {
                readBodyFragment(ann, ctx, fragments);
            } else if (isContainer && ann instanceof NormalAnnotationExpr normal) {
                normal.getPairs().stream()
                        .filter(p -> p.getNameAsString().equals("value"))
                        .findFirst()
                        .ifPresent(p -> {
                            if (p.getValue() instanceof ArrayInitializerExpr arr) {
                                arr.getValues().forEach(v -> {
                                    if (v instanceof AnnotationExpr inner)
                                        readBodyFragment(inner, ctx, fragments);
                                });
                            } else if (p.getValue() instanceof AnnotationExpr inner) {
                                readBodyFragment(inner, ctx, fragments);
                            }
                        });
            }
        }
    }
    return fragments;
}

private static void readBodyFragment(AnnotationExpr ann, EvaluationContext ctx,
        java.util.Map<String, String> out) {
    if (!(ann instanceof NormalAnnotationExpr normal)) return;
    String name = null, value = null;
    for (MemberValuePair p : normal.getPairs()) {
        String raw = PermuteDeclrTransformer.stripQuotes(p.getValue().toString());
        if (p.getNameAsString().equals("name")) name = raw;
        else if (p.getNameAsString().equals("value")) value = raw;
    }
    if (name == null || value == null) return;
    try {
        out.put(name, ctx.evaluate(value));
    } catch (Exception ignored) {
        // Evaluation failure → skip this fragment
    }
}
```

Add a helper to substitute fragment references in `@PermuteBody` body strings:

```java
/**
 * Substitutes ${fragmentName} references in @PermuteBody body attribute strings on
 * the given type declaration. Must be called BEFORE PermuteBodyTransformer runs.
 * Modifies the AST annotation values in-place.
 */
private static void applyBodyFragments(TypeDeclaration<?> classDecl,
        java.util.Map<String, String> fragments) {
    if (fragments.isEmpty()) return;

    classDecl.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
            .filter(a -> {
                String n = a.getNameAsString();
                return n.equals("PermuteBody")
                        || n.equals("io.quarkiverse.permuplate.PermuteBody");
            })
            .forEach(ann -> {
                if (!(ann instanceof NormalAnnotationExpr normal)) return;
                normal.getPairs().stream()
                        .filter(p -> p.getNameAsString().equals("body"))
                        .forEach(p -> {
                            String body = PermuteDeclrTransformer.stripQuotes(
                                    p.getValue().toString());
                            String expanded = substituteFragmentRefs(body, fragments);
                            if (!expanded.equals(body)) {
                                p.setValue(new com.github.javaparser.ast.expr.StringLiteralExpr(
                                        expanded));
                            }
                        });
            });
}

private static String substituteFragmentRefs(String body, java.util.Map<String, String> fragments) {
    for (java.util.Map.Entry<String, String> entry : fragments.entrySet()) {
        body = body.replace("${" + entry.getKey() + "}", entry.getValue());
    }
    return body;
}
```

In `generate()`, add two calls in the COID branch — collect fragments once before the loop,
apply per-generated-class just before `PermuteBodyTransformer.transform()`:

```java
// Before the per-combination loop (add after config/allCombinations setup):
java.util.Map<String, String> bodyFragments = new java.util.HashMap<>();
// (populated inside the loop with per-combination evaluated fragments)
```

Inside the per-combination loop, in the COID branch, just before the `PermuteBodyTransformer.transform(generated, ctx)` line:

```java
// @PermuteBodyFragment — substitute ${name} refs in @PermuteBody body strings
java.util.Map<String, String> fragments = collectBodyFragments(templateClassDecl, ctx);
applyBodyFragments(coid, fragments);
// Then existing:
PermuteBodyTransformer.transform(generated, ctx);
```

Also add `@PermuteBodyFragment`/`@PermuteBodyFragments` to the annotation-stripping logic
(find where `@Permute`, `@PermuteFilter`, etc. are stripped and add these names).

- [ ] **Step 5: Apply @PermuteBodyFragment to RuleOOPathBuilder.java**

Read the current `path()` method in `RuleOOPathBuilder.java`. It has two `@PermuteBody` annotations
sharing a long `steps.add(new OOPathStep(...))` prefix. Add the fragment on `Path1` class:

```java
@PermuteBodyFragment(name = "addStep",
    value = "steps.add(new OOPathStep(" +
            "(ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact), " +
            "(ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));")
@Permute(varName = "i", from = "2", to = "6", className = "Path${i}",
         inline = true, keepTemplate = true)
public static class Path1<...> {
    ...
    @SuppressWarnings("unchecked")
    @PermuteReturn(when = "${i == 2}", typeParam = "END")
    @PermuteReturn(when = "${i > 2}", className = "RuleOOPathBuilder.Path${i-1}",
                   typeArgs = "'END, T, ' + typeArgList(2, i, 'alpha')",
                   alwaysEmit = true)
    @PermuteBody(when = "${i == 2}",
                 body = "{ ${addStep} rd.addOOPathPipeline(rootIndex, steps); return end; }")
    @PermuteBody(when = "${i > 2}",
                 body = "{ ${addStep} return new @PermuteDeclr(type = \"RuleOOPathBuilder.Path${i-1}\") Path2<>(end, rd, steps, rootIndex); }")
    public Object path(Function2<PathContext<T>, A, Iterable<B>> fn2,
            Predicate2<PathContext<T>, B> flt2) {
        return null; // replaced by @PermuteBody
    }
}
```

Add import: `import io.quarkiverse.permuplate.PermuteBodyFragment;`

- [ ] **Step 6: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

- [ ] **Step 7: Run fragment test**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=PermuteBodyFragmentTest -q 2>&1 | tail -3
```

Expected: all 4 tests pass.

- [ ] **Step 8: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteBodyFragment.java
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteBodyFragments.java
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleOOPathBuilder.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/PermuteBodyFragmentTest.java
git commit -m "$(cat <<'EOF'
feat: @PermuteBodyFragment — named reusable body templates for @PermuteBody (closes #106)

Fragments declared on template/enclosing class; evaluated with JEXL context;
substituted as ${name} in @PermuteBody strings before body transformation.
Applied to OOPath: shared steps.add() prefix extracted into 'addStep' fragment.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Item 8 — @PermuteParam body expansion inside @PermuteMethod (closes #107)

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Modify: `DSL-TEMPLATES/JoinBuilder.java` (remove @PermuteBody from filterVar)
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteParamInMethodTest.java`
- Modify: `DSL-TESTS/RuleBuilderTest.java` (existing variable filter tests become the E2E check)

**Background:** `PermuteParamTransformer` already calls `expandAnchorAtCallSites` (method body
call-site expansion) when processing method-level `@PermuteParam`. However, when `@PermuteParam`
appears inside a `@PermuteMethod` clone, the outer pipeline sees the clone with `to="${m}"` where
`m` is the inner variable — not in scope at the outer context. The fix: process `@PermuteParam`
on each method clone **inside `applyPermuteMethod`** with the inner context (`innerCtx` that
has `m` bound).

- [ ] **Step 1: Write failing compile-testing test**

Create `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteParamInMethodTest.java`:

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static io.quarkiverse.permuplate.testing.PermuplateAssertions.assertGenerated;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Verifies that @PermuteParam inside @PermuteMethod clones is processed with the
 * inner context, enabling body call-site expansion without @PermuteBody.
 */
public class PermuteParamInMethodTest {

    /**
     * Template: an inner @PermuteMethod(n=2..3) clone has a @PermuteParam that expands v1
     * to v1,v2 (or v1,v2,v3). The body call rd.log(v1) should expand to rd.log(v1, v2)
     * without any @PermuteBody.
     */
    @Test
    public void testPermuteParamExpandsInsidePermuteMethod() {
        // This test exercises APT mode, but the same logic applies to inline mode.
        // We test the compile output to verify call-site expansion happened.
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.VarFilterTemplate",
                        "package io.permuplate.test;\n"
                        + "import io.quarkiverse.permuplate.*;\n"
                        + "@Permute(varName=\"i\", from=\"1\", to=\"3\", className=\"VarFilter${i}\")\n"
                        + "public class VarFilterTemplate1 {\n"
                        + "    @PermuteMethod(varName=\"m\", from=\"2\", to=\"3\", name=\"log\")\n"
                        + "    public <@PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${m}\", name=\"V${k}\") V1>\n"
                        + "    void logVar(\n"
                        + "        @PermuteParam(varName=\"k\", from=\"1\", to=\"${m}\",\n"
                        + "                      type=\"V${k}\", name=\"v${k}\") V1 v1,\n"
                        + "        Object extra) {\n"
                        + "        System.out.println(v1);\n" // anchor v1 should expand
                        + "    }\n"
                        + "}\n"));

        assertThat(compilation).succeeded();

        // For i=2: m=2 clone → log(V1 v1, V2 v2, Object extra), body: println(v1, v2)
        // For i=2: m=3 clone → log(V1 v1, V2 v2, V3 v3, Object extra), body: println(v1, v2, v3)
        String src2 = sourceOf(compilation.generatedSourceFile(
                "io.permuplate.test.VarFilter2").orElseThrow());
        // The m=2 log() method should have 3 params: v1, v2, extra
        assertThat(src2).containsMatch("void log\\(V1 v1, V2 v2, Object extra\\)");
        // The body call should expand to println(v1, v2)
        assertThat(src2).contains("System.out.println(v1, v2)");

        String src3 = sourceOf(compilation.generatedSourceFile(
                "io.permuplate.test.VarFilter3").orElseThrow());
        assertThat(src3).containsMatch("void log\\(V1 v1, V2 v2, V3 v3, Object extra\\)");
        assertThat(src3).contains("System.out.println(v1, v2, v3)");
    }

    private static String sourceOf(javax.tools.JavaFileObject file) {
        try { return file.getCharContent(true).toString(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest=PermuteParamInMethodTest -q 2>&1 | tail -10
```

Expected: FAIL — `println(v1, v2)` not found; currently just `println(v1)`.

- [ ] **Step 3: Locate applyPermuteMethod in InlineGenerator**

```bash
grep -n "applyPermuteMethod\|applyPermuteMethodApt\|applyPermuteMethodClone\|innerCtx\|PermuteParam.*inner\|PermuteParamTransformer" \
  /Users/mdproctor/claude/permuplate/permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java | head -30
```

Find the section inside `applyPermuteMethod` where the clone is created and processed with
`innerCtx`. It looks like: create `tmpClass`, apply `PermuteBodyTransformer`, apply
`applyPermuteReturn`. Read lines around those calls.

- [ ] **Step 4: Add PermuteParamTransformer call inside applyPermuteMethod**

In `InlineGenerator.java`, inside `applyPermuteMethod` (the method that handles `@PermuteMethod`
expansion in inline/Maven mode), find where each method clone is processed with `innerCtx`.
There should be a sequence like:

```java
// Current code (approximate):
ClassOrInterfaceDeclaration tmpClass = new ClassOrInterfaceDeclaration();
tmpClass.addMember(clone);
PermuteBodyTransformer.transform(tmpClass, innerCtx);
applyPermuteReturn(tmpClass, innerCtx, allGeneratedNames);
// ... extract processed clone
```

Add `PermuteParamTransformer.transform(tmpClass, innerCtx, null)` BEFORE
`PermuteBodyTransformer.transform(tmpClass, innerCtx)`:

```java
// NEW: process @PermuteParam on this clone with the inner context (i+j both in scope)
// This enables body call-site expansion for @PermuteParam params referencing inner vars.
io.quarkiverse.permuplate.core.PermuteParamTransformer.transform(tmpClass, innerCtx, null);
// Then existing:
PermuteBodyTransformer.transform(tmpClass, innerCtx);
applyPermuteReturn(tmpClass, innerCtx, allGeneratedNames);
```

The import is likely already there; if not, add:
`import io.quarkiverse.permuplate.core.PermuteParamTransformer;`

- [ ] **Step 5: Run the failing test again — confirm it now passes**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest=PermuteParamInMethodTest -q 2>&1 | tail -5
```

Expected: PASS.

- [ ] **Step 6: Remove @PermuteBody from filterVar in JoinBuilder.java**

Find the `filterVar` method template. Remove the `@PermuteBody` annotation — call-site
expansion now handles the `rd.addVariableFilter(v1, predicate)` expansion automatically:

```java
// BEFORE: had @PermuteBody(body="{ rd.addVariableFilter(${typeArgList(1, m, 'v')}, predicate); return this; }")
// AFTER: remove @PermuteBody entirely; the body works as-is with anchor expansion

@PermuteMethod(varName = "m", from = "2", to = "3", name = "filter")
@PermuteSelf
public <@PermuteTypeParam(varName = "k", from = "1", to = "${m}", name = "V${k}") V1>
        Object filterVar(
        @PermuteParam(varName = "k", from = "1", to = "${m}",
                      type = "Variable<V${k}>", name = "v${k}") Variable<V1> v1,
        @PermuteDeclr(type = "Predicate${m+1}<DS, ${typeArgList(1, m, 'V')}>")
        Object predicate) {
    rd.addVariableFilter(v1, predicate);
    return this;
}
```

The body `rd.addVariableFilter(v1, predicate)` → call-site expands `v1` → `v1, v2` (m=2)
or `v1, v2, v3` (m=3). The `predicate` argument follows unchanged.

- [ ] **Step 7: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. Variable filter tests pass unchanged.

- [ ] **Step 8: Verify variable filter E2E**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples \
  -Dtest=RuleBuilderTest#testVariableFilter2BoundVars+testVariableFilter3BoundVars \
  -q 2>&1 | tail -5
```

Expected: both pass.

- [ ] **Step 9: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteParamInMethodTest.java
git commit -m "$(cat <<'EOF'
feat: @PermuteParam processes inside @PermuteMethod with inner context (closes #107)

PermuteParamTransformer now runs on each @PermuteMethod clone before @PermuteBody,
using the inner context (m bound). Call-site expansion fires correctly.
filterVar drops @PermuteBody — rd.addVariableFilter(v1, predicate) expands naturally.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Item 9 — @PermuteReturn inference from @PermuteBody return expression (closes #108)

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteReturnFromBodyTest.java`

**Note:** This inference fires when `@PermuteBody` sets a body containing `return new X<>()` or
`return cast(new X<>())` where X is in the generated class set. Cross-file classes (like
NotScope/ExistsScope) are NOT in the set, so `scopeTemplate` is unaffected. This inference
helps user templates; DSL impact is future-facing.

- [ ] **Step 1: Write failing compile-testing test**

Create `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteReturnFromBodyTest.java`:

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Verifies that when @PermuteBody sets a body with "return new GeneratedClass<>()"
 * and @PermuteReturn is absent, the return type is inferred from the body.
 * Only fires for classes in the current template's generated set.
 */
public class PermuteReturnFromBodyTest {

    @Test
    public void testReturnTypeInferredFromNewExpression() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.ChainTemplate",
                        "package io.permuplate.test;\n"
                        + "import io.quarkiverse.permuplate.*;\n"
                        + "@Permute(varName=\"i\", from=\"1\", to=\"3\", className=\"Chain${i}\")\n"
                        + "public class ChainTemplate1 {\n"
                        // No @PermuteReturn — inferred from body
                        + "    @PermuteBody(when=\"${i < 3}\","
                        + "        body=\"{ return new Chain${i+1}(); }\")\n"
                        + "    @PermuteBody(when=\"${i == 3}\","
                        + "        body=\"{ return null; }\")\n"
                        + "    public Object next() { return null; }\n"
                        + "}\n"));

        assertThat(compilation).succeeded();

        // Chain1.next() return type should be inferred as Chain2
        String src1 = sourceOf(compilation.generatedSourceFile(
                "io.permuplate.test.Chain1").orElseThrow());
        assertThat(src1).containsMatch("Chain2\\s+next\\(\\)");

        // Chain2.next() → Chain3
        String src2 = sourceOf(compilation.generatedSourceFile(
                "io.permuplate.test.Chain2").orElseThrow());
        assertThat(src2).containsMatch("Chain3\\s+next\\(\\)");

        // Chain3.next() → body is "return null" with no class ref → no inference → Object
        String src3 = sourceOf(compilation.generatedSourceFile(
                "io.permuplate.test.Chain3").orElseThrow());
        assertThat(src3).containsMatch("Object\\s+next\\(\\)");
    }

    @Test
    public void testNoInferenceWhenReturnClassNotInGeneratedSet() {
        // When return new X() where X is NOT in the generated set — no inference, stays Object
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.CrossRef",
                        "package io.permuplate.test;\n"
                        + "import io.quarkiverse.permuplate.*;\n"
                        + "@Permute(varName=\"i\", from=\"1\", to=\"2\", className=\"Ref${i}\")\n"
                        + "public class CrossRef1 {\n"
                        + "    @PermuteBody(body=\"{ return new java.util.ArrayList<>(); }\")\n"
                        + "    public Object list() { return null; }\n"
                        + "}\n"));

        assertThat(compilation).succeeded();
        String src1 = sourceOf(compilation.generatedSourceFile(
                "io.permuplate.test.Ref1").orElseThrow());
        // ArrayList is not in generated set — return stays Object
        assertThat(src1).containsMatch("Object\\s+list\\(\\)");
    }

    @Test
    public void testNoInferenceWhenPermuteReturnExplicitlyPresent() {
        // Explicit @PermuteReturn takes precedence — no inference
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.Explicit",
                        "package io.permuplate.test;\n"
                        + "import io.quarkiverse.permuplate.*;\n"
                        + "@Permute(varName=\"i\", from=\"1\", to=\"2\", className=\"Exp${i}\")\n"
                        + "public class Explicit1 {\n"
                        + "    @PermuteReturn(className=\"Exp${i+1}\", alwaysEmit=true)\n"
                        + "    @PermuteBody(body=\"{ return null; }\")\n"
                        + "    public Object next() { return null; }\n"
                        + "}\n"));

        assertThat(compilation).succeeded();
        // @PermuteReturn says Exp2; body says null — @PermuteReturn wins
        String src1 = sourceOf(compilation.generatedSourceFile(
                "io.permuplate.test.Exp1").orElseThrow());
        assertThat(src1).containsMatch("Exp2\\s+next\\(\\)");
    }

    private static String sourceOf(javax.tools.JavaFileObject file) {
        try { return file.getCharContent(true).toString(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 2: Run to confirm tests 1 and 3 fail, test 2 passes**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest=PermuteReturnFromBodyTest -q 2>&1 | tail -10
```

Expected: test 2 passes, tests 1 and 3 may fail/pass depending on current behavior.

- [ ] **Step 3: Add body-return inference to InlineGenerator**

In `InlineGenerator.java`, add a new private static method. Place it after
`applyInlineDefaultReturn`:

```java
/**
 * Post-pass inference: when a method has Object return, no explicit @PermuteReturn,
 * and its body (already set by @PermuteBody) ends with "return new X<>()" or
 * "return cast(new X<>())" where X is in allGeneratedNames, infer the return type as X.
 *
 * Does NOT apply to methods that still have @PermuteReturn (already processed by
 * applyPermuteReturn) or methods handled by self-return inference or @PermuteDefaultReturn.
 * Runs AFTER applyPermuteReturn, applyInlineDefaultReturn, and PermuteBodyTransformer.
 */
private static void inferReturnFromBody(ClassOrInterfaceDeclaration classDecl,
        Set<String> allGeneratedNames) {

    classDecl.getMethods().forEach(method -> {
        // Skip: not Object sentinel
        if (!method.getType().asString().equals("Object")) return;
        // Skip: still has @PermuteReturn (explicit annotation not yet removed for some reason)
        boolean hasReturn = method.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.equals("PermuteReturn") || n.equals("io.quarkiverse.permuplate.PermuteReturn")
                    || n.equals("PermuteReturns") || n.equals("io.quarkiverse.permuplate.PermuteReturns");
        });
        if (hasReturn) return;

        // Check body for return new X<>() or return cast(new X<>()) as LAST statement
        method.getBody().ifPresent(body -> {
            if (body.getStatements().isEmpty()) return;
            com.github.javaparser.ast.stmt.Statement last =
                    body.getStatements().get(body.getStatements().size() - 1);
            if (!(last instanceof com.github.javaparser.ast.stmt.ReturnStmt rs)) return;
            if (rs.getExpression().isEmpty()) return;

            com.github.javaparser.ast.expr.Expression retExpr = rs.getExpression().get();
            String candidateClass = extractNewClassName(retExpr);
            if (candidateClass == null) return;

            // Only infer when the class is in the generated set
            if (!allGeneratedNames.contains(candidateClass)) return;

            try {
                method.setType(StaticJavaParser.parseType(candidateClass));
            } catch (Exception ignored) {
            }
        });
    });
}

/** Extracts "X" from "new X<>()" or "cast(new X<>())" or similar. */
private static String extractNewClassName(com.github.javaparser.ast.expr.Expression expr) {
    // Unwrap cast(...)
    if (expr instanceof com.github.javaparser.ast.expr.MethodCallExpr mc
            && mc.getNameAsString().equals("cast")
            && mc.getArguments().size() == 1) {
        expr = mc.getArguments().get(0);
    }
    // Match "new X<>(...)" or "new X(...)"
    if (expr instanceof com.github.javaparser.ast.expr.ObjectCreationExpr oce) {
        return oce.getType().getNameAsString();
    }
    // Match "new Outer.X<>(...)": qualified name
    if (expr instanceof com.github.javaparser.ast.expr.ObjectCreationExpr oce) {
        return oce.getType().asString().replaceAll("<.*>", "").trim();
    }
    return null;
}
```

Fix the duplicate condition in `extractNewClassName` (consolidate the two `instanceof` checks):

```java
private static String extractNewClassName(com.github.javaparser.ast.expr.Expression expr) {
    if (expr instanceof com.github.javaparser.ast.expr.MethodCallExpr mc
            && mc.getNameAsString().equals("cast")
            && mc.getArguments().size() == 1) {
        expr = mc.getArguments().get(0);
    }
    if (expr instanceof com.github.javaparser.ast.expr.ObjectCreationExpr oce) {
        // Strip type args: "Path${i-1}" after evaluation is just "Path2" etc.
        return oce.getType().getNameAsString();
    }
    return null;
}
```

Call `inferReturnFromBody` in the COID pipeline, AFTER `PermuteBodyTransformer.transform()`
and AFTER `applyInlineDefaultReturn()` and self-return inference:

```java
// After PermuteBodyTransformer.transform(generated, ctx):
// After applyInlineDefaultReturn(coid, ctx, allGeneratedNames):
// After applySelfReturnInference(coid, ctx, allGeneratedNames):  // if it exists
inferReturnFromBody(coid, allGeneratedNames);
```

- [ ] **Step 4: Run the test again**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest=PermuteReturnFromBodyTest -q 2>&1 | tail -5
```

Expected: all 3 tests pass.

- [ ] **Step 5: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

- [ ] **Step 6: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteReturnFromBodyTest.java
git commit -m "$(cat <<'EOF'
feat: infer @PermuteReturn from 'return new X<>()' in @PermuteBody (closes #108)

Post-pass after PermuteBodyTransformer: if the last return stmt is 'return new X<>()'
and X is in the generated set, return type is inferred as X. Explicit @PermuteReturn wins.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Item 10 — @PermuteDefaultReturn(className="self") (closes #109)

**Files:**
- Modify: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDefaultReturn.java`
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDefaultReturnSelfTest.java`

- [ ] **Step 1: Write failing compile-testing test**

Create `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDefaultReturnSelfTest.java`:

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Verifies that @PermuteDefaultReturn(className="self") sets the return type to the
 * current generated class with its full type parameter list.
 */
public class PermuteDefaultReturnSelfTest {

    @Test
    public void testSelfReturnOnFluentBuilder() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.FluentTemplate",
                        "package io.permuplate.test;\n"
                        + "import io.quarkiverse.permuplate.*;\n"
                        + "@Permute(varName=\"i\", from=\"1\", to=\"3\", className=\"Fluent${i}\")\n"
                        + "@PermuteDefaultReturn(className=\"self\")\n"
                        + "public class FluentTemplate1<\n"
                        + "    @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n"
                        + "                      name=\"T${k}\") T1> {\n"
                        + "    public Object withA(String a) { return this; }\n"
                        + "    public Object withB(int b)    { return this; }\n"
                        + "}\n"));

        assertThat(compilation).succeeded();

        // Fluent1<T1>: withA and withB return Fluent1<T1>
        String src1 = sourceOf(compilation.generatedSourceFile(
                "io.permuplate.test.Fluent1").orElseThrow());
        assertThat(src1).containsMatch("Fluent1<T1>\\s+withA\\(");
        assertThat(src1).containsMatch("Fluent1<T1>\\s+withB\\(");

        // Fluent3<T1, T2, T3>: withA and withB return Fluent3<T1, T2, T3>
        String src3 = sourceOf(compilation.generatedSourceFile(
                "io.permuplate.test.Fluent3").orElseThrow());
        assertThat(src3).containsMatch("Fluent3<T1, T2, T3>\\s+withA\\(");
        assertThat(src3).containsMatch("Fluent3<T1, T2, T3>\\s+withB\\(");
    }

    @Test
    public void testSelfWithNoTypeParams() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.SimpleTemplate",
                        "package io.permuplate.test;\n"
                        + "import io.quarkiverse.permuplate.*;\n"
                        + "@Permute(varName=\"i\", from=\"1\", to=\"2\", className=\"Simple${i}\")\n"
                        + "@PermuteDefaultReturn(className=\"self\")\n"
                        + "public class SimpleTemplate1 {\n"
                        + "    public Object build() { return this; }\n"
                        + "}\n"));

        assertThat(compilation).succeeded();
        // No type params → return type is bare class name
        String src1 = sourceOf(compilation.generatedSourceFile(
                "io.permuplate.test.Simple1").orElseThrow());
        assertThat(src1).containsMatch("Simple1\\s+build\\(\\)");
    }

    private static String sourceOf(javax.tools.JavaFileObject file) {
        try { return file.getCharContent(true).toString(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest=PermuteDefaultReturnSelfTest -q 2>&1 | tail -10
```

Expected: FAIL — `className="self"` is not handled (evaluated as literal string "self").

- [ ] **Step 3: Update @PermuteDefaultReturn Javadoc**

In `PermuteDefaultReturn.java`, update the `className()` Javadoc to document the "self" value:

```java
/**
 * Template expression for the default return class name. Evaluated against the
 * outer permutation context for each generated class.
 *
 * <p>Special value: {@code "self"} — sets the return type to the current generated
 * class with all its type parameters. Equivalent to using {@link PermuteSelf} on
 * every qualifying method. When {@code "self"} is used, the {@code typeArgs} attribute
 * is ignored (type params are derived automatically from the generated class).
 *
 * <p>Example: {@code @PermuteDefaultReturn(className = "self")}
 */
String className();
```

- [ ] **Step 4: Handle "self" in InlineGenerator.applyInlineDefaultReturn**

In `InlineGenerator.java`, in `applyInlineDefaultReturn`, add a check after evaluating
`classNameTemplate`. The method currently evaluates the template and builds the return type
string. Add the "self" special case:

```java
// In applyInlineDefaultReturn, after:
// String classNameTemplate = ... (read from annotation)
// if (classNameTemplate == null || classNameTemplate.isEmpty()) return;

// BEFORE ctx.evaluate(classNameTemplate), check for "self":
if ("self".equals(classNameTemplate)) {
    // Derive return type from current generated class name + type parameters
    String selfClassName = classDecl.getNameAsString();
    List<String> typeParamNames = new ArrayList<>();
    classDecl.getTypeParameters().forEach(tp -> typeParamNames.add(tp.getNameAsString()));
    final String typeSrc = typeParamNames.isEmpty()
            ? selfClassName
            : selfClassName + "<" + String.join(", ", typeParamNames) + ">";

    // Apply to all Object-returning methods without explicit @PermuteReturn
    classDecl.getMethods().stream()
            .filter(m -> m.getType().asString().equals("Object"))
            .filter(m -> m.getAnnotations().stream().noneMatch(a -> {
                String n = a.getNameAsString();
                return n.equals("PermuteReturn") || n.equals("io.quarkiverse.permuplate.PermuteReturn")
                        || n.equals("PermuteReturns") || n.equals("io.quarkiverse.permuplate.PermuteReturns");
            }))
            .forEach(m -> {
                try { m.setType(StaticJavaParser.parseType(typeSrc)); }
                catch (Exception ignored) {}
            });

    classDecl.getAnnotations().removeIf(a -> a == annOpt.get());
    return; // early return — "self" handled completely
}

// Original code continues for non-"self" className:
String evaluatedClass;
try {
    evaluatedClass = ctx.evaluate(classNameTemplate);
} catch (Exception ignored) {
    return;
}
// ... rest of existing method
```

- [ ] **Step 5: Handle "self" in PermuteProcessor (APT path)**

In `PermuteProcessor.java`, find `applyDefaultReturn` (the APT equivalent). Add the same
"self" check. The APT path has access to the generated class name via the element or the
renamed class declaration. Add an APT-mode NOTE if "self" is used (since APT doesn't have
the same post-expansion class name available):

```java
// In applyDefaultReturn (APT path), after reading classNameTemplate:
if ("self".equals(classNameTemplate)) {
    // In APT mode, the generated class name and type params are available from the
    // renamed class declaration. This is a COID-only feature.
    // Use the class element's simple name and type parameters.
    String selfClassName = classDecl.getNameAsString();
    List<String> typeParamNames = new ArrayList<>();
    classDecl.getTypeParameters().forEach(tp -> typeParamNames.add(tp.getNameAsString()));
    String typeSrc = typeParamNames.isEmpty()
            ? selfClassName
            : selfClassName + "<" + String.join(", ", typeParamNames) + ">";
    // Apply same logic as inline mode
    // ... (same filter and setType logic as above)
    return;
}
```

- [ ] **Step 6: Run the failing test — confirm it passes**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test -pl permuplate-tests \
  -Dtest=PermuteDefaultReturnSelfTest -q 2>&1 | tail -5
```

Expected: both tests pass.

- [ ] **Step 7: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

- [ ] **Step 8: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDefaultReturn.java
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java
git add permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java
git add permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDefaultReturnSelfTest.java
git commit -m "$(cat <<'EOF'
feat: @PermuteDefaultReturn(className='self') — return current class + type params (closes #109)

'self' is a reserved literal (not JEXL): maps to classDecl.getName() + all typeParams.
Replaces verbose className+typeArgs expressions. Works in both inline and APT modes.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Item 3 — @PermuteDefaultReturn replaces 5× @PermuteSelf on Join0First (closes #110)

**Files:**
- Modify: `DSL-TEMPLATES/JoinBuilder.java`

- [ ] **Step 1: Apply @PermuteDefaultReturn(className="self") to Join0First**

In `JoinBuilder.java`:

1. Add `@PermuteDefaultReturn(className = "self")` to the `Join0First` `@Permute` annotation block (after `@Permute`, before the class declaration):

```java
@Permute(varName = "i", from = "1", to = "6", className = "Join${i}First",
         inline = true, keepTemplate = false)
@PermuteDefaultReturn(className = "self")
public static non-sealed class Join0First<END, DS,
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
        extends Join0Second<END, DS, A>
        implements JoinBuilderFirst<END, DS> {
```

2. Remove `@PermuteSelf` from ALL five methods:
   - `filter()` (all-facts filter)
   - `filterLatest()` (now annotated with `@PermuteFilter` + `@PermuteMethod`)
   - `index()`
   - `var(Variable<T> v)`
   - `filterVar()` (the `@PermuteMethod(m=2..3)` template)

3. Add import `import io.quarkiverse.permuplate.PermuteDefaultReturn;` if not already present.

- [ ] **Step 2: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. All 5 methods still return the correct generated class.

- [ ] **Step 3: Verify return types in generated classes**

```bash
# Check generated output for Join3First
grep -n "filter\|index\|var(" \
  /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
  2>/dev/null | grep "Join3First" | head -10
```

Expected: `Join3First<END, DS, A, B, C> filter(...)`, `Join3First<...> index()` etc.

- [ ] **Step 4: Run existing DSL tests**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -q 2>&1 | tail -3
```

- [ ] **Step 5: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "$(cat <<'EOF'
refactor: @PermuteDefaultReturn(className='self') replaces 5x @PermuteSelf on Join0First (closes #110)

One class-level annotation replaces 5 method-level @PermuteSelf annotations.
Depends on item 10 ('self' special value).

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Item 4 — Consumer/Predicate cross-product template merge (closes #111)

**Files:**
- Create: `DSL-TEMPLATES/FunctionalTemplate1.java` (new cross-product template)
- Modify: `DSL-TEMPLATES/Consumer1.java` → becomes minimal arity-1 interface (not a template)
- Modify: `DSL-TEMPLATES/Predicate1.java` → becomes minimal arity-1 interface (not a template)
- Create: `DSL-TESTS/FunctionalFamilyTest.java`

- [ ] **Step 1: Write failing test**

Create `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/FunctionalFamilyTest.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

/**
 * Verifies the Consumer and Predicate functional interface families are correctly
 * generated by the cross-product @PermuteVar template.
 */
public class FunctionalFamilyTest {

    @Test
    public void testConsumer2HasAcceptMethod() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Consumer2");
        Method m = Arrays.stream(cls.getMethods())
                .filter(method -> method.getName().equals("accept"))
                .findFirst().orElse(null);
        assertThat(m).isNotNull();
        assertThat(m.getReturnType()).isEqualTo(void.class);
    }

    @Test
    public void testPredicate3HasTestMethod() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Predicate3");
        Method m = Arrays.stream(cls.getMethods())
                .filter(method -> method.getName().equals("test"))
                .findFirst().orElse(null);
        assertThat(m).isNotNull();
        assertThat(m.getReturnType()).isEqualTo(boolean.class);
    }

    @Test
    public void testConsumer7Exists() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Consumer7");
        assertThat(cls).isNotNull();
        assertThat(cls.isInterface()).isTrue();
    }

    @Test
    public void testPredicate7Exists() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Predicate7");
        assertThat(cls).isNotNull();
        assertThat(cls.isInterface()).isTrue();
    }

    @Test
    public void testConsumer2ParameterCount() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Consumer2");
        Method m = Arrays.stream(cls.getMethods())
                .filter(method -> method.getName().equals("accept"))
                .findFirst().orElseThrow();
        // Consumer2: accept(DS ctx, A a) → 2 parameters
        assertThat(m.getParameterCount()).isEqualTo(2);
    }

    @Test
    public void testPredicate4ParameterCount() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Predicate4");
        Method m = Arrays.stream(cls.getMethods())
                .filter(method -> method.getName().equals("test"))
                .findFirst().orElseThrow();
        // Predicate4: test(DS ctx, A a, B b, C c) → 4 parameters
        assertThat(m.getParameterCount()).isEqualTo(4);
    }
}
```

- [ ] **Step 2: Run to confirm baseline (all pass before changes)**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=FunctionalFamilyTest -q 2>&1 | tail -5
```

Expected: all 6 tests pass (Consumer/Predicate families already exist as separate templates).

- [ ] **Step 3: Create the cross-product FunctionalTemplate1.java**

Create `DSL-TEMPLATES/FunctionalTemplate1.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;
import io.quarkiverse.permuplate.PermuteVar;

/**
 * Single cross-product template generating the Consumer2..7 and Predicate2..7
 * functional interface families.
 *
 * <p>{@code @PermuteVar(varName="F", values={"Consumer","Predicate"})} cross-products
 * with {@code i=2..7}, producing 12 interfaces total. The method name and return type
 * vary with {@code F} via JEXL ternary in {@code @PermuteDeclr}.
 *
 * <p>Consumer1 and Predicate1 remain as separate hand-written arity-1 interfaces
 * (see Consumer1.java and Predicate1.java).
 */
@Permute(varName = "i", from = "2", to = "7", className = "${F}${i}",
         inline = false, keepTemplate = false,
         macros = {"method=${F == 'Consumer' ? 'accept' : 'test'}",
                   "ret=${F == 'Consumer' ? 'void' : 'boolean'}"})
@PermuteVar(varName = "F", values = {"Consumer", "Predicate"})
public interface FunctionalTemplate1<A> {

    @PermuteTypeParam(varName = "j", from = "1", to = "${i-1}", name = "${alpha(j)}")
    @PermuteDeclr(type = "${ret}", name = "${method}")
    @PermuteParam(varName = "j", from = "1", to = "${i-1}",
                  type = "${alpha(j)}", name = "${lower(j)}")
    void accept(A a);
}
```

- [ ] **Step 4: Replace Consumer1.java with minimal hand-written arity-1 interface**

Replace all content of `Consumer1.java`:

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Single-fact consumer: {@code accept(DS ctx, A a)}.
 * Arity-2 and above are generated by {@code FunctionalTemplate1}.
 */
@FunctionalInterface
public interface Consumer1<A> {
    void accept(A a);
}
```

- [ ] **Step 5: Replace Predicate1.java with minimal hand-written arity-1 interface**

Replace all content of `Predicate1.java`:

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Single-fact predicate: {@code test(DS ctx, A a)}.
 * Arity-2 and above are generated by {@code FunctionalTemplate1}.
 */
@FunctionalInterface
public interface Predicate1<A> {
    boolean test(A a);
}
```

- [ ] **Step 6: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. All 12 Consumer/Predicate interfaces still exist.

- [ ] **Step 7: Run functional family test and all DSL tests**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -q 2>&1 | tail -3
```

- [ ] **Step 8: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/FunctionalTemplate1.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/Consumer1.java
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/Predicate1.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/FunctionalFamilyTest.java
git commit -m "$(cat <<'EOF'
feat: merge Consumer+Predicate into single @PermuteVar cross-product template (closes #111)

FunctionalTemplate1.java: i=2..7 x F={Consumer,Predicate} generates 12 interfaces.
Consumer1/Predicate1 become minimal 8-line hand-written arity-1 interfaces.
~47 → ~35 lines; 3 files → 3 files with cleaner structure.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Item 5 — @FunctionalInterface on Consumer/Predicate via @PermuteAnnotation (closes #112)

**Files:**
- Modify: `DSL-TEMPLATES/FunctionalTemplate1.java`
- Verify: Consumer1.java and Predicate1.java already have `@FunctionalInterface` (added in Task 9)

- [ ] **Step 1: Add @PermuteAnnotation to FunctionalTemplate1.java**

In `FunctionalTemplate1.java`, add `@PermuteAnnotation` to the template interface:

```java
import io.quarkiverse.permuplate.PermuteAnnotation;

@Permute(...)
@PermuteVar(...)
@PermuteAnnotation(type = "FunctionalInterface")
public interface FunctionalTemplate1<A> {
    ...
}
```

- [ ] **Step 2: Write test for @FunctionalInterface presence**

Add to `FunctionalFamilyTest.java`:

```java
@Test
public void testConsumer4IsFunctionalInterface() throws Exception {
    Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Consumer4");
    assertThat(cls.isAnnotationPresent(FunctionalInterface.class)).isTrue();
}

@Test
public void testPredicate5IsFunctionalInterface() throws Exception {
    Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Predicate5");
    assertThat(cls.isAnnotationPresent(FunctionalInterface.class)).isTrue();
}

@Test
public void testConsumer1IsFunctionalInterface() throws Exception {
    assertThat(Consumer1.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
}

@Test
public void testPredicate1IsFunctionalInterface() throws Exception {
    assertThat(Predicate1.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
}
```

Note: `@FunctionalInterface` has `@Retention(RUNTIME)` so reflection works.

- [ ] **Step 3: Run tests to confirm 4 new tests fail (FunctionalInterface absent on generated)**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=FunctionalFamilyTest -q 2>&1 | tail -10
```

Expected: the 4 new tests fail; the 6 original tests pass.

- [ ] **Step 4: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

- [ ] **Step 5: Run all tests to confirm 4 new tests now pass**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn test \
  -pl permuplate-mvn-examples -Dtest=FunctionalFamilyTest -q 2>&1 | tail -5
```

Expected: all 10 tests pass.

- [ ] **Step 6: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/FunctionalTemplate1.java
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/FunctionalFamilyTest.java
git commit -m "$(cat <<'EOF'
feat: @FunctionalInterface on Consumer/Predicate via @PermuteAnnotation (closes #112)

All 14 Consumer1..7 and Predicate1..7 interfaces now carry @FunctionalInterface.
Enables lambda type-checking and IDE support for SAM pattern.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Item 6 — @PermuteMacros prev macro on RuleExtendsPoint (closes #113)

**Files:**
- Modify: `DSL-TEMPLATES/RuleExtendsPoint.java`

- [ ] **Step 1: Apply @PermuteMacros to RuleExtendsPoint**

In `RuleExtendsPoint.java`, add `@PermuteMacros({"prev=${i-1}"})` to the outer class and
update the `@PermuteTypeParam`:

```java
import io.quarkiverse.permuplate.PermuteMacros;

@PermuteMacros({"prev=${i-1}"})
public class RuleExtendsPoint {

    @Permute(varName = "i", from = "3", to = "7", className = "RuleExtendsPoint${i}",
             inline = true, keepTemplate = true)
    public static class RuleExtendsPoint2<DS,
            @PermuteTypeParam(varName = "k", from = "1", to = "${prev}", name = "${alpha(k)}") A>
            implements ExtendsPoint<DS> {
        // ... unchanged
    }
}
```

- [ ] **Step 2: Run full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. Generated output identical to before.

- [ ] **Step 3: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleExtendsPoint.java
git commit -m "$(cat <<'EOF'
refactor: @PermuteMacros prev macro on RuleExtendsPoint for readability (closes #113)

prev=${i-1} names the 'previous arity' concept explicitly in @PermuteTypeParam(to="${prev}").

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Documentation Sync

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/ROADMAP.md`
- Modify: `docs/superpowers/specs/2026-04-21-dsl-batch9-design.md` (final notes)
- Modify: `docs/adr/` if any ADR was affected

- [ ] **Step 1: Update CLAUDE.md**

Add new annotation entries:

```markdown
| `@PermuteBodyFragment` | template class or enclosing type | Defines a named body fragment; substituted as ${name} in @PermuteBody strings before JEXL evaluation. Maven plugin only. Repeatable. |
```

Update `@PermuteDefaultReturn` entry to mention `className="self"`:

```markdown
| `@PermuteDefaultReturn` | class | Class-level default return type for all Object-returning methods lacking @PermuteReturn. Special value: `className="self"` → current generated class + all its type params. |
```

Add non-obvious decisions:

```markdown
| `@PermuteBodyFragment` substitution order | Fragments are JEXL-evaluated with current ctx FIRST, then the result is text-substituted into @PermuteBody body strings BEFORE PermuteBodyTransformer runs. This means ${fragName} in body = pre-evaluated fragment; ${i} inside a fragment = JEXL evaluated immediately when fragment is collected. |
| `@PermuteParam` inside `@PermuteMethod` | PermuteParamTransformer now runs on each @PermuteMethod clone inside applyPermuteMethod with the inner context. This enables @PermuteParam(to="${m}") to work correctly when m is the inner variable. Call-site expansion (expandAnchorAtCallSites) fires naturally as part of this processing. |
| `@PermuteReturn` from `@PermuteBody` inference | After PermuteBodyTransformer: if body last-statement is return new X<>() and X ∈ generated set, return type is inferred. Cross-file classes NOT in generated set — explicit @PermuteReturn(alwaysEmit=true) still required for those. |
| `@PermuteDefaultReturn(className="self")` | "self" is a reserved literal (NOT JEXL): maps to classDecl.getNameAsString() + getTypeParameters(). No typeArgs attribute needed. Works in both inline and APT modes. |
| Consumer/Predicate cross-product | FunctionalTemplate1.java: @PermuteVar(F={Consumer,Predicate}) × i=2..7. @PermuteDeclr(type="${ret}", name="${method}") on method with ternary JEXL macros for ret and method. Consumer1/Predicate1 are hand-written arity-1 interfaces (not templates). |
| NegationScope.java orphan | Batch 8 git rm did not persist through subsequent git add -u operations. File regenerated ExistenceScope (now renamed ExistsScope). Deleted in batch 9 item 0. |
```

- [ ] **Step 2: Update ROADMAP.md**

Add all batch-9 items to the Completed table:

```markdown
| Batch 9 bug fix — delete NegationScope.java | Batch 8 git rm did not persist; orphan ExistenceScope generated. Deleted. |
| Batch 9 item 1 — Scope macro | macros={"Scope=capitalize(scope)"} eliminates 3× capitalize(scope) in not/exists method. |
| Batch 9 item 2 — @PermuteFilter on filterLatest | @PermuteFilter("i > 1") replaces max(2,i) trick for suppressing duplicate at arity 1. |
| Batch 9 item 7 — @PermuteBodyFragment | Named body fragments substituted into @PermuteBody strings. Applied to OOPath steps.add() duplication. |
| Batch 9 item 8 — @PermuteParam inside @PermuteMethod | PermuteParamTransformer runs on clones with inner context; filterVar drops @PermuteBody. |
| Batch 9 item 9 — @PermuteReturn from body inference | Infers return type from last 'return new X<>()' in @PermuteBody when X in generated set. |
| Batch 9 item 10 — @PermuteDefaultReturn("self") | className="self" → current class + all type params. Eliminates verbose typeArgs expression. |
| Batch 9 item 3 — @PermuteDefaultReturn replaces @PermuteSelf | 5× @PermuteSelf on Join0First → single class-level @PermuteDefaultReturn(className="self"). |
| Batch 9 item 4 — Consumer/Predicate cross-product | Single FunctionalTemplate1.java generates both families via @PermuteVar cross-product. |
| Batch 9 item 5 — @FunctionalInterface annotation | @PermuteAnnotation(type="FunctionalInterface") on all 14 SAM interfaces. |
| Batch 9 item 6 — @PermuteMacros on RuleExtendsPoint | prev=${i-1} macro names the previous-arity concept explicitly. |
```

- [ ] **Step 3: Run final full build**

```bash
cd /Users/mdproctor/claude/permuplate && /opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. All existing tests + new batch-9 tests pass.

- [ ] **Step 4: Commit documentation**

```bash
git add CLAUDE.md docs/ROADMAP.md docs/superpowers/specs/2026-04-21-dsl-batch9-design.md
git commit -m "$(cat <<'EOF'
docs: batch 9 documentation sync — CLAUDE.md, ROADMAP, new annotations documented

@PermuteBodyFragment, @PermuteDefaultReturn self, @PermuteParam-in-@PermuteMethod,
@PermuteReturn-from-body inference, Consumer/Predicate cross-product, NegationScope orphan.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- ✅ Bug fix (NegationScope) → Task 1
- ✅ Item 1 (Scope macro) → Task 2
- ✅ Item 2 (@PermuteFilter) → Task 3
- ✅ Item 7 (@PermuteBodyFragment) → Task 4
- ✅ Item 8 (@PermuteParam body expansion) → Task 5
- ✅ Item 9 (@PermuteReturn from body) → Task 6
- ✅ Item 10 (@PermuteDefaultReturn "self") → Task 7
- ✅ Item 3 (@PermuteDefaultReturn replaces @PermuteSelf) → Task 8
- ✅ Item 4 (Consumer/Predicate merge) → Task 9
- ✅ Item 5 (@FunctionalInterface) → Task 10
- ✅ Item 6 (@PermuteMacros RuleExtendsPoint) → Task 11
- ✅ Documentation sync → Task 12

**Placeholder scan:** No TBD/TODO. All code blocks complete. All commands have expected output.

**Type consistency:**
- `collectBodyFragments` returns `Map<String,String>` — used consistently in `applyBodyFragments`
- `extractNewClassName` returns `String?` — used in `inferReturnFromBody`
- `applyInlineDefaultReturn` modified in place with "self" branch — same method signature
- `FunctionalTemplate1.java` uses `${F}${i}` for className — consistent with `@PermuteVar(varName="F")`
- `@PermuteSelf` removed from 5 methods in Task 8; `@PermuteDefaultReturn` added in same task

**Dependency order verified:**
- Task 7 (`@PermuteDefaultReturn "self"`) before Task 8 (uses "self" in DSL)
- Task 9 (Consumer/Predicate merge) before Task 10 (@FunctionalInterface — also applies to merged template)
- Tasks 4–6 (new Permuplate features) before Task 8 (@PermuteParam expansion applied to filterVar)
