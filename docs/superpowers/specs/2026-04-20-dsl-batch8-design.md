# Batch 8 — DSL Deep Polish: Spec

**Date:** 2026-04-20  
**Scope:** Ten targeted improvements to Permuplate core + Drools DSL, derived from a systematic audit  
of every annotation usage pattern in the DSL after batches 6–7.

---

## Motivation

After batches 6–7, the DSL stands at 851 lines with 277 tests passing. This batch is a final deep
polish: every remaining ternary, every hand-coded method that could be templated, every macro that
is defined twice, and the two structural limits (Path2 hand-coded, extendsRule duplication) that
could not be fixed without new Permuplate features.

Items are ordered from smallest to largest change. Each item has clear boundaries and can be
committed independently against its own issue.

---

## Item 1 — `not()`/`exists()` via `values={"not","exists"}` (DSL rename + existing feature)

### What
Rename `NegationScope` → `NotScope` and `ExistenceScope` → `ExistsScope`.  
Replace `k=1..2` with `@PermuteMethod(varName="scope", values={"not","exists"})`.

### Why
`@PermuteMethod(values=)` was shipped in an earlier batch but not applied here.  
With `capitalize("not")="Not"` and `capitalize("exists")="Exists"`, all three ternaries in the  
not/exists method vanish. The renamed class names (`NotScope`, `ExistsScope`) are shorter and match  
the method names.

### Before (in JoinBuilder.java)
```java
@PermuteMethod(varName="k", from="1", to="2",
               name="${k == 1 ? 'not' : 'exists'}")
@PermuteReturn(className="${k == 1 ? 'NegationScope' : 'ExistenceScope'}",
               typeArgs="'Join'+i+'Second<END, DS, '+alphaList+'>, DS'",
               alwaysEmit=true)
@PermuteBody(body="{ RuleDefinition<DS> scopeRd = new RuleDefinition<>(\"${k == 1 ? 'not-scope' : 'exists-scope'}\"); rd.${k == 1 ? 'addNegation' : 'addExistence'}(scopeRd); return new ${k == 1 ? 'Negation' : 'Existence'}Scope<>(this, scopeRd); }")
```

### After (in JoinBuilder.java)
```java
@PermuteMethod(varName="scope", values={"not","exists"}, name="${scope}")
@PermuteReturn(className="${capitalize(scope)}Scope",
               typeArgs="'Join'+i+'Second<END, DS, '+alphaList+'>, DS'",
               alwaysEmit=true)
@PermuteBody(body="{ RuleDefinition<DS> scopeRd = new RuleDefinition<>(\"${scope}-scope\"); rd.add${capitalize(scope)}(scopeRd); return new ${capitalize(scope)}Scope<>(this, scopeRd); }")
```

### Changes required
- `NegationScope.java`: rename class + template className + `@PermuteDeclr` references
- `JoinBuilder.java`: update the not/exists method as shown
- `RuleDefinition.java`: rename `addNegation` → `addNot`, `addExistence` → `addExists`
- All test files: no compile-time references to `NegationScope`/`ExistenceScope` by name

### Tests
- Existing not/exists tests in `RuleBuilderTest` must still pass
- No new tests needed beyond confirming zero regressions (the functionality is unchanged)

---

## Item 2 — `max(a,b)` / `min(a,b)` JEXL built-in functions

### What
Add `max(a,b)` and `min(a,b)` as built-in JEXL functions in `EvaluationContext`.

### Why
The `filterLatest` suppression at i=1 uses an awkward ternary:
```java
from="${i > 1 ? i : i+1}"   // means: from = max(2, i)
```
With `max()`: `from="${max(2,i)}"` — immediately readable. More broadly, clamping expressions
appear naturally in user templates and have no natural JEXL spelling without this.

### Implementation
In `EvaluationContext`: add `JEXL_MAX` and `JEXL_MIN` lambda scripts alongside `JEXL_ALPHA` etc.
```
max(a, b) => a >= b ? a : b
min(a, b) => a <= b ? a : b
```
Both take two arguments (Integer or Long — JEXL unboxes from Object). Register in `JEXL_FUNCTIONS` map.

