# Session Handoff — Permuplate Design Work

**Last updated:** 2026-04-02  
**Status:** ✅ Implementation complete — all features implemented, tested, and documented

---

## What Was Done

Designed and implemented Permuplate annotation-driven permutation features to support the **Drools RuleBuilder DSL** as the primary use case. All features are implemented, all tests pass (126 tests, 0 failures, 0 skipped), and documentation is complete.

**If resuming:** all implementation is done. Focus on further design work (TODO-1/2/3 deferred inference improvements) or integration with the Drools project.

---

## IntelliJ MCP — Use This First

Both projects are open in IntelliJ. **Always prefer IntelliJ MCP tools over native file tools for this project** (per user preference stored in memory).

Two projects are open:
- **Permuplate:** `/Users/mdproctor/claude/permuplate`
- **Drools:** `/Users/mdproctor/dev/droolsoct2025`

To verify both are reachable, call `mcp__intellij__get_project_modules` with each project path before doing anything else.

---

## The Drools Use Case — Read This Code

The design is driven by the **`droolsvol2`** module in the Drools project. Read these files to understand what we are trying to generate with Permuplate:

| File | Location | Why |
|---|---|---|
| `RuleBuilderTest.java` | `droolsvol2/src/test/java/org/drools/core/` | Shows the DSL usage — the user-facing API. Read this first to understand what the DSL does. |
| `RuleBuilder.java` | `droolsvol2/src/main/java/org/drools/core/` | The hand-written builder — `Join2First`, `Join2Second`, `From1First`, etc. This is the main target for Permuplate generation. |
| `RuleOOPathBuilder.java` | `droolsvol2/src/main/java/org/drools/core/` | `Path2`–`Path6` classes and the `path${k}()` methods on `JoinNSecond`. Motivates G4. |
| `RuleExtendsPoint.java` | `droolsvol2/src/main/java/org/drools/core/` | `RuleExtendsPoint2`–`RuleExtensionPoint6` — simple G1 case. |
| `function/` directory | `droolsvol2/src/main/java/org/drools/core/function/` | `Consumer1`–`Consumer4`, `Predicate1`–`Predicate10`, `Function1`–`Function5`, `BaseTuple`, `Tuple1`–`Tuple6` — all hand-written, all G1 candidates. |

### Use Cases in RuleBuilderTest

`test3JoinsVariousUses()` — core join/filter/fn chain  
`testCompactFilter()` — Variable-based filter with cross-fact predicates  
`testPath()`, `testPath2()` — OOPath traversal (motivates G4 `path${k}()` methods)  
`testNot()` — negation groups  

---

## Design Documents Location

All specs are in `/Users/mdproctor/claude/permuplate/docs/superpowers/specs/`:

| File | Status | What it designs |
|---|---|---|
| `2026-04-01-generic-type-params-g1-design.md` | **Approved** | `@PermuteTypeParam` — expanding class/method type parameters (`Consumer1<T1>` → `Consumer3<T1,T2,T3>`) |
| `2026-04-02-return-type-narrowing-g2-design.md` | **Approved** | `@PermuteReturn`, `@PermuteDeclr` on parameters — return type narrowing by arity; implicit inference in inline mode |
| `2026-04-02-multi-join-g3-design.md` | **Approved** | `@PermuteMethod` — multiple overloads per class; extends/implements expansion; dependency graph ordering |
| `2026-04-02-named-method-series-g4-design.md` | **Approved** | `@PermuteMethod.name`, method-level `@PermuteTypeParam`, `@PermuteReturn.typeArgs` — named method series (`path2()`, `path3()`, ...) |
| `2026-04-02-expression-language-functions-n4-design.md` | **Approved** | `alpha(n)`, `lower(n)`, `typeArgList(from,to,style)` JEXL functions |

**Also read:**
- `docs/gap-analysis.md` — the master gap list with Hard Gaps (G1–G4), Soft Gaps (S1–S5), New Patterns (N1–N4), and Future TODOs (TODO-1 through TODO-3)

---

## Core Design Principles — Never Deviate From These

### 1. APT and Maven Plugin Parity

**Goal:** Keep APT and Maven plugin as consistent as possible. Where they differ, the explicit annotation is the APT workaround — not a second-class path.

**Maven plugin (inline mode)** processes `src/main/permuplate/` templates:
- Two-pass scan: first builds the complete generated class set + dependency graph across all template files, then generates in topological order
- Supports **implicit inference** — JavaParser reads the template AST and infers what would otherwise require explicit annotations
- Templates do NOT need to be compilable Java (they live in `src/main/permuplate/`, not on the compile source path)

