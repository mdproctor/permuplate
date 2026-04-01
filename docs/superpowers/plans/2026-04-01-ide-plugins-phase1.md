# IDE Plugin Foundation — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `permuplate-ide-support` (the pure-Java annotation string algorithm) and wire its validation rules into the annotation processor — the foundation that Sub-projects 3 (IntelliJ) and 4 (VS Code) will depend on.

**Architecture:** New `permuplate-ide-support` Maven module (pure Java 17, no external deps) containing the parse/match/rename/validate algorithm. The existing `PermuteDeclrTransformer`, `PermuteParamTransformer`, and `PermuteProcessor` are updated to use this library for all annotation string validation, replacing the current leading-literal prefix-only check with full substring matching plus new orphan/no-anchor rules.

**Tech Stack:** Java 17, JUnit 5 (for ide-support tests), existing Google compile-testing (for processor integration tests). Maven build: `/opt/homebrew/bin/mvn`.

**Note on Sub-projects 3 & 4:** IntelliJ and VS Code plugins are out of scope for this plan. They will be planned separately once this foundation is complete and published.

---

## File structure

**New files:**
```
permuplate-ide-support/
  pom.xml
  src/main/java/io/quarkiverse/permuplate/ide/
    AnnotationStringPart.java
    AnnotationStringTemplate.java
    RenameResult.java
    ValidationError.java
    AnnotationStringAlgorithm.java
  src/test/java/io/quarkiverse/permuplate/ide/
    AnnotationStringAlgorithmTest.java

permuplate-tests/src/test/java/io/quarkiverse/permuplate/
  OrphanVariableTest.java          ← new test class
```

**Modified files:**
```
pom.xml                                          ← add permuplate-ide-support module + dependencyManagement
permuplate-core/src/main/java/...core/
  PermuteDeclrTransformer.java                   ← replace checkPrefix with algorithm calls
  PermuteParamTransformer.java                   ← replace prefix check with algorithm calls
permuplate-processor/src/main/java/...processor/
  PermuteProcessor.java                          ← add R1, R1b; replace className prefix check
permuplate-tests/src/test/java/.../
  DegenerateInputTest.java                       ← add R1 and R1b tests
  PrefixValidationTest.java                      ← un-ignore or update substring-based tests
```

---

## Task 1: Create `permuplate-ide-support` module skeleton

**Files:**
- Create: `permuplate-ide-support/pom.xml`
- Modify: `pom.xml` (root)

- [ ] **Create `permuplate-ide-support/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.quarkiverse.permuplate</groupId>
        <artifactId>quarkus-permuplate-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>quarkus-permuplate-ide-support</artifactId>
    <name>Permuplate :: IDE Support</name>
    <description>
        Pure-Java algorithm library for annotation string matching, rename computation,
        and validation. No IDE dependency — used by the IntelliJ plugin, VS Code extension,
        and the annotation processor's compile-time validation rules.
    </description>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.1</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <compilerArgs><arg>-proc:none</arg></compilerArgs>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <includes><include>**/*Test.java</include></includes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Add module to root `pom.xml`**

In the `<modules>` block, add `<module>permuplate-ide-support</module>` BEFORE `permuplate-processor`:

```xml
<modules>
    <module>permuplate-annotations</module>
    <module>permuplate-core</module>
    <module>permuplate-ide-support</module>
    <module>permuplate-processor</module>
    ...
</modules>
```

Also add to `<dependencyManagement>`:

```xml
<dependency>
    <groupId>io.quarkiverse.permuplate</groupId>
    <artifactId>quarkus-permuplate-ide-support</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.1</version>
</dependency>
```

- [ ] **Build to verify module loads**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-ide-support -am -q
```

Expected: BUILD SUCCESS (empty module compiles fine)

---

## Task 2: Core types

**Files:**
- Create: `permuplate-ide-support/src/main/java/io/quarkiverse/permuplate/ide/AnnotationStringPart.java`
- Create: `permuplate-ide-support/src/main/java/io/quarkiverse/permuplate/ide/AnnotationStringTemplate.java`
- Create: `permuplate-ide-support/src/main/java/io/quarkiverse/permuplate/ide/RenameResult.java`
- Create: `permuplate-ide-support/src/main/java/io/quarkiverse/permuplate/ide/ValidationError.java`

- [ ] **Create `AnnotationStringPart.java`**

```java
package io.quarkiverse.permuplate.ide;

/**
 * One segment of a parsed annotation string. Either a variable placeholder
 * ({@code isVariable=true}, text is the variable name e.g. {@code "i"}) or a
 * static literal ({@code isVariable=false}, text is the literal text e.g.
 * {@code "Callable"}).
 */
public record AnnotationStringPart(boolean isVariable, String text) {

    /** Factory for a variable part, e.g. from {@code ${i}}. */
    public static AnnotationStringPart variable(String name) {
        return new AnnotationStringPart(true, name);
    }

    /** Factory for a static literal part. */
    public static AnnotationStringPart literal(String text) {
        return new AnnotationStringPart(false, text);
    }
}
```

- [ ] **Create `AnnotationStringTemplate.java`**

```java
package io.quarkiverse.permuplate.ide;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A parsed annotation string template, consisting of alternating literal and
 * variable parts. For example {@code "${v1}Callable${v2}"} parses to:
 * {@code [Variable("v1"), Literal("Callable"), Variable("v2")]}.
 *
 * <p>
 * All static literals are anchors — they must appear as substrings within the
 * target class name (in declaration order) for the string to be considered a
 * reference to that class.
 */
public record AnnotationStringTemplate(List<AnnotationStringPart> parts) {

    /**
     * Returns the full template string reconstructed from its parts.
     * e.g. {@code "${v1}Callable${v2}"}.
     */
    public String toLiteral() {
        return parts.stream()
                .map(p -> p.isVariable() ? "${" + p.text() + "}" : p.text())
                .collect(Collectors.joining());
    }

    /**
     * Returns the non-empty static literal segments in declaration order.
     * Adjacent literal parts are returned as separate entries (they arise when
     * string constants are expanded next to each other).
     */
    public List<String> staticLiterals() {
        return parts.stream()
                .filter(p -> !p.isVariable() && !p.text().isEmpty())
                .map(AnnotationStringPart::text)
                .toList();
    }

    /** Returns {@code true} if the string contains no {@code ${...}} variables at all. */
    public boolean hasNoVariables() {
        return parts.stream().noneMatch(AnnotationStringPart::isVariable);
    }

    /** Returns {@code true} if the string has no non-empty static literal after expansion. */
    public boolean hasNoLiteral() {
        return staticLiterals().isEmpty();
    }
}
```

- [ ] **Create `RenameResult.java`**

```java
package io.quarkiverse.permuplate.ide;

/**
 * Result of {@link AnnotationStringAlgorithm#computeRename}.
 *
 * <ul>
 * <li>{@link Updated} — the rename could be computed; {@code newTemplate} is
 *     the updated annotation string.</li>
 * <li>{@link NoMatch} — the string does not reference the renamed class at all;
 *     leave it unchanged.</li>
 * <li>{@link NeedsDisambiguation} — the string references the class but the
 *     algorithm cannot determine the new literal(s) automatically because the
 *     prefix/suffix also changed. The IDE must show a dialog asking the user
 *     what each affected literal should become. {@code affectedLiterals} lists
 *     the old literal values in declaration order.</li>
 * </ul>
 */
public sealed interface RenameResult {

    record Updated(String newTemplate) implements RenameResult {}

    record NoMatch() implements RenameResult {}

    record NeedsDisambiguation(java.util.List<String> affectedLiterals)
            implements RenameResult {}
}
```

- [ ] **Create `ValidationError.java`**

