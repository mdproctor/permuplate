# Sealed JoinFirst/JoinSecond Hierarchy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add sealed interfaces `JoinBuilderFirst<END,DS>` and `JoinBuilderSecond<END,DS>` inside `JoinBuilder.java`. The template classes implement them. Permuplate's existing `expandSealedPermits` fills the permits clause with all generated class names. Enables Java 21+ pattern dispatch over the builder family.

**Architecture:** Pure DSL change — no new Permuplate engine features needed. Uses the existing `expandSealedPermits` mechanism (already implemented for `@PermuteSource` / sealed class expansion). The sealed interfaces are declared with the template class name as the sole permit — Permuplate replaces it with all generated names.

**Epic:** #79

**Tech Stack:** Java 17 source (generated code must compile on Java 17+), JavaParser 3.28.0, Maven.

**Key constraint:** The Drools DSL examples compile with Java 17 (`maven-compiler-plugin` source/target 17). Java 17 supports sealed classes. The pattern switch dispatch in the example/test can be guarded or placed in a Java 21+ test helper.

---

## File Structure

| Action | Path | What changes |
|---|---|---|
| Modify | `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java` | Add sealed interfaces; add implements clauses |
| Create | `permuplate-mvn-examples/src/test/java/.../drools/SealedHierarchyTest.java` | Verify sealed permits expansion |

---

### Task 1: Read the current `JoinBuilder.java` structure

- [ ] **Step 1: Locate and read the file**

```bash
cat /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    | head -80
```

Identify:
- The outer class name (`JoinBuilder`)
- The template class name for the `First` family (e.g. `Join0First` or the template equivalent)
- The template class name for the `Second` family
- The `@Permute` annotation on each template (to know the `from`/`to` range)

- [ ] **Step 2: Check whether `expandSealedPermits` is already working**

```bash
grep -n "sealed\|permits\|expandSealedPermits" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
```

Confirm that no sealed declarations exist yet. Also check the generated output to see the pattern used by the 2026-04-18 sealed permits expansion:

```bash
grep -rn "sealed\|permits" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/ \
    | head -20
```

---

### Task 2: Add sealed interfaces to `JoinBuilder.java`

- [ ] **Step 1: Understand the `expandSealedPermits` mechanism**

Read the relevant portion of `InlineGenerator`:
```bash
grep -n "expandSealedPermits\|sealed\|permits" \
    /Users/mdproctor/claude/permuplate/permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java \
    | head -20
```

Confirm: `expandSealedPermits` replaces the template class name in the `permits` clause with all generated class names. For example, `permits Join0First` with a `@Permute(from="1", to="6")` generates `permits Join1First, Join2First, ..., Join6First`.

- [ ] **Step 2: Add sealed interfaces inside `JoinBuilder.java`**

Add two sealed interface declarations directly inside the `JoinBuilder` outer class, before the template class declarations:

```java
// Sealed interface for the JoinSecond family.
// The permits clause lists the template class name — Permuplate expands it to
// Join1Second, Join2Second, ..., Join6Second.
public sealed interface JoinBuilderSecond<END, DS> permits Join0Second {}

// Sealed interface for the JoinFirst family.
// Permuplate expands permits to Join1First, Join2First, ..., Join6First.
public sealed interface JoinBuilderFirst<END, DS> permits Join0First {}
```

Note: the exact template class names (`Join0First`, `Join0Second`) must match what's actually used in `JoinBuilder.java`. Confirm from the file read in Task 1.

- [ ] **Step 3: Add `implements` clauses to the template classes**

Find the template class declarations (e.g. `static class Join0First<...>`) and add:

```java
@Permute(varName="i", from="1", to="6", className="Join${i}First", inline=true, keepTemplate=false)
static class Join0First<END, DS, A> implements JoinBuilderFirst<END, DS> {
    // ...
}
```

```java
@Permute(varName="i", from="0", to="6", className="Join${i}Second", inline=true, keepTemplate=false)
static class Join0Second<END, DS> implements JoinBuilderSecond<END, DS> {
    // ...
}
```

The `implements JoinBuilderFirst<END, DS>` clause uses fixed type parameters that match the sealed interface declaration. Permuplate copies the implements clause verbatim to all generated classes — the type args `<END, DS>` are the sealed interface's type parameters, and all generated classes share the same pair.

