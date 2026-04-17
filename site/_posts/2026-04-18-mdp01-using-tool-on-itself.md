---
layout: post
title: "Using the Tool on Itself"
date: 2026-04-18
phase: 2
phase_label: "Phase 2 — The Drools DSL Sandbox"
type: phase-update
entry_type: note
subtype: diary
projects: [permuplate]
---

I published the DSL article yesterday and immediately wanted to push further.
The article covered the first round — RuleExtendsPoint and BaseTuple. What it
didn't cover was what happened when we tried to template the harder cases.

The answer: the tool had gaps. Four of them.

## The gaps you find by eating your own cooking

Claude and I started by surveying what was left: NegationScope, ExistenceScope,
RuleOOPathBuilder, and the six `extendsRule()` overloads duplicated across
ParametersFirst and RuleBuilder. I thought the NegationScope case would be
trivial — two structurally identical classes, string-set permutation, done.

It wasn't. The Maven plugin didn't support `@Permute(values={...})`. It tried
to evaluate `from` and `to` as numbers, hit empty strings (the defaults when
`values` is set), and threw:

```
@Permute from/to expression failed to evaluate:
Expression did not evaluate to a number: null
```

The APT processor handled string-set correctly; the Maven plugin never got it.

That was gap one. The fix was a one-line guard in `validateConfig` — skip from/to
evaluation when values is non-empty — plus a second fix to write the kept template
class to generated sources when `keepTemplate=true` with `inline=false`.

RuleOOPathBuilder went smoothly. Five Path classes, 128 lines, one template.
Path3 generates Path4..Path6 with `@PermuteReturn` driving the descending
return-type chain. 74 lines of template, all 74 OOPath tests green.

## The extendsRule() problem exposed two more gaps

The `extendsRule()` overloads were the interesting case. RuleBuilder has six of
them; ParametersFirst has six more. Each takes a `RuleExtendsPointN` and returns
a `JoinNFirst`, body identical every time. Obvious `@PermuteMethod` candidate.

Except `@PermuteMethod` only works inside a `@Permute`-annotated class. RuleBuilder
is a standalone top-level class. That was gap two.

The fix: `inline=true` on top-level class templates. Previously the plugin
rejected it — `@Permute inline=true is only valid on nested static classes`.
InlineGenerator assumed the template was always nested and used
`outputParent.addMember(generated)`. For top-level templates, generated classes
go to `outputCu.addType(generated)`. A dozen lines changed, one new test, the
guard removed.

With that fix, the file-naming trick works: name the source file `RuleBuilder.java`,
put a template class `RuleBuilderTemplate` inside it, use `className="RuleBuilder"`.
The plugin renames `RuleBuilderTemplate` to `RuleBuilder` and writes
`target/.../RuleBuilder.java` — file name matches public class name.

Then gap three appeared in the generated output. Every `extendsRule()` overload had:

```java
return cast(new @PermuteDeclr(type = "JoinBuilder.Join${j-1}First")
        JoinBuilder.Join1First<>(null, child));
```

with the annotation still there, `j` unevaluated, the class name unchanged
across all six overloads. The annotation wasn't being processed.

The root cause: JavaParser puts `new @Ann A.B<>()` TYPE_USE annotations on the
**scope type** (`A`), not the full type (`A.B`). `transformNewExpressions` checked
`newExpr.getType().getAnnotations()` — empty. It needed to also check
`newExpr.getType().getScope().get().getAnnotations()`. That was fix three.

Then fix four: `transformNewExpressions` needed to run AFTER
`PermuteParamTransformer`, not before. The transformer internally replaces the
method node in `tmpParam`, making any mutations to the original `clone` stale.
Claude's subagents spent two iterations on this before the ordering became clear.

In the end I kept reflection for the extendsRule() body — it's the same pattern
`extensionPoint()` already uses, and cleaner than TYPE_USE on qualified names
even now that it works.

## The tally

Six templates where there were none at the session start. ~496 lines of
hand-written boilerplate eliminated. Four framework gaps closed. Three garden
entries submitted for the non-obvious things we hit.

The pre-existing test failures from earlier in the session — two tests where
JEXL exceptions propagated as `RuntimeException` instead of compile errors —
were also fixed along the way. The build is fully green for the first time
in this project's history.