```java
package io.quarkiverse.permuplate.ide;

/**
 * A validation error found by {@link AnnotationStringAlgorithm#validate}.
 *
 * @param kind       the type of error
 * @param varName    the variable name involved (for {@code ORPHAN_VARIABLE}); empty otherwise
 * @param suggestion a human-readable fix suggestion
 */
public record ValidationError(ErrorKind kind, String varName, String suggestion) {

    public enum ErrorKind {
        /** String contains no {@code ${...}} variables — every permutation produces the same value. */
        NO_VARIABLES,
        /** A static literal does not appear as a substring of the target name. */
        UNMATCHED_LITERAL,
        /** A variable's corresponding text region in the target name is empty. */
        ORPHAN_VARIABLE,
        /** After expanding string constants, the string has no static literal anchor. */
        NO_ANCHOR
    }

    public static ValidationError noVariables(String suggestion) {
        return new ValidationError(ErrorKind.NO_VARIABLES, "", suggestion);
    }

    public static ValidationError unmatchedLiteral(String literal, String targetName) {
        return new ValidationError(ErrorKind.UNMATCHED_LITERAL, "",
                "literal \"" + literal + "\" does not appear in \"" + targetName + "\"");
    }

    public static ValidationError orphanVariable(String varName, String literal, boolean isSuffix) {
        String pos = isSuffix ? "after" : "before";
        return new ValidationError(ErrorKind.ORPHAN_VARIABLE, varName,
                "${" + varName + "} has no corresponding text " + pos + " \"" + literal
                        + "\" — remove it");
    }

    public static ValidationError noAnchor(String suggestion) {
        return new ValidationError(ErrorKind.NO_ANCHOR, "", suggestion);
    }
}
```

- [ ] **Build**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-ide-support -q
```

Expected: BUILD SUCCESS

---

## Task 3: Implement `parse()` and `expandStringConstants()` with tests

**Files:**
- Create: `permuplate-ide-support/src/main/java/io/quarkiverse/permuplate/ide/AnnotationStringAlgorithm.java`
- Create: `permuplate-ide-support/src/test/java/io/quarkiverse/permuplate/ide/AnnotationStringAlgorithmTest.java`

- [ ] **Write failing tests first**

Create `AnnotationStringAlgorithmTest.java`:

```java
package io.quarkiverse.permuplate.ide;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AnnotationStringAlgorithmTest {

    // =========================================================
    // parse()
    // =========================================================

    @Test void parse_emptyString() {
        var t = AnnotationStringAlgorithm.parse("");
        assertEquals(1, t.parts().size());
        assertFalse(t.parts().get(0).isVariable());
        assertEquals("", t.parts().get(0).text());
        assertTrue(t.hasNoVariables());
        assertTrue(t.hasNoLiteral());
    }

    @Test void parse_literalOnly() {
        var t = AnnotationStringAlgorithm.parse("Callable");
        assertEquals(1, t.parts().size());
        assertFalse(t.parts().get(0).isVariable());
        assertEquals("Callable", t.parts().get(0).text());
        assertTrue(t.hasNoVariables());
        assertFalse(t.hasNoLiteral());
    }

    @Test void parse_variableOnly() {
        var t = AnnotationStringAlgorithm.parse("${i}");
        assertEquals(3, t.parts().size()); // "" + var + ""
        assertTrue(t.parts().get(1).isVariable());
        assertEquals("i", t.parts().get(1).text());
        assertFalse(t.hasNoVariables());
        assertTrue(t.hasNoLiteral());
    }

    @Test void parse_mixed() {
        var t = AnnotationStringAlgorithm.parse("${v1}Callable${v2}");
        assertEquals(3, t.parts().size());
        assertTrue(t.parts().get(0).isVariable()); assertEquals("v1", t.parts().get(0).text());
        assertFalse(t.parts().get(1).isVariable()); assertEquals("Callable", t.parts().get(1).text());
        assertTrue(t.parts().get(2).isVariable()); assertEquals("v2", t.parts().get(2).text());
    }

    @Test void parse_adjacentVariables() {
        var t = AnnotationStringAlgorithm.parse("${v1}${v2}Callable${v3}");
        // parts: ""(lit) v1 ""(lit) v2 ""(lit) Callable(lit) v3 ""(lit) — implementation may merge empties
        // What matters: static literals contain only "Callable", variables include v1,v2,v3
        assertEquals(List.of("Callable"), t.staticLiterals());
        var varNames = t.parts().stream().filter(AnnotationStringPart::isVariable)
                .map(AnnotationStringPart::text).toList();
        assertEquals(List.of("v1", "v2", "v3"), varNames);
    }

    @Test void parse_multipleLiterals() {
        var t = AnnotationStringAlgorithm.parse("Async${i}Handler");
        assertEquals(List.of("Async", "Handler"), t.staticLiterals());
    }

    @Test void parse_dollarSignWithoutBrace_isTreatedAsLiteral() {
        var t = AnnotationStringAlgorithm.parse("Callable$i");
        assertEquals(1, t.staticLiterals().size());
        assertEquals("Callable$i", t.staticLiterals().get(0));
    }

    // =========================================================
    // expandStringConstants()
    // =========================================================

    @Test void expand_noConstants() {
        var t = AnnotationStringAlgorithm.parse("${prefix}Callable${i}");
        var expanded = AnnotationStringAlgorithm.expandStringConstants(t, Map.of());
        // prefix not defined → not expanded, stays as variable
        assertEquals(List.of("Callable"), expanded.staticLiterals());
        assertTrue(expanded.parts().stream().anyMatch(p -> p.isVariable() && p.text().equals("prefix")));
    }

    @Test void expand_singleConstant() {
        var t = AnnotationStringAlgorithm.parse("${prefix}Callable${i}");
        var expanded = AnnotationStringAlgorithm.expandStringConstants(t, Map.of("prefix", "My"));
        // "${prefix}" → "My" literal, result is "MyCallable${i}"
        assertEquals(List.of("MyCallable"), expanded.staticLiterals());
    }

    @Test void expand_constantComposesFullLiteral() {
        var t = AnnotationStringAlgorithm.parse("${prefix}${i}");
        var expanded = AnnotationStringAlgorithm.expandStringConstants(t, Map.of("prefix", "Callable"));
        // "${prefix}" → "Callable", result is "Callable${i}"
        assertEquals(List.of("Callable"), expanded.staticLiterals());
        assertFalse(expanded.hasNoLiteral());
    }

    @Test void expand_multipleConstants() {
        var t = AnnotationStringAlgorithm.parse("${a}${b}Callable${i}");
        var expanded = AnnotationStringAlgorithm.expandStringConstants(t, Map.of("a", "My", "b", "Good"));
        assertEquals(List.of("MyGoodCallable"), expanded.staticLiterals());
    }

    @Test void expand_emptyStringConstant() {
        var t = AnnotationStringAlgorithm.parse("${a}Callable${i}");
        var expanded = AnnotationStringAlgorithm.expandStringConstants(t, Map.of("a", ""));
        assertEquals(List.of("Callable"), expanded.staticLiterals());
    }
}
```

- [ ] **Run tests to confirm they fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-ide-support 2>&1 | grep -E "(FAIL|ERROR|BUILD)"
```

Expected: BUILD FAILURE — AnnotationStringAlgorithm does not exist yet

- [ ] **Create `AnnotationStringAlgorithm.java` with `parse()` and `expandStringConstants()`**

