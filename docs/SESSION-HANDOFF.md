# Session Handoff — Permuplate

**Last updated:** 2026-04-02  
**Status:** ✅ **Implementation complete** — all features implemented, tested, and documented  
**Tests:** 126 passing, 0 failures, 0 skipped  
**Build command:** `/opt/homebrew/bin/mvn clean install`

---

## One-Line Summary

Permuplate is a Java annotation processor that generates type-safe arity permutations from a single template class. All core features (N4, G1, G2a, G2b, G3, G4) have been designed, implemented, tested, and documented. The only remaining open question is a structural refactor (moving example tests into the example module).

---

## IntelliJ MCP — Use This First

**Always prefer IntelliJ MCP tools over native file tools** (user preference).

Two projects open in IntelliJ:
- **Permuplate:** `/Users/mdproctor/claude/permuplate`
- **Drools (reference):** `/Users/mdproctor/dev/droolsoct2025`

Verify both with `mcp__intellij__get_project_modules` before doing anything else.

---

## What Has Been Implemented (Everything)

All features are fully implemented, reviewed, and merged to `main`.

| Feature | What it does | Key files |
|---|---|---|
| **N4** | `alpha(n)`, `lower(n)`, `typeArgList(from,to,style)` JEXL built-in functions available in every `${...}` expression | `EvaluationContext.java` (PermuplateStringFunctions nested class) |
| **G1** | `@PermuteTypeParam` — expands a sentinel class or method type parameter into a sequence. `Consumer1<T1>` → `Consumer2<T1,T2>`, `Consumer3<T1,T2,T3>`, etc. Implicit expansion fires automatically when `@PermuteParam` uses `T${j}` type. | `PermuteTypeParamTransformer.java` |
| **G2a** | `@PermuteDeclr` on method parameters — `name` is optional (default `""`): omit to change type only; set to also rename with body propagation | `PermuteDeclrTransformer.transformMethodParams()` + `processMethodParamDeclr()` |
| **G2b** | `@PermuteReturn` — return type narrowing by arity. Implicit inference in Maven inline mode (zero annotations for T${j} naming). Boundary omission: last class has method silently omitted. | `InlineGenerator.applyImplicitInference()`, `applyPermuteReturn()` + `PermuteProcessor.applyPermuteReturn()` |
| **G3** | `@PermuteMethod` — multiple method overloads per class (inner j loop). `to` inferred as `@Permute.to - i`. `name` attribute for distinct method names (`path2()`, `path3()`, ...). Extends/implements clause auto-expansion for sibling generated classes. | `InlineGenerator.applyPermuteMethod()`, `applyExtendsExpansion()` + `PermuteProcessor.applyPermuteMethodApt()` |
| **G4** | Method-level `@PermuteTypeParam` — used inside `@PermuteMethod` for growing method type param lists (`<PB>`, `<PB,PC>`, `<PB,PC,PD>`). R3 prefix check intentionally omitted (sentinel is arbitrary placeholder). `@PermuteReturn.typeArgs` and `@PermuteMethod.name` were also part of G4 spec but were already implemented in G2b/G3. | `PermuteTypeParamTransformer.transformMethod()` |
| **S3** | PermuteMojo now uses `AnnotationStringAlgorithm.matches()` (consistent with APT) | `PermuteMojo.generateTopLevel()` |
| **S4** | Test for empty `@PermuteParam` range (from > to) | `PermuteParamTest.testPermuteParamEmptyRangeRemovesSentinel()` |
| **S5** | `@Ignore` test removed — uses `strings={"v1=my","v2=","v3=2"}` to declare JEXL vars | `PrefixValidationTest` |
| **N1/S1/S2** | Inline=true on interfaces + two templates in same parent — both tested | `InlineGenerationTest` |
| **N3** | `@PermuteParam` on abstract interface methods — works, tested | `PermuteParamTest.testPermuteParamOnAbstractInterfaceMethod()` |

---

## Test Classes

All in `permuplate-tests/src/test/java/io/quarkiverse/permuplate/`:

