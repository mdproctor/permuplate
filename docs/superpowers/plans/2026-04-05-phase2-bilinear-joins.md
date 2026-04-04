# Phase 2 — First/Second Split and Bi-Linear Joins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the First/Second class hierarchy split with END phantom type and bi-linear join overloads, enabling pre-built fact sub-networks to be joined into other chains for Rete node-sharing patterns.

**Architecture:** PermuteMojo is fixed to chain multiple inline templates from the same parent file. Two new hand-written files (`BaseRuleBuilder<END>`, `JoinSecond<DS>`) provide the base class and interface. `RuleDefinition` gains a `TupleSource` abstraction that handles both single-fact and bi-linear sources uniformly. `JoinBuilder.java` is rewritten with two templates: `Join0Second` (with single-source and bi-linear `join()`) and `Join0First extends Join0Second` (with `filter()` and `fn()`). All classes gain the `END` phantom type parameter.

**Tech Stack:** JavaParser, Apache Commons JEXL3, Google compile-testing (JUnit 4), Maven (`/opt/homebrew/bin/mvn`).

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `permuplate-maven-plugin/src/main/java/.../maven/PermuteMojo.java` | Modify | Fix multi-template chaining |
| `permuplate-mvn-examples/src/main/java/.../drools/BaseRuleBuilder.java` | **Create** | `BaseRuleBuilder<END>` with `end()` |
| `permuplate-mvn-examples/src/main/java/.../drools/JoinSecond.java` | **Create** | `JoinSecond<DS>` interface |
| `permuplate-mvn-examples/src/main/java/.../drools/RuleDefinition.java` | Modify | TupleSource, accumulatedFacts, matchedTuples, addBilinearSource |
| `permuplate-mvn-examples/src/main/java/.../drools/RuleBuilder.java` | Modify | `from()` returns `Join1First<Void,DS,T>` |
| `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java` | Rewrite | Two templates with END: Join0Second + Join0First |
| `permuplate-mvn-examples/src/test/java/.../drools/RuleBuilderTest.java` | Modify | Add bi-linear tests; update constructor calls |

---

## Task 1: Fix PermuteMojo — Chain Multiple Inline Templates from Same Parent

**Files:**
- Modify: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/PermuteMojo.java`

**Problem:** When two `@Permute(inline=true)` templates live in the same parent file, `PermuteMojo` processes each independently and writes to the same output file — the second write overwrites the first. The fix groups inline templates by source file and chains the `InlineGenerator.generate()` calls.

**Why declaration order matters for `Join0Second` before `Join0First`:** `Join0Second`'s `join()` has `@PermuteReturn(className="Join${i+1}First")`. When `InlineGenerator.generate()` calls `scanAllGeneratedClassNames()`, it scans the *current* CU for all `@Permute` annotations to build the boundary-omission set. If `Join0Second` is processed first (original CU still has both templates), both JoinFirst and JoinSecond names are discovered — boundary omission works correctly. If processed second, JoinFirst names would already be gone.

- [ ] **Step 1: Replace the inline scan loop in `execute()` (lines 120–127)**

In `PermuteMojo.execute()`, replace the inline scan block:

```java
// --- Inline: scan templateDirectory ---
if (templateDirectory.exists()) {
    getLog().info("Permuplate: scanning " + templateDirectory + " for inline templates");
    SourceScanner.ScanResult templateScan = SourceScanner.scan(templateDirectory);
    for (SourceScanner.AnnotatedType entry : templateScan.types()) {
        processType(entry);
    }
}
```

With:

```java
// --- Inline: scan templateDirectory ---
if (templateDirectory.exists()) {
    getLog().info("Permuplate: scanning " + templateDirectory + " for inline templates");
    SourceScanner.ScanResult templateScan = SourceScanner.scan(templateDirectory);

    // Group inline templates by source file. Multiple inline templates in the same
    // parent must be chained: output CU of each call becomes input of the next.
    // Declaration order is preserved by SourceScanner.findAll() (depth-first).
    java.util.Map<java.nio.file.Path, java.util.List<SourceScanner.AnnotatedType>> inlineByFile =
            new java.util.LinkedHashMap<>();

    for (SourceScanner.AnnotatedType entry : templateScan.types()) {
        PermuteConfig config;
        try {
            config = AnnotationReader.readPermute(entry.permuteAnn());
        } catch (AnnotationReader.MojoAnnotationException e) {
            throw new MojoExecutionException(entry.sourceFile() + ": " + e.getMessage(), e);
        }
        boolean isNested = entry.classDecl().isNestedType();
        if (config.inline && !isNested) {
            throw new MojoExecutionException(entry.sourceFile() +
                    ": @Permute inline=true is only valid on nested static classes");
        }
        validateConfig(config, entry.sourceFile().toString());
        if (config.inline) {
            inlineByFile.computeIfAbsent(entry.sourceFile(),
                    k -> new java.util.ArrayList<>()).add(entry);
        } else {
            generateTopLevel(entry, config);
        }
    }

    for (java.util.Map.Entry<java.nio.file.Path,
            java.util.List<SourceScanner.AnnotatedType>> fileGroup : inlineByFile.entrySet()) {
        generateInlineGroup(fileGroup.getKey(), fileGroup.getValue());
    }
}
```

- [ ] **Step 2: Add `generateInlineGroup()` method to PermuteMojo**

Add this method after the existing `generateInline()` method:

```java
/**
 * Processes all inline templates from a single parent file in declaration order,
 * chaining InlineGenerator calls so the output CU of each becomes the input of
 * the next. Writes the final combined output once.
 *
 * <p>This is required when a parent file has multiple @Permute(inline=true) templates.
 * Without chaining, each call would write independently, overwriting the previous output.
 */