### DSL change
`JoinBuilder.java`:
```java
// Before:
@PermuteMethod(varName="x", from="${i > 1 ? i : i+1}", to="${i}", name="filter")
// After:
@PermuteMethod(varName="x", from="${max(2,i)}", to="${i}", name="filter")
```

### Tests
- Unit test in `EvaluationContextTest` (or similar): `max(3,1)=3`, `min(3,1)=1`, `max(1,1)=1`
- Processor integration test: verify `filterLatest` suppresses correctly at i=1 with `max(2,i)`
- Existing `RuleBuilderTest` must still pass

---

## Item 3 — `typeArgList` custom prefix support

### What
Change `typeArgList(from, to, style)` so unknown styles are treated as literal prefixes + index,
instead of throwing `IllegalArgumentException`.

### Why
Currently: `typeArgList(1,3,'T')` → `T1, T2, T3` (works).  
Currently: `typeArgList(1,3,'V')` → throws.  
After: `typeArgList(1,3,'V')` → `V1, V2, V3`.  
After: `typeArgList(1,3,'v')` → `v1, v2, v3`.

This is the enabler for item 4 (variable filter templating).

### Implementation
In `EvaluationContext.JEXL_TYPE_ARG_LIST`:
```
// Change the else branch from:
else __throwHelper.throwFor(style);
// To:
else result = result + style + k;
```

The `'T'` style already works as "literal T + index". The new behavior makes all styles
other than `'alpha'` and `'lower'` work as literal prefix + index, with `'T'` being the
pre-existing special case of that rule.

`EvaluationContext.throwFor(String style)` and the Java-side `typeArgList` method also need
the same change (remove the throw, return prefix+index instead).

### Tests
- Unit test: `typeArgList(1,3,'V')` → `"V1, V2, V3"`
- Unit test: `typeArgList(1,2,'v')` → `"v1, v2"`
- Unit test: `typeArgList(2,4,'Param')` → `"Param2, Param3, Param4"`
- Existing tests using `'T'`, `'alpha'`, `'lower'` must be unaffected

---

## Item 4 — Variable filter overloads templated (depends on item 3)

### What
Replace two hand-coded methods in `Join0First` with a single `@PermuteMethod(m=2..3)` template.

### Why
These two methods are structurally identical, varying only by variable count:
```java
public <V1,V2> Object filter(Variable<V1> v1, Variable<V2> v2, Predicate3<DS,V1,V2> predicate)
public <V1,V2,V3> Object filter(Variable<V1> v1, Variable<V2> v2, Variable<V3> v3, Predicate4<DS,V1,V2,V3> predicate)
```

### After
```java
@PermuteMethod(varName="m", from="2", to="3", name="filter")
@PermuteSelf
@PermuteBody(body="{ rd.addVariableFilter(${typeArgList(1,m,'v')}, predicate); return this; }")
public <@PermuteTypeParam(varName="k", from="1", to="${m}", name="V${k}") V1> Object filterVar(
        @PermuteParam(varName="k", from="1", to="${m}", type="Variable<V${k}>", name="v${k}") Variable<V1> v1,
        @PermuteDeclr(type="Predicate${m+1}<DS, ${typeArgList(1,m,'V')}>") Object predicate) {
}
```

- `typeArgList(1,m,'V')` → `V1, V2` or `V1, V2, V3` (type params in `Predicate` type)
- `typeArgList(1,m,'v')` → `v1, v2` or `v1, v2, v3` (call args in body)
- G4 applies: `@PermuteTypeParam` on method type params inside `@PermuteMethod`
- `@PermuteSelf` sets return type to current generated class (same as other filter overloads)

### Tests
- Integration test: compile `Join3First` from template, verify presence of both overloads
- Verify `filter(Variable<V1>,Variable<V2>,Predicate3<DS,V1,V2>)` exists at all arities
- Verify `filter(Variable<V1>,Variable<V2>,Variable<V3>,Predicate4<DS,V1,V2,V3>)` exists at all arities
- Existing variable filter tests in `RuleBuilderTest` must still pass

