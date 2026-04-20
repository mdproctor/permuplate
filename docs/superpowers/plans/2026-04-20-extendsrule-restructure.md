# extendsRule() Restructure + ADR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply `@PermuteSelf` (from the `@PermuteSelf` plan, closes #80) and `@PermuteDefaultReturn` (from the `@PermuteDefaultReturn` plan, closes #82) to reduce annotation verbosity in `RuleBuilder.java` and `ParametersFirst.java`. Create ADR-0005 documenting why `extendsRule()` cannot be fully deduplicated across these two entry-point classes even with string-set `@PermuteMethod`.

**Architecture:** Structural refactor of existing DSL templates using newly available annotations. ADR saved to `docs/adr/`.

**Prerequisites:** Plans for closes #80 (`@PermuteSelf`) and closes #82 (`@PermuteDefaultReturn`) must be implemented first.

**Epic:** #79

**Tech Stack:** Java 17, JavaParser 3.28.0, Maven.

---

## File Structure

| Action | Path | What changes |
|---|---|---|
| Modify | `permuplate-mvn-examples/src/main/permuplate/.../drools/RuleBuilder.java` | Apply @PermuteSelf / @PermuteDefaultReturn |
| Modify | `permuplate-mvn-examples/src/main/permuplate/.../drools/ParametersFirst.java` | Apply @PermuteSelf / @PermuteDefaultReturn |
| Create | `docs/adr/ADR-0005-extendsrule-duplication.md` | Architecture decision record |

---

### Task 1: Understand the current state of the templates

- [ ] **Step 1: Read `RuleBuilder.java` and find self-returning methods**

```bash
grep -n "return this\|@PermuteReturn\|@PermuteSelf\|public Object\|extendsRule" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleBuilder.java \
    | head -40
```

Identify:
- How many methods use `return this;`
- Whether they all share the same `@PermuteReturn(className=..., typeArgs=..., alwaysEmit=true)` pattern
- The `extendsRule()` method — what it does and how it differs from `ParametersFirst.extendsRule()`

- [ ] **Step 2: Read `ParametersFirst.java` and find self-returning methods**

```bash
grep -n "return this\|@PermuteReturn\|@PermuteSelf\|public Object\|extendsRule\|ruleName" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/ParametersFirst.java \
    | head -40
```

- [ ] **Step 3: Determine whether `@PermuteDefaultReturn` or `@PermuteSelf` is the right fit**

Decision rule:
- If all (or most) `Object`-returning methods in the template class are self-returning (same `className`, same `typeArgs`) — use `@PermuteDefaultReturn` at the class level.
- If only a handful of methods are self-returning — use `@PermuteSelf` per-method.
- If the inference from Plan B (self-return inference) already fires for these methods without any annotation — no change needed; verify this first.

```bash
# Count Object-returning methods in RuleBuilder template
grep -c "public Object" \
    /Users/mdproctor/claude/permuplate/permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleBuilder.java
```

---

### Task 2: Apply annotation reduction to `RuleBuilder.java`

Proceed based on the analysis from Task 1.

**Case A: All Object-returning methods return self with the same class/typeArgs expression.**

Add `@PermuteDefaultReturn` at the class level on the template class. Remove per-method `@PermuteReturn` from all self-returning methods.

Example (adapt to actual class structure):
```java
@Permute(varName="i", from="2", to="6", className="RuleBuilder${i}", inline=true, keepTemplate=false)
@PermuteDefaultReturn(className="RuleBuilder${i}", typeArgs="typeArgList(1, i, 'T')", alwaysEmit=true)
static class RuleBuilderTemplate<T1, T2, ...> {
    // All Object-returning methods automatically get RuleBuilder${i}<T1,...,Ti> return type
    public Object param(String name, T1 value) { return this; }
    // ...

    // extendsRule() differs — it creates a new type, not returns self:
    @PermuteReturn(className="ExtendsRuleBuilder${i+1}", typeArgs="...", alwaysEmit=true)
    public Object extendsRule() { ... }
}
```

**Case B: Only a few methods return self.**

Apply `@PermuteSelf` per-method:
```java
@PermuteSelf
public Object param(String name, T1 value) { return this; }
```