private void generateInlineGroup(java.nio.file.Path sourceFile,
        java.util.List<SourceScanner.AnnotatedType> entries) throws Exception {
    if (entries.isEmpty()) return;

    com.github.javaparser.ast.CompilationUnit currentCu = entries.get(0).cu();

    for (SourceScanner.AnnotatedType entry : entries) {
        String templateName = entry.classDecl().getNameAsString();

        // Find the template in the CURRENT CU — may be output of a previous call.
        ClassOrInterfaceDeclaration currentTemplate = currentCu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals(templateName))
                .orElseThrow(() -> new MojoExecutionException(sourceFile +
                        ": cannot find template class '" + templateName + "' in current CU"));

        // Re-read @Permute config from the template in the current CU.
        com.github.javaparser.ast.expr.AnnotationExpr permuteAnn =
                currentTemplate.getAnnotations().stream()
                        .filter(a -> a.getNameAsString().equals("Permute")
                                || a.getNameAsString().equals("io.quarkiverse.permuplate.Permute"))
                        .findFirst()
                        .orElseThrow(() -> new MojoExecutionException(sourceFile +
                                ": @Permute annotation missing on '" + templateName + "'"));

        PermuteConfig config = AnnotationReader.readPermute(permuteAnn);
        java.util.List<java.util.Map<String, Object>> allCombinations =
                PermuteConfig.buildAllCombinations(config);
        currentCu = InlineGenerator.generate(currentCu, currentTemplate, config, allCombinations);
    }

    // Write the final combined output once.
    ClassOrInterfaceDeclaration topLevel = currentCu.findFirst(
            ClassOrInterfaceDeclaration.class, c -> !c.isNestedType())
            .orElseThrow(() -> new MojoExecutionException(
                    sourceFile + ": cannot find top-level class in output"));
    String parentClassName = topLevel.getNameAsString();
    String packageName = currentCu.getPackageDeclaration()
            .map(p -> p.getNameAsString()).orElse("");
    String qualifiedName = packageName.isEmpty() ? parentClassName
            : packageName + "." + parentClassName;
    writeGeneratedFile(qualifiedName, currentCu.toString());
    getLog().info("Permuplate: generated inline group in " + qualifiedName);
}
```

- [ ] **Step 3: Build the plugin module only**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-maven-plugin -am 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/PermuteMojo.java
git commit -m "$(cat <<'EOF'
fix(maven-plugin): chain multiple inline templates from same parent file

PermuteMojo now groups inline templates by source file and processes them
in declaration order. The output CU of each InlineGenerator.generate() call
becomes the input of the next, with one combined write at the end. This
prevents the overwrite bug where the second template's output replaced the
first's. Required for JoinBuilder.java having both Join0Second and Join0First.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Add BaseRuleBuilder<END> and JoinSecond<DS>

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/BaseRuleBuilder.java`
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/JoinSecond.java`

- [ ] **Step 1: Create BaseRuleBuilder.java**

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Base class for all generated JoinNFirst and JoinNSecond classes.
 *
 * <p>The {@code END} phantom type parameter enables typed nested scopes. When a
 * scope-creating operation ({@code not()}, {@code exists()}) is called on a JoinNFirst,
 * it captures {@code this} as the END for the inner scope's builder. Calling {@code end()}
 * inside the inner scope returns that outer builder — fully typed — allowing the fluent
 * chain to continue at the outer arity.
 *
 * <p>For top-level rules (created via {@link RuleBuilder#from}), END is {@code Void}
 * and {@code end()} returns {@code null}. It is never called on top-level chains.
 *
 * <p>Arity trace showing END and end() in action (from real Drools pattern):
 * <pre>
 *   .params()           → From1First&lt;Void,DS,Params3&gt;                  arity: 1
 *   .join(persons)      → Join2First&lt;Void,DS,Params3,Person&gt;            arity: 2
 *   .not()              → Not2&lt;Join2Second&lt;Void,...&gt;, DS, Params3, Person&gt;
 *       .join(misc)     → Join3First&lt;Join2Second&lt;Void,...&gt;, ...&gt;         arity: 3 (inside scope)
 *       .join(libs)     → Join4First&lt;Join2Second&lt;Void,...&gt;, ...&gt;         arity: 4 (inside scope)
 *   .end()              → Join2Second&lt;Void,DS,Params3,Person&gt;            arity: 2 (reset!)
 *   .fn((a,b,c) -&gt; ...)  Consumer3&lt;Context&lt;DS&gt;,Params3,Person&gt;          arity 2 confirmed
 * </pre>
 *
 * <p>The not-scope facts (misc, libs) are NOT added to the outer chain's arity — they
 * only constrain which outer (Params3, Person) combinations are valid. This matches the
 * Rete NegativeExistsNode pattern: the inner sub-network filters the outer tuples but
 * does not contribute additional fact types to the outer chain.
 */
public class BaseRuleBuilder<END> {

    private final END end;

    public BaseRuleBuilder(END end) {
        this.end = end;
    }

    /**
     * Returns to the outer builder context, resetting the chain's arity to what it
     * was before the current scope was entered. For top-level chains (END = Void),
     * returns null and should never be called.
     */
    public END end() {
        return end;
    }
}
```

- [ ] **Step 2: Create JoinSecond.java**

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Marker interface implemented by all generated JoinNSecond classes.
 *
 * <p>Exposes the underlying {@link RuleDefinition} so that bi-linear join bodies
 * can extract the right sub-network's definition without reflection. All generated
 * JoinNSecond classes implement this via the {@code Join0Second} template.
 *
 * <p>Used as a cast target in {@code joinBilinear()} bodies:
 * <pre>
 *   JoinSecond&lt;DS&gt; second = (JoinSecond&lt;DS&gt;) secondChain;
 *   rd.addBilinearSource(second.getRuleDefinition());
 * </pre>
 *
 * <p>Since {@code JoinNFirst extends JoinNSecond}, a pre-built {@code JoinNFirst}
 * satisfies this interface and can be passed wherever {@code JoinNSecond} is expected —
 * the key property enabling bi-linear node-sharing patterns.
 */
