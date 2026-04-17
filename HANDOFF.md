# Handover — 2026-04-18

**Head commit:** `7ec605e` — chore: remove src/main/java stubs replaced by permuplate templates
**GitHub:** No open issues. Epics #48 and #52 closed. Build fully green.

---

## What Changed This Session

### Drools DSL sandbox — all planned template work complete

Six files moved from `src/main/java/` to `src/main/permuplate/` as inline templates.
~496 lines of hand-written boilerplate eliminated.

| Template | Result |
|---|---|
| RuleExtendsPoint | `@Permute(inline=true, keepTemplate=true)` + `@PermuteTypeParam` — 88 → 37 lines |
| BaseTuple | Delegation refactor + full inline template — ~290 lines saved |
| RuleOOPathBuilder | Path3 template generates Path4..Path6 — 128 → 74 lines |
| NegationScope + ExistenceScope | `@Permute(values={"Existence"}, inline=false, keepTemplate=true)` — 110 → 55 lines |
| RuleBuilder + ParametersFirst | Top-level inline template + `@PermuteMethod(j=2..7)` — 216 → 170 lines |

### Four framework gaps closed

1. **Maven plugin string-set** (#51) — `@Permute(values={...})` now works in PermuteMojo
2. **`keepTemplate=true` for `inline=false`** (#51) — template class written to generated sources
3. **`inline=true` on top-level classes** (#56) — `InlineGenerator.generate()` branches on `isNestedType()`
4. **`@PermuteDeclr` TYPE_USE on qualified names** (#57) — scope annotations now checked

### Other fixes

- `@PermuteDeclr` on `ElementType.METHOD` — method rename + return type (#46, epic #52)
- JEXL exceptions in transformers now produce compile errors not RuntimeExceptions (#54)
- Two pre-existing test failures fixed
- `transformNewExpressions` widened from `TypeDeclaration<?>` to `Node`; now `public static`

---

## Immediate Next Step

Read the actual droolsvol2 source at `/Users/mdproctor/dev/droolsoct2025/droolsvol2/` for at least one arity family (Join or Tuple equivalent) before writing the follow-up blog article. The sandbox was built from vol2 *tests*, not source — the savings claims need validation against the real implementation.

---

## References

| Context | Where |
|---|---|
| Previous handover | `git show HEAD~1:HANDOFF.md` |
| Working notes | `docs/drools-dsl-template-improvements.md` |
| DSL article | `site/_posts/2026-04-17-mdp02-dsl-that-generated-itself.md` |
| Session diary | `site/_posts/2026-04-18-mdp01-using-tool-on-itself.md` |
| Session plan | `docs/superpowers/plans/2026-04-17-method-rename-and-basetuple-template.md` |
