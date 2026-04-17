# Drools DSL Template Improvements — Working Notes

Notes for a follow-up article after the next round of changes. All figures are
for the **sandbox** (`permuplate-mvn-examples`) not the actual droolsvol2 codebase.
See "Vol2 question" section at the bottom.

---

## Done

### RuleExtendsPoint (committed)
- **Before:** 88 lines, 6 hand-written inner classes (RuleExtendsPoint2..7)
- **Pattern:** identical 10-line bodies, only type parameter list grows
- **After:** 37-line template in `src/main/permuplate/`, generates RuleExtendsPoint3..7
- **Annotation used:** `@Permute(inline=true, keepTemplate=true)` + `@PermuteTypeParam`
- **Savings:** 51 lines (58% reduction)
- **Zero reference changes** — inner class names and outer class preserved

### BaseTuple (committed)
- **Before:** 422 lines total; 330 lines across inner Tuple1..6
- **Step 1 (prerequisite):** delegation-to-super refactor — 422 → 332 lines
  - Each Tuple2..6 `get()`/`set()` previously re-implemented the full if-chain
  - After: each class handles only its own index, delegates to `super`
  - Better design independent of templating
- **Step 2:** 5 hand-written inner classes (Tuple2..6) → 133-line template + outer class
- **Annotations used:** `@Permute`, `@PermuteTypeParam`, `@PermuteDeclr` (field + method),
  `@PermuteExtends`, `@PermuteStatements`, `@PermuteParam`, `@PermuteValue`, `@PermuteConst`
- **New feature required:** `@PermuteDeclr` on `ElementType.METHOD` — did not exist,
  added it (getA() → getB() requires renaming the method declaration, not just the field)
- **Framework fixes needed:**
  - `PermuteStatementsTransformer`: `super(a)` explicit constructor invocations couldn't
    be parsed as block statements; added single-statement fallback
  - `InlineGenerator.stripPermuteAnnotations()`: wasn't stripping constructor-level and
    local variable annotations from kept template class; fixed
- **Savings:** ~200 lines eliminated from hand-written Tuple variants
- **BaseTuple.get/set** changed from abstract to concrete (default-throw) — required so
  `return super.get(index)` is legal Java in template Tuple1

---

## Planned (sandbox, not yet started)

### NegationScope + ExistenceScope
- **Current:** 57 + 53 = 110 lines, structurally identical classes
- **Only difference:** class name and field name (`notRd` vs `existsRd`)
- **Approach:** `@Permute(values={"Negation","Existence"}, className="${T}Scope")`
  string-set permutation, single template generating both classes
- **Estimate:** ~60 lines → saves ~50 lines (45%)
- **Complexity:** low — no new features needed

### RuleOOPathBuilder (Path2..Path6)
- **Current:** 128 lines, 5 inner classes (Path2..Path6), each ~22 lines
- **Pattern:** same 4 fields, same 5-line constructor, same 8-line body;
  only type parameter count and return type differ per class
- **Approach:** `@Permute(inline=true, keepTemplate=true)` on Path2, `@PermuteExtends`
  for return type chain (Path3 returns Path2, Path4 returns Path3, etc.),
  `@PermuteTypeParam` for expanding type params
- **Estimate:** ~35 lines → saves ~93 lines (73%)
- **Complexity:** medium — same pattern as BaseTuple but simpler (no new features needed)
- **Note:** path2()..path6() methods in JoinBuilder template reference
  `RuleOOPathBuilder.Path${k}` — those JEXL strings would remain unchanged since
  the generated PathN classes keep the same names

### extendsRule() overloads — ParametersFirst + RuleBuilder
- **Current:** 6 overloads × ~6 lines each in two classes = ~76 lines
- **Pattern:** each overload takes `RuleExtendsPoint${n+1}` and returns `Join${n}First`,
  body is always the same 4 lines
- **Blocker:** `ep.baseRd()` — the parameter is typed as Object in the template,
  can't call baseRd() without a common interface
- **Required change:** add `interface ExtendsPoint<DS> { RuleDefinition<DS> baseRd(); }`
  and have all RuleExtendsPointN implement it — small but a real API change
- **Approach after interface added:** `@PermuteMethod(j=2..7)` on a single
  `extendsRule()` template method, `@PermuteReturn` for the JoinNFirst return type
- **Estimate:** ~25 lines (two classes combined) → saves ~51 lines (67%)
- **Complexity:** medium — ExtendsPoint interface needed; @PermuteMethod already works

### @PermuteSource Capability A — potential extension
- The extendsRule() type parameters (A, B, C...) are currently specified as JEXL
  string expressions (`typeArgList(1, j-1, 'alpha')`)
- If @PermuteSource were extended to work at **method level** inside @PermuteMethod,
  type params could be inferred from `RuleExtendsPoint${j}` automatically
- Not currently implemented — would make the template cleaner but is not blocking
- Worth noting for the article as "where @PermuteSource could evolve"

---

## Total savings (sandbox)

| Work | Status | Lines saved |
|---|---|---|
| RuleExtendsPoint | Done | ~51 |
| BaseTuple (step 1: delegation refactor) | Done | ~90 |
| BaseTuple (step 2: template) | Done | ~200 |
| NegationScope + ExistenceScope | Planned | ~50 |
| RuleOOPathBuilder | Planned | ~93 |
| extendsRule() overloads (×2 classes) | Planned | ~51 |
| **Total** | | **~535** |

Current hand-written infrastructure: ~1,500 lines. After all planned work: ~965 lines.
Template source (654 lines) drives 2,755 generated lines — 4.2× expansion.

---

## Vol2 question

All figures above are **sandbox only**. The sandbox was built by studying the vol2
*tests*, not the vol2 *source* — so implementation patterns may differ.

**Expected:** savings in vol2 would be larger, not smaller. Each Join class in vol2
likely has more methods per arity variant (more complete implementation), so the
lines-per-variant are higher and the arity family multiplier bites harder.

**Unknown until examined:**
- Whether vol2 already has code generation in place for its arity families
- Whether the class naming and structure matches the sandbox closely enough to
  apply the same templates
- Whether vol2 uses the same delegation pattern or re-implements if-chains

**Before writing the follow-up article:** read the actual vol2 source for at least
one arity family (e.g., its Join or Tuple equivalent) to validate assumptions.