public interface JoinSecond<DS> {
    RuleDefinition<DS> getRuleDefinition();
}
```

- [ ] **Step 3: Verify compilation**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/BaseRuleBuilder.java \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/JoinSecond.java
git commit -m "$(cat <<'EOF'
feat(drools-example): add BaseRuleBuilder<END> and JoinSecond<DS>

BaseRuleBuilder<END> provides the end() method for typed nested scope return.
END is Void for top-level chains (from()), or the outer builder type for nested
scopes (not(), exists()). Documented with full arity trace showing how end()
resets the arity after a not()-scope.

JoinSecond<DS> is the marker interface exposing getRuleDefinition() so
bi-linear join bodies can access the right sub-network without reflection.
JoinNFirst extends JoinNSecond, so pre-built JoinNFirst satisfies this interface.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Refactor RuleDefinition — TupleSource, Bi-Linear Execution

**Files:**
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java`

This is a full rewrite of the class. Replace the entire file content:

- [ ] **Step 1: Replace RuleDefinition.java**

```java
package io.quarkiverse.permuplate.example.drools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Captures the structure of a rule built via the DSL and executes it.
 *
 * <p>Sources are represented as {@link TupleSource} entries — a unified abstraction
 * over single-fact sources (each contributes one fact per step) and bi-linear
 * sub-network sources (each contributes a tuple of N facts per step). This models
 * the Rete bi-linear beta node pattern where a right-input sub-network executes
 * independently and its matched tuples are cross-producted with the left chain.
 *
 * <p>The {@code accumulatedFacts} field tracks the total number of fact columns
 * added so far. This is used by {@link #addFilter} to capture the correct
 * registration position for single-fact filters — {@code sources.size()} is no
 * longer correct since a bi-linear source is one entry but contributes N columns.
 */
public class RuleDefinition<DS> {

    @FunctionalInterface
    interface NaryPredicate {
        boolean test(Object ctx, Object[] facts);
    }

    @FunctionalInterface
    interface NaryConsumer {
        void accept(Object ctx, Object[] facts);
    }

    /**
     * A source that produces fact-tuples given a context. Linear sources produce
     * singleton-array tuples (one fact each). Bi-linear sources produce multi-element
     * tuples from an independent sub-network execution.
     */
    @FunctionalInterface
    interface TupleSource<DS> {
        List<Object[]> tuples(DS ctx);
    }

    private final String name;
    private final List<TupleSource<DS>> sources = new ArrayList<>();
    private int accumulatedFacts = 0;
    private final List<NaryPredicate> filters = new ArrayList<>();
    private NaryConsumer action;
    private final List<List<Object>> executions = new ArrayList<>();

    public RuleDefinition(String name) {
        this.name = name;
    }

    // -------------------------------------------------------------------------
    // Builder methods — called by generated JoinFirst/Second classes
    // -------------------------------------------------------------------------

    /**
     * Adds a single-fact data source. Each element from the DataSource becomes
     * a singleton-array tuple in the cross-product.
     */
    public void addSource(Object sourceSupplier) {
        @SuppressWarnings("unchecked")
        Function<DS, DataSource<?>> fn = (Function<DS, DataSource<?>>) sourceSupplier;
        sources.add(ctx -> fn.apply(ctx).asList().stream()
                .map(f -> new Object[]{f})
                .collect(Collectors.toList()));
        accumulatedFacts += 1;
    }

    /**
     * Adds a bi-linear sub-network source. The sub-network's RuleDefinition is executed
     * independently against the same ctx; its matched tuples are cross-producted with
     * the current chain's tuples. The sub-network's internal filters apply only within
     * the sub-network — they gate which of its tuples enter the cross-product, not which
     * combined tuples pass overall.
     *
     * <p>This models the Rete bi-linear beta node: the right-input sub-network executes
     * as an independent unit. Facts are flattened for method calls (predicates and
     * consumers see a flat Object[] array), matching the Rete convention of traversing
     * the tuple tree when invoking lambdas.
     */
    public void addBilinearSource(RuleDefinition<DS> subNetwork) {
        sources.add(ctx -> subNetwork.matchedTuples(ctx));
        accumulatedFacts += subNetwork.factArity();
    }

    /**
     * Returns the total number of fact columns this RuleDefinition contributes per
     * matched tuple. Used by the parent chain when this RuleDefinition is used as a
     * bi-linear source, so the parent can correctly track accumulatedFacts.
     */
    public int factArity() {
        return accumulatedFacts;
    }

    public void addFilter(Object typedPredicate) {
        // Capture the accumulated fact count at registration time — not sources.size().
        // A bi-linear source is one entry in sources but contributes N fact columns.
        // This count tells wrapPredicate which index is the "latest" fact for
        // single-fact (Predicate2) filters registered after a join.
        int registeredFactCount = accumulatedFacts;
        filters.add(wrapPredicate(typedPredicate, registeredFactCount));
    }

    public void setAction(Object typedConsumer) {
        this.action = wrapConsumer(typedConsumer);
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Executes this rule against the given context, recording matched fact combinations.
     */
    public RuleDefinition<DS> run(DS ctx) {
        executions.clear();
        for (Object[] facts : matchedTuples(ctx)) {
            if (action != null) action.accept(ctx, facts);
            executions.add(Arrays.asList(facts));
        }
        return this;
    }

    /**
     * Executes sources and filters, returning the list of matched fact-tuple arrays.
     * Called by {@link #run} and also by parent chains when this RuleDefinition is
     * used as a bi-linear source via {@link #addBilinearSource}.
     */
    List<Object[]> matchedTuples(DS ctx) {
        List<Object[]> combinations = new ArrayList<>();
        combinations.add(new Object[0]);

        for (TupleSource<DS> source : sources) {
            List<Object[]> next = new ArrayList<>();
            for (Object[] tuple : source.tuples(ctx)) {
                for (Object[] combo : combinations) {
                    Object[] extended = Arrays.copyOf(combo, combo.length + tuple.length);
                    System.arraycopy(tuple, 0, extended, combo.length, tuple.length);
                    next.add(extended);
                }
            }
            combinations = next;
        }

        return combinations.stream()
                .filter(facts -> filters.stream().allMatch(f -> f.test(ctx, facts)))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Test assertions API
    // -------------------------------------------------------------------------

    public String name() { return name; }

    /** Number of source entries (not fact columns — use factArity() for columns). */
    public int sourceCount() { return sources.size(); }

    public int filterCount() { return filters.size(); }

    public boolean hasAction() { return action != null; }

    public int executionCount() { return executions.size(); }

    public List<Object> capturedFacts(int execution) {
        return Collections.unmodifiableList(executions.get(execution));
    }

    public Object capturedFact(int execution, int position) {
        return executions.get(execution).get(position);
    }

    // -------------------------------------------------------------------------
    // Reflection wrappers — called once at rule-build time
    // -------------------------------------------------------------------------

    private static NaryPredicate wrapPredicate(Object typed, int registeredFactCount) {
        Method m = findMethod(typed, "test");
        int factArity = m.getParameterCount() - 1;
        return (ctx, facts) -> {
            try {
                Object[] trimmed;
                if (factArity == 1 && registeredFactCount > 1) {
                    // Single-fact filter: pick the fact at the registered position.
                    // registeredFactCount - 1 is the 0-based index of the latest fact
                    // at the time this filter was registered via addFilter().
                    trimmed = new Object[]{facts[registeredFactCount - 1]};
                } else if (facts.length > factArity) {
                    // Multi-fact filter registered before all joins were added:
                    // truncate to the facts that were in scope at registration time.
                    trimmed = Arrays.copyOf(facts, factArity);
                } else {
                    trimmed = facts;
                }
                Object[] args = buildArgs(ctx, trimmed);
                return (Boolean) m.invoke(typed, args);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Predicate invocation failed", e);
            }
        };
    }

    private static NaryConsumer wrapConsumer(Object typed) {
        Method m = findMethod(typed, "accept");
        return (ctx, facts) -> {
            try {
                Object[] args = buildArgs(ctx, facts);
                m.invoke(typed, args);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Consumer invocation failed", e);
            }
        };
    }

    private static Method findMethod(Object target, String name) {
        return Arrays.stream(target.getClass().getMethods())
                .filter(m -> m.getName().equals(name) && !m.isSynthetic())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No method '" + name + "' on " + target.getClass()));
    }

    private static Object[] buildArgs(Object ctx, Object[] facts) {
        Object[] args = new Object[facts.length + 1];
        args[0] = ctx;
        System.arraycopy(facts, 0, args, 1, facts.length);
        return args;
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples -am 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` (existing tests will fail at runtime because JoinBuilder constructors change in Task 6, but compilation should pass)