---

## Item 5 — Method macros for bilinear join, extendsRule, OOPath

### What
Add `@PermuteMethod macros=` to three sites where repeated `typeArgList(...)` calls can be named.
No Permuplate core changes — all use the shipped `@PermuteMethod macros=` feature.

### Changes

**Bilinear join** (`join(JoinNSecond)` in `Join0Second`):
```java
@PermuteMethod(varName="j", from="1", name="join",
               macros={"joinAll=typeArgList(1,i+j,'alpha')", "joinRight=typeArgList(i+1,i+j,'alpha')"})
@PermuteReturn(className="Join${i+j}First", typeArgs="'END, DS, ' + joinAll")
// @PermuteDeclr: type="Join${j}Second<Void, DS, ${joinRight}>"
```

**extendsRule** (identical change in both `RuleBuilderTemplate` and `ParametersFirstTemplate`):
```java
@PermuteMethod(varName="j", from="2", to="7", name="extendsRule",
               macros={"prevAlpha=typeArgList(1,j-1,'alpha')"})
@PermuteReturn(className="JoinBuilder.Join${j-1}First", typeArgs="'Void, DS, ' + prevAlpha", alwaysEmit=true)
// @PermuteDeclr: type="RuleExtendsPoint.RuleExtendsPoint${j}<DS, ${prevAlpha}>"
```

**OOPath pathTemplate** (`pathTemplate()` in `Join0Second`):
```java
macros={"tail=typeArgList(i,i+n-1,'alpha')", "prev=typeArgList(i,i+n-2,'alpha')",
         "outerJoin='Join'+(i+1)+'First<END, DS, '+alphaList+', BaseTuple.Tuple'+n+'<'+tail+'>>'",
         "prevTuple='BaseTuple.Tuple'+(n-1)+'<'+prev+'>'"}
@PermuteReturn(className="RuleOOPathBuilder.Path${n}",
               typeArgs="outerJoin + ', ' + prevTuple + ', ' + tail",
               when="i < 6")
```

### Tests
- Build must succeed (existing tests pass) — the generated output is identical, only annotations change
- No new tests required; this is a refactoring of annotation expressions, not behavior

---

## Item 6 — `@PermuteMacros` shared file-level macros

### What
New `@PermuteMacros` annotation applicable to any outer class or interface. Macros defined here
are added to the JEXL context for all `@Permute` templates that are nested (direct or indirect)
within the annotated type. Macros are evaluated per-permutation with the current loop variables.

### Why
`alphaList=typeArgList(1,i,'alpha')` is defined identically in both `Join0Second` and `Join0First`
in `JoinBuilder.java`. There is no mechanism to define it once.

### Annotation definition
```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteMacros {
    String[] value();   // same format as @Permute.macros: "name=jexlExpr"
}
```

### Usage
```java
@PermuteMacros({"alphaList=typeArgList(1,i,'alpha')"})
public class JoinBuilder {
    @Permute(varName="i", from="1", to="6", className="Join${i}Second",
             inline=true, keepTemplate=false)  // no more macros= here
    public static class Join0Second<...> { ... }

    @Permute(varName="i", from="1", to="6", className="Join${i}First",
             inline=true, keepTemplate=false)  // no more macros= here
    public static class Join0First<...> { ... }
}
```

### Implementation
- `InlineGenerator`: when scanning a template class for `@Permute`, also scan its enclosing types
  (walking up `getParentNode()`) for `@PermuteMacros`. Collect all macros from the outer chain and
  prepend to the template's own macro list. Innermost macros take precedence (applied last, can
  shadow outer macros).
- `PermuteProcessor` (APT path): same walk, same prepend. Source-level `@PermuteMacros` is
  read via `getAnnotation()` or annotation mirrors.
