# Idea Log

Undecided possibilities — things worth remembering but not yet decided.
Promote to an ADR when ready to decide; discard when no longer relevant.

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