- [ ] **Step 3: Commit**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java
git commit -m "$(cat <<'EOF'
feat(drools-example): TupleSource abstraction and bi-linear execution in RuleDefinition

TupleSource<DS> unifies linear sources (singleton-array tuples) and bi-linear
sub-network sources (matched-tuple lists). accumulatedFacts tracks the total
fact column count — sources.size() was incorrect once bi-linear sources
(one entry, N columns) were added.

addBilinearSource(RuleDefinition) executes the sub-network independently via
matchedTuples(), then cross-products the results. The sub-network's filters
apply only within it, modeling the Rete bi-linear beta node pattern.

matchedTuples() extracted from run() so parent chains can use it when this
RuleDefinition is a bi-linear source. factArity() exposes the column count
for parent chain tracking.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Update RuleBuilder.from() for END = Void

**Files:**
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilder.java`

- [ ] **Step 1: Replace RuleBuilder.java**

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Entry point for the Drools RuleBuilder DSL approximation.
 *
 * <p>{@code from()} creates the initial {@code Join1First<Void, DS, A>}. The {@code Void}
 * END type means no outer scope exists — {@code end()} on top-level chains returns null
 * and is never called. When nested scopes ({@code not()}, {@code exists()}) arrive in
 * Phase 3, they will capture the outer builder type as END and restore it via {@code end()}.
 *
 * <pre>{@code
 * RuleBuilder<Ctx> builder = new RuleBuilder<>();
 * RuleDefinition<Ctx> rule = builder.from("adults", ctx -> ctx.persons())
 *         .filter((ctx, a) -> a.age() >= 18)
 *         .fn((ctx, a) -> System.out.println(a.name()));
 * rule.run(ctx);
 * }</pre>
 */
public class RuleBuilder<DS> {

    /**
     * Starts building a rule with its first fact source.
     * Returns {@code Join1First<Void, DS, A>} — Void indicates no outer scope.
     */
    public <A> JoinBuilder.Join1First<Void, DS, A> from(String name,
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addSource(firstSource);
        return new JoinBuilder.Join1First<>(null, rd);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleBuilder.java
git commit -m "$(cat <<'EOF'
feat(drools-example): RuleBuilder.from() returns Join1First<Void,DS,A>

END=Void for top-level rules (no outer scope). Constructor now takes
(null, rd) matching the new (END end, RuleDefinition<DS> rd) signature.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Rewrite JoinBuilder.java — Two Templates with END

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`