```java
package io.quarkiverse.permuplate.ide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java algorithm for annotation string matching, rename computation,
 * and validation. No external dependencies — works identically in the
 * annotation processor, IntelliJ plugin, and VS Code extension (TypeScript port).
 *
 * <p>
 * <strong>Java is the source of truth.</strong> The TypeScript port in
 * {@code permuplate-vscode/src/algorithm.ts} must be kept exactly in sync.
 * Any bug fix or behaviour change here must be ported to TypeScript in the
 * same commit, with a matching Jest test. See CLAUDE.md.
 *
 * <h2>Algorithm summary</h2>
 * <ol>
 * <li>Expand {@code strings} constants (string variables → static text)</li>
 * <li>Match: all static literals must appear as substrings in the class name,
 *     in declaration order</li>
 * <li>Rename: for each literal, strip the surrounding prefix/suffix to extract
 *     the new literal; return {@link RenameResult.NeedsDisambiguation} if any
 *     strip fails (prefix/suffix also changed)</li>
 * <li>Validate: check R2 (unmatched), R3 (orphan), R4 (no anchor) in that order;
 *     R2 short-circuits R3 and R4</li>
 * </ol>
 */
public class AnnotationStringAlgorithm {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    private AnnotationStringAlgorithm() {}

    // =========================================================
    // parse()
    // =========================================================

    /**
     * Parses an annotation string template into a sequence of literal and
     * variable parts.
     *
     * <p>
     * {@code "${v1}Callable${v2}"} → {@code [Var("v1"), Lit("Callable"), Var("v2")]}
     */
    public static AnnotationStringTemplate parse(String template) {
        List<AnnotationStringPart> parts = new ArrayList<>();
        Matcher m = VARIABLE.matcher(template);
        int lastEnd = 0;
        while (m.find()) {
            // literal segment before this variable (may be empty)
            parts.add(AnnotationStringPart.literal(template.substring(lastEnd, m.start())));
            parts.add(AnnotationStringPart.variable(m.group(1)));
            lastEnd = m.end();
        }
        // trailing literal (may be empty)
        parts.add(AnnotationStringPart.literal(template.substring(lastEnd)));
        return new AnnotationStringTemplate(parts);
    }

    // =========================================================
    // expandStringConstants()
    // =========================================================

    /**
     * Expands string constants from {@code @Permute strings} into the template.
     * Variable parts whose name exists in {@code constants} are replaced with
     * literal parts. Integer loop variables (not in {@code constants}) are left
     * as variables.
     *
     * <p>
     * Adjacent literal parts produced by expansion are merged into one.
     */
    public static AnnotationStringTemplate expandStringConstants(
            AnnotationStringTemplate t, Map<String, String> constants) {
        List<AnnotationStringPart> expanded = new ArrayList<>();
        for (AnnotationStringPart part : t.parts()) {
            if (part.isVariable() && constants.containsKey(part.text())) {
                expanded.add(AnnotationStringPart.literal(constants.get(part.text())));
            } else {
                expanded.add(part);
            }
        }
        // Merge adjacent literals
        List<AnnotationStringPart> merged = new ArrayList<>();
        for (AnnotationStringPart p : expanded) {
            if (!merged.isEmpty() && !merged.get(merged.size() - 1).isVariable() && !p.isVariable()) {
                String combined = merged.get(merged.size() - 1).text() + p.text();
                merged.set(merged.size() - 1, AnnotationStringPart.literal(combined));
            } else {
                merged.add(p);
            }
        }
        return new AnnotationStringTemplate(merged);
    }
}
```

- [ ] **Run parse/expand tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-ide-support -Dtest="AnnotationStringAlgorithmTest#parse*+expand*" 2>&1 | grep -E "(Tests run|FAIL|BUILD)"
```

Expected: all parse/expand tests pass

- [ ] **Commit**

```bash
git add permuplate-ide-support/ pom.xml
git commit -m "feat: create permuplate-ide-support with parse() and expandStringConstants()"
```

---

## Task 4: Implement `matches()` with tests

**Files:**
- Modify: `permuplate-ide-support/src/main/java/io/quarkiverse/permuplate/ide/AnnotationStringAlgorithm.java`
- Modify: `permuplate-ide-support/src/test/java/io/quarkiverse/permuplate/ide/AnnotationStringAlgorithmTest.java`

- [ ] **Add matches() tests**

Add to `AnnotationStringAlgorithmTest`:

```java
    // =========================================================
    // matches()
    // =========================================================

    @Test void matches_singleLiteralAtStart() {
        var t = parse("Callable${i}");
        assertTrue(AnnotationStringAlgorithm.matches(t, "Callable2"));
    }

    @Test void matches_singleLiteralInMiddle() {
        var t = parse("${v1}Callable${v2}");
        assertTrue(AnnotationStringAlgorithm.matches(t, "MyCallable2"));
    }

    @Test void matches_singleLiteralAtEnd() {
        var t = parse("${v1}Callable");
        assertTrue(AnnotationStringAlgorithm.matches(t, "MyCallable"));
    }

    @Test void matches_longPrefixAndSuffix() {
        var t = parse("${v1}Callable${v2}");
        assertTrue(AnnotationStringAlgorithm.matches(t, "ThisIsMyPrefixCallableThisIsMySuffix3"));
    }

    @Test void matches_noMatch() {
        var t = parse("Foo${i}");
        assertFalse(AnnotationStringAlgorithm.matches(t, "Callable2"));
    }

    @Test void matches_classNameWithOnlyNumericSuffix() {
        var t = parse("Callable${i}");
        assertTrue(AnnotationStringAlgorithm.matches(t, "Callable2"));
        assertFalse(AnnotationStringAlgorithm.matches(t, "2"));
    }

    @Test void matches_multipleLiteralsCorrectOrder() {
        var t = parse("Async${i}Handler");
        assertTrue(AnnotationStringAlgorithm.matches(t, "AsyncDiskHandler2"));
    }

    @Test void matches_multipleLiteralsWrongOrder() {
        var t = parse("Async${i}Handler");
        assertFalse(AnnotationStringAlgorithm.matches(t, "HandlerAsyncDisk2"));
    }

    @Test void matches_firstLiteralPresentSecondAbsent() {
        var t = parse("Async${i}Cache");
        assertFalse(AnnotationStringAlgorithm.matches(t, "AsyncDiskHandler2"));
    }

    @Test void matches_allVariablesNoLiteral_neverMatches() {
        var t = parse("${v1}${v2}");
        assertFalse(AnnotationStringAlgorithm.matches(t, "Callable2"));
        assertFalse(AnnotationStringAlgorithm.matches(t, "anything"));
    }

    // Helper for tests
    private static AnnotationStringTemplate parse(String s) {
        return AnnotationStringAlgorithm.parse(s);
    }
```

- [ ] **Run to confirm they fail**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-ide-support -Dtest="AnnotationStringAlgorithmTest#matches*" 2>&1 | grep -E "(FAIL|ERROR|BUILD)"
```

Expected: BUILD FAILURE — `matches()` not implemented

- [ ] **Implement `matches()`**

Add to `AnnotationStringAlgorithm.java`:

```java
    /**
     * Returns {@code true} if all static literals in {@code t} appear as
     * substrings within {@code className}, in declaration order.
     *
     * <p>
     * A string with no static literals (all variables) never matches.
     */
    public static boolean matches(AnnotationStringTemplate t, String className) {
        List<String> literals = t.staticLiterals();
        if (literals.isEmpty()) return false;

        int searchFrom = 0;
        for (String literal : literals) {
            int pos = className.indexOf(literal, searchFrom);
            if (pos < 0) return false;
            searchFrom = pos + literal.length();
        }
        return true;
    }
```

