# Drools DSL Compaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compact the Drools DSL sandbox using improved Permuplate annotations. Collapses five path methods into one, removes four reflection blocks, cleans up `BaseTuple`, and deduplicates `extendsRule()`.

**Architecture:** DSL-only changes — all modifications are in `permuplate-mvn-examples/`. Depends on the engine enhancements plan (`2026-04-20-engine-enhancements.md`) being completed first. Items B1 and B3 require C1. Item B2 requires C2. Items A1 and A2 are independent.

**Tech Stack:** Java 17, Permuplate inline generation, Maven.

**Before starting:** Verify the engine plan is merged:
```bash
git log --oneline main | grep "engine\|PermuteBody\|capitalize" | head -5
```

---

## File Structure

| Action | Path | What changes |
|---|---|---|
| Modify | `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java` | B1: collapse path2..6; B3: collapse not/exists; B2: remove joinBilinear+extensionPoint reflection |
| Modify | `permuplate-mvn-examples/src/main/permuplate/.../drools/RuleBuilder.java` | B2: remove extendsRule reflection; A2: remove extendsRule method |
| Modify | `permuplate-mvn-examples/src/main/permuplate/.../drools/ParametersFirst.java` | B2: remove extendsRule reflection; A2: remove extendsRule method |
| Modify | `permuplate-mvn-examples/src/main/permuplate/.../drools/BaseTuple.java` | A1: replace get/set with @PermuteBody |
| Create | `permuplate-mvn-examples/src/main/java/.../drools/AbstractRuleEntry.java` | A2: shared extendsRule base |
| Test (manual) | `permuplate-mvn-examples/src/test/java/.../drools/` | Run existing test suite after each change |

The DSL is tested via a Maven build: the Maven plugin generates source, javac compiles it, and the test suite in `permuplate-mvn-examples` exercises the DSL. After each task:
```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

---

### Task 1 (B1): Collapse `path2()`..`path6()` into one `@PermuteMethod` template

**Problem:** Five identical methods in `JoinBuilder.Join0Second` (~50 lines) differ only in path depth `n`. With C1 complete, `@PermuteBody` can use `n` from the `@PermuteMethod` context.

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`

- [ ] **Step 1: Read the current path methods**

```bash
grep -n "public.*path[0-9]\|@PermuteTypeParam.*i+[0-9]\|@PermuteReturn.*Path[0-9]" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    | head -30
```

Note the exact line range of `path2()` through `path6()` (5 consecutive methods).

- [ ] **Step 2: Replace the five path methods with one template method**

Delete the five `path2()`..`path6()` methods from `JoinBuilder.java` and replace them with:

```java
        /**
         * Starts an OOPath traversal chain of depth {@code n} (root + n-1 steps → TupleN).
         * Traverse by chaining {@code .path(fn, pred)} calls — one per step beyond the root.
         * Suppressed on Join6Second (when="i < 6") — no Join7First exists.
         *
         * <p>The generated method names are {@code path2()} through {@code path6()},
         * matching the pre-existing hand-written API in vol2.
         */
        @PermuteMethod(varName = "n", from = "2", to = "6", name = "path${n}")
        @PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+n-1}", name = "${alpha(m)}")
        @PermuteReturn(
                className = "RuleOOPathBuilder.Path${n}",
                typeArgs = "'Join'+(i+1)+'First<END, DS, '+typeArgList(1,i,'alpha')+',"
                        + " BaseTuple.Tuple'+n+'<'+typeArgList(i,i+n-1,'alpha')+'>>,"
                        + " BaseTuple.Tuple'+(n-1)+'<'+typeArgList(i,i+n-2,'alpha')+'>, '"
                        + "+typeArgList(i,i+n-1,'alpha')",
                when = "i < 6")
        @PermuteBody(body = "{ java.util.List<OOPathStep> steps = new java.util.ArrayList<>();"
                + " Object nextJoin = new @PermuteDeclr(type = \"Join${i+1}First\") Join1First<>(end(), rd);"
                + " return cast(new RuleOOPathBuilder.Path${n}<>(nextJoin, rd, steps, rd.factArity() - 1)); }")
        @SuppressWarnings("unchecked")
        public <B> Object pathTemplate() {
            return null; // replaced by @PermuteBody
        }
```