This is a complete rewrite. The file has two template classes:
1. `Join0Second` — declared FIRST (PermuteMojo ordering requirement), generates `Join1Second..Join6Second`
2. `Join0First extends Join0Second<END, DS, A>` — declared SECOND, generates `Join1First..Join6First`

- [ ] **Step 1: Replace JoinBuilder.java**

```java
package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteMethod;
import io.quarkiverse.permuplate.PermuteReturn;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Container for the JoinFirst and JoinSecond class families generated by Permuplate.
 *
 * <p>Two template classes are declared here:
 * <ol>
 *   <li>{@code Join0Second} — generates {@code Join1Second..Join6Second}: the "gateway"
 *       class family with {@code join()} methods (both single-source and bi-linear).</li>
 *   <li>{@code Join0First} — generates {@code Join1First..Join6First}: the "full" class
 *       family with {@code filter()} and {@code fn()}, extending the corresponding Second.</li>
 * </ol>
 *
 * <p><b>Declaration order matters:</b> {@code Join0Second} is declared before {@code Join0First}
 * so that PermuteMojo processes it first. This ensures boundary omission on
 * {@code join()} (which references {@code Join${i+1}First}) sees the JoinFirst generated
 * names in {@code scanAllGeneratedClassNames()}.
 *
 * <p><b>END phantom type:</b> All generated classes carry an {@code END} type parameter
 * inherited from {@link BaseRuleBuilder}. For top-level rules END={@code Void}; for future
 * nested scopes ({@code not()}, {@code exists()}) END is the outer builder type, enabling
 * typed return via {@code end()}. See {@link BaseRuleBuilder} for the full semantics.
 *
 * <p><b>Bi-linear joins:</b> {@code join(JoinNSecond)} overloads on {@code Join0Second}
 * enable joining with a pre-built fact sub-network. The right chain executes independently
 * and its matched tuples are cross-producted with the current chain's tuples. This models
 * the Rete bi-linear beta node for node-sharing between rules that share a common sub-network.
 */
public class JoinBuilder {

    /**
     * Template generating Join1Second through Join6Second.
     *
     * <p>Declared FIRST so PermuteMojo processes it before Join0First, ensuring
     * boundary omission on join() sees the complete generated-class set.
     *
     * <p>Contains two join() overloads:
     * <ul>
     *   <li>Single-source: {@code join(Function<DS, DataSource<B>>)} — typed via
     *       method-level {@code @PermuteTypeParam}; propagation renames B automatically.</li>
     *   <li>Bi-linear: {@code join(JoinNSecond<Void,DS,...>)} — generated by
     *       {@code @PermuteMethod(j)} for all valid right-chain arities. The pre-built
     *       chain always uses {@code Void} as END (no outer scope).</li>
     * </ul>
     */
    @Permute(varName = "i", from = 1, to = 6, className = "Join${i}Second",
             inline = true, keepTemplate = false)
    public static class Join0Second<END, DS,
            @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
            extends BaseRuleBuilder<END>
            implements JoinSecond<DS> {

        protected final RuleDefinition<DS> rd;

        public Join0Second(END end, RuleDefinition<DS> rd) {
            super(end);
            this.rd = rd;
        }

        @Override
        public RuleDefinition<DS> getRuleDefinition() {
            return rd;
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(Object o) {
            return (T) o;
        }

        /**
         * Advances the arity by one fact type from a fresh data source.
         *
         * <p>{@code @PermuteTypeParam} renames {@code <B>} to the next alpha letter per arity
         * (B at i=1, C at i=2, …). Propagation automatically renames B in
         * {@code DataSource<B>} alongside the declaration — no {@code @PermuteDeclr} needed.
         *
         * <p>Boundary omission removes this from Join6Second — Join7First is not in the
         * generated set, so {@code @PermuteReturn} silently omits it at i=6.
         *
         * <p>Uses reflection to instantiate the next JoinFirst class. The constructor now
         * takes {@code (END end, RuleDefinition<DS> rd)} — erased to {@code (Object, RuleDefinition)}
         * at runtime, found via {@code getConstructor(Object.class, RuleDefinition.class)}.
         */
        @PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
        @PermuteReturn(className = "Join${i+1}First",
                       typeArgs = "'END, DS, ' + typeArgList(1, i+1, 'alpha')")
        public <B> Object join(java.util.function.Function<DS, DataSource<B>> source) {
            rd.addSource(source);
            String cn = getClass().getSimpleName();
            int n = Integer.parseInt(cn.replaceAll("[^0-9]", ""));
            String nextName = getClass().getEnclosingClass().getName() + "$Join" + (n + 1) + "First";
            try {
                return cast(Class.forName(nextName)
                        .getConstructor(Object.class, RuleDefinition.class)
                        .newInstance(end(), rd));
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate " + nextName, e);
            }
        }

        /**
         * Bi-linear join: joins with a pre-built fact sub-network.
         *
         * <p>The right chain (a pre-built {@code JoinNSecond} or {@code JoinNFirst} since
         * First extends Second) executes independently against the same ctx. Its matched
         * tuples are cross-producted with the current chain's tuples. The right chain's
         * internal filters apply only within the sub-network — they gate which of its
         * tuples enter the cross-product, not which combined tuples pass overall.
         *
         * <p>The pre-built chain always uses {@code Void} as END (no outer scope). This
         * is why the parameter type is {@code JoinNSecond<Void, DS, ...>} — the chain was
         * built at the top level and has no nested context to return to.
         *
         * <p>{@code to} is omitted: inferred as {@code @Permute.to - i = 6 - i}.
         * For i=1: j=1..5. For i=5: j=1. For i=6: empty range → no overloads generated.
         * Total bi-linear overloads across all arities: 15 (complete matrix, vs Drools'
         * ~3 hand-written with some commented out).
         *
         * <p>{@code @PermuteTypeParam} inside {@code @PermuteMethod} (G4): expands the
         * sentinel {@code <C>} into j new alpha-named method type params (alpha(i+1)..alpha(i+j)).
         * {@code @PermuteDeclr} sets the parameter type to the correct {@code JoinNSecond} type.
         */
        @PermuteMethod(varName = "j", from = "1", name = "join")
        @PermuteReturn(className = "Join${i+j}First",
                       typeArgs = "'END, DS, ' + typeArgList(1, i+j, 'alpha')")
        public <@PermuteTypeParam(varName = "k", from = "${i+1}", to = "${i+j}",
                                   name = "${alpha(k)}") C> Object joinBilinear(
                @PermuteDeclr(type = "Join${j}Second<Void, DS, ${typeArgList(i+1, i+j, 'alpha')}>")
                Object secondChain) {
            JoinSecond<DS> second = (JoinSecond<DS>) secondChain;
            rd.addBilinearSource(second.getRuleDefinition());
            String myCn = getClass().getSimpleName();
            int myN = Integer.parseInt(myCn.replaceAll("[^0-9]", ""));
            String otherCn = secondChain.getClass().getSimpleName();
            int otherN = Integer.parseInt(otherCn.replaceAll("[^0-9]", ""));
            String nextName = getClass().getEnclosingClass().getName()
                    + "$Join" + (myN + otherN) + "First";
            try {
                return cast(Class.forName(nextName)
                        .getConstructor(Object.class, RuleDefinition.class)
                        .newInstance(end(), rd));
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate " + nextName, e);
            }
        }
    }

    /**
     * Template generating Join1First through Join6First.
     *
     * <p>Extends {@code Join0Second<END, DS, A>} — G3 extends clause auto-expansion
     * detects "Join" prefix + embedded number 0 + alpha prefix match, expanding to
     * {@code Join1Second<END, DS, A>}, {@code Join2Second<END, DS, A, B>}, etc.
     *
     * <p>Holds {@code filter()} (single-fact and all-facts overloads) and the terminal
     * {@code fn()}. All inherited from the template; inherited {@code join()} overloads
     * come from Second via extends.
     */
    @Permute(varName = "i", from = 1, to = 6, className = "Join${i}First",
             inline = true, keepTemplate = false)
    public static class Join0First<END, DS,
            @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
            extends Join0Second<END, DS, A> {

        public Join0First(END end, RuleDefinition<DS> rd) {
            super(end, rd);
        }

        /**
         * All-facts filter — applies a predicate to all accumulated facts.
         * {@code when="true"} prevents boundary omission — JoinNFirst is always generated.
         */
        @PermuteReturn(className = "Join${i}First",
                       typeArgs = "'END, DS, ' + typeArgList(1, i, 'alpha')",
                       when = "true")
        public Object filter(
                @PermuteDeclr(type = "Predicate${i+1}<DS, ${typeArgList(1, i, 'alpha')}>")
                Object predicate) {
            rd.addFilter(predicate);
            return this;
        }

        /**
         * Single-fact filter — applies a predicate to the most recently joined fact only.
         * Suppressed at i=1 via {@code @PermuteMethod} ternary: at arity 1 both overloads
         * would have {@code filter(Predicate2<DS, A>)} — a compile error. The JEXL ternary
         * {@code from="${i > 1 ? i : i+1}"} produces {@code from=2, to=1} at i=1 (empty
         * range), silently omitting this method. At i≥2: from=to=i, one clone per arity.
         */
        @PermuteMethod(varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}", name = "filter")
        @PermuteReturn(className = "Join${i}First",
                       typeArgs = "'END, DS, ' + typeArgList(1, i, 'alpha')",
                       when = "true")
        public Object filterLatest(
                @PermuteDeclr(type = "Predicate2<DS, ${alpha(i)}>")
                Object predicate) {
            rd.addFilter(predicate);
            return this;
        }

        /**
         * Terminal operation. {@code when="true"} prevents boundary omission —
         * RuleDefinition is not in the generated set.
         */
        @PermuteReturn(className = "RuleDefinition", typeArgs = "'DS'", when = "true")
        public Object fn(
                @PermuteDeclr(type = "Consumer${i+1}<DS, ${typeArgList(1, i, 'alpha')}>")
                Object action) {
            rd.setAction(action);
            return rd;
        }
    }
}
```

