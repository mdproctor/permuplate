# Idea Log

Undecided possibilities — things worth remembering but not yet decided.
Promote to an ADR when ready to decide; discard when no longer relevant.

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

**Priority:** high
**Status:** active

Three bugs found in the vol2 reference implementation during cross-reference
analysis. They must be fixed in the real Drools codebase when migrating.

1. **`RuleExtensionPoint6` naming typo** — class name in `RuleExtendsPoint.java`
   should be `RuleExtendsPoint6` (matching the rest of the family: 2..5 use
   `RuleExtendsPoint`). Current: `RuleExtensionPoint6`. Sandbox already uses
   the correct name.

2. **`Join3Second.path5()` wrong return type** — method returns `Path4<...>`
   but should return `Path5<...>`. The type parameter list is also wrong
   (`Tuple4` instead of `Tuple5`). Sandbox abstracts this away; real Drools
   will expose it.

3. **`ParametersFirst.params()` wrong varargs logic** — stub uses
   `Class... cls` but `cls.getClass().getComponentType()` gives `Class.class`
   not `P.class`. Should use `P... cls` (same varargs type-capture pattern as
   `as()` and `type()`). Without this fix, the params type is lost at runtime.

**Context:** Found during comprehensive vol2 vs sandbox cross-reference analysis
(2026-04-07). The sandbox is correct in all three cases — these are vol2 stub
bugs, not sandbox bugs.

**Promoted to:**
