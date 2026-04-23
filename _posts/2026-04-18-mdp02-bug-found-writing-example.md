---
layout: post
title: "The Bug Found Writing the Example"
date: 2026-04-18
phase: 4
phase_label: "Phase 4 — Documentation and Maintenance"
type: phase-update
entry_type: note
subtype: diary
projects: [permuplate]
---

Four annotations had no runnable example — `@PermuteAnnotation`,
`@PermuteThrows`, `@PermuteCase`, and `@PermuteImport`. We added
`AnnotatedCallable2.java` to cover all four in one place. Writing it
found a bug.

## The silent failure in @PermuteCase

The first attempt used a string literal in the `body` attribute:

```java
@PermuteCase(varName = "k", from = "2", to = "${i}",
             index = "${k}", body = "return \"arg\" + ${k};")
```

The generated switch had the right number of cases — but each was empty.
No body. No error. No warning. Just `case 2:` falling through to `default:`.

The cause is in `PermuteCaseTransformer.buildSwitchEntry()`. It wraps the
evaluated body in `{...}` and calls `StaticJavaParser.parseBlock()`. If that
throws — and it does when the body contains Java string literals — the catch
block returns `new BlockStmt()` silently:

```java
try {
    block = StaticJavaParser.parseBlock("{" + bodyStr + "}");
} catch (Exception e) {
    block = new BlockStmt();  // silent empty fallback
}
```

The fix: avoid string literals inside `body`. Primitives work. Method calls
work. `return String.valueOf(${k});` generates correctly.
`return "arg" + ${k};` disappears without a word.

The symptom — correctly-numbered cases with no statements — points at the
wrong `from`/`to` range or annotation placement before it points at a parse
failure. It took reading the transformer source to find it.