- [ ] **Step 2: Run the full build**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`. If `RuleBuilderTest` compile-fails due to the filter/fn tests (lambda inference issues), see Step 3. If it fails for a different reason, read the error carefully — likely the reflective constructor lookup.

- [ ] **Step 3: If existing tests fail, update RuleBuilderTest constructor references**

The existing tests use `var` throughout so they require no type annotation changes. The only potential issue is lambda inference in the filter tests when the generated types change. If you see compile errors in `RuleBuilderTest`, check that the `from()` call still type-infers correctly with `var`.

If the build fails with a reflective `NoSuchMethodException`, the generated constructor signature doesn't match `getConstructor(Object.class, RuleDefinition.class)`. Verify that `Join0Second` and `Join0First` constructors are `(END end, RuleDefinition<DS> rd)` in the generated output at `target/generated-sources/permuplate/`.

- [ ] **Step 4: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "$(cat <<'EOF'
feat(drools-example): rewrite JoinBuilder with First/Second split and END phantom type

Join0Second (declared first) generates Join1Second..Join6Second:
- join(Function<DS,DataSource<B>>) — typed single-source join with @PermuteTypeParam
- join(JoinNSecond<Void,DS,...>) — bi-linear join overloads via @PermuteMethod(j)
  Complete 15-overload matrix vs Drools' partial 3 hand-written.

Join0First (declared second) extends Join0Second<END,DS,A>:
- G3 alpha-branch auto-expands extends clause with END+DS prefix
- filter() all-facts and filterLatest() single-fact (ternary suppression at i=1)
- fn() terminal returning RuleDefinition<DS>

All classes gain END phantom type. Constructors take (END end, RuleDefinition<DS> rd).
Reflective instantiation uses getConstructor(Object.class, RuleDefinition.class).

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Add Bi-Linear Join Tests

**Files:**
- Modify: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java`