- [ ] **Step 3: Add `@PermuteBody` import to JoinBuilder.java**

At the top of `JoinBuilder.java`, add if not present:
```java
import io.quarkiverse.permuplate.PermuteBody;
```

- [ ] **Step 4: Build and verify**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS. If it fails, read the error and adjust the `typeArgs` JEXL string (the concatenation form with `"..."+"..."` may need parentheses or escaping adjustments).

- [ ] **Step 5: Verify the generated source has path2..path6**

```bash
find /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/target -name "Join1Second.java" | \
    xargs grep -l "path2\|path3" | head -3
```

Open a generated `Join3Second.java` and confirm `path2()`, `path3()`, `path4()` exist and `path6()` is absent (suppressed by `when="i < 6"` — wait, Join3Second has i=3, so `when="i < 6"` means they ARE generated; only Join6Second has them suppressed).

Actually: on Join6Second (i=6), `when="i < 6"` evaluates to false, so path methods are omitted. Correct.

- [ ] **Step 6: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "feat: collapse path2..path6 into single @PermuteMethod template in JoinBuilder"
```

---

### Task 2 (B3): Collapse `not()` and `exists()` into one template method

**Note:** This task uses JEXL ternary expressions in `@PermuteMethod(name=...)` and `@PermuteBody`. If the result is unreadable or doesn't work, skip and document why.

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java`

- [ ] **Step 1: Read the current not() and exists() methods**

```bash
grep -n "public Object not\|public Object exists\|NegationScope\|ExistenceScope\|addNegation\|addExistence" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    | head -20
```

Note the exact method bodies.

- [ ] **Step 2: Replace not() and exists() with a single template method**

Delete both methods and replace with:

```java
        /**
         * Starts a negation scope (k=1, "not") or existence scope (k=2, "exists").
         * Generated as two overloads: not() and exists().
         */
        @PermuteMethod(varName = "k", from = "1", to = "2",
                       name = "${k == 1 ? 'not' : 'exists'}")
        @PermuteReturn(className = "${k == 1 ? 'NegationScope' : 'ExistenceScope'}",
                       typeArgs = "'Join'+i+'Second<END, DS, '+typeArgList(1,i,'alpha')+'>, DS'",
                       alwaysEmit = true)
        @PermuteBody(body = "{ RuleDefinition<DS> scopeRd = new RuleDefinition<>(\"${k==1?'not':'exists'}-scope\");"
                + " rd.${k==1?'addNegation':'addExistence'}(scopeRd);"
                + " return new ${k==1?'Negation':'Existence'}Scope<>(this, scopeRd); }")
        public Object scopeTemplate() {
            return null; // replaced by @PermuteBody
        }
```

- [ ] **Step 3: Build and verify**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -10
```

**If BUILD SUCCESS:** The collapse worked. Commit and continue.

**If FAIL:** The JEXL ternary approach is too fragile. Restore the original two methods:
```bash
git checkout permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
```
Document the failure reason. Skip this task and move to Task 3.

- [ ] **Step 4 (if success): Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "feat: collapse not() and exists() into single @PermuteMethod template in JoinBuilder"
```

- [ ] **Step 4 (if failure): Document and skip**

```bash
git checkout permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
# Add a comment to JoinBuilder.java above not() and exists():
# NOTE: not() and exists() are structurally identical but cannot be collapsed:
# @PermuteMethod ternary in name= and @PermuteBody ternary for method dispatch
# produce unreadable templates. Left as two explicit methods intentionally.
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "docs: explain why not() and exists() are kept as two explicit methods"
```

---