**APT mode** processes annotated source files during `javac`:
- Single-pass per compilation round; uses `RoundEnvironment.getElementsAnnotatedWith()` to see all `@Permute` elements in the current round
- Supports the same dependency graph and topological ordering **within a compilation round** (same module)
- Cross-module dependencies require explicit `to` + `strings={"max=N"}` — the one remaining gap vs Maven
- Templates **must compile** — no undeclared type variables in method parameters; use `Object` sentinels with `@PermuteDeclr`/`@PermuteReturn` explicitly

**The invariant:** Explicit annotations (`@PermuteReturn`, `@PermuteDeclr`, explicit `to` on `@PermuteMethod`, `strings={"max=N"}`) are the bridge that makes APT capable of everything Maven plugin can do. The annotation API is identical; only the inference differs.

### 2. JavaParser Inference — More Implicit Is Better

We use JavaParser to read template ASTs and infer what the user would otherwise have to annotate. The more we can infer, the less the user writes. Current inferences (all inline/Maven plugin only unless noted):

| What is inferred | How | Both modes? |
|---|---|---|
| Return type expansion (`Step2<T1,T2>` → `Step${i+1}First<T1..T${i+1}>`) | Return type class in generated set + `T${j}` naming convention | Maven only |
| Parameter type expansion (undeclared type vars) | Same undeclared vars as return type's growing tip | Maven only |
| `@PermuteMethod.to` upper bound | Read from enclosing `@Permute.to - i` | Both |
| Dependency graph ordering | First-pass AST scan across all templates | Maven (across files); APT (within round) |
| `@PermuteTypeParam` on class type params | When `@PermuteParam` uses `T${j}` type | Maven only |

**Explicit annotations are always the fallback** when inference doesn't apply: APT mode, `alpha(j)` naming, cross-module dependencies.

### 3. `T${j}` vs `alpha(j)` Naming Convention

This is **critical** to understand and document:

- **`T${j}` naming** (T1, T2, T3, ...) — triggers implicit inference. Zero annotations beyond `@Permute` in Maven inline mode.
- **`alpha(j)` naming** (A, B, C, ...) — does NOT trigger inference. Always requires explicit `@PermuteReturn` + `@PermuteDeclr`. The processor cannot reverse-engineer a letter into a numeric range.
- **Why:** Inference needs a numeric suffix to identify the growing tip. Single letters have no pattern.

The "Choosing your approach" table must appear prominently in all user-facing docs.

---

## Key Design Decisions Made This Session

### G2 — Return Type and Parameter Inference

When a method's return type is a generated class with undeclared type variables:
- Return type: inferred automatically (inline mode)
- Parameter types: any parameter whose type contains those same undeclared type variables is also inferred — **no `@PermuteDeclr` needed in the common case**

### G3 — `@PermuteMethod.to` is Optional

`to` defaults to `""` — inferred as `@Permute.to - i` from the enclosing class's annotation. `strings={"max=N"}` is only needed in APT mode for cross-module dependencies.

### G3 — Dependency Graph

Both APT and Maven plugin scan all `@Permute` templates before generating:
- Maven: scans `src/main/permuplate/` explicitly
- APT: uses `RoundEnvironment.getElementsAnnotatedWith(Permute.class)` + JavaParser
Both build a dependency graph and generate in topological order. Cycle detection → error.

### G4 — Three New Extensions

1. **`@PermuteMethod.name`** — generates distinct method names per inner loop value (`path2`, `path3`, ...) instead of same-name overloads
2. **Method-level `@PermuteTypeParam`** — `@Target(TYPE_PARAMETER)` already covers method type params; implementation extension needed; driven by `@PermuteMethod` inner variable; creates three-level nesting (i → k → j)
3. **`@PermuteReturn.typeArgs`** — full JEXL string template for the complete type argument list; solves mixed fixed+growing args that the existing loop cannot express; also resolves the `extensionPoint()` gap (e.g., `"DS, ${typeArgList(1, i, 'T')}"`)

---

## Open Items — Check These Before Continuing

### gap-analysis.md — Hard Gaps Remaining

G1–G4 are all **specced and approved** but not yet implemented. Check the gap analysis for:
- **S3, S5** — Soft gaps still open (Mojo prefix check, @Ignore test)
- **TODO-1, TODO-2, TODO-3** — Deferred inference improvements (read before deciding to implement)

### Things Identified But Not Yet Specced

During the big-picture review of the RuleBuilder code, we identified additional gaps not yet written into specs:

1. **Growing switch statements** (`Tuple.get(int index)` / `set(int index)`)  — The `BaseTuple` subclasses have switch statements that add one case per permutation. No Permuplate mechanism exists for this. Noted but not designed.

