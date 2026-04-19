# Drools DSL Improvements — Design Spec

**Goal:** Compact the Drools DSL sandbox by maximising existing Permuplate annotations and extending the annotation engine where gaps show up. Result: less boilerplate, no reflection workarounds, and a set of new Permuplate features validated against a real consumer.

**Scope:** Nine items in dependency order. Items C1 and C2 are engine changes; all others are DSL-side changes enabled by them.

**Working directory:** `/Users/mdproctor/claude/permuplate`  
**DSL templates:** `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/`

---

## Item C1 — `@PermuteBody` inner-variable access in `@PermuteMethod` context

### Problem

`@PermuteMethod` already processes `@PermuteDeclr` and `@PermuteParam` on each clone with the inner context (including the method variable, e.g. `j` or `n`). `@PermuteBody` is not processed this way — it runs later with the outer context only. This means a body template like `"{ return new RuleOOPathBuilder.Path${n}<>(...); }"` cannot substitute `n` even though `n` is the `@PermuteMethod` variable.

### Fix

In `InlineGenerator.applyPermuteMethod()` (Maven plugin) and the equivalent APT path in `PermuteProcessor`, after processing `@PermuteDeclr` on each clone, also apply `PermuteBodyTransformer.transform(clone, innerCtx)`. This means `@PermuteBody` on a `@PermuteMethod`-annotated template method has access to the inner loop variable.

**File:** `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`  
**Method:** `applyPermuteMethod()` — add `PermuteBodyTransformer.transform(clone, innerCtx)` after the existing `PermuteDeclrTransformer` call on each clone.

Also apply in APT: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` — the `@PermuteMethod` handling loop.

### Test

New test in `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodTest.java`:  
Template with `@PermuteMethod(varName="n", from="2", to="3", name="method${n}")` + `@PermuteBody(body="{ return ${n}; }")`. Assert generated `method2()` returns `2` and `method3()` returns `3`.

### Acceptance

`@PermuteBody` on a `@PermuteMethod` template method evaluates the body template with the inner method variable available. Existing `@PermuteBody` tests still pass.

---

## Item B1 — `path2`..`path6` collapsed to one `@PermuteMethod` template

### Problem

Five nearly identical methods in `JoinBuilder.Join0Second` (path depth 2–6, ~10 lines each). Only differ in the path depth `n`.

### Fix

Replace all five with a single template method using `@PermuteMethod(varName="n", from="2", to="6", name="path${n}")`. The `@PermuteBody` template uses `${n}` to construct the correct `RuleOOPathBuilder.Path${n}` constructor call. `@PermuteTypeParam to="${i+n-1}"` and `@PermuteReturn className="RuleOOPathBuilder.Path${n}"` use `n` from the method context (enabled by C1).

**File:** `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java`

Replace the five `path2()`…`path6()` methods with:

```java
@PermuteMethod(varName = "n", from = "2", to = "6", name = "path${n}")
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+n-1}", name = "${alpha(m)}")
@PermuteReturn(
    className = "RuleOOPathBuilder.Path${n}",
    typeArgs = "'Join'+(i+1)+'First<END, DS, '+typeArgList(1,i,'alpha')+', BaseTuple.Tuple'+n+'<'+typeArgList(i,i+n-1,'alpha')+'>>, BaseTuple.Tuple'+(n-1)+'<'+typeArgList(i,i+n-2,'alpha')+'>, '+typeArgList(i,i+n-1,'alpha')",
    when = "i < 6")
@PermuteBody(body = "{ java.util.List<OOPathStep> steps = new java.util.ArrayList<>(); Object nextJoin = new @PermuteDeclr(type=\"Join${i+1}First\") Join1First<>(end(), rd); return cast(new RuleOOPathBuilder.Path${n}<>(nextJoin, rd, steps, rd.factArity() - 1)); }")
@SuppressWarnings("unchecked")
public <B> Object pathTemplate() { return null; }
```

Note: `RuleOOPathBuilder.Path${n}<>()` in the body string is evaluated by JEXL before StaticJavaParser sees it, so `Path2`/`Path3`/etc. appear as valid Java.

### Acceptance

Generated `Join1Second`..`Join6Second` each have `path2()`..`path6()` (up to `when="i<6"` boundary). All existing OOPath tests pass. Line count in `JoinBuilder.java` reduced by ~35 lines.

---

## Item B3 — `not()` and `exists()` collapsed

### Problem

Two structurally identical methods in `JoinBuilder.Join0Second` differ only in class name (`NegationScope`/`ExistenceScope`) and the `RuleDefinition` method called (`addNegation`/`addExistence`).

### Fix

Replace with a single template method using `@PermuteMethod` over a string set (`values={"not","exists"}`... but `@PermuteMethod` currently only supports integer ranges.

**Alternative (no new feature needed):** Use `@PermuteBody` with `@PermuteMethod(varName="k", from="1", to="2")` where k=1→Negation, k=2→Existence, mapping via a JEXL conditional expression:

```java
@PermuteMethod(varName = "k", from = "1", to = "2",
    name = "${k == 1 ? 'not' : 'exists'}")