| Class | What it tests |
|---|---|
| `PermuteTest` | Type permutation range, nested class/interface promotion, string variables, cross-product via `extraVars` |
| `PermuteDeclrTest` | Field, constructor param, for-each variable, and method parameter renaming |
| `PermuteParamTest` | Fixed params before/after sentinel, multiple sentinels, anchor expansion, empty range, abstract interface |
| `PermuteTypeParamTest` | `@PermuteTypeParam` explicit/implicit, bounds propagation, fixed type params, R1/R3/R4 validation |
| `PermuteReturnTest` | `@PermuteReturn` APT explicit + Maven implicit inference, boundary omission, V2/V3/V6 validation |
| `PermuteMethodTest` | `@PermuteMethod` multiple overloads, inferred `to`, leaf nodes, extends expansion, APT mode, method-level `@PermuteTypeParam` |
| `ExpressionFunctionsTest` | `alpha`, `lower`, `typeArgList` — unit tests + end-to-end JEXL path tests |
| `ExampleTest` | Real-world domain templates: `ProductFilter2`, `AuditRecord2`, `ValidationSuite.FieldValidator2`, `BiCallable1x1` |
| `DogFoodingTest` | `Callable1` generates `Callable2`–`Callable10` |
| `DegenerateInputTest` | All `@Permute` attribute error paths with message content and source-position assertions |
| `PrefixValidationTest` | String-literal prefix rules for `@PermuteDeclr` and `@PermuteParam` across all placements |
| `AptInlineGuardTest` | APT rejection of `inline=true`; `keepTemplate` warning |
| `OrphanVariableTest` | R2 (substring matching), R3 (orphan variable), R4 (no anchor) |
| `InlineGenerationTest` | `InlineGenerator` and `AnnotationReader`; interface templates; two templates in same parent |

---

## Module Structure

```
permuplate-parent/
├── permuplate-annotations/     @Permute, @PermuteDeclr, @PermuteParam, @PermuteTypeParam,
│                               @PermuteReturn, @PermuteMethod, @PermuteExtends, @PermuteVar
├── permuplate-core/            EvaluationContext (with JEXL functions), transformers:
│                               PermuteDeclrTransformer, PermuteParamTransformer,
│                               PermuteTypeParamTransformer
├── permuplate-ide-support/     AnnotationStringAlgorithm (matching, rename, validation)
├── permuplate-processor/       APT entry point (PermuteProcessor) — thin shell on core
├── permuplate-maven-plugin/    Maven Mojo + InlineGenerator + AnnotationReader
├── permuplate-apt-examples/    APT examples (ProductFilter2, AuditRecord2, etc.)
├── permuplate-mvn-examples/    Maven plugin examples (Handlers inline)
└── permuplate-tests/           All unit tests using Google compile-testing
```

---

## Key Architecture: How Each Feature Works

### Pipeline order in InlineGenerator.generate()

```
1. Rename class + constructors
2. PermuteTypeParamTransformer.transform()   — class-level type param expansion (G1)
3. applyPermuteMethod()                      — @PermuteMethod inner j loop (G3)
   └─ For each clone: transformMethod()      — method-level type params (G4)
   └─ For each clone: applyPermuteReturn()   — return type per j
   └─ For each clone: processMethodParamDeclr() — @PermuteDeclr on params per j
4. PermuteDeclrTransformer.transform()       — @PermuteDeclr on fields/ctor/for-each
5. PermuteParamTransformer.transform()       — @PermuteParam sentinel expansion
6. collectExplicitReturnMethodNames()        — track explicit before stripping
7. applyPermuteReturn()                      — explicit @PermuteReturn (G2b)
8. applyImplicitInference()                  — T${j} return + param inference (G2b)
9. applyExtendsExpansion()                   — extends clause expansion (G3)
10. Strip @Permute annotation
```

### Pipeline order in PermuteProcessor.generatePermutation()

```
1. Clone + rename class + constructors
2. PermuteTypeParamTransformer.transform()   — class-level type param expansion (G1)
3. PermuteDeclrTransformer.transform()       — @PermuteDeclr on fields/ctor/for-each/method params
4. PermuteParamTransformer.transform()       — @PermuteParam sentinel expansion
5. applyPermuteMethodApt()                   — @PermuteMethod APT explicit (G3)
6. applyPermuteReturn()                      — explicit @PermuteReturn (G2b)
7. Strip @Permute annotation
8. Write generated file via Filer
```

### Critical design decisions