- [ ] **Step 1: Apply the chosen pattern to `RuleBuilder.java`**

Make the changes identified in Task 1.

- [ ] **Step 2: Build and verify**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

---

### Task 3: Apply annotation reduction to `ParametersFirst.java`

- [ ] **Step 1: Apply the chosen pattern to `ParametersFirst.java`**

Same analysis as Task 2 but for `ParametersFirst`. The template class likely has a similar set of self-returning `param()`, `list()`, `map()`, etc. methods.

- [ ] **Step 2: Build and verify**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run all DSL tests**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-mvn-examples -q 2>&1 | tail -5
```

Expected: all tests pass.

---

### Task 4: Create ADR-0005

- [ ] **Step 1: Check existing ADRs for numbering**

```bash
ls /Users/mdproctor/claude/permuplate/docs/adr/
```

Confirm the next ADR number is 0005 (or adjust if higher-numbered ADRs exist).

- [ ] **Step 2: Create `ADR-0005-extendsrule-duplication.md`**

Create `/Users/mdproctor/claude/permuplate/docs/adr/ADR-0005-extendsrule-duplication.md`:

```markdown
# ADR-0005: extendsRule() Duplication in RuleBuilder and ParametersFirst

**Status:** Accepted

**Date:** 2026-04-20

**Epic:** #79 (annotation reduction)

---

## Context

`extendsRule()` appears in both `RuleBuilderTemplate` and the `ParametersFirst` template,
and the two copies are nearly identical. They differ only in one detail: `ruleName()` returns
a fixed string `"extends"` vs. the rule's own name field. The question arose whether to
deduplicate them using `@PermuteMethod(values={"extends","name"})` or by factoring into an
abstract base class.

---

## Why full deduplication is impossible

### Option 1: `@PermuteMethod(values={...})` on a shared template

This would require a single method body that covers both the `extendsRule()` variant (which
calls `ruleName()` returning `"extends"`) and a hypothetical `nameRule()` variant. But:

1. The method NAME in both classes is `extendsRule` — `@PermuteMethod` with `values` renames
   the method, not the ruleName logic. No Permuplate mechanism can generate two *different
   bodies* for the same method across two different template classes in one template.

2. `@PermuteMethod` on a base class is not processed by Permuplate's inline generation
   pipeline — only methods directly on the annotated template class are processed.

### Option 2: Shared abstract base class

A shared base class or interface with a `default extendsRule()` impl would require all
generated classes (`RuleBuilder2..6`, `ParametersFirst2..6`) to extend or implement it.
This conflicts with the generated classes already implementing other interfaces
(`JoinBuilderFirst`, etc.) and potentially violating the single-inheritance rule for classes.

### Option 3: Accept the duplication

The body is ~8 lines. The behavioral difference (`ruleName()` return value) is delegated to
an abstract/overridden method, making the duplication self-contained and easily understood.

---

## Decision

Accept the duplication. The `extendsRule()` method body in each entry-point template is
short, self-contained, and differs only through the `ruleName()` virtual dispatch. Annotating
the shared `ruleName()` difference with a comment is sufficient.

---

## Consequences

- When applying this Drools DSL pattern to the real Drools codebase, engineers should expect
  each entry-point class to carry its own `extendsRule()` copy.
- Future entry-point classes (e.g. a `ConditionFirst` entry point) will follow the same pattern.
- If a code-generation approach is ever desired, `@PermuteStatements` with a `strings=`
  set could replace the single differing line — but the added complexity exceeds the benefit
  for an 8-line method.

---

## Related

- Plans: `2026-04-20-permute-self.md`, `2026-04-20-permute-default-return.md`
- Closes: #85
```

---

### Task 5: Full build and commit

- [ ] **Step 1: Full clean build**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Commit**

```bash
git add \
    permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/RuleBuilder.java \
    permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/ParametersFirst.java \
    docs/adr/ADR-0005-extendsrule-duplication.md
git commit -m "refactor: apply @PermuteSelf/@PermuteDefaultReturn to entry points; add ADR-0005 for extendsRule duplication (closes #85)"
```
