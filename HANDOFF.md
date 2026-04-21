# Handover — 2026-04-21 (batch 10, inference pass, 6 DSL improvements)

**Head commit:** `2f2ea5c` — everything committed and clean.
**Status:** Clean — nothing uncommitted.

---

## What Changed This Session

### Batch 10 — 6 Permuplate improvements (inference-focused)

| Feature | What it does |
|---|---|
| Constructor-coherence inference | After `@PermuteReturn` resolves to class X, auto-renames `new SeedClass<>()` in method body to match. Removed 4 TYPE_USE `@PermuteDeclr` from `JoinBuilder`/`ExtendsRuleMixin`. |
| `@PermuteMixin` on non-template | Classes in `src/main/permuplate/` with `@PermuteMixin` but no `@Permute` now processed by `PermuteMojo.processNonTemplateMixins()`. `RuleBuilder`/`ParametersFirst` lost their dummy `@Permute(from=1,to=1)`. |
| `@PermuteNew(className=...)` | TYPE_USE annotation for explicit constructor renaming; fallback for cases where coherence inference can't resolve. Not currently used in DSL (inference covers it). |
| `addVariableFilter` m=2..6 | `RuleDefinition` moved to `src/main/permuplate/` + `@PermuteMixin(VariableFilterMixin.class)`. `filterVar` in `JoinBuilder` extended to `to="6"`. |
| `createEmptyTuple` reflection | Switch (case 1..6) replaced with `Class.forName(BaseTuple.class.getName() + "$Tuple" + size)`. |
| `@PermuteSealedFamily` | Auto-generates sealed marker interface + adds `implements` to each generated class. `JoinBuilder` manual sealed interface declarations removed. |

All changes applied to the DSL and documented in CLAUDE.md. 305 tests stable throughout.

---

## Key non-obvious decisions (batch 10)

- **Constructor-coherence**: uses `replaceAll("\\d+","")` not `replaceAll("\\d+$","")` — strips ALL digit sequences; embedded-arity names like `Join2First` have digits mid-name, not at the end.
- **`@PermuteMixin` on non-template**: `ClassOrInterfaceDeclaration` in JavaParser covers interfaces too — guard requires `|| coid.isInterface()`.
- **`allGeneratedNames` not used in coherence**: cross-file families absent from per-CU set; family-matching double guard (digit presence + same stripped family) is sufficient.
- **`RuleDefinition` is now in `src/main/permuplate/`**, not `src/main/java/`.

*Previous session decisions: `git show HEAD~7:HANDOFF.md`*

---

## Immediate Next Step

Maven Central release. Group ID still undecided:
- `io.github.mdproctor` — instant namespace approval
- `io.quarkiverse` — slower Quarkiverse review

**305 tests green. Everything pushed.**

---

## References

| What | Where |
|---|---|
| Batch 10 plan | `docs/superpowers/plans/2026-04-21-dsl-deepdive-batch10.md` |
| Blog entry | `site/_posts/2026-04-21-mdp02-inference-pass-six-more.md` |
| Garden entries (batch 10) | `GE-20260421-2df2ba`, `GE-20260421-dbc509`, `GE-20260421-5886e0` |
| Previous handover | `git show HEAD~7:HANDOFF.md` |
| ROADMAP | `docs/ROADMAP.md` |
