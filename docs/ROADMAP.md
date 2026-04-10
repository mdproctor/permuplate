# Permuplate Roadmap

Prioritised list of improvements beyond the current feature set. Updated 2026-04-10.

---

## Priority order

| Priority | What | Why |
|---|---|---|
| 1 | Maven Central release | Unblocks everything else. Change group ID to `io.github.mdproctor` for instant namespace approval, or apply to Quarkiverse for `io.quarkiverse` approval. |
| 2 | Gradle plugin | ~60% of the Java ecosystem uses Gradle. Inline generation mode is entirely unavailable to Gradle projects today. |
| 3 | Quarkus extension | The `io.quarkiverse` group ID implies a promise not yet fulfilled. A real extension adds dev mode hot reload (templates reprocess on save), a Dev UI panel showing template→generated mappings, and native image compatibility via Quarkus build-time steps. |
| 4 | Named type sets | Loops over *types* instead of *integers* — e.g. generate `StringColumn`, `IntegerColumn`, `LongColumn` from one template. Unlocks a completely different class of use cases: DAOs, codecs, serializers, column types. |
| 5 | `@PermuteIf` | Conditionally include/exclude a method or field for a specific permutation value. Currently the only mechanism is `from`/`to` range — you cannot skip one value in the middle. |
| 6 | Record generation | `@Permute` on a `record` type. Records are immutable so no constructor body accumulation is needed — much simpler than class templates. Increasingly idiomatic Java. |
| 7 | `@PermuteAnnotation` | Vary annotations *on* the generated class per permutation, e.g. add `@FunctionalInterface` only at arity 1, or `@Deprecated` above a threshold. |
| 8 | Kotlin / KSP | Kotlin Symbol Processing is the KSP equivalent of APT. A Permuplate KSP plugin would serve the Kotlin ecosystem with the same template model. Higher effort; different audience. |

---

## Feature ideas (not yet prioritised)

### Build tooling
- **Ant / Bazel / Buck support** — long tail after Gradle

### New annotation capabilities
- **Enum generation** — `@Permute` on an `enum` type; `@PermuteCase` generating the constants
- **Sealed class `permits` expansion** — automatically add generated siblings to a sealed interface's `permits` clause
- **`@PermuteBody`** — replace an entire method body per permutation (more powerful than `@PermuteStatements` which only inserts at first/last)
- **`@PermuteIf` on the whole class** — suppress generation of an entire class for a specific value (range alone cannot skip a value in the middle)

### Developer experience
- **Better JEXL error messages** — raw JEXL exceptions surfaced as annotation processor errors are hard to parse; a smarter error layer would help
- **`permuplate-test-support` module** — fluent API for verifying generated output, e.g. `assertGenerated("Join3").hasField("Callable3 c3").hasMethod("join(Object, Object, List)")`, so template authors don't need to write raw string assertions
- **VS Code extension** — port the IntelliJ plugin's template-aware navigation and refactoring to VS Code (tracked as issue #4)

### Language coverage
- **Java 21+ pattern matching in switch** — ensure `@PermuteCase` generates syntactically valid pattern match arms, not just classic `case N:` entries

---

## Won't do (and why)

- **Runtime bytecode generation** — contradicts the core value proposition (compile-time, IDE-navigable, zero runtime dep)
- **External template files (Freemarker, Mustache)** — contradicts the core value proposition (template must be valid, compilable Java)
