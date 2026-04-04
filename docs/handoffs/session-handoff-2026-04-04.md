# Permuplate — Session Handoff
**Date:** 2026-04-04
**Status:** Active development. 131 processor tests + 15 Drools example tests. All green.

---

## What This Project Is

**Permuplate** is a Java annotation processor that generates type-safe arity permutations from a single template class. You annotate a class with `@Permute(varName="i", from=2, to=6, className="Join${i}")` and the tool generates 5 classes — one per value of i — via AST transformation using JavaParser.

The key insight that makes this genuinely novel: **the template is valid, compilable Java**. Every comparable tool (Vavr generators, Freemarker, RxJava scripts) uses templates that are NOT valid Java. Permuplate templates compile, navigate in the IDE, and type-check. The generator runs at `generate-sources` time (Maven) or as an annotation processor (APT).

The primary motivation is eliminating the boilerplate in the Drools RuleBuilder DSL — which has hand-written `Join2First`, `Join3First`, … `Join5First` classes that are nearly identical, differing only in arity.

---

## The Journey (Chronological)

### Phase 0: Foundation (initial commit)
Basic `@Permute` annotation processor: for each value of `i`, clone a class, rename it, write a new `.java` file. Uses JavaParser for AST manipulation, JEXL3 for `${...}` expression evaluation.

### Phase 1: `@PermuteDeclr` and `@PermuteParam`
- `@PermuteDeclr` — renames a field/variable's type and name, propagates the rename through its scope (field → class-wide, constructor param → constructor body, for-each var → loop body)
- `@PermuteParam` — the "sentinel" pattern: one annotated parameter expands to a sequence; anchor at call sites auto-expanded to match

### Phase 2: `@PermuteVar` (cross-product)
Extra loop axes: `@PermuteVar(varName="k", from=2, to=4)` creates a cross-product. `BiCallable${i}x${k}` generates 9 classes.

### Phase 3: Inline mode + Maven plugin
APT can only CREATE new files, not modify existing parent files. Inline mode (`inline=true`) required a Maven plugin that runs at `generate-sources`, reads `src/main/permuplate/` templates, transforms them, and writes augmented parent class files to `target/generated-sources/permuplate/`. Huge architectural split: APT path vs Maven plugin inline path.

### Phase 4: permuplate-core + IDE support
Refactored shared transformer classes into `permuplate-core` (used by both APT and Maven plugin). Added `permuplate-ide-support` with `AnnotationStringAlgorithm` — substring matching (R2), orphan variable detection (R3), no-anchor detection (R4).

### Phase 5: N4 — Expression Functions
Three built-in JEXL functions:
- `alpha(n)` → A, B, C, … (1-indexed, 1-26 range)
- `lower(n)` → a, b, c, …
- `typeArgList(from, to, style)` → "T1, T2, T3" or "A, B, C" depending on style

Critical bug discovered here: JEXL3's uberspect does NOT autobox `Integer` to `int` for static method dispatch. Had to register these as JEXL lambda scripts in `MapContext` rather than via `JexlBuilder.namespaces`.

### Phase 6: G1 — `@PermuteTypeParam`
Two modes:
- **Explicit**: `@PermuteTypeParam(varName="j", from="1", to="${i}", name="${alpha(j)}")` on a type parameter — generates A, B, C type params. Supports bounds propagation.
- **Implicit**: when `@PermuteParam(type="T${j}")` references a class type parameter, the class type params auto-expand to match. Only fires in Maven plugin inline mode — APT templates must compile with fixed type params.

### Phase 7: G2 — `@PermuteReturn`
Return type narrowing by arity. Two sub-features:
- **G2a** — `@PermuteDeclr` extended to method parameters (previously only fields/vars). Changes parameter type; optional name change + rename propagation through method body.
- **G2b** — `@PermuteReturn(className="Join${i+1}First", typeArgs="...")` replaces a method's return type. Boundary omission: when evaluated class is NOT in generated set, method silently omitted. `when="true"` overrides this.

