# Permuplate Roadmap

Prioritised list of improvements beyond the current feature set. Updated 2026-04-20 (batches 6–8).

---

## Completed (shipped)

| What | Notes |
|---|---|
| IntelliJ plugin | Rename propagation, generated-file detection, safe delete, inspections. Phase 3, Apr 8–9. |
| String-set iteration (`values=`) | `@Permute(values={"Foo","Bar",...})` — loops over named strings, not integers. |
| `@PermuteFilter` | Skip specific permutations via JEXL boolean. Replaces `@PermuteIf` (stale roadmap item — already done). |
| Record templates | `@Permute` on `record` types. |
| `@PermuteAnnotation` + `@PermuteThrows` | Add annotations / throws clauses per permutation with optional JEXL guard. |
| `@PermuteSource` + `@PermuteDelegate` | Template composition. |
| `@PermuteBody` | Replaces an entire method or constructor body per permutation. Also works inside `@PermuteMethod` clones. |
| `@PermuteEnumConst` + Enum templates | `@Permute` on `enum` types; `@PermuteEnumConst` expands sentinel constants. |
| Sealed class `permits` expansion | Maven plugin auto-expands `permits` clause when template name is placeholder. |
| `permuplate-test-support` module | Fluent `assertGenerated(compilation, className).hasField(...)` assertion API. |
| `alwaysEmit=true` on `@PermuteReturn` | Self-documenting boundary omission opt-out. |
| `capitalize()` / `decapitalize()` + `@PermuteVar` string-set | Case JEXL functions; `@PermuteVar(values={...})` cross-product axis fully wired. |
| Better JEXL error messages | `@PermuteStatements` bounds and `@PermuteMethod` name failures surface as compiler errors. |
| `@PermuteSwitchArm` | Java 21+ arrow-switch pattern arms. IntelliJ rename propagation for `pattern`. |
| `@PermuteCase` arrow-switch | Detects and generates arrow-form entries for Java 21 switch statements and expressions. |
| `@PermuteSwitchArm` APT source-level validation | Clear error when project source level < Java 21. |
| `@PermuteMethod` string-set axis | `values={"Sync","Async"}` generates one overload per string. Mirrors `@Permute(values=...)` semantics. |
| `@PermuteSelf` | Method-level: return type = current generated class + type params. Zero-annotation self-return for fluent builders. |
| Self-return inference (Maven) | Methods with `return this;` body and `Object` sentinel auto-get the generated class as return type. No annotation needed. |
| `@PermuteDefaultReturn` | Class-level default return type for all `Object`-returning methods lacking explicit `@PermuteReturn`. |
| `@Permute macros=` | Named JEXL expressions evaluated per permutation; available as `${name}` throughout the template. |
| `@PermuteMethod macros=` | Named JEXL expressions evaluated with the inner method variable in scope. |
| `@PermuteReturn replaceLastTypeArgWith=` | Return type = current class type params with last one replaced. Eliminates ternary typeArgs on type-narrowing methods. |
| Alpha growing-tip inference | When `@PermuteReturn` has `className` but no `typeArgs` + single-value alpha `@PermuteTypeParam`, `typeArgs` is inferred automatically. |
| Sealed JoinFirst/JoinSecond hierarchy | Generated families are `sealed`; enables Java 21 pattern dispatch over the builder arity. |
| ADR-0006 | Documents why `extendsRule()` duplication is structurally unavoidable — constraint reference for real Drools integration. |
| not()/exists() string-set conversion | Renamed `NegationScope→NotScope`, `ExistenceScope→ExistsScope`; `addNegation→addNot`, `addExistence→addExists`. `@PermuteMethod(values={"not","exists"})` + `capitalize()` replaces 3 hand-coded ternaries. |
| `max()`/`min()` JEXL built-ins | New `max(a,b)` and `min(a,b)` functions in `EvaluationContext`; `max(2,i)` eliminates ternary in `filterLatest`. |
| `typeArgList` custom prefix | Unknown style = literal prefix+index (`V→V1,V2`); enables variable-prefix type arg lists without special-casing each style. |
| Variable filter overloads templated | `@PermuteMethod(m=2..3)` + G4 `@PermuteTypeParam` replaces two hand-coded filter overloads; eliminates ~40 lines of duplication. |
| Method macros (bilinear, extendsRule, OOPath) | `macros=` on `@PermuteMethod` for `joinAll`/`joinRight` (bilinear join), `prevAlpha` (extendsRule), `outerJoin`/`prevTuple` (OOPath). |
| `@PermuteReturn(typeParam=)` + repeatability | `typeParam=` sets return type to a named type parameter; `@PermuteReturn` is now `@Repeatable` (first matching `when=` wins); `@PermuteBody` gains `when=` + `@Repeatable`. `Path2` unified into single template. |
| `@PermuteMacros` | New annotation: shared JEXL macros on outer class; all nested `@Permute` templates inherit (innermost wins). Eliminates cross-template macro duplication. |
| `@PermuteMixin` | New annotation: inject annotated methods from a mixin class before the transform pipeline. Solves ADR-0006 — `ExtendsRuleMixin` is shared by `RuleBuilderTemplate` and `ParametersFirstTemplate`. |
| Constructor super-call inference | Auto-insert `super(p1..p_{N-1})` when template extends-previous and constructor has no existing super-call and no `@PermuteStatements`. Explicit `@PermuteStatements` always wins. Removes explicit annotation from `Tuple1`. |
| `@PermuteExtendsChain` | New annotation: extends-previous-in-family shorthand. Generates `extends ${className}${i-1}<typeArgList(1,i-1,'alpha')>`. Explicit `@PermuteExtends` always wins. Applied to `BaseTuple.Tuple1`. |

---

## Priority order

| Priority | What | Why |
|---|---|---|
| 1 | Maven Central release | Unblocks everything else. Change group ID to `io.github.mdproctor` for instant namespace approval, or apply to Quarkiverse for `io.quarkiverse` approval. |
| 2 | Gradle plugin | ~60% of the Java ecosystem uses Gradle. Inline generation mode is entirely unavailable to Gradle projects today. |
| 3 | Quarkus extension | The `io.quarkiverse` group ID implies a promise not yet fulfilled. A real extension adds dev mode hot reload (templates reprocess on save), a Dev UI panel showing template→generated mappings, and native image compatibility via Quarkus build-time steps. |
| 4 | VS Code extension | Algorithm (`permuplate-ide-support`) and porting guide are ready. Parked as issue #4. |
| 5 | Kotlin / KSP | Kotlin Symbol Processing is the KSP equivalent of APT. A Permuplate KSP plugin would serve the Kotlin ecosystem with the same template model. Higher effort; different audience. |

---

## Feature ideas (not yet prioritised)

### Build tooling
- **Gradle plugin** — inline generation mode unavailable to Gradle users (Priority 2 above, listed here for completeness)
- **Ant / Bazel / Buck support** — long tail after Gradle

### Developer experience
- **VS Code extension** — port the IntelliJ plugin to VS Code (parked, issue #4)
- **More JEXL error message coverage** — remaining silent-catch sites in inference steps could be hardened further

---

## Won't do (and why)

- **Runtime bytecode generation** — contradicts the core value proposition (compile-time, IDE-navigable, zero runtime dep)
- **External template files (Freemarker, Mustache)** — contradicts the core value proposition (template must be valid, compilable Java)
- **`@PermuteCase` guard condition** — integer case labels (`case 1:`, `case 2 ->`) cannot have guards in Java 21+. Guards are only valid for pattern labels (`case Integer n when guard ->`). Use `@PermuteSwitchArm` which already has `when=` for guarded pattern cases.