### Task 3 (B2): Eliminate reflection blocks with `@PermuteDeclr TYPE_USE`

**Requires:** C2 from the engine plan confirmed working (qualified TYPE_USE test passes).

**Problem:** Four reflection blocks in the DSL instantiate generated classes by computing their names at runtime. With qualified TYPE_USE working, `@PermuteDeclr` on the constructor call can replace this.

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java`
- Modify: `permuplate-mvn-examples/src/main/permuplate/.../drools/RuleBuilder.java`
- Modify: `permuplate-mvn-examples/src/main/permuplate/.../drools/ParametersFirst.java`

**The four reflection blocks and their replacements:**

**Block 1 — `joinBilinear()` in Join0Second (JoinBuilder.java):**

Find the reflection block (8 lines starting with `String myCn = ...`). Replace with:
```java
            rd.addBilinearSource(second.getRuleDefinition());
            return cast(new @PermuteDeclr(type = "JoinBuilder.Join${i+j}First") JoinBuilder.Join1First<>(end(), rd));
```

**Block 2 — `extensionPoint()` in Join0Second (JoinBuilder.java):**

Find the reflection block (8 lines starting with `String cn = getClass().getSimpleName()`). Replace with:
```java
            return cast(new @PermuteDeclr(type = "RuleExtendsPoint.RuleExtendsPoint${i+1}") RuleExtendsPoint.RuleExtendsPoint2<>(rd));
```

**Block 3 — `extendsRule()` in RuleBuilder.java:**

Find the reflection block (~8 lines starting with `String cn = ep.getClass().getSimpleName()`). Replace with:
```java
            return cast(new @PermuteDeclr(type = "JoinBuilder.Join${j-1}First") JoinBuilder.Join1First<>(null, child));
```

**Block 4 — `extendsRule()` in ParametersFirst.java:**

Same replacement as Block 3.

- [ ] **Step 1: Verify C2 test passes (engine plan must be done)**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteDeclrTest#testPermuteDeclrTypeUseOnQualifiedConstructor -q 2>&1 | tail -5
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`. If this fails, the engine fix in C2 needs to be applied first.

- [ ] **Step 2: Replace Block 1 (joinBilinear reflection) in JoinBuilder.java**

Read the method first:
```bash
grep -n "joinBilinear\|addBilinearSource\|myCn\|otherN\|nextName\|Class.forName" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    | head -15
```

Replace the reflection block with:
```java
            rd.addBilinearSource(second.getRuleDefinition());
            return cast(new @PermuteDeclr(type = "JoinBuilder.Join${i+j}First") JoinBuilder.Join1First<>(end(), rd));
```

Remove the `@SuppressWarnings("unchecked")` if it was only needed for the cast on reflection.

- [ ] **Step 3: Replace Block 2 (extensionPoint reflection) in JoinBuilder.java**

```bash
grep -n "extensionPoint\|cn = getClass\|RuleExtendsPoint.class.getName\|Class.forName" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    | head -10
```

Replace the reflection block with:
```java
            return cast(new @PermuteDeclr(type = "RuleExtendsPoint.RuleExtendsPoint${i+1}") RuleExtendsPoint.RuleExtendsPoint2<>(rd));
```

- [ ] **Step 4: Build to verify JoinBuilder changes**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

If failure: one of the TYPE_USE replacements is wrong. Read the error and adjust. The most common issue is that the @PermuteDeclr annotation must be on the correct type node. Try the simpler unqualified form first if the qualified form fails.

- [ ] **Step 5: Replace Block 3 (extendsRule reflection) in RuleBuilder.java**

```bash
grep -n "cn = ep.getClass\|joinName\|Class.forName" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleBuilder.java \
    | head -10
```

Replace the reflection block in `extendsRule()` with:
```java
            return cast(new @PermuteDeclr(type = "JoinBuilder.Join${j-1}First") JoinBuilder.Join1First<>(null, child));
```

- [ ] **Step 6: Replace Block 4 (extendsRule reflection) in ParametersFirst.java**