- [ ] **Run matches tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-ide-support -Dtest="AnnotationStringAlgorithmTest#matches*" 2>&1 | grep -E "(Tests run|BUILD)"
```

Expected: all pass

- [ ] **Commit**

```bash
git add permuplate-ide-support/src
git commit -m "feat: implement matches() — all literals as ordered anchors"
```

---

## Task 5: Implement `computeRename()` with tests

**Files:**
- Modify: `permuplate-ide-support/src/main/java/io/quarkiverse/permuplate/ide/AnnotationStringAlgorithm.java`
- Modify: `permuplate-ide-support/src/test/java/io/quarkiverse/permuplate/ide/AnnotationStringAlgorithmTest.java`

- [ ] **Add computeRename() tests**

Add to `AnnotationStringAlgorithmTest`:

```java
    // =========================================================
    // computeRename()
    // =========================================================

    @Test void computeRename_singleLiteralOnlyLiteralChanges() {
        var t = parse("Callable${i}");
        var result = AnnotationStringAlgorithm.computeRename(t, "Callable2", "Handler2");
        assertInstanceOf(RenameResult.Updated.class, result);
        assertEquals("Handler${i}", ((RenameResult.Updated) result).newTemplate());
    }

    @Test void computeRename_longPrefixSuffixPreserved() {
        var t = parse("${v1}Callable${v2}");
        var result = AnnotationStringAlgorithm.computeRename(t,
                "ThisIsMyPrefixCallableThisIsMySuffix3",
                "ThisIsMyPrefixHookThisIsMySuffix3");
        assertInstanceOf(RenameResult.Updated.class, result);
        assertEquals("${v1}Hook${v2}", ((RenameResult.Updated) result).newTemplate());
    }

    @Test void computeRename_numericSuffixChangesButIsCapuredByVariable() {
        var t = parse("Callable${i}");
        // "2" → "3": old suffix "2" → new name ends "3", not "2" → NoMatch? 
        // Actually: old_prefix="" strip from "Handler3" → "Handler3"; old_suffix="2" → "Handler3" doesn't end with "2" → NoMatch
        // But wait: the numeric suffix is part of the variable ${i}. The literal is "Callable".
        // old_prefix="" (before "Callable" in "Callable2"), old_suffix="2" (after "Callable" in "Callable2")
        // new name "Handler3": starts with "" ✓, ends with "2"? NO → NeedsDisambiguation
        // Hmm — the spec says "Numeric suffix changes (variable captures it): Callable2 → Handler3 → Handler${i} ✓"
        // This means the algorithm should handle this case. The suffix "2" changed to "3".
        // When old_suffix doesn't match: since ${i} is the variable capturing the suffix, it's fine.
        // The algorithm must try: strip old_prefix, then take everything up to where old_suffix WOULD be,
        // but since the suffix is captured by a variable, we look for the literal differently.
        //
        // REVISED: the suffix "2" is captured by variable ${i} at the END. When stripping:
        // old_suffix = "2", new_name ends in "3" not "2". Since there's a VARIABLE after the literal (${i}),
        // the suffix corresponds to that variable's value. Variables are wildcards — we don't need the
        // suffix to EXACTLY match; we just need the new literal to be whatever is left after stripping
        // the old prefix.
        //
        // Simpler implementation: for the trailing suffix, since it's captured by a variable,
        // strip old_prefix from start of new_name, then whatever remains is the new_literal + new_suffix.
        // To isolate new_literal: we don't strip the suffix — instead we look for the NEXT literal
        // (there is none here) or take all remaining chars as the new literal+suffix together.
        // Since there IS a trailing variable, we know the suffix belongs to that variable, so
        // new_literal = new_name stripped of old_prefix, minus the suffix length? No, suffix length changed.
        //
        // The correct approach: when there's a variable after the last literal, we DON'T strip any suffix
        // from the new name — instead the new literal is everything from after the old_prefix to the end
        // of the new name... but that would give "Handler3" not "Handler".
        //
        // ACTUAL SPEC interpretation: The VARIABLE ${i} captures the numeric suffix.
        // "Callable2" → "Handler3": old_prefix="" strips → "Handler3". old_suffix="2" → doesn't match "3".
        // Since the suffix IS captured by a variable, the old_suffix matching fails → NeedsDisambiguation?
        // But spec says this should work. Let me re-read...
        //
        // The spec says: when prefix/suffix ALSO change → NoMatch (disambiguation). The numeric suffix
        // changing IS a suffix change. So this case → NeedsDisambiguation.
        // The spec then says "the variable ${i} captures the new suffix at generate time" — meaning the
        // ANNOTATION STRING doesn't need to be updated for the suffix (the variable handles it).
        // Only the LITERAL needs updating.
        //
        // So: the algorithm should detect "suffix changed but it's covered by a variable → ignore suffix mismatch"
        // Implementation: if old_suffix doesn't match AND there IS a variable after the literal → ignore suffix,
        // take remaining after stripping old_prefix as new_literal (don't care about suffix).
        //
        // Test the expected output:
        var result = AnnotationStringAlgorithm.computeRename(t, "Callable2", "Handler3");
        assertInstanceOf(RenameResult.Updated.class, result);
        assertEquals("Handler${i}", ((RenameResult.Updated) result).newTemplate());
    }

    @Test void computeRename_multipleLiteralsSecondChanges() {
        var t = parse("Async${i}Handler");
        var result = AnnotationStringAlgorithm.computeRename(t,
                "AsyncDiskHandler2", "AsyncDiskProcessor2");
        assertInstanceOf(RenameResult.Updated.class, result);
        assertEquals("Async${i}Processor", ((RenameResult.Updated) result).newTemplate());
    }

    @Test void computeRename_multipleLiteralsBothChange_needsDisambiguation() {
        var t = parse("Async${i}Handler");
        var result = AnnotationStringAlgorithm.computeRename(t,
                "AsyncDiskHandler2", "SyncSSDProcessor2");
        assertInstanceOf(RenameResult.NeedsDisambiguation.class, result);
        var nd = (RenameResult.NeedsDisambiguation) result;
        // Both "Async" and "Handler" are affected
        assertTrue(nd.affectedLiterals().contains("Async") || nd.affectedLiterals().contains("Handler"));
    }

    @Test void computeRename_prefixAlsoChanged_needsDisambiguation() {
        var t = parse("${v1}Callable${v2}");
        var result = AnnotationStringAlgorithm.computeRename(t, "MyCallable2", "YourHook3");
        assertInstanceOf(RenameResult.NeedsDisambiguation.class, result);
        assertEquals(List.of("Callable"), ((RenameResult.NeedsDisambiguation) result).affectedLiterals());
    }

    @Test void computeRename_stringDoesNotMatchOldClass_noMatch() {
        var t = parse("Callable${i}");
        var result = AnnotationStringAlgorithm.computeRename(t, "Handler2", "Processor2");
        assertInstanceOf(RenameResult.NoMatch.class, result);
    }

    @Test void computeRename_noChange_returnsNoMatch() {
        // "Callable2" → "Callable3": only suffix changes, literal "Callable" unchanged
        var t = parse("Callable${i}");
        var result = AnnotationStringAlgorithm.computeRename(t, "Callable2", "Callable3");
        // "Callable" is still "Callable" → Updated with same literal, or NoMatch?
        // The literal didn't change → return NoMatch (no update needed)
        assertInstanceOf(RenameResult.NoMatch.class, result);
    }