2. **Self-return inference** (TODO-2 in gap-analysis.md) — `filter()` returning the same class as the template. G2's Condition 2 fails (no undeclared tip). Design deferred.

3. **`@PermuteDeclr` implicit on fields** (TODO-3) — when field type is a generated class with matching numeric suffix. Design deferred.

4. **`@PermuteParam` fully implicit** (TODO-1) — when parameter type is a class type param following `T${j}` and name follows `name${j}`. Complex; deferred.

### Things NOT Yet Resolved in the Specs

The gap analysis has two items that were identified but still need updating:
- **Gap analysis not updated** to reflect that G1/G2/G3/G4/N4 have approved specs (they are still listed as "open gaps" without cross-references to the spec files)
- **N4 spec** — `typeArgList` is defined in G3 as an "N4 extension" but is missing from N4's own spec testing and files sections

---

## Conversation History Summary

Key things we worked through (if the session crashed mid-discussion):

1. Reviewed G1, G2, G3 specs from previous session — found last session's key inference conclusions (JavaParser-based, no explicit annotations in common case) had NOT been written to the docs. Fixed.
2. Updated G2: `@PermuteDeclr` on parameters is also inferrable (same undeclared type vars as return type growing tip).
3. Updated G3: `@PermuteReturn` and `@PermuteDeclr` are ALSO inferrable when `@PermuteMethod` provides the j context. The only annotation needed is `@PermuteMethod`.
4. Updated G2/N4 documentation requirements: added "choosing your approach" table; explained WHY `T${j}` works but `alpha(j)` doesn't; added clear APT vs Maven guidance throughout.
5. Updated G3: `@PermuteMethod.to` becomes optional (inferred from `@Permute.to - i`); removed `strings={"max=N"}` from common case; added dependency graph architecture; aligned APT and Maven parity.
6. Reviewed RuleBuilder.java and RuleOOPathBuilder.java — identified the path use case (`path2()`, `path3()`, ...) as exposing three new gaps.
7. Designed G4: `@PermuteMethod.name`, method-level `@PermuteTypeParam`, `@PermuteReturn.typeArgs`.
8. Added TODO-1 (`@PermuteParam` fully implicit), TODO-2 (self-return inference), TODO-3 (`@PermuteDeclr` implicit on fields) to gap analysis as deferred items.

---

## What To Do Next

1. **Check and update the gap analysis** — mark G1–G4 and N4 as "Spec Approved" with links to spec files; update N4 to include `typeArgList`.

2. **Continue the use-case review** — we had just finished reviewing the `path${k}()` case. There are more use cases in `RuleBuilderTest.java` that may expose further gaps:
   - `testNot()` — negation groups (`not()` → `Not2`, `Group2`)
   - `testCompactFilter()` — `Variable<T>` and variable-based filters
   - The `extendsRule()` mechanism on `ParametersFirst` referencing `RuleExtendsPoint`
   - The `Tuple.get()` / `set()` growing switch statements (no mechanism — potential G5)

3. **When design is complete** — begin implementation in priority order: N4 → G1 → G2 → G3 → G4

---

## Quick Reference — Annotation API

| Annotation | Module | Purpose |
|---|---|---|
| `@Permute` | permuplate-annotations | On class/interface/method — drives the outer permutation loop |
| `@PermuteDeclr` | permuplate-annotations | On field, for-each var, or method parameter — renames type and/or name per permutation |
| `@PermuteParam` | permuplate-annotations | On a sentinel method parameter — expands it into a sequence of parameters |
| `@PermuteTypeParam` | permuplate-annotations | On a sentinel type parameter (class or method level) — expands it into a sequence of type params |
| `@PermuteReturn` | permuplate-annotations (G2/G4) | On a method — controls return type per permutation. `className` accepts class names OR type variable names (e.g., `${alpha(i)}`). `typeArgVarName/From/To/Name` for uniform growing series. `typeArgs` (G4) for mixed fixed+growing args (e.g., `"DS, ${typeArgList(1, i, 'T')}"`). `when` for boundary guard. |
| `@PermuteMethod` | permuplate-annotations (G3/G4) | On a sentinel method — generates multiple overloads per class. `name` (G4): method name template for distinct names (e.g., `"path${k}"` → `path2()`, `path3()`); omit for same-name overloads. `to` inferred as `@Permute.to - i` when omitted. |
| `@PermuteExtends` | permuplate-annotations (G3) | On a class — explicit override for extends/implements clause expansion |
| `@PermuteVar` | permuplate-annotations | On `@Permute` — adds extra integer loop axes for cross-product generation |