Same replacement as Step 5:
```java
            return cast(new @PermuteDeclr(type = "JoinBuilder.Join${j-1}First") JoinBuilder.Join1First<>(null, child));
```

- [ ] **Step 7: Full build**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS. All existing DSL tests pass.

- [ ] **Step 8: Commit**

```bash
git add \
    permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleBuilder.java \
    permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/ParametersFirst.java
git commit -m "feat: replace reflection blocks with @PermuteDeclr TYPE_USE in Drools DSL"
```

---

### Task 4 (A1): `BaseTuple.get()` and `set()` use `@PermuteBody`

**Problem:** The `get()` and `set()` override methods in `BaseTuple.Tuple1` use `@PermuteConst` to introduce a local variable for the index comparison. `@PermuteBody` replaces the entire body more directly.

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/.../drools/BaseTuple.java`

- [ ] **Step 1: Read the current get() and set() methods**

```bash
grep -n "PermuteConst\|public.*get.*index\|public.*set.*index\|int idx" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/BaseTuple.java \
    | head -20
```

- [ ] **Step 2: Replace get() with @PermuteBody**

Find the current `get()` override (6 lines including `@PermuteConst`). Replace with:

```java
        @PermuteBody(body = "{ if (index == ${i-1}) return unchecked(${lower(i)}); return super.get(index); }")
        @Override
        public <T> T get(int index) {
            return super.get(index); // replaced by @PermuteBody
        }
```

- [ ] **Step 3: Replace set() with @PermuteBody**

Find the current `set()` override. Replace with:

```java
        @PermuteBody(body = "{ if (index == ${i-1}) { ${lower(i)} = unchecked(t); return; } super.set(index, t); }")
        @Override
        public <T> void set(int index, T t) {
            super.set(index, t); // replaced by @PermuteBody
        }
```

- [ ] **Step 4: Remove the `@PermuteConst` import from BaseTuple.java**

Delete:
```java
import io.quarkiverse.permuplate.PermuteConst;
```

Add if not present:
```java
import io.quarkiverse.permuplate.PermuteBody;
```

- [ ] **Step 5: Build and verify**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS. OOPath tests verify the tuple `get()`/`set()` behavior.

- [ ] **Step 6: Verify generated Tuple2 has correct get() body**

```bash
find /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/target -name "BaseTuple.java" | \
    xargs grep -A5 "public.*T.*get.*index" | head -20
```

Expect something like: `if (index == 1) return unchecked(b);` in Tuple2's `get()`.

- [ ] **Step 7: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/BaseTuple.java
git commit -m "feat: replace @PermuteConst local variable with @PermuteBody in BaseTuple get/set"
```

---

### Task 5 (A2): `extendsRule()` shared abstract base

