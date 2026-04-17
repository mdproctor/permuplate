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

## Done (continued)

### NegationScope + ExistenceScope ✅
- **Before:** 57 + 53 = 110 lines hand-written
- **After:** 55-line template, `@Permute(values={"Existence"}, className="${T}Scope", inline=false, keepTemplate=true)` on NegationScope
- **Savings:** ~55 lines (50%)
- **New features required:** Maven plugin string-set support (#51), `keepTemplate=true` for `inline=false` (#51)

### RuleOOPathBuilder ✅
- **Before:** 128 lines, 5 hand-written inner classes
- **After:** 74-line template, Path3 generates Path4..Path6 via `@Permute(inline=true, keepTemplate=true)`
- **Savings:** 54 lines (42%)

### extendsRule() overloads — ParametersFirst + RuleBuilder ✅
- **Before:** 94 + 122 = 216 lines hand-written (6 overloads each)
- **After:** 80 + 90 = 170-line top-level inline templates using `@PermuteMethod(j=2..7)`
- **Savings:** templates generate 300 lines from 170 source lines
- **New features required:** `inline=true` on top-level classes (#56), `@PermuteDeclr` TYPE_USE on qualified names (#57)
- **Body uses reflection** — `ep.getClass().getSimpleName()` derives arity. TYPE_USE `@PermuteDeclr` on qualified names works after #57 but reflection is cleaner for the `JoinBuilder.JoinNFirst` pattern.

### @PermuteSource Capability A — future extension
- The extendsRule() type parameters (A, B, C...) use JEXL `typeArgList(1, j-1, 'alpha')`
- Method-level @PermuteSource type inference would eliminate these expressions
- Not yet implemented — good future article topic

---

## Total savings (sandbox) — all done

| Work | Lines saved |
|---|---|
| RuleExtendsPoint | ~51 |
| BaseTuple (delegation refactor) | ~90 |
| BaseTuple (template) | ~200 |
| NegationScope + ExistenceScope | ~55 |
| RuleOOPathBuilder | ~54 |
| RuleBuilder + ParametersFirst (extendsRule) | ~46 |
| **Total** | **~496** |

Hand-written infrastructure reduced from ~1,500 to ~1,004 lines.
Templates (now ~804 lines) drive ~3,055 generated lines — ~3.8× expansion.

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