Implicit inference (inline mode only): if return type base class is in generated set AND type args follow `T+number` growing-tip pattern → auto-inferred without annotation.

### Phase 8: G3 — `@PermuteMethod` + Extends Expansion
Two sub-features:
- **@PermuteMethod(varName="j")** — generates multiple method overloads per class via inner j loop. `to` is optional (inferred as `@Permute.to - i`). Combined with `@PermuteReturn` for return type narrowing per overload.
- **Extends clause expansion** — auto-detects when the extends clause references a sibling class (same name prefix, same embedded number as template). Auto-renames and expands type args.

### Phase 9: G4 — Method-level `@PermuteTypeParam`
`@PermuteTypeParam` works on method type parameters inside `@PermuteMethod`. R3 prefix check intentionally NOT applied (sentinel name is arbitrary placeholder).

### Phase 10: Drools Phase 1 Example
Built `permuplate-mvn-examples/src/main/permuplate/.../drools/` — a self-contained, testable Drools RuleBuilder DSL approximation:

**Templates:**
- `Consumer1.java` → Consumer2..Consumer7 (`inline=false`, `keepTemplate=true`)
- `Predicate1.java` → Predicate2..Predicate7
- `JoinBuilder.java` — `inline=true` container with `Join0First` → Join1First..Join6First

**Infrastructure (hand-written):**
- `Person`, `Account`, `Order`, `Product`, `Transaction` — Java records
- `DataSource<T>` — generic list wrapper
- `Ctx` — record holding one DataSource per domain type
- `RuleDefinition<DS>` — records sources/filters/actions, executes cross-product join, test assertion API
- `RuleBuilder<DS>` — entry point: `from(String name, Function<DS, DataSource<A>> source)` → `JoinBuilder.Join1First<DS,A>`

**15 tests** covering structural assertions (sourceCount, filterCount, hasAction) and behavioural assertions (cross-product correctness, filter application, captured fact verification) at arities 1–6.

### Phase 11: G3 Alpha Fix (today, 2026-04-04)
Discovered and fixed two bugs in `applyExtendsExpansion()`:
1. **Forward-reference formula**: was `newNum = currentEmbeddedNum + 1` → produced `Join2First extends Join3Second` (wrong). Fixed to `newNum = currentEmbeddedNum` → `Join2First extends Join2Second` (same-N).
2. **Alpha naming silently skipped**: `allTNumber` check rejected `<DS, A>` type args. Fixed by adding alpha branch using `postG1TypeParams` (a list of post-G1-expansion type param names, already captured but not wired). New test: `testExtendsClauseAlphaNaming` (with DS prefix) and `testExtendsClauseAlphaNamingNoFixedPrefix` (without).

This unblocks **Drools Phase 2** (First/Second split).

---

## Current Architecture

```
permuplate-annotations/     @Permute, @PermuteDeclr, @PermuteParam, @PermuteVar,
                             @PermuteTypeParam, @PermuteReturn, @PermuteMethod
permuplate-core/            EvaluationContext (JEXL3), PermuteConfig, PermuteVarConfig
                             PermuteDeclrTransformer, PermuteParamTransformer,
                             PermuteTypeParamTransformer
permuplate-ide-support/     AnnotationStringAlgorithm (R2/R3/R4 validation)
permuplate-processor/       PermuteProcessor (APT) — thin shell over permuplate-core
permuplate-maven-plugin/    PermuteMojo + InlineGenerator — generate-sources phase
permuplate-apt-examples/    APT examples (Join2.java, ContextJoin2.java, JoinLibrary.java)
permuplate-mvn-examples/    Maven plugin examples + Drools DSL
permuplate-tests/           131 unit tests via Google compile-testing
```

**Build:** `/opt/homebrew/bin/mvn clean install` from repo root.
**Maven is at:** `/opt/homebrew/bin/mvn`

---

## Key Design Decisions

