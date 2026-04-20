# Permuplate Roadmap

Prioritised list of improvements beyond the current feature set. Updated 2026-04-20 (batch 3).

---

## Completed (shipped)

| What | Notes |
|---|---|
| IntelliJ plugin | Rename propagation, generated-file detection, safe delete, inspections. Phase 3, Apr 8–9. |
| String-set iteration (`values=`) | `@Permute(values={"Foo","Bar",...})` — loops over named strings, not integers. |
| `@PermuteFilter` | Skip specific permutations via JEXL boolean. Replaced the planned `@PermuteIf`. |
| Record templates | `@Permute` on `record` types — `Tuple2Record.java` is the canonical example. |
| `@PermuteAnnotation` | Adds annotations to generated classes/methods/fields with optional JEXL guard. |
| `@PermuteThrows` | Adds throws clauses to generated methods with optional JEXL guard. |
| `@PermuteSource` + `@PermuteDelegate` | Template composition — derive a class family from another generated family. |
| `@PermuteBody` | Replaces an entire method or constructor body per permutation. |
| `@PermuteEnumConst` | Expands a sentinel enum constant into a sequence of constants per permutation. |
| Enum templates | `@Permute` on `enum` types — `PriorityEnum1.java` is the canonical example. |
| Sealed class `permits` expansion | Maven plugin inline mode auto-expands the `permits` clause when the template name is used as a placeholder. |
| `permuplate-test-support` module | Fluent `assertGenerated(compilation, className).hasField(...).hasCase(...)` assertion API. |
| `alwaysEmit=true` on `@PermuteReturn` | Self-documenting alternative to `when="true"` for boundary omission opt-out. |
| `capitalize()` / `decapitalize()` JEXL functions | First-character case manipulation for string-set permutations. |
| `@PermuteBody` in `@PermuteMethod` context | Body templates can now reference the inner method variable (e.g. `${n}`). |
| `@PermuteVar` string-set axis | `values={"A","B"}` on `@PermuteVar` for cross-product with string variables. Fully wired; tests and docs added. |
| Better JEXL error messages | `@PermuteStatements` bad bounds and `@PermuteMethod` bad name templates now surface as compiler errors. |
| `@PermuteSwitchArm` | Generates Java 21+ arrow-switch pattern arms (`case Type var -> body`). Full IntelliJ rename propagation for `pattern` attribute. |

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
- **Ant / Bazel / Buck support** — long tail after Gradle

### New annotation capabilities
- **`@PermuteIf` on the whole class** — suppress generation of an entire class for a specific value (range alone cannot skip a value in the middle)
- **`@PermuteVar` string-set axis on `@PermuteMethod`** — `@PermuteMethod` currently only supports integer ranges; string-set axes are not yet supported
- **`@PermuteSwitchArm` guard in APT mode** — guards work in inline (Maven plugin) mode; APT mode doesn't validate the generated Java 21 syntax

### Developer experience
- **VS Code extension** — port the IntelliJ plugin's template-aware navigation and refactoring to VS Code (tracked as issue #4)
- **More JEXL error message coverage** — `@PermuteStatements` bounds and `@PermuteMethod` name are improved; remaining silent-catch sites (inference steps) could still be hardened

---

## Won't do (and why)

- **Runtime bytecode generation** — contradicts the core value proposition (compile-time, IDE-navigable, zero runtime dep)
- **External template files (Freemarker, Mustache)** — contradicts the core value proposition (template must be valid, compilable Java)