```

- [ ] **Implement `computeRename()`**

Add to `AnnotationStringAlgorithm.java`:

```java
    /**
     * Computes the updated annotation string after renaming a class from
     * {@code oldClassName} to {@code newClassName}.
     *
     * <ul>
     * <li>{@link RenameResult.Updated} — new template computed successfully</li>
     * <li>{@link RenameResult.NoMatch} — string doesn't reference this class,
     *     or the literal didn't change (no update needed)</li>
     * <li>{@link RenameResult.NeedsDisambiguation} — string references the class
     *     but prefix/suffix also changed; IDE must ask user for new literals</li>
     * </ul>
     */
    public static RenameResult computeRename(AnnotationStringTemplate t,
            String oldClassName, String newClassName) {
        List<String> literals = t.staticLiterals();
        if (literals.isEmpty()) return new RenameResult.NoMatch();
        if (!matches(t, oldClassName)) return new RenameResult.NoMatch();

        // Find each literal's position in the old class name
        List<int[]> positions = new ArrayList<>(); // [start, end]
        int searchFrom = 0;
        for (String literal : literals) {
            int pos = oldClassName.indexOf(literal, searchFrom);
            if (pos < 0) return new RenameResult.NoMatch(); // shouldn't happen (matches() passed)
            positions.add(new int[]{pos, pos + literal.length()});
            searchFrom = pos + literal.length();
        }

        // Determine whether there is a variable BEFORE the first literal and AFTER the last
        boolean hasLeadingVar = t.parts().stream()
                .takeWhile(p -> !p.text().equals(literals.get(0)) || p.isVariable())
                .anyMatch(AnnotationStringPart::isVariable);
        boolean hasTrailingVar = t.parts().stream()
                .dropWhile(p -> !(!p.isVariable() && p.text().equals(literals.get(literals.size() - 1))))
                .skip(1)
                .anyMatch(AnnotationStringPart::isVariable);

        // For each literal, compute old_prefix and old_suffix (the inter-literal regions)
        List<String> newLiterals = new ArrayList<>();
        List<String> ambiguousLiterals = new ArrayList<>();
        int newSearchFrom = 0;

        for (int i = 0; i < literals.size(); i++) {
            String literal = literals.get(i);
            int[] pos = positions.get(i);

            // old_prefix = from previous literal end (or class start) to this literal start
            int prevEnd = (i == 0) ? 0 : positions.get(i - 1)[1];
            String oldPrefix = oldClassName.substring(prevEnd, pos[0]);

            // old_suffix = from this literal end to next literal start (or class end)
            int nextStart = (i == literals.size() - 1) ? oldClassName.length() : positions.get(i + 1)[0];
            String oldSuffix = oldClassName.substring(pos[1], nextStart);

            // Try to strip old_prefix from current position in new name
            String remaining = newClassName.substring(newSearchFrom);
            String afterPrefix;
            if (oldPrefix.isEmpty()) {
                afterPrefix = remaining;
            } else if (remaining.startsWith(oldPrefix)) {
                afterPrefix = remaining.substring(oldPrefix.length());
            } else {
                // Prefix changed → this literal needs disambiguation
                ambiguousLiterals.add(literal);
                newLiterals.add(null);
                continue;
            }

            // Strip old_suffix from end of remaining, unless it's covered by a trailing variable
            String newLiteral;
            boolean isLastLiteral = (i == literals.size() - 1);
            if (oldSuffix.isEmpty()) {
                newLiteral = afterPrefix;
            } else if (isLastLiteral && hasTrailingVar) {
                // Trailing variable captures the suffix — suffix change is expected and ignored.
                // The new literal is everything up to the same relative position from the end.
                // Use suffix length as guide only if suffix length didn't change.
                if (afterPrefix.length() >= oldSuffix.length()
                        && afterPrefix.endsWith(oldSuffix)) {
                    newLiteral = afterPrefix.substring(0, afterPrefix.length() - oldSuffix.length());
                } else {
                    // Suffix changed length too — take chars up to old literal length
                    if (afterPrefix.length() < literal.length()) {
                        ambiguousLiterals.add(literal);
                        newLiterals.add(null);
                        continue;
                    }
                    newLiteral = afterPrefix.substring(0, afterPrefix.length() - oldSuffix.length());
                    // Still might be wrong length — just use what we have
                    // Find the "natural" end by looking for what changed
                    // Simplest: new literal = afterPrefix minus any trailing digits if suffix was digits
                    if (oldSuffix.matches("\\d+")) {
                        // Strip trailing digits from afterPrefix as the new suffix
                        int j = afterPrefix.length() - 1;
                        while (j >= 0 && Character.isDigit(afterPrefix.charAt(j))) j--;
                        newLiteral = afterPrefix.substring(0, j + 1);
                    } else {
                        newLiteral = afterPrefix; // best effort
                    }
                }
            } else if (afterPrefix.endsWith(oldSuffix)) {
                newLiteral = afterPrefix.substring(0, afterPrefix.length() - oldSuffix.length());
            } else {
                // Suffix changed → disambiguation
                ambiguousLiterals.add(literal);
                newLiterals.add(null);
                continue;
            }

            newLiterals.add(newLiteral);
            newSearchFrom += oldPrefix.length() + newLiteral.length();
        }

        if (!ambiguousLiterals.isEmpty()) {
            return new RenameResult.NeedsDisambiguation(ambiguousLiterals);
        }

        // Rebuild template, replacing old literals with new ones
        boolean anyChanged = false;
        for (int i = 0; i < literals.size(); i++) {
            if (!literals.get(i).equals(newLiterals.get(i))) {
                anyChanged = true;
                break;
            }
        }
        if (!anyChanged) return new RenameResult.NoMatch();

        // Replace literals in the template
        String newTemplate = t.toLiteral();
        for (int i = 0; i < literals.size(); i++) {
            String old = literals.get(i);
            String replacement = newLiterals.get(i);
            newTemplate = newTemplate.replaceFirst(Pattern.quote(old), Matcher.quoteReplacement(replacement));
        }
        return new RenameResult.Updated(newTemplate);
    }
```

- [ ] **Run computeRename tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-ide-support -Dtest="AnnotationStringAlgorithmTest#computeRename*" 2>&1 | grep -E "(Tests run|FAIL|BUILD)"
```

Expected: all pass. If any fail, debug the specific case — the numeric suffix change test may need iteration.

- [ ] **Commit**

```bash
git add permuplate-ide-support/src
git commit -m "feat: implement computeRename() with disambiguation support"
```

---

## Task 6: Implement `validate()` with tests

**Files:**
- Modify: `permuplate-ide-support/src/main/java/io/quarkiverse/permuplate/ide/AnnotationStringAlgorithm.java`
- Modify: `permuplate-ide-support/src/test/java/io/quarkiverse/permuplate/ide/AnnotationStringAlgorithmTest.java`

- [ ] **Add validate() tests**

Add to `AnnotationStringAlgorithmTest`:

```java
    // =========================================================
    // validate()
    // =========================================================

    private static List<ValidationError> validate(String template, String targetName,
            Map<String, String> constants) {
        var t = AnnotationStringAlgorithm.expandStringConstants(
                AnnotationStringAlgorithm.parse(template), constants);
        return AnnotationStringAlgorithm.validate(t, targetName, constants);
    }

    private static List<ValidationError> validate(String template, String targetName) {
        return validate(template, targetName, Map.of());
    }

    @Test void validate_r2_unmatchedSingleLiteral() {
        var errors = validate("Foo${i}", "Callable2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.UNMATCHED_LITERAL, errors.get(0).kind());
    }

    @Test void validate_r2_unmatchedSecondLiteral() {
        var errors = validate("Async${i}Cache", "AsyncDiskHandler2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.UNMATCHED_LITERAL, errors.get(0).kind());
    }

    @Test void validate_r2_shortCircuitsR3andR4() {
        // "${v1}Foo${v2}" on "Callable2": "Foo" not in "Callable2" → only R2, no orphan
        var errors = validate("${v1}Foo${v2}", "Callable2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.UNMATCHED_LITERAL, errors.get(0).kind());
    }

    @Test void validate_r3_orphanSingleAtStart() {
        // "${v1}Callable${v2}" on "Callable2": prefix before "Callable" = "" → v1 orphan
        var errors = validate("${v1}Callable${v2}", "Callable2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.ORPHAN_VARIABLE, errors.get(0).kind());
        assertEquals("v1", errors.get(0).varName());
    }

    @Test void validate_r3_orphanMultipleAdjacent() {
        // "${v1}${v2}Callable${v3}" on "Callable2": collective prefix="" → v1 and v2 both orphan
        var errors = validate("${v1}${v2}Callable${v3}", "Callable2");
        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.kind() == ValidationError.ErrorKind.ORPHAN_VARIABLE));
        var varNames = errors.stream().map(ValidationError::varName).toList();
        assertTrue(varNames.contains("v1"));
        assertTrue(varNames.contains("v2"));
    }

    @Test void validate_r3_notOrphanSingleVariable() {
        // "${v1}Callable${v2}" on "MyCallable2": prefix "My" non-empty → valid
        var errors = validate("${v1}Callable${v2}", "MyCallable2");
        assertTrue(errors.isEmpty());
    }

    @Test void validate_r3_adjacentVariablesNonEmptyCollective() {
        // "${v1}${v2}Callable${v3}" on "MyCallable2": collective prefix "My" non-empty → neither orphan
        var errors = validate("${v1}${v2}Callable${v3}", "MyCallable2");
        assertTrue(errors.isEmpty());
    }

    @Test void validate_r3_suffixNotOrphan() {
        // "Callable${v1}" on "Callable2": suffix "2" non-empty → v1 not orphan
        var errors = validate("Callable${v1}", "Callable2");
        assertTrue(errors.isEmpty());
    }

    @Test void validate_r4_pureVariables() {
        var errors = validate("${v1}${v2}", "Callable2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.NO_ANCHOR, errors.get(0).kind());
    }

    @Test void validate_r4_noExpansion() {
        // "${prefix}${i}" with no constants for prefix → no anchor after expansion
        var errors = validate("${prefix}${i}", "Callable2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.NO_ANCHOR, errors.get(0).kind());
    }

    @Test void validate_valid_substrMatch() {
        var errors = validate("${v1}Callable${v2}", "ThisIsMyPrefixCallable3");
        assertTrue(errors.isEmpty());
    }

    @Test void validate_valid_multipleLiteralsInOrder() {
        var errors = validate("Async${i}Handler", "AsyncDiskHandler2");
        assertTrue(errors.isEmpty());
    }

    @Test void validate_valid_stringConstantComposesLiteral() {
        var errors = validate("${prefix}${i}", "Callable2", Map.of("prefix", "Callable"));
        assertTrue(errors.isEmpty());
    }

    @Test void validate_valid_typeObjectNoVariableAllowedForInnerAnnotation() {
        // type="Object" on for-each Object o2 — no variable in type, but this is
        // explicitly allowed for inner annotations (R1 applies only to @Permute.className)
        // validate() itself does NOT enforce R1 — caller decides scope.
        // So validate("Object", "Object") with no variables → what happens?
        // "Object" has no ${...} → hasNoVariables() = true.
        // But R1 (NO_VARIABLES) is NOT a rule that validate() enforces — it's enforced
        // by the processor only for @Permute.className. validate() only enforces R2/R3/R4.
        // "Object" on "Object": literal "Object" is in "Object" → R2 passes.
        // No variables → no R3/R4 to check. Result: empty errors.
        var errors = validate("Object", "Object");
        assertTrue(errors.isEmpty()); // R1 is caller's responsibility, not validate()'s
    }
```