| Decision | Why |
|---|---|
| `alpha(n)` and `lower(n)` registered as JEXL lambda scripts in MapContext (NOT via JexlBuilder.namespaces) | JEXL3's uberspect does not autobox `Integer` to `int` for static method dispatch. Functions are available without prefix in all `${...}` expressions. Range 1–26; out-of-range throws at generation time. |
| `typeArgList(from, to, style)` returns `""` when `from > to` | Empty-range case for growing type arg lists. Styles: `"T"` → `T1,T2,...`, `"alpha"` → `A,B,...`, `"lower"` → `a,b,...`. Unknown style throws at generation time. |
| `@PermuteTypeParam` implicit expansion | When `@PermuteParam(type="T${j}")` references a class type parameter, class type params auto-expand (no annotation needed). Maven inline only — APT templates must compile with fixed type params. |
| `@PermuteTypeParam` R1 restriction | Return type referencing an expanding type param → compile error (ambiguous across permutations). |
| Method-level `@PermuteTypeParam` R3 omitted | The sentinel (`PB`, `A`) is an arbitrary placeholder — it need not match the generated names (`T1`, `B`, etc.). Class-level R3 is retained. |
| `@PermuteReturn` implicit inference Conditions 1+2 | (1) return type base class in generated set; (2) type args = declared fixed params + undeclared `T+number` growing tip. Both must hold. Maven inline only. |
| Boundary omission | Method omitted when return type class not in generated set. Applies to both `@PermuteReturn` (explicit) and implicit inference. `when="true"` overrides. |
| Leaf node pattern | Last generated class has `join()` omitted — mirrors hand-written Drools pattern. Empty j range for `@PermuteMethod` is the multi-join equivalent. |
| `T${j}` naming enables zero-annotation inference | Processor needs numeric suffix to identify the growing tip. `alpha(j)` names have no numeric pattern — inference does not fire. Use explicit `@PermuteReturn` for letter-based naming. |
| `@PermuteDeclr` method parameter validation deferred | Sentinel type (`Object`) deliberately doesn't match annotation string (the actual generated type). `validatePrefixes()` does NOT cover method params — a comment explains this. |
| `@PermuteMethod.to` is optional | Inferred as `@Permute.to - i`. Explicit `to` + `strings={"max=N"}` is the APT workaround for cross-module deps only. |
| `applyPermuteMethod()` runs before PermuteDeclrTransformer | Each overload clone has `@PermuteDeclr` processed with inner (i,j) context. Downstream transform sees no remaining annotations on these methods. |
| Extends clause expansion uses name-prefix family matching | `prefixBeforeFirstDigit()` guards against third-party classes that happen to share the template's embedded digit. `"Join"` expands; `"External"` does not. |
| Two-pass scan for dependency ordering | Both APT (`RoundEnvironment.getElementsAnnotatedWith`) and Maven (file scan) scan all templates first, build generated class set + dependency graph, then generate in topological order. |
| `applyPermuteReturnToSingleMethod()` has no boundary omission | In `@PermuteMethod` inner loop, the leaf-node mechanism is the empty j range, not class-name lookup. `when` expression guard still applies. |

---

## Open Question (Not Yet Implemented)

### Moving example tests into the example module

**The issue:** `ExampleTest.java` and `DogFoodingTest.java` live in `permuplate-tests/` but test templates from `permuplate-apt-examples/`. The template files exist as duplicates in both modules. Tests should co-locate with what they test.

**The design decision needed:** How to make `ProcessorTestSupport` (which lives in `permuplate-tests/src/test/java/`) accessible to `permuplate-apt-examples/src/test/java/`:

- **Option A (recommended):** Publish `permuplate-tests` as a Maven test-jar. `permuplate-apt-examples` depends on it via `<type>test-jar</type>`. Standard Maven pattern, no code duplication.
- **Option B:** Extract `ProcessorTestSupport` to a new `permuplate-test-support` module. Cleanest architecture but adds a module.
- **Option C:** Copy needed helpers into `permuplate-apt-examples`. Some duplication but zero new dependencies.

**Work involved:** Create `permuplate-apt-examples/src/test/java/`, add test dependencies to its pom.xml, move `ExampleTest.java` + `DogFoodingTest.java`, update `templateSource()` to look in `src/main/java/` instead of `src/test/java/`, remove duplicate template files from `permuplate-tests/`.

---

## Deferred Features (TODOs — Not Started)

| TODO | Idea | Why deferred |
|---|---|---|
| **TODO-1** | `@PermuteParam` fully implicit — when parameter type is a T${j} class type param and name follows `name${j}`, infer `@PermuteParam` automatically | Complex coupling: `to` inference relies on outer loop being 1:1 with class type params; edge cases unclear |
| **TODO-2** | Self-return inference — when `filter()` returns the same class as the template, auto-expand return type to match generated class | G2 Condition 2 fails (all type args declared, no undeclared tip); new mechanism needed |
| **TODO-3** | `@PermuteDeclr` implicit on fields — when field type is a generated class with matching numeric suffix, infer without annotation | Low impact; explicit annotation is not painful |