- Macro name collision with `@Permute.macros=`: template's own macros take precedence over
  container macros (consistent with innermost-wins).

### Tests
- Unit test: template nested in class annotated with `@PermuteMacros` — macro is available in
  template expressions
- Test: template's own `macros=` shadows a container macro with the same name
- Integration test: `JoinBuilder` compiles with `@PermuteMacros` and both sub-templates use `alphaList`

---

## Item 7 — `@PermuteReturn(typeParam="X")` new attribute

### What
New attribute `String typeParam() default ""` on `@PermuteReturn`.  
When non-empty, the return type is set to the named type parameter (e.g., `END`), not a class.

### Why
`Path2.path()` returns the `END` type parameter. This is the only remaining reason Path2 cannot
be folded into the `Path3` template. With this attribute, Path2 becomes the i=2 case of a unified
`Path2..Path6` template.

### When-condition interaction
`typeParam=` participates in when-based selection alongside `className=`.  
Multiple `@PermuteReturn` with `when=` on the same method: the first one whose `when` evaluates
to `true` wins (same as existing behavior for `className` + `when`).

### Implementation
- Add `typeParam()` to `@PermuteReturn` annotation
- In `applyPermuteReturn` (InlineGenerator) and `applyPermuteReturnSimple`/`applyPermuteReturn`
  (PermuteProcessor): after evaluating `when`, if `typeParam` is non-empty, set the method's
  return type to `ClassOrInterfaceType` with the given name (no type args)
- Mutual exclusion: if both `typeParam` and `className` are non-empty on the same annotation,
  emit a compile error

### Usage (unified Path template)
```java
@Permute(varName="i", from="2", to="6", className="Path${i}",
         inline=true, keepTemplate=true)
public static class Path2<END, T extends BaseTuple, A, B,
        @PermuteTypeParam(varName="k", from="3", to="${i}", name="${alpha(k)}") C> {
    ...
    @PermuteReturn(when="${i==2}", typeParam="END")
    @PermuteReturn(when="${i>2}", className="RuleOOPathBuilder.Path${i-1}",
                   typeArgs="'END, T, '+typeArgList(2,i,'alpha')", alwaysEmit=true)
    @PermuteBody(when="${i==2}", body="{ steps.add(new OOPathStep(...)); rd.addOOPathPipeline(rootIndex, steps); return end; }")
    @PermuteBody(when="${i>2}", body="{ steps.add(new OOPathStep(...)); return new @PermuteDeclr(type=\"RuleOOPathBuilder.Path${i-1}\") Path2<>(end, rd, steps, rootIndex); }")
    public Object path(Function2<PathContext<T>, A, Iterable<B>> fn2,
                       Predicate2<PathContext<T>, B> flt2) { return null; }
}
```

This eliminates the `Path2` hand-written class (~22 lines), replacing it with the i=2 case of the
`Path2` template (which uses `keepTemplate=true`, so the template itself is the i=2 class).

### Tests
- Unit test: `@PermuteReturn(typeParam="END")` sets return type to `END`
- Integration test: unified Path template compiles; Path2 class has return type `END` on `path()`
- Existing OOPath tests in `RuleBuilderTest` must pass

---

## Item 8 — `@PermuteMixin` annotation — solve ADR-0006

### What
New annotation `@PermuteMixin(Class<?>[] value)`. When placed on a template class, the methods
from the listed mixin class(es) that carry Permuplate annotations are injected into the template's
AST before the transform pipeline runs. Injected methods participate fully in `@PermuteMethod`,
`@PermuteReturn`, etc.

### Why
`extendsRule()` is duplicated verbatim in `RuleBuilderTemplate` and `ParametersFirstTemplate`.
ADR-0006 documents this as structurally unavoidable with the current pipeline. `@PermuteMixin`
breaks that structural limit.

### Annotation definition
```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteMixin {
    Class<?>[] value();
}
```

