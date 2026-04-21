---
layout: post
title: "Twenty features, two passes, one annotation processor"
date: 2026-04-21
type: phase-update
entry_type: note
subtype: diary
projects: [permuplate]
tags: [permuplate, drools-dsl, annotation-processor]
---

The last session ended with 277 tests and a claim that the DSL was feature-complete.
I wanted to verify that before pushing to Maven Central, so I did a systematic audit of
every template file. What I found convinced me we weren't done.

## The audit that found ten more things

The first pass looked at every `@PermuteMethod`, `@PermuteReturn`, and `@PermuteBody`
in the DSL and asked: is there a simpler form? Is there anything batch 6–7 unlocked
that we haven't applied yet?

The not/exists method had three `capitalize(scope)` calls where one macro would do.
The `filterLatest` suppression was encoded as `from="${max(2, i)}"` — correct, but the
actual intent was "suppress at arity 1." `@PermuteFilter("i > 1")` says that directly.
The `NegationScope.java` file, which batch 8 was supposed to delete, was still there
generating orphan classes — a `git rm` that didn't survive a subsequent `git add -u`.

That last one was caught by one of the reviewer subagents during a quality pass, not by
me. I'd assumed the file was gone.

The deeper finds took longer. The `Path2` class had always been hand-coded because
`@PermuteReturn` couldn't express "return a type parameter, not a generated class."
Adding `typeParam="END"` to the annotation eliminated it. The `extendsRule()` method
was duplicated verbatim across two template files with no way to share it — ADR-0006
had documented this as structurally unavoidable. `@PermuteMixin` made it avoidable.
We went from 26 lines of identical code in two files to a shared mixin class that both
templates reference.

## The second pass found what the first missed

Batch 8 shipped 10 items and brought the test count to 299. I did the same audit again.

The most significant find was `@PermuteParam` inside `@PermuteMethod` clones. The
transformer that handles parameter expansion runs in the outer pipeline, where the
inner method variable (`m`, `j`) isn't in scope. JEXL throws a variable-undefined
exception, which is silently swallowed. The `filterVar` method had a `@PermuteBody`
annotation specifically to work around this — I'd added it knowing it was a workaround
without diagnosing the root cause. The fix was to run `PermuteParamTransformer` on each
clone inside `applyPermuteMethod` with the inner context. Once that landed, the
`@PermuteBody` could be deleted and the method body just read `rd.addVariableFilter(v1, predicate)`.
Call-site anchor expansion handles the rest.

The `PermuteAnnotationTransformer` gap was found the same way — by trying something
that should work and watching it silently not work. `@PermuteAnnotation(type="FunctionalInterface")`
on a non-inline template did nothing. No error. The transformer that adds the target
annotation before stripping `@PermuteAnnotation` was simply never called in the
non-inline pipeline. Claude caught it in a quality review; we fixed it.

The Consumer/Predicate families — two 24-line templates, structurally identical,
differing only in class name prefix, method name, and return type — collapsed into one
template with `@PermuteVar(F={"Consumer","Predicate"})` cross-producted with the arity
loop. JEXL ternary macros drive the differences:

```java
macros = {"method=${F == 'Consumer' ? 'accept' : 'test'}",
           "ret=${F == 'Consumer' ? 'void' : 'boolean'}"}
```

Twelve interfaces from one template. The arity-1 interfaces stayed hand-written — they
don't follow the parameterized pattern cleanly enough to fold into the cross-product.

## Numbers

Two batches, 20 items, one long day. Test count: 277 → 305. The main templates are
all shorter — `JoinBuilder` alone went from 354 lines to under 300.

The annotation processor now has `@PermuteMacros`, `@PermuteMixin`, `@PermuteExtendsChain`,
`@PermuteBodyFragment`, `@PermuteReturn(typeParam=)`, and `@PermuteDefaultReturn(className="self")`.
The last one is the most satisfying — instead of spelling out the full class name and
type argument expression on every fluent builder class, you write `"self"`.