**Problem:** `extendsRule()` is copy-pasted between `RuleBuilderTemplate` and `ParametersFirstTemplate`. They differ by one line: `new RuleDefinition<>("extends")` vs `new RuleDefinition<>(name)`.

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/AbstractRuleEntry.java`
- Modify: `permuplate-mvn-examples/src/main/permuplate/.../drools/RuleBuilder.java`
- Modify: `permuplate-mvn-examples/src/main/permuplate/.../drools/ParametersFirst.java`

- [ ] **Step 1: Create `AbstractRuleEntry.java`**

```java
package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.PermuteMethod;
import io.quarkiverse.permuplate.PermuteReturn;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Common base for {@code RuleBuilder} and {@code ParametersFirst}.
 * Holds the shared {@code extendsRule()} template method, which differs between
 * subclasses only in how the child {@link RuleDefinition} is named.
 *
 * <p>Subclasses implement {@link #ruleName()} to provide the rule name string
 * passed to {@code new RuleDefinition<>(ruleName())}.
 */
public abstract class AbstractRuleEntry<DS> {

    @SuppressWarnings("unchecked")
    protected static <T> T cast(Object o) {
        return (T) o;
    }

    /** Returns the name to use when creating a child {@link RuleDefinition}. */
    protected abstract String ruleName();

    /**
     * Extends a previously captured rule's fact sources and filters into a new child rule.
     * Six overloads generated by {@code @PermuteMethod} for fact arities 1..6.
     */
    @PermuteMethod(varName = "j", from = "2", to = "7", name = "extendsRule")
    @PermuteReturn(className = "JoinBuilder.Join${j-1}First",
                   typeArgs = "'Void, DS, ' + typeArgList(1, j-1, 'alpha')",
                   alwaysEmit = true)
    public <@PermuteTypeParam(varName = "k", from = "1", to = "${j-1}", name = "${alpha(k)}") A>
            Object extendsRule(
            @PermuteDeclr(type = "RuleExtendsPoint.RuleExtendsPoint${j}<DS, ${typeArgList(1, j-1, 'alpha')}>")
            ExtendsPoint<DS> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(ruleName());
        ep.baseRd().copyInto(child);
        return cast(new @PermuteDeclr(type = "JoinBuilder.Join${j-1}First") JoinBuilder.Join1First<>(null, child));
    }
}
```

Note: This class is in `src/main/java/` (not `src/main/permuplate/`) since it is not a template itself — the `@PermuteMethod` expansion happens in the subclass template's generated output, not here. Wait — actually the `@PermuteMethod` annotation is ON this base class method. For inline generation, the template class extends this, and the template processor would need to see the base class method...

Actually this won't work for inline generation: the Maven plugin processes the template class's own members, not inherited methods. The `extendsRule()` template must be IN the template class body to be processed.

**Alternative approach:** Use an interface with a default method... No, default methods can't have annotations that change their behavior.

**Revised approach:** Keep `extendsRule()` in both templates but extract only the BODY logic into a shared method in `AbstractRuleEntry`. The template method calls the shared logic:

```java
// In AbstractRuleEntry.java:
protected Object doExtendsRule(ExtendsPoint<DS> ep, String joinClassName) {
    RuleDefinition<DS> child = new RuleDefinition<>(ruleName());
    ep.baseRd().copyInto(child);
    try {
        return cast(Class.forName(joinClassName)
                .getConstructor(Object.class, RuleDefinition.class)
                .newInstance(null, child));
    } catch (Exception e) {
        throw new RuntimeException("extendsRule: " + e.getMessage(), e);
    }
}
```

Then in RuleBuilder.java and ParametersFirst.java, the template `extendsRule()` body becomes:
```java
    return doExtendsRule(ep, getClass().getPackage().getName() + ".JoinBuilder$Join${j-1}First");
```

But `${j-1}` still needs to be a JEXL expression — which it is, since the body is a template processed by the Permuplate engine. Wait: with B2 done, the body is just:
```java
    return cast(new @PermuteDeclr(type="JoinBuilder.Join${j-1}First") JoinBuilder.Join1First<>(null, child));
```

The only difference between the two `extendsRule()` methods is now just `new RuleDefinition<>("extends")` vs `new RuleDefinition<>(name)`. These two lines can't easily be shared without inheritance. **Accept the duplication** or use the abstract base for the `ruleName()` pattern.

If using abstract base with `ruleName()`:
- Both template classes become non-template classes in `src/main/java/` that extend `AbstractRuleEntry`... but then they can't use `@PermuteMethod` (that requires inline generation).

**Best practical solution:** Leave `extendsRule()` in both templates (it's only ~10 lines after B2 removes the reflection), and add a comment noting it's intentionally duplicated. The `ruleName()` difference is too small to justify the complexity.

Update `AbstractRuleEntry.java` to a simpler shared helper:

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Shared utilities for rule entry point classes (RuleBuilder and ParametersFirst).
 * The extendsRule() template method cannot be fully deduplicated because the two
 * entry points differ in their RuleDefinition naming strategy, and @PermuteMethod
 * on a non-template base class is not processed by the inline generation pipeline.
 */
abstract class AbstractRuleEntry<DS> {

    @SuppressWarnings("unchecked")
    protected static <T> T cast(Object o) {
        return (T) o;
    }

    protected abstract String ruleName();
}
```

And update `RuleBuilderTemplate` and `ParametersFirstTemplate` to extend it:
- `RuleBuilderTemplate<DS> extends AbstractRuleEntry<DS>` → implements `ruleName()` returning `"extends"`
- `ParametersFirstTemplate<DS> extends AbstractRuleEntry<DS>` → implements `ruleName()` returning `name`

The `cast()` helper is then removed from both template files (inherited from the base).

- [ ] **Step 2: Create `AbstractRuleEntry.java` (simplified version)**

Create `/Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/AbstractRuleEntry.java`:

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Common base for {@code RuleBuilder} and {@code ParametersFirst}.
 * Provides the {@link #cast(Object)} helper and the {@link #ruleName()} contract.
 *
 * <p>The {@code extendsRule()} template method cannot be deduplicated here because
 * {@code @PermuteMethod} on a non-template base class is not processed by the
 * Permuplate inline generation pipeline. It remains in each template file as the
 * only necessary duplication.
 */
abstract class AbstractRuleEntry<DS> {

    @SuppressWarnings("unchecked")
    protected static <T> T cast(Object o) {
        return (T) o;
    }

    /** Returns the name to use when creating a child {@link RuleDefinition}. */
    protected abstract String ruleName();
}
```

- [ ] **Step 3: Update `RuleBuilderTemplate` to extend `AbstractRuleEntry`**

In `RuleBuilder.java`:
1. Change `public class RuleBuilderTemplate<DS>` to `public class RuleBuilderTemplate<DS> extends AbstractRuleEntry<DS>`
2. Remove the private `cast()` helper (inherited)
3. Add `@Override protected String ruleName() { return "extends"; }`
4. Update `extendsRule()` body to use `new RuleDefinition<>(ruleName())` instead of `new RuleDefinition<>("extends")`

- [ ] **Step 4: Update `ParametersFirstTemplate` to extend `AbstractRuleEntry`**

In `ParametersFirst.java`:
1. Change `public class ParametersFirstTemplate<DS>` to `public class ParametersFirstTemplate<DS> extends AbstractRuleEntry<DS>`
2. Remove the private `cast()` helper (inherited)
3. Add `@Override protected String ruleName() { return name; }`
4. Update `extendsRule()` body to use `new RuleDefinition<>(ruleName())`

- [ ] **Step 5: Build and verify**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add \
    permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/AbstractRuleEntry.java \
    permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleBuilder.java \
    permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/ParametersFirst.java
git commit -m "feat: extract AbstractRuleEntry base class; share cast() helper and ruleName() contract"
```

---

### Task 6 (C3 DSL): Replace `when="true"` with `alwaysEmit=true` in all DSL templates

**Requires:** C3 from the engine plan (alwaysEmit attribute added).

**Files:** All five template files in `permuplate-mvn-examples/src/main/permuplate/.../drools/`.

- [ ] **Step 1: Count and list all when="true" occurrences**

```bash
grep -rn 'when = "true"\|when="true"' \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/
```

- [ ] **Step 2: Replace all occurrences with `alwaysEmit = true`**

```bash
find /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate \
    -name "*.java" -exec sed -i '' 's/when = "true"/alwaysEmit = true/g; s/when="true"/alwaysEmit = true/g' {} \;
```

- [ ] **Step 3: Build to verify**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/
git commit -m "feat: replace when=\"true\" with alwaysEmit=true in all Drools DSL templates"
```

---

### Final verification

- [ ] **Full clean build of the entire project**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS with all tests passing.

- [ ] **Count the line reduction in JoinBuilder.java**

```bash
wc -l /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
```

Compare against the original line count before this plan. Expected reduction: ~35 lines from path collapse + ~32 lines from reflection elimination.