- [ ] **Step 1: Add 4 bi-linear join tests before the closing `}` of RuleBuilderTest**

```java
// =========================================================================
// Bi-linear joins — pre-built sub-networks joined into another chain
// =========================================================================

@Test
public void testJoin2FirstSatisfiesJoin2SecondAtCompileTime() {
    // Structural: Join2First<Void,Ctx,Person,Account> IS-A Join2Second<Void,Ctx,Person,Account>.
    // This test only compiles if the extends relation is correct.
    // If Join2First does not extend Join2Second, this assignment fails at compile time.
    JoinBuilder.Join2Second<Void, Ctx, Person, Account> asSecond =
            builder.from("persons", ctx -> ctx.persons())
                   .join(ctx -> ctx.accounts());
    assertThat(asSecond).isNotNull();
}

@Test
public void testBilinearJoin1Plus2Gives3Facts() {
    // Pre-build a 2-fact sub-network: only adult persons with high-balance accounts.
    var personAccounts = builder.from("pa", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0);

    // Bi-linear join: orders (1 fact) × personAccounts (2 facts) → 3-fact rule.
    // personAccounts' internal filter gates its own tuples; only Alice+ACC1 qualifies.
    var rule = builder.from("orders", ctx -> ctx.orders())
            .join(personAccounts)
            .fn((ctx, a, b, c) -> {});

    assertThat(rule.sourceCount()).isEqualTo(2); // 1 linear + 1 bi-linear = 2 entries
    rule.run(ctx);

    // 2 orders × 1 qualifying (Alice+ACC1) = 2 matches
    assertThat(rule.executionCount()).isEqualTo(2);
    assertThat(rule.capturedFact(0, 0)).isInstanceOf(Order.class);
    assertThat(rule.capturedFact(0, 1)).isEqualTo(new Person("Alice", 30));
    assertThat(rule.capturedFact(0, 2)).isEqualTo(new Account("ACC1", 1000.0));
}

@Test
public void testBilinearSubnetworkFiltersApplyIndependently() {
    // The right chain's filter (balance > 500) applies within the sub-network only.
    // It gates which Account tuples enter the cross-product — it does NOT re-run
    // against the combined (Person, Account) tuple. Result: both persons are joined
    // with only ACC1, giving 2 matches (not 1).
    var highBalanceAccounts = builder.from("acc", ctx -> ctx.accounts())
            .filter((ctx, a) -> a.balance() > 500.0);  // only ACC1 passes

    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(highBalanceAccounts)
            .fn((ctx, a, b) -> {});

    rule.run(ctx);

    // 2 persons × 1 qualifying account = 2 combinations
    assertThat(rule.executionCount()).isEqualTo(2);
    assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    assertThat(rule.capturedFact(1, 1)).isEqualTo(new Account("ACC1", 1000.0));
}

@Test
public void testBilinearNodeSharingTwoRulesReuseSameSubnetwork() {
    // Two rules share the same pre-built personAccounts sub-network.
    // This is the core Rete node-sharing pattern: in a real Rete network,
    // both rules would share the same beta memory for the personAccounts sub-network.
    var personAccounts = builder.from("pa", ctx -> ctx.persons())
            .join(ctx -> ctx.accounts())
            .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0);

    var rule1 = builder.from("orders", ctx -> ctx.orders())
            .join(personAccounts)
            .fn((ctx, a, b, c) -> {});

    var rule2 = builder.from("products", ctx -> ctx.products())
            .join(personAccounts)
            .fn((ctx, a, b, c) -> {});

    rule1.run(ctx);
    rule2.run(ctx);

    // Both rules: N facts × 1 qualifying personAccounts tuple (Alice+ACC1)
    assertThat(rule1.executionCount()).isEqualTo(2); // 2 orders × 1 pair
    assertThat(rule2.executionCount()).isEqualTo(2); // 2 products × 1 pair

    // Both rules see Person and Account from the shared sub-network
    assertThat(rule1.capturedFact(0, 1)).isEqualTo(new Person("Alice", 30));
    assertThat(rule2.capturedFact(0, 1)).isEqualTo(new Person("Alice", 30));
}
```

- [ ] **Step 2: Run all tests**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | tail -20
```

Expected: `BUILD SUCCESS` with all 24 tests passing (20 existing + 4 new).

- [ ] **Step 3: Commit**

```bash
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java
git commit -m "$(cat <<'EOF'
test(drools-example): add 4 bi-linear join tests

testJoin2FirstSatisfiesJoin2SecondAtCompileTime: structural compile-time proof
  that First extends Second (fails to compile if the extends relation is wrong).

testBilinearJoin1Plus2Gives3Facts: 1-fact chain joined with pre-built 2-fact
  chain produces 3-fact combined tuples. Verifies sub-network filter isolates.

testBilinearSubnetworkFiltersApplyIndependently: right chain's filter gates its
  own tuples only, not the combined tuple. Both persons join with ACC1 (2 matches).

testBilinearNodeSharingTwoRulesReuseSameSubnetwork: two rules sharing the same
  pre-built sub-network both execute correctly — the core Rete node-sharing use case.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Update DROOLS-DSL.md

**Files:**
- Modify: `permuplate-mvn-examples/DROOLS-DSL.md`

- [ ] **Step 1: Update the Known Limitations table**

Remove the outdated rows (these are now fixed):
- `| join() return type is raw (no type args) | ...`
- The entire `### Arity-2+ Type Safety Limitation` subsection

