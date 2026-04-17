# Handover — 2026-04-17

**Head commit:** `87ad27e` — docs: CLAUDE.md sync + blog entry 2026-04-17
**GitHub:** Zero open issues.

---

## What Changed This Session

### Website example — finally correct
Three iterations to get it right. The example now shows `Join2<A, B>` generating `Join3<A,B,C>` with all three types expanding in lockstep: class type params, `Callable3<A,B,C>` field, and `DataSource<Tuple3<A,B,C>>` right field. No `Object` anywhere. Diff block shows the three-way expansion.

### IntelliJ MCP smoke test — 7/7 pass
- All rename propagation scenarios verified: template, mid-range generated file, end-range generated file
- `@PermuteSource.value` updates atomically on class rename ✓
- Both inspections fire on deliberate mismatch ✓
- **Key finding:** `ide_diagnostics` MCP tool does NOT run `LocalInspectionTool` plugins — use `get_file_problems`

### Generics sweep — 21 example template files
All `permuplate-tests/src/test/java/.../example/` files now use proper generics. Pattern: `<A, @PermuteTypeParam B>`, typed Callables via `typeArgList`, typed List fields via `@PermuteDeclr`. Five test assertion files updated.

**Known limitation discovered:** Two independent `@PermuteTypeParam` axes in cross-product templates (`@PermuteVar`) produce duplicate type params. `BiCallable1x1`, `Combo1x1`, `DualParam2` left with `Object` until fixed.

### Garden — 4 entries submitted
- `ide_diagnostics` vs `get_file_problems` gotcha
- Two-axis `@PermuteTypeParam` limitation
- Non-first type param pattern (`<A, @PermuteTypeParam B>`)
- `typeArgList` single-quote technique

---

## Immediate Next Step

Two tracked issues to consider:
1. **Fix dual `@PermuteTypeParam` for cross-product templates** — `BiCallable1x1`, `Combo1x1`, `DualParam2` still use `Object`; requires processor change
2. **`@PermuteConditional`** — highest-complexity remaining feature; needs design

Otherwise, no tracked work remains. Clean state.

---

## References

| Context | Where |
|---|---|
| Previous handover | `git show HEAD~1:HANDOFF.md` |
| Blog entry | `site/_posts/2026-04-17-mdp01-generics-website-smoketest.md` |
| Example templates | `permuplate-tests/src/test/java/.../example/` |
| CLAUDE.md additions | `## Key non-obvious decisions` — 3 new rows |