- [ ] **Implement `validate()`**

Add to `AnnotationStringAlgorithm.java`:

```java
    /**
     * Validates an annotation string template against a target name (class name,
     * field name, parameter name, etc.) after expanding string constants.
     *
     * <p>
     * Returns a list of {@link ValidationError}s. Rules checked:
     * <ul>
     * <li><b>R2 (Unmatched literal):</b> each static literal must appear as a
     *     substring in the target name, in declaration order. First mismatch
     *     short-circuits R3 and R4.</li>
     * <li><b>R3 (Orphan variable):</b> a variable whose corresponding text
     *     region in the target name is empty (collectively for adjacent variables).</li>
     * <li><b>R4 (No anchor):</b> no static literal at all after expansion.</li>
     * </ul>
     *
     * <p>
     * <b>R1 (no variables in className) is NOT checked here</b> — it is enforced
     * by the processor for {@code @Permute.className} only and must be called
     * separately by the processor.
     *
     * @param t              the expanded annotation string template
     * @param targetName     the actual class/field/param name to validate against
     * @param constants      the string constants from {@code @Permute strings}
     *                       (already expanded into {@code t}, passed for reference)
     * @return empty list if valid; otherwise one or more errors
     */
    public static List<ValidationError> validate(AnnotationStringTemplate t,
            String targetName, Map<String, String> constants) {
        List<String> literals = t.staticLiterals();

        // R4: no anchor
        if (literals.isEmpty()) {
            return List.of(ValidationError.noAnchor(
                    "add a literal portion or define the variable in @Permute strings"));
        }

        // R2: find each literal in order; first failure short-circuits R3
        List<int[]> positions = new ArrayList<>();
        int searchFrom = 0;
        for (String literal : literals) {
            int pos = targetName.indexOf(literal, searchFrom);
            if (pos < 0) {
                return List.of(ValidationError.unmatchedLiteral(literal, targetName));
            }
            positions.add(new int[]{pos, pos + literal.length()});
            searchFrom = pos + literal.length();
        }

        // R3: orphan variables — check variables before/between/after literals
        List<ValidationError> errors = new ArrayList<>();
        checkOrphans(t, targetName, literals, positions, errors);
        return errors;
    }

    private static void checkOrphans(AnnotationStringTemplate t, String targetName,
            List<String> literals, List<int[]> positions, List<ValidationError> errors) {
        // Collect variable groups: variables before literal[0], between literal[i] and [i+1], after last
        // A "group" of adjacent variables collectively covers one region in the target name.

        List<AnnotationStringPart> parts = t.parts();
        int literalIdx = 0;

        List<String> currentGroup = new ArrayList<>();
        for (AnnotationStringPart part : parts) {
            if (!part.isVariable()) {
                String literal = part.text();
                if (literal.isEmpty()) continue;
                // Flush current variable group — they cover the region BEFORE this literal
                if (!currentGroup.isEmpty()) {
                    int literalStart = positions.get(literalIdx)[0];
                    int prevEnd = (literalIdx == 0) ? 0 : positions.get(literalIdx - 1)[1];
                    String region = targetName.substring(prevEnd, literalStart);
                    if (region.isEmpty()) {
                        // All variables in this group are orphan
                        for (String varName : currentGroup) {
                            errors.add(ValidationError.orphanVariable(varName, literal, false));
                        }
                    }
                    currentGroup.clear();
                }
                literalIdx++;
            } else {
                currentGroup.add(part.text());
            }
        }

        // Final group: variables AFTER the last literal
        if (!currentGroup.isEmpty() && !literals.isEmpty()) {
            int lastEnd = positions.get(positions.size() - 1)[1];
            String region = targetName.substring(lastEnd);
            if (region.isEmpty()) {
                String lastLiteral = literals.get(literals.size() - 1);
                for (String varName : currentGroup) {
                    errors.add(ValidationError.orphanVariable(varName, lastLiteral, true));
                }
            }
        }
    }
```

- [ ] **Run validate tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-ide-support 2>&1 | grep -E "(Tests run|BUILD|FAIL)"
```

Expected: all tests pass, BUILD SUCCESS

- [ ] **Commit**

```bash
git add permuplate-ide-support/src
git commit -m "feat: implement validate() with R2/R3/R4 rules and short-circuit"
```

---

## Task 7: Add `permuplate-ide-support` to processor and wire R1 / R1b

**Files:**
- Modify: `permuplate-processor/pom.xml`
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java`

- [ ] **Write R1 and R1b tests first**

Add to `DegenerateInputTest.java` (before the closing `}`):

```java
    // -------------------------------------------------------------------------
    // R1 — @Permute.className has no variable
    // -------------------------------------------------------------------------

    @Test
    public void testClassNameNoVariableWithRangeIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "FixedName")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("FixedName");
        assertThat(compilation).hadErrorContaining("no variables");
    }

    @Test
    public void testClassNameNoVariableWithFromEqualsToIsError() {
        // from==to: Filer would NOT catch the duplicate (only one file written).
        // R1 must catch it early with a clear message.
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 3, className = "FixedName")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("FixedName");
        assertThat(compilation).hadErrorContaining("no variables");
    }

    @Test
    public void testClassNameWithVariableDoesNotTriggerR1() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 3, className = "Foo${i}")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));
        assertThat(compilation).succeeded();
    }

    // -------------------------------------------------------------------------
    // R1b — extraVars variable absent from className
    // -------------------------------------------------------------------------

    @Test
    public void testExtraVarsMissingFromClassNameIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 2, to = 3, className = "Foo${i}",
                                 extraVars = { @PermuteVar(varName = "k", from = 2, to = 3) })
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_VAR_FQN));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("k");
        assertThat(compilation).hadErrorContaining("className");
    }

    @Test
    public void testExtraVarsPresentInClassNameDoesNotError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 2, to = 3, className = "Foo${i}x${k}",
                                 extraVars = { @PermuteVar(varName = "k", from = 2, to = 3) })
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_VAR_FQN));
        assertThat(compilation).succeeded();
    }
```

- [ ] **Run to confirm failures**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="DegenerateInputTest#testClassName*,DegenerateInputTest#testExtraVars*" 2>&1 | grep -E "(Tests run|FAIL|BUILD)"
```

Expected: some tests fail (R1 and R1b not implemented yet)

- [ ] **Add `permuplate-ide-support` dependency to `permuplate-processor/pom.xml`**

```xml
<dependency>
    <groupId>io.quarkiverse.permuplate</groupId>
    <artifactId>quarkus-permuplate-ide-support</artifactId>