---

## Things NOT Yet Done (New Gaps Found)

Two structural gaps identified during RuleBuilder analysis that have no implementation plan:

1. **Growing switch statements** (`BaseTuple.get(int index)` / `set(int index)`) — each Tuple level adds a case. No Permuplate mechanism exists. Workaround: use array-based implementation (`Object[]`) instead of switch; typed getters/setters are covered by G4 `@PermuteMethod.name`.

2. **`@PermuteReturn.typeArgs` with mixed fixed+growing** — partially addressed by `typeArgs="DS, ${typeArgList(1, i, 'T')}"`. The `extensionPoint()` case (returns `RuleExtendsPoint${i+1}<DS, T1,...,T${i}>`) needs this. Designed in G4 spec; not yet implemented in a test.

---

## Core Design Principles

### APT vs Maven Plugin

**Maven plugin (inline mode):**
- Templates in `src/main/permuplate/` — NOT on compile path
- Two-pass: scan all → dependency graph → generate in topological order
- Supports all implicit inference (`T${j}` naming → zero annotations)
- Use for `inline=true`, nested sibling classes

**APT mode:**
- Templates in `src/main/java/` — must be valid compilable Java
- Uses `RoundEnvironment` for all-templates scan (same-module)
- Explicit annotations required (`Object` sentinels, `@PermuteReturn`, `@PermuteDeclr`)
- Cross-module deps need explicit `to` + `strings={"max=N"}`

**Choosing between T${j} and alpha(j):**

| Approach | Naming | Annotation burden |
|---|---|---|
| Minimum annotations (Maven inline) | `T1, T2, T3` | Just `@Permute` — everything inferred |
| Single-letter names (Maven inline) | `alpha(j)` → `A, B, C` | Explicit `@PermuteReturn` + `@PermuteDeclr` required |
| APT mode | Any | Explicit `@PermuteReturn` + `@PermuteDeclr` + `Object` sentinels |

### Error reporting

All errors from `PermuteProcessor` must include at least `Element` location (element-level minimum). Attribute-level is preferred. No bare `messager.printMessage(ERROR, msg)` calls ever.

---

## Key Files to Read First When Resuming

1. `docs/gap-analysis.md` — master list of all gaps, TODOs, status
2. `CLAUDE.md` — non-obvious decisions table (now includes G1-G4 entries)
3. `README.md` — user-facing docs with all annotations documented
4. `docs/SESSION-HANDOFF.md` — this file

### If picking up the "example tests" task:
- `permuplate-tests/src/test/java/io/quarkiverse/permuplate/ExampleTest.java` — tests to move
- `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DogFoodingTest.java` — tests to move
- `permuplate-tests/src/test/java/io/quarkiverse/permuplate/ProcessorTestSupport.java` — helper that needs to be shared
- `permuplate-apt-examples/pom.xml` — needs test dependencies added
- The template files listed in `permuplate-tests/src/test/java/.../example/` that are duplicates of `permuplate-apt-examples/src/main/java/.../example/`: `AuditRecord2.java`, `BiCallable1x1.java`, `ProductFilter2.java`, `ValidationSuite.java` — can be removed from `permuplate-tests/` after the move

---

## Annotation API Quick Reference

| Annotation | Target | Purpose |
|---|---|---|
| `@Permute` | `CLASS`, `METHOD` | Outer loop — drives N classes or N method overloads |
| `@PermuteVar` | (on `@Permute.extraVars`) | Additional integer loop axes for cross-product generation |
| `@PermuteDeclr` | `FIELD`, `LOCAL_VARIABLE`, `PARAMETER` | Renames type (always) and identifier (when `name` non-empty) per permutation |
| `@PermuteParam` | `PARAMETER` | Expands a sentinel parameter into N generated params; rewrites call-site anchors |
| `@PermuteTypeParam` | `TYPE_PARAMETER` | Expands a sentinel type parameter (class or method level) into N type params |
| `@PermuteReturn` | `METHOD` | Controls return type per permutation; implicit inference in Maven inline + T${j} naming |
| `@PermuteMethod` | `METHOD` | Generates multiple overloads via inner j loop; `name` for distinct method names |
| `@PermuteExtends` | `TYPE` | Explicit override for extends/implements clause expansion |
