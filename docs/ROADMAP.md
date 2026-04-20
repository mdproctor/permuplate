# Permuplate Roadmap

Prioritised list of improvements beyond the current feature set. Updated 2026-04-20 (batch 5).

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