### Usage
```java
// New file: ExtendsRuleMixin.java
class ExtendsRuleMixin<DS> {
    @PermuteMethod(varName="j", from="2", to="7", name="extendsRule",
                   macros={"prevAlpha=typeArgList(1,j-1,'alpha')"})
    @PermuteReturn(className="JoinBuilder.Join${j-1}First",
                   typeArgs="'Void, DS, ' + prevAlpha", alwaysEmit=true)
    public <@PermuteTypeParam(varName="k", from="1", to="${j-1}", name="${alpha(k)}") A>
            Object extendsRule(
            @PermuteDeclr(type="RuleExtendsPoint.RuleExtendsPoint${j}<DS, ${prevAlpha}>")
            ExtendsPoint<DS> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(ruleName());
        ep.baseRd().copyInto(child);
        return cast(new JoinBuilder.@PermuteDeclr(type="JoinBuilder.Join${j-1}First") Join1First<>(null, child));
    }
}

// RuleBuilderTemplate:
@Permute(...) @PermuteMixin(ExtendsRuleMixin.class)
public class RuleBuilderTemplate<DS> extends AbstractRuleEntry<DS> { ... }

// ParametersFirstTemplate:
@Permute(...) @PermuteMixin(ExtendsRuleMixin.class)
public class ParametersFirstTemplate<DS> extends AbstractRuleEntry<DS> { ... }
```

### Implementation
- At template processing time (both `InlineGenerator` and `PermuteProcessor`), detect `@PermuteMixin`
  on the template class
- For each mixin class reference: locate the mixin class's JavaParser `ClassOrInterfaceDeclaration`
  in the same source set (same compilation unit or already-parsed set)
- Clone the mixin class's method declarations and inject them into the template class AST
  before the transform pipeline runs
- Methods are injected with their full annotation set (including `@PermuteMethod`, `@PermuteReturn`, etc.)
- The mixin class itself is not added to the generated output (it is a source-only helper)
- Constraint: mixin class must be in the same compilation unit (same file or same Maven source root)
  as the template. Cross-module mixins are not supported in this version.

### Tests
- Unit test: mixin methods are injected and expanded correctly
- Integration test: `RuleBuilder` and `ParametersFirst` both get `extendsRule()` overloads from the mixin
- Integration test: existing `NamedRuleTest` and `ExtensionPointTest` must pass unchanged
- Test: mixin class itself does not appear in generated output

---

## Item 9 — Constructor super-call inference

### What
When a generated class extends the previous class in the same family AND has a constructor whose
parameters exactly equal "parent's params + one new param (the new field)", the processor auto-
inserts `super(a, b, ..., prevParam);` as the first constructor statement — eliminating the need
for `@PermuteStatements`.

### Trigger conditions (all must hold)
1. The template has `@PermuteExtends(className="X${i-1}", ...)` — extends previous in family
2. The template has a constructor whose parameter list = all but the last parameter of the
   full-arity constructor
3. No existing `@PermuteStatements` on that constructor (explicit annotation wins)

### Inference rule
Given condition 1: the parent class at i-1 has a full-args constructor with params `a..alpha(i-2)`.
Given condition 2: the current constructor has params `a..alpha(i-1)` (one more than parent).
Inference: prepend `super(a, b, ..., alpha(i-2));` — all args except the last.

The inference uses the **parameter names** from the constructor signature (which `@PermuteParam`
has already expanded at the point inference runs). It does NOT require knowledge of the parent
class's actual generated form — it simply omits the last parameter from the current constructor's
arg list.

### BaseTuple impact
`@PermuteStatements(position="first", body="super(${typeArgList(1,i-1,'lower')});")` on
`Tuple1`'s full-args constructor can be removed. Inference fires automatically.

### Tests
- Unit test: inference fires for the exact trigger conditions
- Unit test: inference does NOT fire when `@PermuteStatements` is explicitly present (explicit wins)
- Unit test: inference does NOT fire when the constructor param count doesn't follow the pattern
- Integration test: `BaseTuple` compiles without `@PermuteStatements`; generated `Tuple2..6` have
  correct `super()` calls
- Existing `TupleAsTest` must pass

---

