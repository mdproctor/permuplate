# Handover — 2026-04-21 (batches 8–9, DSL deep polish, 305 tests)

**Head commit:** `09d0de0` — blog entry written, everything pushed to origin  
**Status:** Clean — everything committed and pushed.

---

## What Changed This Session

### Batch 8 — DSL deep polish, 10 items (issues #92–#101, epic #91)

| Feature | What it does |
|---|---|
| `not()`/`exists()` rename + `@PermuteMethod(values=)` | NegationScope→NotScope, ExistenceScope→ExistsScope; 3 ternaries eliminated via `Scope` macro |
| `max()/min()` JEXL built-ins | `max(2,i)` replaces `${i > 1 ? i : i+1}` ternary in filterLatest |
| `typeArgList` custom prefix | `'V'→V1,V2,V3`; enables variable filter templating |
| Variable filter overloads templated | `@PermuteMethod(m=2..3)` + G4 typeParam replaces 2 hand-coded methods |
| Method macros on bilinear/extendsRule/OOPath | `joinAll`, `joinRight`, `prevAlpha`, `outerJoin`, `prevTuple` |
| `@PermuteReturn(typeParam=)` | Return type = named type param; `@PermuteReturn` now `@Repeatable`; Path2 unified |
| `@PermuteMacros` | Shared macros on outer class; `alphaList` in JoinBuilder defined once |
| `@PermuteMixin` | Inject mixin methods; `ExtendsRuleMixin`; ADR-0006 resolved |
| Constructor super-call inference | Auto-inserts `super(p1..p_{N-1})`; removes `@PermuteStatements` from Tuple1 |
| `@PermuteExtendsChain` | Extends-previous-in-family shorthand; applied to BaseTuple |

### Batch 9 — DSL second-pass, 10 items + bug fix (issues #103–#113, epic #102)

| Feature | What it does |
|---|---|
| Bug fix: delete NegationScope.java | Orphan from batch 8's `git rm` that didn't persist |
| `Scope` macro | `capitalize(scope)` → `${Scope}` macro; 3 calls → 1 |
| `@PermuteFilter("i > 1")` on filterLatest | More expressive than `max(2,i)` approach |
| `@PermuteBodyFragment` | Named body fragments substituted into `@PermuteBody` strings before JEXL |
| `@PermuteParam` inside `@PermuteMethod` | Runs PermuteParamTransformer on clones with inner context; `filterVar` drops `@PermuteBody` |
| `@PermuteReturn` from body inference | Infers return type from `return new X<>()` when X in generated set |
| `@PermuteDefaultReturn(className="self")` | Returns current class + all type params; `typeArgs` ignored |
| `@PermuteDefaultReturn` replaces 5× `@PermuteSelf` | One class annotation on Join0First |
| Consumer/Predicate cross-product merge | `FunctionalTemplate1` + `@PermuteVar`; Consumer1/Predicate1 hand-written in `src/main/java/` |
| `@FunctionalInterface` via `@PermuteAnnotation` | Fixed `PermuteAnnotationTransformer` gap in non-inline pipeline |
| `@PermuteMacros prev=${i-1}` on RuleExtendsPoint | Readability; found+fixed `evaluateRaw()` macro type bug |

---

## Known non-obvious decisions added this session

- **`evaluateRaw()`** in `EvaluationContext` — preserves native JEXL type (Integer) for single `${expr}` templates; needed for numeric macros used in `@PermuteTypeParam` bounds. `evaluate()` always returns String.
- **`PermuteAnnotationTransformer` gap** — was never called in `PermuteMojo.generateTopLevel` (non-inline pipeline); `@PermuteAnnotation` silently had no effect on non-inline templates. Fixed batch 9.
- **Consumer1/Predicate1** now in `src/main/java/` (hand-written arity-1 interfaces), not `src/main/permuplate/`
- **`@PermuteParam` inside `@PermuteMethod`**: outer transformer skips these methods; each clone processed with inner context before `@PermuteBody`
- **`max`/`min` are now reserved JEXL built-in names** — `strings={"max=3"}` patterns silently fail (overwritten). Use `maxN` etc. (bit two APT tests in batch 9 cleanup)
- **ADR-0006 resolved** — `@PermuteMixin` + `ExtendsRuleMixin` eliminates `extendsRule()` duplication

---

## Immediate Next Step

Maven Central release. Group ID decision still open:
- `io.github.mdproctor` — instant namespace approval
- `io.quarkiverse` — slower Quarkiverse review

**305 tests green. All issues #92–#113 closed. Everything pushed.**

---

## References

| What | Where |
|---|---|
| Batch 8 spec | `docs/superpowers/specs/2026-04-20-dsl-batch8-design.md` |
| Batch 9 spec | `docs/superpowers/specs/2026-04-21-dsl-batch9-design.md` |
| Garden PR (4 entries batch 9) | `GE-20260421-c37188`, `GE-20260421-e86212`, `GE-20260421-bbc3a9`, `GE-20260421-876557` |
| Blog entry | `site/_posts/2026-04-21-mdp01-twenty-features-two-passes.md` |
| ROADMAP | `docs/ROADMAP.md` — batches 6–9 in Completed |