</dependency>
```

- [ ] **Implement R1 and R1b in `PermuteProcessor.processTypePermutation()`**

In `PermuteProcessor.java`, read the file first. Find `processTypePermutation`. Add these checks after the `from > to` check and after `validateStrings`/`validateExtraVars`, before the `leadingLiteral` prefix check:

```java
// R1: className must contain at least one ${...} variable
if (!permute.className().contains("${")) {
    error(String.format(
            "@Permute className \"%s\" contains no variables — every permutation will" +
                    " produce the same class name; add a ${%s} expression",
            permute.className(), permute.varName()),
            typeElement, permuteMirror, findAnnotationValue(permuteMirror, "className"));
    return;
}

// R1b: every declared variable (varName + extraVars) must appear in className
String classNameTemplate = permute.className();
Set<String> missingVars = new java.util.LinkedHashSet<>();
if (!classNameTemplate.contains("${" + permute.varName() + "}")) {
    // varName not in className — not an error by itself (varName must be in range,
    // but the duplicate-class error handles this if from<to).
    // Only R1b covers the extraVars case.
}
for (io.quarkiverse.permuplate.PermuteVar extra : permute.extraVars()) {
    if (!classNameTemplate.contains("${" + extra.varName() + "}")) {
        missingVars.add(extra.varName());
    }
}
if (!missingVars.isEmpty()) {
    for (String missing : missingVars) {
        error(String.format(
                "@Permute className \"%s\" never uses extraVars variable \"%s\" —" +
                        " every value of %s generates the same class name, producing duplicates;" +
                        " add ${%s} to className",
                permute.className(), missing, missing, missing),
                typeElement, permuteMirror, findAnnotationValue(permuteMirror, "extraVars"));
    }
    return;
}
```

Also add the import at the top of `PermuteProcessor.java`:
```java
import java.util.Set;
```

- [ ] **Remove the now-redundant leadingLiteral prefix check** (it's replaced by the full algorithm in Task 8; for now just ensure R1 and R1b work)

The existing `leadingLiteral` check still runs — leave it in place for now. Task 8 replaces it.

- [ ] **Build and run R1/R1b tests**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor -am -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="DegenerateInputTest#testClassName*,DegenerateInputTest#testExtraVars*" 2>&1 | grep -E "(Tests run|BUILD|FAIL)"
```

Expected: all R1/R1b tests pass

- [ ] **Run full suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | grep -E "(Tests run|BUILD)" | tail -5
```

Expected: all existing tests still pass

- [ ] **Commit**

```bash
git add permuplate-processor/ permuplate-tests/src
git commit -m "feat: R1 (className no variable) and R1b (extraVars absent from className) compile errors"
```

---

## Task 8: Replace prefix-only check with full algorithm in `PermuteDeclrTransformer` and `PermuteParamTransformer`

This task wires the new `validate()` call into the transformer validators, replacing the existing `checkPrefix` method's simple `startsWith` logic with full substring matching, orphan detection, and no-anchor detection.

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java`
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteParamTransformer.java`
- Modify: `permuplate-core/pom.xml` (add ide-support dependency)
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/OrphanVariableTest.java`

- [ ] **Add `permuplate-ide-support` to `permuplate-core/pom.xml`**

```xml
<dependency>
    <groupId>io.quarkiverse.permuplate</groupId>
    <artifactId>quarkus-permuplate-ide-support</artifactId>
</dependency>
```

- [ ] **Write OrphanVariableTest — all cases listed in spec**

Create `permuplate-tests/src/test/java/io/quarkiverse/permuplate/OrphanVariableTest.java`:

```java
package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.example.Callable2;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for the full annotation-string validation rules (R2, R3, R4) applied
 * via {@code PermuteDeclrTransformer} and {@code PermuteParamTransformer}.
 *
 * <p>
 * R1 tests live in {@link DegenerateInputTest}. Prefix-match tests
 * (the old rule, now a subset of R2) live in {@link PrefixValidationTest}.
 */
public class OrphanVariableTest {

    private static final String PKG = Callable2.class.getPackageName();
    private static final String PERMUTE_FQN = Permute.class.getName();
    private static final String PERMUTE_DECLR_FQN = PermuteDeclr.class.getName();
    private static final String PERMUTE_PARAM_FQN = PermuteParam.class.getName();

    private static Compilation compile(Class<?> anchor, String cls, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        anchor.getPackageName() + "." + cls, source));
    }

    // -------------------------------------------------------------------------
    // R2 — Unmatched literal (substring not found) on @PermuteDeclr
    // -------------------------------------------------------------------------

    @Test
    public void testR2_multipleLiterals_secondNotFound_isError() {
        // "Async${i}Cache" on AsyncDiskHandler2: "Async" found, "Cache" not found after it
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Async${i}Cache", name = "c${i}") Object asyncDiskHandler2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Cache");
    }

    @Test
    public void testR2_multipleLiterals_bothFound_noError() {
        // "Async${i}Handler" on AsyncDiskHandler2: both found in order
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Async${i}Handler", name = "c${i}") Object asyncDiskHandler2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).succeeded();
    }

    // -------------------------------------------------------------------------
    // R3 — Orphan variable
    // -------------------------------------------------------------------------

    @Test
    public void testR3_orphanVariableAtStart_isError() {
        // "${v1}Callable${v2}" on Callable2: prefix before "Callable" is "" → v1 orphan
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "${v1}Callable${v2}", name = "c${i}") Object callable2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("v1");
    }

    @Test
    public void testR3_notOrphan_nonEmptyPrefix_noError() {
        // "${v1}Callable${v2}" on MyCallable2: prefix "My" non-empty → not orphan
        // Note: "${v1}" with no strings entry → might trigger R4 if no literals? No, "Callable" IS a literal.
        // And v1 corresponds to "My" → not orphan. Should pass.
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "${v1}Callable${v2}", name = "c${i}") Object myCallable2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).succeeded();
    }

    @Test
    public void testR3_r2ShortCircuits_onlyR2ErrorReported() {
        // "${v1}Foo${v2}" on Callable2: "Foo" not in "Callable2" → R2 fires; no orphan error
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "${v1}Foo${v2}", name = "c${i}") Object callable2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Foo");
        // v1 should NOT be mentioned as orphan — R2 short-circuited R3
        assertThat(compilation.errors().stream()
                .noneMatch(e -> e.getMessage(null).contains("v1")
                        && e.getMessage(null).contains("orphan")))
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // R4 — No anchor
    // -------------------------------------------------------------------------

    @Test
    public void testR4_pureVariables_isError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "${v1}${v2}", name = "c${i}") Object callable2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        // R4: no anchor error
        assertThat(compilation).hadErrorContaining("anchor");
    }

    @Test
    public void testR4_stringConstantProducesAnchor_noError() {
        // "${prefix}${i}" with strings={"prefix=Callable"} → expands to "Callable${i}" → valid
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}",
                                 strings = {"prefix=Callable"})
                        public class Foo2 {
                            private @PermuteDeclr(type = "${prefix}${i}", name = "c${i}") Object callable2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).succeeded();
    }
}
```