## Item 10 — `@PermuteExtendsChain` shorthand annotation

### What
New annotation `@PermuteExtendsChain` — shorthand for the common pattern of extending the
previous class in a generated family with a shrinking alpha type arg list.

### Semantics
`@PermuteExtendsChain` on a template class implies:
- `extends PreviousClass<type args 1..i-1>` where `PreviousClass` is the same family as the
  current template at index `i-1`
- Type args: alpha style, `typeArgList(1, i-1, 'alpha')`
- This is exactly `@PermuteExtends(className="${familyName}${i-1}", typeArgs="typeArgList(1,i-1,'alpha')")`
  where `familyName` is inferred from the template's `className` pattern

### Family name inference
From `className="Tuple${i}"`, the family base is `Tuple`. The previous class is `Tuple${i-1}`.
The processor extracts the prefix before the first `${` to determine the family name.

### Usage
```java
// Before:
@Permute(varName="i", from="2", to="6", className="Tuple${i}", ...)
@PermuteExtends(className="Tuple${i-1}", typeArgs="typeArgList(1, i-1, 'alpha')")
public static class Tuple1<...> extends BaseTuple { ... }

// After:
@Permute(varName="i", from="2", to="6", className="Tuple${i}", ...)
@PermuteExtendsChain
public static class Tuple1<...> extends BaseTuple { ... }
```

### Implementation
- New `@PermuteExtendsChain` annotation in `permuplate-annotations`
- In `InlineGenerator` and `PermuteProcessor`: detect `@PermuteExtendsChain` before the extends
  expansion step; derive family base and i-1 class; apply as if `@PermuteExtends` were present
- If `@PermuteExtends` is already present, `@PermuteExtendsChain` is silently ignored (explicit wins)

### Tests
- Unit test: `@PermuteExtendsChain` on `Tuple1` generates `Tuple2 extends Tuple1<A>`, etc.
- Unit test: explicit `@PermuteExtends` takes precedence over `@PermuteExtendsChain`
- Integration test: `BaseTuple` compiles with `@PermuteExtendsChain`; generated classes extend correctly

---

## Documentation updates (apply throughout)

After each item:
1. Update `CLAUDE.md` non-obvious decisions table with any new decisions or inference rules
2. Update annotation table in `CLAUDE.md` for new annotations (`@PermuteMacros`, `@PermuteMixin`, `@PermuteExtendsChain`) and new attributes (`@PermuteReturn.typeParam`)
3. Update `OVERVIEW.md` annotation API detail section for each new/changed annotation
4. Update `docs/ROADMAP.md` — move completed items to "Completed" table
5. No stale content: remove references to `NegationScope`/`ExistenceScope` after item 1, Path2 hand-coded note after item 7, ADR-0006 "unavoidable" note after item 8

---

## Issue/Epic structure

One epic covers all 10 items. Each item is a separate GitHub issue under the epic.  
Each commit references its issue: `feat: <description> (closes #N)`.  
No item is merged until its tests pass and documentation is updated.

---

## Testing strategy

| Level | What | Tools |
|---|---|---|
| Unit | EvaluationContext (max/min/typeArgList), annotation attribute parsing | JUnit in `permuplate-tests` |
| Integration (compile-testing) | Each annotation/inference fires correctly; generated source contains expected code | Google compile-testing |
| End-to-end (Maven build) | Full Maven build of `permuplate-mvn-examples` succeeds with new DSL | `mvn clean install` |
| Regression | All 277 existing tests pass after each item | `mvn test` in `permuplate-tests` |
| DSL correctness | `RuleBuilderTest`, `NamedRuleTest`, `ExtensionPointTest`, `TupleAsTest` still pass | `mvn test` in `permuplate-mvn-examples` |

---

## Success criteria

- All 10 items shipped with tests at every level
- DSL line count further reduced from 851 (estimate: ≥100 additional lines removed)
- Zero regressions in 277 tests
- All docs updated; zero stale content
- All commits reference issues; all issues closed under the batch 8 epic