### APT vs Maven plugin split
APT can only CREATE new files. It cannot modify the parent class file to add nested classes (inline mode). Maven plugin runs pre-compilation and CAN modify the parent file. Result: `inline=true` is an error in APT mode with a helpful migration message.

### Template validity
The template is valid, compilable Java. This means the IDE can navigate it, the compiler validates it at template-class arity (from=1 means Join1First acts as the template). Generated files are written to `target/generated-sources/permuplate/` and are never committed.

### Two-pass scan
Both APT and Maven plugin scan ALL `@Permute` templates before generating ANY class. This builds the complete "generated names" set needed for boundary omission (knowing whether a return type is in the generated set). Topological ordering handles cross-template dependencies.

### G1 before G3
`PermuteTypeParamTransformer.transform()` runs BEFORE `applyExtendsExpansion()` in `InlineGenerator.generate()`. This is critical: the `postG1TypeParams` list (used by G3's alpha branch) must reflect post-expansion type params.

### JEXL lambda scripts for built-in functions
`alpha(n)`, `lower(n)`, `typeArgList(from, to, style)` are registered as JEXL lambda scripts in `MapContext` — NOT via `JexlBuilder.namespaces`. Reason: JEXL3's uberspect does not autobox `Integer` to `int` for static method dispatch. This took a while to figure out.

### Alpha naming requires explicit annotations everywhere
Single-letter names (A, B, C) have no numeric suffix — Permuplate's implicit inference uses `T+number` pattern to detect growing type params. If you use alpha naming, you MUST use `@PermuteTypeParam`, `@PermuteReturn`, and `@PermuteDeclr` explicitly throughout. This is deliberate: it matches Drools conventions.

---

## Current State Assessment

### What works well
- 131 processor tests + 15 Drools example tests — comprehensive coverage
- Both APT and Maven plugin modes — flexible integration
- Real-world Drools example validates the approach end-to-end
- `typeArgList()` function handles empty ranges gracefully (returns "" when from > to)
- Boundary omission correctly generates leaf nodes (Join6First has no join())
- G3 alpha fix now enables the First/Second split in Phase 2

### Known limitations and pain points

**1. join() returns raw type — arity-2+ lambdas need explicit casts**
After `join()`, the chain loses compile-time type parameters. `join()` returns raw `JoinNFirst` because the next arity's type params (B, C, etc.) are not in scope in the template class. Tests at arity 2+ need pre-typed `Function<Ctx, DataSource<?>>` constants and explicit casts like `((Person) a).age()`.

This is the biggest Phase 2 motivator: once the First/Second split is in place, `join(JoinNSecond)` can be typed properly.

**2. join() uses reflective instantiation (fragile)**
Since `rd.asNext()` (unchecked cast of RuleDefinition to JoinNFirst) causes `ClassCastException` at runtime (JVM inserts `checkcast`), `join()` in JoinBuilder uses reflection to create the next JoinFirst class by name-parsing. Works but is brittle — relies on the naming pattern `JoinNFirst` and `getEnclosingClass()`.

**3. keepTemplate=false for JoinBuilder**
The JoinBuilder template uses `keepTemplate=false` because the template class `Join0First` doesn't fit the generated naming scheme and would cause compilation issues if retained. This is specific to the alpha-naming + container-class pattern.

**4. G3 requires numeric class names**
`applyExtendsExpansion()` detects siblings by embedded numeric suffix. `JoinFirst` (no number) would not be detected. `Join0First` works; the `0` is the template's discriminator.

**5. Method bodies not transformed**
Permuplate only transforms annotation parameters, type declarations, and method signatures. Method bodies are left as-is. The JEXL expressions in `@PermuteDeclr(type="...")` etc. are evaluated, but `new Join${i+1}First(rd)` in a method body would be treated as literal Java, not a template expression.

**6. wrapPredicate arity truncation**
`RuleDefinition.wrapPredicate()` truncates the facts array to `m.getParameterCount() - 1` before invoking the predicate. This is because filters registered at arity 2 (Predicate3 with 3 params: ctx+a+b) receive the full fact tuple from the cross-product when later filters have higher arity. The truncation is semantically correct but surprising.

**7. Consumer/Predicate range is to=7, not to=6**
Consumer template uses `from=2, to=7` and `@PermuteTypeParam(to="${i-1}")`. At i=7, this gives Consumer7<DS,A,B,C,D,E,F> — 6 fact params. The fn() at Join6First calls Consumer7. This is correct but the `to=7` (not 6) is counterintuitive. The `to="${i-1}"` is also correct (not `to="${i}"` as the spec originally said — that was a spec bug).

---

## Open Questions

**1. ctx position in lambda signatures**
Currently `(ctx, a, b, c) -> ...`. The alternative is `(a, b, c, ctx) -> ...`. Decision deferred — review before finalising the Drools API.

**2. Self-return inference (TODO-2)**
`filter()` requires explicit `@PermuteReturn(when="true")` because self-return inference is not implemented. When the return type is the class itself, this could be inferred automatically.

**3. Move example tests to example module**
Currently example tests are in `permuplate-tests/`. Should they move to `permuplate-mvn-examples/src/test/` for better locality? Deferred (TODO-4).

---

## Roadmap

### Next: Drools Phase 2 — First/Second Split (NOW UNBLOCKED)
The G3 alpha fix enables this. Create two template families:
- `Join0Second<DS, A>` — generates Join1Second..Join6Second with `join()` methods (arity-advancing, leaf node at 6 via boundary omission)
- `Join0First<DS, A> extends Join0Second<DS, A>` — generates Join1First..Join6First with `filter()` (arity-preserving)

G3 auto-expands the extends clause: `Join2First<DS, A, B> extends Join2Second<DS, A, B>` correctly via the alpha branch.

`join()` on `JoinNSecond` returns `Join(N+1)First` (arity-advancing). In Phase 2, the parameter type of `join()` overloads will accept `JoinNSecond` — enabling `join(existingJoin2First)` since `Join2First extends Join2Second`.

**What this enables:** Multi-step joins — joining a pre-built 2-fact structure instead of a single source.

### TODO-2: Self-return inference
When a method's return type is the class itself (same class name, same type args), infer `@PermuteReturn` automatically. This would eliminate the need for `@PermuteReturn(className="Join${i}First", typeArgs="...", when="true")` on `filter()`.

### Drools Phase 3
- `not()` / negation groups (`Not2`, `Group2` pattern)
- `path()` traversal (OOPath — `path2()`, `path3()` via G4 method naming)
- `Variable<T>` cross-fact filters
- `params()` typed parameter binding

### Real Drools migration
Apply Permuplate to the actual Drools codebase at `/Users/mdproctor/dev/droolsoct2025/droolsvol2/`:
1. Consumer family (pure G1)
2. Predicate family (G1)
3. Join chain (G1 + G2 + G3)

Key files in real Drools:
- `RuleBuilder.java` — hand-written builder with Join2First..Join5First, Join2Second..Join5Second
- `RuleBuilderTest.java` — usage examples this example approximates
- `function/` — Consumer1..4, Predicate1..10, Function1..5 (all G1 candidates)

### IDE support improvements
`permuplate-ide-support` has `AnnotationStringAlgorithm` with R2/R3/R4 rules. Future work: IntelliJ plugin using this for real-time validation of annotation strings.

---

## Where Everything Lives

| Location | Purpose |
|---|---|
| `/Users/mdproctor/claude/permuplate/` | Main repo |
| `docs/superpowers/specs/` | Design specs for all features |
| `docs/superpowers/plans/` | Implementation plans |
| `permuplate-mvn-examples/DROOLS-DSL.md` | Detailed Drools DSL design doc |
| `permuplate-mvn-examples/src/main/permuplate/...drools/` | Drools templates |
| `permuplate-tests/src/test/java/io/quarkiverse/permuplate/` | All unit tests |