- [ ] **Run to confirm failures**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=OrphanVariableTest 2>&1 | grep -E "(Tests run|FAIL|BUILD)"
```

Expected: several failures (R2 substring, R3, R4 not yet implemented in transformers)

- [ ] **Replace `checkPrefix` in `PermuteDeclrTransformer` with algorithm calls**

In `PermuteDeclrTransformer.java`, read the file first. Replace the `checkPrefix` method and all its call sites.

Add imports:
```java
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import io.quarkiverse.permuplate.ide.ValidationError;
import java.util.List;
import java.util.Map;
```

Replace the entire `checkPrefix` private method with:

```java
private static boolean checkAnnotationString(String annotationAttr, String template,
        String targetDesc, String actual, Messager messager, Element element,
        Map<String, String> stringConstants) {
    AnnotationStringTemplate t = AnnotationStringAlgorithm.expandStringConstants(
            AnnotationStringAlgorithm.parse(template), stringConstants);

    List<ValidationError> errors = AnnotationStringAlgorithm.validate(t, actual, stringConstants);
    if (errors.isEmpty()) return true;

    for (ValidationError err : errors) {
        String msg = switch (err.kind()) {
            case UNMATCHED_LITERAL ->
                String.format("%s literal \"%s\" does not match any substring of %s \"%s\"",
                        annotationAttr, err.suggestion().contains("\"")
                                ? err.suggestion().replaceAll(".*\"(.*?)\".*", "$1") : template,
                        targetDesc, actual);
            case ORPHAN_VARIABLE ->
                String.format("%s: variable ${%s} has no corresponding text in \"%s\" — %s",
                        annotationAttr, err.varName(), actual, err.suggestion());
            case NO_ANCHOR ->
                String.format("%s string has no static literal to match against \"%s\" — %s",
                        annotationAttr, actual, err.suggestion());
            case NO_VARIABLES ->
                String.format("%s \"%s\" contains no variables — it will generate the same %s" +
                        " for every permutation", annotationAttr, template, targetDesc);
        };
        if (messager != null) messager.printMessage(Diagnostic.Kind.ERROR, msg, element);
    }
    return false;
}
```

Update all calls from `checkPrefix("@PermuteDeclr type", params[0], "field type", ...)` to `checkAnnotationString("@PermuteDeclr type", params[0], "field type", ..., Map.of())`.

The signature change: the last argument is `Map<String, String> stringConstants` — pass `Map.of()` for now (string constants will be threaded through in a follow-up if needed; for the tests, no string constants are used).

- [ ] **Similarly update `PermuteParamTransformer.validatePrefixes`**

Replace the `namePrefix`/`actualName.startsWith()` check with `checkAnnotationString` (the same new method, copy it into `PermuteParamTransformer` or extract to a shared utility).

For simplicity, add a package-private static method `checkAnnotationString` to `PermuteDeclrTransformer` and call it from `PermuteParamTransformer`.

- [ ] **Build and run OrphanVariableTest**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-core -am -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=OrphanVariableTest 2>&1 | grep -E "(Tests run|BUILD|FAIL)"
```

Expected: all OrphanVariableTest tests pass

- [ ] **Run full suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | grep -E "(Tests run|BUILD)" | tail -5
```

Expected: all pass (the two `@Ignored` adjacent-variable tests are skipped)

- [ ] **Commit**

```bash
git add permuplate-core/ permuplate-processor/ permuplate-tests/src
git commit -m "feat: wire validate() into transformers — R2 substring, R3 orphan, R4 no-anchor"
```

---

## Task 9: Replace leading-literal className check with algorithm in `PermuteProcessor`

The existing `leadingLiteral` prefix check for `className` is now replaced with the full substring algorithm (R2 applied to `className` vs template class name).

**Files:**
- Modify: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

- [ ] **Replace the `leadingLiteral` block**

In `processTypePermutation`, find the block:
```java
String leadingLiteral = permute.className().contains("${")
        ? permute.className().substring(0, permute.className().indexOf("${"))
        : permute.className();
String templateSimpleName = typeElement.getSimpleName().toString();
if (!leadingLiteral.isEmpty() && !templateSimpleName.startsWith(leadingLiteral)) {
    error(...);
    return;
}
```

Replace with:

```java
// R2 for className: all static literals must be substrings of the template class name
String templateSimpleName = typeElement.getSimpleName().toString();
AnnotationStringTemplate classNameTemplate =
        AnnotationStringAlgorithm.parse(permute.className());
if (!classNameTemplate.hasNoLiteral()
        && !AnnotationStringAlgorithm.matches(classNameTemplate, templateSimpleName)) {
    error(String.format(
            "@Permute className \"%s\" has a literal that does not appear in the template" +
                    " class name \"%s\" — the className expression must reference the" +
                    " template class name",
            permute.className(), templateSimpleName),
            typeElement, permuteMirror, findAnnotationValue(permuteMirror, "className"));
    return;
}
```

Add import:
```java
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
```

- [ ] **Verify existing className prefix tests still pass**

```bash
/opt/homebrew/bin/mvn install -pl permuplate-processor -am -q && \
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest="DegenerateInputTest#testClassName*,PrefixValidationTest" 2>&1 | grep -E "(Tests run|BUILD|FAIL)"
```

Expected: all pass (existing tests are a subset of the new rule)

- [ ] **Run full suite**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | grep -E "(Tests run|BUILD)" | tail -5
```

Expected: all tests pass, BUILD SUCCESS

- [ ] **Commit**

```bash
git add permuplate-processor/
git commit -m "feat: replace leading-literal className check with full substring algorithm (R2)"
```

---

## Task 10: Documentation updates

**Files:**
- Modify: `README.md`
- Modify: `OVERVIEW.md`
- Modify: `CLAUDE.md`

- [ ] **Update CLAUDE.md non-obvious decisions table**

Add these rows:

```markdown
| Annotation string validation | All annotation string attributes (`@PermuteDeclr type/name`, `@PermuteParam name`, `@Permute className`) are validated using `AnnotationStringAlgorithm.validate()` from `permuplate-ide-support`. The old `checkPrefix` (leading-literal + `startsWith`) is replaced by full substring matching. |
| R1 applies only to @Permute.className | Inner annotations (`@PermuteDeclr`, `@PermuteParam`) may have attributes with no variable (e.g. `type = "Object"`) when the type genuinely does not vary. R1 (no variables error) is enforced only for `@Permute.className` by `PermuteProcessor`, not by the transformers. |
| R2 short-circuits R3/R4 | If any static literal is not found as a substring of the target name, orphan variable (R3) and no-anchor (R4) checks are skipped for that string — the orphan computation is undefined when the literal isn't found. |
| Adjacent variables are collective | `${v1}${v2}Callable${v3}` — the variables before "Callable" collectively cover the prefix region. Orphan detection applies to the region as a whole, not per-variable. If the prefix is non-empty, neither is orphan. |
```

Also update the module layout to include `permuplate-ide-support`.

- [ ] **Update OVERVIEW.md module structure**

Add `permuplate-ide-support` to the module tree listing with description: `— annotation string algorithm (matching, rename, validation); no IDE deps`.

- [ ] **Update Testing Strategy table in OVERVIEW.md**

Add row: `| OrphanVariableTest | R2 (substring matching), R3 (orphan variable — single, adjacent, non-orphan), R4 (no anchor), R2 short-circuits R3/R4 |`

- [ ] **Run full build**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests 2>&1 | grep -E "(Tests run|BUILD)" | tail -3
```

Expected: BUILD SUCCESS, all tests pass

- [ ] **Final commit**

```bash
git add README.md OVERVIEW.md CLAUDE.md
git commit -m "docs: document permuplate-ide-support, validation rules, and algorithm design"
git push origin main
```

---

## Self-review

**Spec coverage:**
- ✅ `permuplate-ide-support` module created with `parse()`, `expandStringConstants()`, `matches()`, `computeRename()`, `validate()`
- ✅ All core types: `AnnotationStringPart`, `AnnotationStringTemplate`, `RenameResult` (Updated/NoMatch/NeedsDisambiguation), `ValidationError` (NO_VARIABLES/UNMATCHED_LITERAL/ORPHAN_VARIABLE/NO_ANCHOR)
- ✅ R1: className no variable — PermuteProcessor, catches from==to case
- ✅ R1b: extraVars variable absent from className — PermuteProcessor
- ✅ R2: substring matching replaces prefix-only check — PermuteDeclrTransformer, PermuteParamTransformer, PermuteProcessor className
- ✅ R3: orphan variable — collective for adjacent variables
- ✅ R4: no anchor after expansion
- ✅ R2 short-circuits R3/R4
- ✅ NeedsDisambiguation: when prefix/suffix also change during rename
- ✅ Test coverage: parse, expand, match, rename, validate, OrphanVariableTest, DegenerateInputTest R1/R1b additions
- ✅ Two @Ignored forward tests in PrefixValidationTest document adjacent-variable semantics pending Sub-project 1

**Placeholder scan:** None found.

**Type consistency:** `AnnotationStringAlgorithm` used consistently throughout. `RenameResult.NeedsDisambiguation` introduced in Task 5, referenced in Task 5 tests and spec for IDE plugins. `ValidationError.ErrorKind` defined in Task 2, used in Task 6. All consistent.