@PermuteReturn(className = "${k == 1 ? 'NegationScope' : 'ExistenceScope'}",
    typeArgs = "'Join'+i+'Second<END, DS, '+typeArgList(1,i,'alpha')+'>, DS'",
    when = "true")
@PermuteBody(body = "{ RuleDefinition<DS> scopeRd = new RuleDefinition<>(\"${k==1?'not':'exists'}-scope\"); rd.${k==1?'addNegation':'addExistence'}(scopeRd); return new ${k==1?'Negation':'Existence'}Scope<>(this, scopeRd); }")
public Object scopeTemplate() { return null; }
```

This depends on C1 (body needs `k`). If the JEXL ternary in `name=""` is too fragile, keep the two methods as-is and skip this item.

### Acceptance

If feasible: `not()` and `exists()` generated correctly in all Join classes. Saves ~15 lines. If ternary approach proves unreadable, document as skipped with rationale.

---

## Item C2 — `@PermuteDeclr TYPE_USE` on qualified names

### Problem

`@PermuteDeclr TYPE_USE` on a constructor call like `new @PermuteDeclr(type="JoinBuilder.Join${n}First") JoinBuilder.Join1First<>()` places the annotation on the scope type (`JoinBuilder`), not the full name. The transformer currently has a workaround that checks the scope's annotations, but it does not reconstruct the full qualified name. This causes 4 reflection blocks in the DSL (joinBilinear, extensionPoint, extendsRule ×2).

### Fix

In `PermuteDeclrTransformer.transformNewExpressions()`, when a `ObjectCreationExpr` has a scoped type (e.g. `JoinBuilder.Join1First`), check both the type's own annotations AND `type.getScope().getAnnotations()`. When the scope annotation contains a `type` that includes a `.` (qualified name), reconstruct by splitting on the last `.` — left part is the scope, right part is the simple name — and set both `type.getName()` and `type.getScope()` appropriately.

**File:** `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java`

### Test

New test in `PermuteDeclrTest.java`: template with `new @PermuteDeclr(type="Outer.Inner${i}") Outer.Inner1<>()`. Assert generated class has `new Outer.Inner2<>()` (not `new Outer.Inner1<>()`).

### Acceptance

Qualified name TYPE_USE works. Existing tests pass.

---

## Item B2 — Eliminate reflection blocks using qualified TYPE_USE

### Problem (DSL-side, depends on C2)

Four reflection blocks in the DSL replace what should be `@PermuteDeclr TYPE_USE` on qualified names.

### Fix

After C2 lands, update `JoinBuilder.java`, `RuleBuilder.java`, `ParametersFirst.java`:

**`joinBilinear()`** — replace ~8 lines of reflection with:
```java
return cast(new @PermuteDeclr(type = "JoinBuilder.Join${i+j}First") JoinBuilder.Join1First<>(end(), rd));
```

**`extensionPoint()`** — replace ~8 lines of reflection with:
```java
return cast(new @PermuteDeclr(type = "RuleExtendsPoint.RuleExtendsPoint${i+1}") RuleExtendsPoint.RuleExtendsPoint2<>(rd));
```

**`extendsRule()` (both files)** — replace ~8 lines of reflection with:
```java
return cast(new @PermuteDeclr(type = "JoinBuilder.Join${j-1}First") JoinBuilder.Join1First<>(null, child));
```

### Acceptance

All four reflection blocks removed. DSL still compiles and all tests pass. Reflection-free codebase for these patterns.

---

## Item A1 — `BaseTuple get()/set()` use `@PermuteBody`

### Problem

`BaseTuple.Tuple1.get()` and `set()` use `@PermuteConst` to introduce a named local variable for the index comparison. `@PermuteBody` (already available) is cleaner.

### Fix

Replace both methods in `BaseTuple.java`:

```java
// get():
@PermuteBody(body = "{ if (index == ${i-1}) return unchecked(${lower(i)}); return super.get(index); }")
@Override public <T> T get(int index) { return super.get(index); }

