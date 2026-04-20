# Handover — 2026-04-20 (batches 6–7 + DSL deep dive)

**Head commit:** `85601f0` — ROADMAP updated, all pushed to origin  
**Status:** Clean — everything committed and pushed.

---

## What Changed This Session

### Batch 6 — Annotation reduction and Drools DSL improvement (epic #79, issues #80–#85)

Six features — all shipped and reflected in the DSL:

| Feature | What it does |
|---|---|
| `@PermuteSelf` | Method-level: return type = current generated class + type params. Replaced 6 `@PermuteReturn` on Join0First fluent methods. |
| Self-return inference (Maven) | Auto-detect `return this;` → set return type. No annotation needed in inline mode. |
| `@PermuteDefaultReturn` | Class-level default return type for all Object-returning methods. |
| `@Permute macros=` | Named JEXL expressions per permutation. **Critical: must not use built-in names (`alpha`, `lower`, `typeArgList`).** Use `alphaList` etc. |
| Sealed JoinFirst/JoinSecond | `non-sealed` classes implementing sealed interfaces. Enables Java 21 pattern dispatch. |
| ADR-0006 | Documents why `extendsRule()` duplication is structurally unavoidable. |

### Batch 7 — Inference and readability (epic #86, issues #87–#90)

Four features — all shipped and applied to JoinBuilder:

| Feature | DSL impact |
|---|---|
| Alpha growing-tip inference | `join()` drops `typeArgs=` entirely — inferred from single-value `@PermuteTypeParam` with alpha naming. |
| `@PermuteMethod macros=` | `path${n}()` typeArgs decomposed into `tail`/`prev` named pieces. Requires `IdentityHashMap` for multi-phase node tracking. |
| `@PermuteReturn replaceLastTypeArgWith=` | `type()` drops complex ternary → `replaceLastTypeArgWith="T"`. |
| `macros=alphaList` applied | 5× `typeArgList(1,i,'alpha')` eliminated in JoinBuilder. |

### DSL result

JoinBuilder: 437 → 354 lines (−19%). Total DSL: 955 → 851 lines (−11%).  
`@PermuteReturn` with `typeArgs=`: 18 → 5. Reflection blocks: 2 → 0.

---

## Known constraints / non-obvious decisions

- **`macros=` reserved names**: `alpha`, `lower`, `typeArgList`, `capitalize`, `decapitalize`, `__throwHelper` are JEXL built-ins — macros with these names are silently overwritten. Use `alphaList`, `lowerList`, etc. (GE-20260420-a396f1)
- **`@PermuteSelf` + `@PermuteMethod`**: fires on template before cloning — all clones inherit the outer class return type. Only safe when the inner variable doesn't affect the returned class.
- **`@PermuteDefaultReturn.typeArgs`** includes angle brackets (`<A, B>`) — unlike `@PermuteReturn.typeArgs` which is content without brackets.
- **Sealed interfaces require `non-sealed` on implementing classes** when a subclass exists — `Join0First` extends `Join0Second`, so both need `non-sealed`, not just `final`.
- **ADR-0006**: `extendsRule()` duplication in RuleBuilder+ParametersFirst is structurally unavoidable — `@PermuteMethod` on base class methods is not processed by the inline generation pipeline.

---

## Immediate Next Step

Maven Central release — the annotation processor is feature-complete. Group ID decision: `io.github.mdproctor` (instant namespace approval) or Quarkiverse `io.quarkiverse` (slower review). Everything pushed, 277 tests green.

---

## References

| What | Where |
|---|---|
| ADR-0006 (extendsRule duplication) | `docs/adr/0006-extendsrule-duplication.md` |
| Garden entries this session | `GE-20260420-a396f1` (macros name collision), `GE-20260420-362475` (IdentityHashMap technique) |
| Specs | `docs/superpowers/specs/2026-04-20-*.md` (4 files) |
| Plans | `docs/superpowers/plans/2026-04-20-*.md` (10 files) |
| Roadmap | `docs/ROADMAP.md` — updated, batches 6–7 marked shipped |
