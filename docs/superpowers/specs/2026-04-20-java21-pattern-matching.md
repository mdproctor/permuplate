# Java 21 Pattern Matching Completeness Design

## Goal

Two independent improvements to Java 21 support:

**A — `@PermuteCase` arrow-switch support:** Extend `PermuteCaseTransformer` to detect and generate arrow-switch entries (`case N -> body`) when the template switch uses arrow form, in addition to the existing colon-switch support.

**B — APT source-level validation:** When `@PermuteSwitchArm` is used in APT mode and the project compiles below Java 21, emit a clear compiler error pointing at the annotated class rather than letting the consumer get a confusing javac parse error about "patterns in switch are a preview feature".

---

## A — `@PermuteCase` arrow-switch support

### Problem

`PermuteCaseTransformer` always produces `SwitchEntry.Type.STATEMENT_GROUP` entries (colon-switch). If the template method uses an arrow-switch (`case N -> body` or `return switch (x) { case N -> ... }`), inserting colon-style entries produces syntactically invalid Java — you cannot mix colon and arrow cases in one switch.

Also, `PermuteCaseTransformer` only handles `SwitchStmt`. Templates using a switch expression (`return switch (x) { ... }`) are silently ignored.

### Design

**Detection:** After finding the switch in the method body, inspect the existing entries. If any entry has `SwitchEntry.Type.EXPRESSION`, `BLOCK`, or `THROWS_STATEMENT`, the switch is in arrow form. Otherwise it is colon form (existing behavior unchanged).

**Switch discovery:** Extend to handle both `SwitchStmt` and `SwitchExpr`. Introduce a `findSwitchEntries(MethodDeclaration)` helper mirroring the one in `PermuteSwitchArmTransformer` — tries `SwitchStmt` first, falls back to `SwitchExpr`.

**Arrow-form entry construction:** For each `k` in `[from, to]`:
- Evaluate `index` and `body` templates as today
- Build the arm text: `"case <index> -> <blockBody>"` where:
  - `blockBody = body.trim()` if body starts with `{` → `Type.BLOCK`
  - otherwise `blockBody = "{ " + body + " }"` after auto-appending `;` if missing → `Type.BLOCK`
- Parse with JAVA_21 level (already the global level since the @PermuteSwitchArm upgrade), extract first `SwitchEntry`
- Insert before `default` arm (same `findDefaultArmIndex` logic)

**Backward compatibility:** Existing colon-switch templates work exactly as before. The only new code path fires when arrow form is detected.

**No API changes.** `@PermuteCase` annotation attributes (`varName`, `from`, `to`, `index`, `body`) are unchanged.

### Files

| Action | Path |
|---|---|
| Modify | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteCaseTransformer.java` |
| Create | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteCaseArrowTest.java` |

### Tests

**Unit/correctness tests (new `PermuteCaseArrowTest.java`):**

1. `testArrowIntegerCasesInsertedBeforeDefault` — template with `return switch (i) { case 0 -> 0; default -> -1; }`, `@PermuteCase` inserts `case 1 ->`, `case 2 ->`. Assert arrow form in generated source.
2. `testArrowSwitchStatementNotExpression` — standalone `switch (x) { case 0 -> doA(); default -> {} }` (SwitchStmt arrow form). Assert new integer arms inserted in arrow style.
3. `testEmptyRangeArrowSwitch` — from > to on arrow-switch leaves switch unchanged.
4. `testArrowBodyAsExpressionNoSemicolon` — body `"x * 2"` (no `;`) auto-gets semicolon, parses cleanly.
5. `testArrowBodyAsBlock` — body `"{ System.out.println(${k}); yield ${k}; }"` — block body passes through.
6. `testColonSwitchUnchanged` — existing colon-switch template still generates `Type.STATEMENT_GROUP` entries (regression guard).
7. `testGuardOnArrowCaseNotSupported` — `@PermuteCase` has no `when` attribute; guard conditions are `@PermuteSwitchArm`'s domain. Confirm coexistence compiles cleanly.

**Happy path / integration tests (add to existing `PermuteCaseTest.java`):**

8. `testArrowSwitchExpressionEndToEnd` — full compile-testing test: template uses `return switch`, `@PermuteCase` adds arms, generated class compiles and the switch is syntactically valid Java 21.

**End-to-end (APT example):**

Add a new example file `permuplate-apt-examples/.../ArrowDispatch2.java` demonstrating `@PermuteCase` with arrow-switch.

---

## B — APT source-level validation for `@PermuteSwitchArm`

### Problem

When a user adds `@PermuteSwitchArm` to a template and compiles with `--release 17`, the APT processor generates a `.java` file containing `case Integer n -> ...`. The javac compile step then fails with:

```
error: patterns in switch are a preview feature and are disabled by default.
  (use --enable-preview to enable patterns in switch)
```

This error points at the *generated file*, not the template. The user has no idea that `@PermuteSwitchArm` is the cause.

### Design

In `PermuteProcessor.process()`, after resolving elements annotated with `@Permute`, check whether any method on the type carries `@PermuteSwitchArm`. If so, verify the source version is at least Java 21.

**Check:**
```java
if (processingEnv.getSourceVersion().compareTo(SourceVersion.RELEASE_21) < 0) {
    error("@PermuteSwitchArm generates Java 21 pattern matching syntax. "
        + "Set --release 21 (or later) in your maven-compiler-plugin configuration.",
        typeElement);
}
```

`SourceVersion.RELEASE_21` is available in Java 21+ javac. The processor's own compilation target is already Java 17 source, but `SourceVersion` values above 17 exist and are comparable.

**Scan approach:** Use JavaParser to scan the parsed AST for `@PermuteSwitchArm` (simple name or FQN) on any method in the template class. Fire the check immediately after the class is parsed, before any transformation.

**Emit as error, not warning.** The generated output is guaranteed to fail compilation below Java 21, so a warning would be misleading.

### Files

| Action | Path |
|---|---|
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/DegenerateInputTest.java` |

### Tests

**Correctness / degenerate input tests (add to `DegenerateInputTest.java`):**

1. `testPermuteSwitchArmBelowJava21EmitsError` — compile with forced source level 17 (or check: if the test environment is Java 21+, the check may not trigger without overriding the source version returned by `processingEnv`). **Implementation note:** In compile-testing, the source version presented to the processor is determined by the javac `-source` flag. Pass `"-source", "17"` to `Compiler.javac()` options to force the check. Assert `compilation.failed()` and `hadErrorContaining("RELEASE_21")` or `hadErrorContaining("--release 21")`.
2. `testPermuteSwitchArmAtJava21DoesNotError` — compile without `-source 17` (uses default, which is 21+ in this project). Assert compilation succeeds.

**Happy path / integration:**

3. `testPermuteSwitchArmWorks` — already covered by existing `PermuteSwitchArmTest.java`. No new test needed; the existing suite confirms correctness at Java 21+.

---

## Dependency

A and B are fully independent. Implement in either order. Suggested order: B first (smaller, pure validation), then A (new transformer path).

## Out of scope

- `@PermuteCase` guard conditions (`when=`) — that is `@PermuteSwitchArm`'s domain.
- `@PermuteSwitchArm` validation of guard expressions — those already work.
- Maven plugin source-level validation — the Maven plugin (`PermuteMojo`) runs at `generate-sources` time in a JVM that is already at the project's Java version. The generated files are compiled by the project's own javac invocation. If the project uses `--release 17`, the compilation will fail; but the error is more legible because the Maven plugin writes the file explicitly (not via APT stream). Low priority.