// set():
@PermuteBody(body = "{ if (index == ${i-1}) { ${lower(i)} = unchecked(t); return; } super.set(index, t); }")
@Override public <T> void set(int index, T t) { super.set(index, t); }
```

Removes `@PermuteConst` import and usage from `BaseTuple.java`.

### Acceptance

`BaseTuple.Tuple2`..`Tuple6` generated correctly. Existing OOPath tests pass.

---

## Item A2 — `extendsRule()` shared abstract base

### Problem

`extendsRule()` is duplicated between `RuleBuilderTemplate` and `ParametersFirstTemplate`. The bodies differ by one string: `"extends"` vs `name` (the named-rule name field).

### Fix

Introduce `AbstractRuleEntry<DS>` as a common abstract base with:
- Abstract method `String ruleName()`
- The `extendsRule()` template method (using `ruleName()` to create the `RuleDefinition`)
- The `cast()` helper

`RuleBuilderTemplate` extends `AbstractRuleEntry<DS>` and implements `ruleName()` returning `"extends"`.  
`ParametersFirstTemplate` extends `AbstractRuleEntry<DS>` and implements `ruleName()` returning `name`.

Both are inline templates — the base class is a regular (non-template) class in `src/main/java/`.

**New file:** `permuplate-mvn-examples/src/main/java/.../drools/AbstractRuleEntry.java`

### Acceptance

`extendsRule()` removed from both template files. `AbstractRuleEntry` holds the single implementation. Existing rule-extension tests pass.

---

## Item C3 — `alwaysEmit` attribute on `@PermuteReturn`

### Problem

`when="true"` appears 12 times in the DSL purely to opt out of boundary omission. The intent (never omit this method regardless of boundary) is not obvious from the string `"true"`.

### Fix

Add `boolean alwaysEmit() default false` to the `@PermuteReturn` annotation. When `alwaysEmit=true`, boundary omission is skipped — equivalent to `when="true"` but self-documenting. The transformer checks `alwaysEmit` first; if true, skip the boundary check. `when` continues to work for backward compatibility.

**Files:**
- `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturn.java` — add `alwaysEmit()`
- `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` — check `alwaysEmit` in `@PermuteReturn` handling
- `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` — same

### DSL update

Replace all `when="true"` in `JoinBuilder.java`, `RuleBuilder.java`, `ParametersFirst.java` with `alwaysEmit=true`.

### Acceptance

All 12 `when="true"` occurrences replaced. Existing `when="some-expression"` usages unaffected.

---

## Item C4 — `capitalize()` and `decapitalize()` JEXL functions

### Problem

String-set permutations (`values={"Negation","Existence"}`) need case-manipulation to derive method names and class names from the same variable. Currently no JEXL function exists for this.

### Fix

Add two functions to `EvaluationContext.PermuplateStringFunctions`:
- `capitalize(String s)` — uppercases first character: `"negation"` → `"Negation"`
- `decapitalize(String s)` — lowercases first character: `"Negation"` → `"negation"`

Also register as JEXL lambdas in `JEXL_FUNCTIONS` (same pattern as `alpha` and `lower`).

**File:** `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/EvaluationContext.java`

### Acceptance

`${capitalize('hello')}` evaluates to `"Hello"`. `${decapitalize('Hello')}` evaluates to `"hello"`. Tested in `EvaluationContext` unit test or inline in a `PermuteTest` compilation test.

---

## Dependency graph

```
C1 ──► B1
C1 ──► B3
C2 ──► B2
A1 (independent)
A2 (independent)
C3 (independent)
C4 (independent)
```

## Implementation order

1. C1 — engine: `@PermuteBody` in `@PermuteMethod` context
2. B1 — DSL: path methods collapsed
3. B3 — DSL: not/exists collapsed (skip if ternary approach proves unreadable)
4. C2 — engine: qualified TYPE_USE
5. B2 — DSL: eliminate reflection blocks
6. A1 — DSL: BaseTuple get/set with @PermuteBody
7. A2 — DSL: extendsRule shared base
8. C3 — engine: alwaysEmit on @PermuteReturn
9. C4 — engine: capitalize/decapitalize JEXL functions

---

## Out of scope

- C5 (typeArgs macro system) — complexity vs value ratio too high
- C6 (`@PermuteVar` string-set axis) — requires broader @PermuteVar rework; park for later
- Consumer1/Predicate1 merge — method return types differ (void vs boolean); not achievable cleanly