- [ ] **Step 2: Update Phase 2 section**

Replace the "### Why Not Phase 1?" subsection with:

```markdown
### Phase 2 Implementation

Phase 2 is implemented. The First/Second split, END phantom type, and bi-linear join
overloads are all live. The remaining blocker that was noted in the handoff (G3 alpha
expansion) was resolved in Phase 11 (G3 alpha fix, 2026-04-04). The PermuteMojo
multi-template chaining fix was implemented as part of Phase 2.
```

- [ ] **Step 3: Add new "Phase 2 Architecture" section**

Add after the updated Phase 2 section:

```markdown
## Phase 2 Architecture: First/Second Split with END

### The END Phantom Type

Every generated `JoinNFirst` and `JoinNSecond` carries `END` — the type of the outer
builder context that `end()` returns to.

- **Top-level rules** (created via `from()`): `END = Void`, `end()` returns null, never called.
- **Nested scopes** (Phase 3 `not()`, `exists()`): `END = outer builder type`, `end()`
  returns the outer builder at its original arity.

**Arity trace through a nested scope:**
```
.join(persons)         → Join2First<Void,DS,Params3,Person>         arity: 2
.not()                 → Not2<Join2Second<Void,...>, DS, Params3, Person>
    .join(misc)        → Join3First<Join2Second<Void,...>, ...>      arity: 3 (inside)
    .join(libs)        → Join4First<Join2Second<Void,...>, ...>      arity: 4 (inside)
.end()                 → Join2Second<Void,DS,Params3,Person>         arity: 2 (reset!)
.fn((a,b,c) -> ...)    Consumer3<Context<DS>,Params3,Person>
```
The not-scope facts (misc, libs) are NOT added to the outer chain's arity — they only
constrain which outer tuples are valid. This matches the Rete NegativeExistsNode pattern.
END was added in Phase 2 (not Phase 3) to avoid a breaking API change later.

### Bi-Linear Joins

`JoinNSecond.join(JoinMSecond<Void,DS,...>)` joins two independent fact chains into a
combined rule. The right chain executes independently; its matched tuples are
cross-producted with the current chain's tuples. The right chain's internal filters
apply only within the sub-network.

```java
// Pre-build a sub-network: adult persons with high-balance accounts
var personAccounts = builder.from("pa", ctx -> ctx.persons())
        .join(ctx -> ctx.accounts())
        .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0);

// Two rules sharing the same sub-network (Rete node-sharing pattern)
var rule1 = builder.from("orders", ctx -> ctx.orders())
        .join(personAccounts)       // Join2Second accepted via JoinNFirst extends JoinNSecond
        .fn((ctx, a, b, c) -> {});  // a: Order, b: Person, c: Account

var rule2 = builder.from("products", ctx -> ctx.products())
        .join(personAccounts)
        .fn((ctx, a, b, c) -> {});  // a: Product, b: Person, c: Account
```

**Complete overload matrix (15 total):** Permuplate generates all `i+j ≤ 6`
combinations systematically via `@PermuteMethod(j)`. Real Drools has ~3 hand-written
overloads with one commented out and gaps at higher arities.

### Comparison with Real Drools (updated)

| Feature | Real Drools | This Example |
|---|---|---|
| END phantom type | ✅ full support | ✅ added in Phase 2 |
| First extends Second | ✅ | ✅ G3 auto-expands |
| Typed `join(Function)` | ✅ | ✅ Phase 1.5 |
| Single-fact `filter()` | ✅ | ✅ Phase 1.5 |
| Bi-linear `join(JoinNSecond)` | ✅ partial (3 overloads, 1 commented out) | ✅ complete (15 overloads) |
| Boundary omission | ❌ manual | ✅ automatic |
| `not()` / `exists()` scopes | ✅ | Phase 3 (infrastructure ready) |
```

- [ ] **Step 4: Commit docs**

```bash
git add permuplate-mvn-examples/DROOLS-DSL.md
git commit -m "$(cat <<'EOF'
docs(drools-example): document Phase 2 — END type, bi-linear joins, updated comparison

Remove outdated raw-type limitation rows (fixed in Phase 1.5). Update Phase 2
status (now implemented). Add Phase 2 Architecture section: END phantom type
with full arity trace for nested scopes, bi-linear join with node-sharing
example, complete 15-overload matrix comparison vs Drools' partial coverage.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- ✅ PermuteMojo fix — Task 1
- ✅ BaseRuleBuilder<END> — Task 2
- ✅ JoinSecond<DS> interface — Task 2
- ✅ TupleSource abstraction — Task 3
- ✅ accumulatedFacts tracking — Task 3
- ✅ addBilinearSource() — Task 3
- ✅ matchedTuples() — Task 3
- ✅ factArity() — Task 3
- ✅ RuleBuilder.from() with Void — Task 4
- ✅ Join0Second template with END — Task 5
- ✅ Join0First extends Join0Second — Task 5
- ✅ Bi-linear join overloads (@PermuteMethod j-loop + G4) — Task 5
- ✅ Reflective constructor update — Task 5
- ✅ 4 bi-linear join tests — Task 6
- ✅ DROOLS-DSL.md update — Task 7

**Type consistency:** `RuleDefinition<DS>` used consistently. `BaseRuleBuilder<END>` in Task 2 matches `extends BaseRuleBuilder<END>` in Task 5. `JoinSecond<DS>` interface in Task 2 matches `implements JoinSecond<DS>` in Task 5 and cast target in `joinBilinear()`. `accumulatedFacts` field defined in Task 3 matches `factArity()` return in Task 3 and `addBilinearSource()` usage in Task 3. `wrapPredicate(typed, registeredFactCount)` parameter name consistent across Task 3.
