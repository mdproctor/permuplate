---
layout: post
title: "JEXL completion: building an IntelliJ language from scratch"
date: 2026-04-23
type: phase-update
entry_type: note
subtype: diary
projects: [permuplate]
tags: [permuplate, intellij-plugin, jexl, language-injection]
---

Every `${...}` expression in a Permuplate template has been opaque since the
beginning. You write `@Permute(className="Join${i}", from="3", to="${max}")` and
the IDE treats the string contents as text. No completion when you type `${`,
no warning if you mistype the variable name, no hint when you can't remember
the signature of `typeArgList`. I wanted to fix that.

The first question was whether any of this already existed. JEXL is used widely
enough — Camel, Jenkins, various rule engines — that I expected something on the
JetBrains Marketplace. There wasn't. The closest thing was the Stapler plugin from
the Jenkins project: one 385-line inspection that calls the Apache Commons JEXL
parser and reports parse errors. No lexer, no language definition, no completion.
We borrowed the validation pattern (BSD-2-Clause, credited in the source) and built
the rest from scratch.

The architecture is a language injection stack. A custom `JexlLexer` tokenises
expressions — identifiers, operators, single-quoted strings, punctuation — and
feeds a flat `PsiParser` that produces no grammar tree. Every IDE service we needed
operates at the token level: the syntax highlighter maps token types to colour keys,
the completion contributor iterates identifiers across the token stream, the
annotator walks leaf nodes. A full grammar tree would have been over-engineering
for ten token types.

`JexlContextResolver` is the interesting piece. Given any element inside an
injected JEXL fragment, it determines which variables are in scope — the primary
loop variable from `@Permute.varName`, any extra axes from
`@Permute(extraVars={@PermuteVar(...)})`, named constants from `strings=`,
macros from `macros=`, and the inner variable if the expression belongs to
`@PermuteMethod` or `@PermuteSwitchArm`. It does this by walking the PSI tree
from the injection host back through the host literal, the annotation parameter
list, the annotation, and up to the enclosing class.

Three non-obvious bugs surfaced during review.

The first: `@PermuteVar` has `@Target({})` — it can only appear nested inside
`@Permute(extraVars={...})`, never as a standalone class annotation. The initial
implementation scanned class-level annotations for `@PermuteVar` nodes, which
is dead code that never matches. The tests used `@PermuteVar` directly on the
class — syntactically valid in the PSI, silently wrong at runtime. The spec
review caught it; we rewrote the resolver to read the `extraVars` attribute
array instead.

The second: in a flat PSI tree, every token's parent is the file root. The
annotator's guard `if (element.getParent() != file) return` is always false —
every element passes through, and the annotator body runs once per token rather
than once per file. IntelliJ deduplicates `HighlightInfo` by range and severity
before rendering, so there's no user-visible symptom. The fix is
`if (!(element instanceof JexlFile)) return` — one node per file, which is what
we actually wanted. The original guard looks correct until you think through what
a flat tree means for parent pointers.

The third: `MultiHostInjector.addPlace()` is documented as composable — you can
call it multiple times within one `startInjecting`/`doneInjecting` session to
build a compound injection from disjoint ranges. What the docs don't mention is
that this concatenates the range contents into a single `PsiFile`. An attribute
like `className="Combo${i}x${i}"` was producing one injected file containing
`"ii"` instead of two files each containing `"i"`. The annotator flagged `ii` as
unknown. The fix is one session per range, which the end-to-end tests surfaced
immediately once we wrote them.

`JexlAnnotator` also filters JEXL keywords (`true`, `false`, `null`, `empty`,
`size`, `not`) before flagging undefined identifiers — the lexer has no keyword
recognition, so they tokenise as identifiers and would otherwise produce false
warnings in any conditional expression.

The completion contributor offers all in-scope variables and the seven built-in
functions (`alpha`, `lower`, `typeArgList`, `capitalize`, `decapitalize`, `max`,
`min`). Built-in completions insert `()` with the caret between the parens.
Parameter hints fire when the cursor is inside a function call; they track nested
paren depth so `typeArgList(1, max(i, 2), 'T')` reports the correct argument
index for the third position.

Maven Central is still the next unlock — without it, none of this reaches Gradle
users, and Gradle projects can't use the Maven plugin either.