---

### Task 3: Write a verification test

- [ ] **Step 1: Create `SealedHierarchyTest.java`**

Find the existing test directory:
```bash
find /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/test -name "*.java" | head -5
```

Create the test at the same package location as existing tests:

```java
package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.example.drools.JoinBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the sealed JoinBuilderFirst and JoinBuilderSecond interfaces
 * are correctly expanded and that generated classes implement them.
 */
public class SealedHierarchyTest {

    @Test
    public void testJoin1FirstImplementsJoinBuilderFirst() {
        // Join1First must implement JoinBuilderFirst — verified via instanceof at runtime.
        // (The compile step verifies the sealed permits expansion succeeded.)
        assertTrue(JoinBuilder.JoinBuilderFirst.class.isAssignableFrom(
                JoinBuilder.Join1First.class));
    }

    @Test
    public void testJoin6FirstImplementsJoinBuilderFirst() {
        assertTrue(JoinBuilder.JoinBuilderFirst.class.isAssignableFrom(
                JoinBuilder.Join6First.class));
    }

    @Test
    public void testJoin0SecondImplementsJoinBuilderSecond() {
        assertTrue(JoinBuilder.JoinBuilderSecond.class.isAssignableFrom(
                JoinBuilder.Join0Second.class));
    }

    @Test
    public void testJoin6SecondImplementsJoinBuilderSecond() {
        assertTrue(JoinBuilder.JoinBuilderSecond.class.isAssignableFrom(
                JoinBuilder.Join6Second.class));
    }

    @Test
    public void testJoinBuilderFirstIsSealed() throws Exception {
        // The sealed interface must report that it is sealed.
        Class<?> cls = JoinBuilder.JoinBuilderFirst.class;
        // isSealed() is Java 17 API
        assertTrue((Boolean) cls.getClass().getMethod("isSealed").invoke(cls),
                "JoinBuilderFirst must be a sealed interface");
    }
}
```

Note: if `isSealed()` is not available in the compile target via reflection in this way, simplify to just checking `isAssignableFrom` — the compile step already proves sealedness worked.

---

### Task 4: Build and verify

- [ ] **Step 1: Run the Maven plugin build**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS. If there are sealed-class compilation errors, check:
- The template class name in `permits` matches the actual template class name exactly
- The generated classes all appear in the `permits` clause of the interface in the output

- [ ] **Step 2: Check the generated `JoinBuilder.java` for correct sealed expansion**

```bash
find /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/target \
    -name "JoinBuilder.java" | xargs grep -A 2 "sealed interface\|permits\|implements JoinBuilder" \
    2>/dev/null | head -40
```

Expected output contains:
```
public sealed interface JoinBuilderFirst<END, DS> permits Join1First, Join2First, ..., Join6First {}
public sealed interface JoinBuilderSecond<END, DS> permits Join0Second, Join1Second, ..., Join6Second {}
```

And each generated class starts with `implements JoinBuilderFirst<END, DS>` or `implements JoinBuilderSecond<END, DS>`.

- [ ] **Step 3: Run the tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples \
    -Dtest=SealedHierarchyTest -q 2>&1 | tail -5
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 4: Run the full test suite**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

---

### Task 5: Document and commit

- [ ] **Step 1: Add a comment block to `JoinBuilder.java`**

Above the sealed interface declarations, add a Javadoc comment explaining the purpose:

```java
/**
 * Sealed marker interface for the JoinFirst builder family.
 *
 * <p>Enables Java 21+ pattern switch dispatch over the builder arity:
 * <pre>{@code
 * JoinBuilder.JoinBuilderFirst<?, ?> b = ...;
 * int arity = switch (b) {
 *     case JoinBuilder.Join1First<?,?,?> j -> 1;
 *     case JoinBuilder.Join2First<?,?,?,?> j -> 2;
 *     // ...
 * };
 * }</pre>
 *
 * <p>The permits clause is expanded by Permuplate from the template class name.
 */
```

- [ ] **Step 2: Commit**

```bash
git add \
    permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java \
    permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/SealedHierarchyTest.java
git commit -m "feat: seal JoinFirst/JoinSecond families for Java 21 pattern dispatch (closes #84)"
```
