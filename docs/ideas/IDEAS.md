# Idea Log

Undecided possibilities — things worth remembering but not yet decided.
Promote to an ADR when ready to decide; discard when no longer relevant.

---

## 2026-04-09 — Transitive constructor limitation in @PermuteDeclr TYPE_USE

**Priority:** medium
**Status:** resolved (2026-04-14)

**Rule established:** `@PermuteDeclr TYPE_USE` works for `@Permute`-level variables only. It cannot reference `@PermuteMethod` inner variables (`j`) because `PermuteDeclrTransformer` runs in the outer context after `PermuteMethodTransformer` has already consumed those overloads. Reflection remains required when the target class name depends on a `@PermuteMethod` variable.

**What was done:** Refactored JoinBuilder sandbox template — replaced reflection with `@PermuteDeclr TYPE_USE` in `join()` and `path2()`..`path6()` (all use `Join${i+1}First`, an outer variable). `joinBilinear()` keeps reflection (`Join${i+j}First` requires `j`) and `extensionPoint()` keeps reflection (qualified type name `RuleExtendsPoint.RuleExtendsPoint1` — TYPE_USE requires a simple unqualified name on the `new` expression).

**Context:** Arose during brainstorming for JoinNFirst/JoinNSecond Permuplate templates (2026-04-09). The `join()` and `extensionPoint()` methods in droolsvol2's RuleBuilder both use direct `new` — so the TYPE_USE annotation works there. The limitation matters for any future method that delegates creation to a helper.

**Promoted to:**

---

## 2026-04-09 — Automated end-to-end tests for rename cascade in IntelliJ and VS Code

**Priority:** high
**Status:** resolved (2026-04-15)

**What was done:** Added two cascade tests to `AnnotationStringRenameProcessorTest`:
- Template rename (Join2→Merge2): verifies constructor rename + annotation string + cross-file annotation string in one operation
- Generated file rename (Join3→Merge2): verifies redirect fires AND full cascade followsAlso fixed a pre-existing index bug: `PermuteTemplateIndex`, `PermuteGeneratedIndex`, and `PermuteElementResolver` all checked for `Integer` literals for `from`/`to`, but issue #16 changed those attributes to `String`. This silently broke 14 tests. Fixed `getIntAttr()`/`parseLiteralInt()` in all three locations. Plugin now at 58 tests, 0 failures (was 42 passing before fix).

VS Code: out of scope — extension parked.

**Promoted to:**

---

## 2026-04-07 — IDE refactoring safety for templates and generated code

**Priority:** high
**Status:** active

Two coupled IDE tooling problems with no solution yet:

1. **Template refactor propagation** — when a developer renames a method in a
   Permuplate template (e.g. `join()` → `combine()` in `Join0Second`), IntelliJ
   and VS Code rename refactoring only touches the template occurrence. The N
   generated permutations (`Join1Second.join()` through `Join6Second.join()`) are
   not updated because they're generated files outside the rename scope. The
   developer must manually hunt down all call sites in the generated output — or
   worse, doesn't realise the rename didn't propagate.

2. **Generated code modification warning** — if a developer edits a generated
   class directly (e.g. adds a method to `Join3First.java` in
   `target/generated-sources/permuplate/`), their change is silently overwritten
   on the next build. No warning, no error — the edit disappears. For `inline=true`
   mode the risk is even higher since generated files live in `src/`.

**Questions to explore:**
- Can a Permuplate IDE plugin intercept rename refactors on annotated templates
  and replay them across all generated permutations?
- Should generated files be marked `@Generated` or with a file header so IDEs
  can warn on edit?
- Is there a Maven/Gradle hook that could detect source-level edits to generated
  files and fail the build with an explanation?
- For IntelliJ: can a plugin suppress refactoring on generated files and redirect
  the developer to the template?

**Context:** Raised during session wrap 2026-04-07. Affects anyone using
Permuplate in a real project — not just the Drools sandbox. Particularly sharp
for the migration phase where generated Drools classes will be actively worked on.

**Promoted to:**

---

## 2026-04-07 — ctx position and two-context design for imperfect reasoning

**Priority:** high
**Status:** active

Lambda signatures are currently `(ctx, a, b, ...)` with DS context first. Two
connected decisions are deferred until the imperfect-reasoning system is designed:

1. **ctx position** — putting DS context first pushes the first join fact to `b`,
   which is already a mild naming concern. Adding a second context for node-level
   uncertainty would push it to `c` or further. Contexts at the end `(a, b, ctx)`
   or implicit (closure-based) may be cleaner. Cannot decide without knowing the
   full two-context shape.

2. **Two-context design for imperfect reasoning** — a pluggable uncertainty system
   (Bayesian probability, Dempster-Shafer, fuzzy logic, MYCIN certainty factors)
   requires a second context beyond the DS RuleUnit context. Uncertainty can attach
   to rules, filters, and/or facts depending on the model. The accumulation/
   combination logic is the plugin's responsibility. The exact type, position,
   and threading of this second context is TBD — will be discovered during
   first implementation attempt.

**Decision:** defer both. Refactor `ctx` position when the two-context shape is
known. Start migration with current convention; signature changes are mechanical
and can be done in one pass.

**Context:** Arose from discussion of locking in ctx position before migration
(2026-04-07). The two issues are coupled — cannot decide ctx position without
knowing how many contexts the DSL will carry and in what order.

**Promoted to:**

---

## 2026-04-07 — Fix three vol2 bugs before or during Drools migration

**Status:** resolved (2026-04-14) — all three fixed by the Permuplate template conversion in droolsvol2 (commit e50cd09997). Verified 2026-04-14 with 65 tests passing.

1. `RuleExtendsPoint6` — correct name generated by template ✅
2. `Join3Second.path5()` — correct return type (`Path4<..., Tuple5<...>, ...>`) generated; vol2's path5 consumes the first step as an argument ✅
3. `ParametersFirst.params(B... cls)` — correct varargs type-capture pattern in the droolsvol2 template ✅
