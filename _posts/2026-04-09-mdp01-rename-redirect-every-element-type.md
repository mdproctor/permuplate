---
layout: post
title: "Permuplate — Rename Redirect for Every Element Type"
date: 2026-04-09
phase: 3
phase_label: "Phase 3 — The IntelliJ Plugin"
---
# Permuplate — Rename Redirect for Every Element Type

**Date:** 2026-04-09
**Type:** phase-update

---

## What I was trying to achieve: close the rename gap we left open

The previous entry noted that rename redirect worked for classes but not for methods or fields. Shift+F6 on `join3()` inside `Join3.java`: IntelliJ renames the generated method directly. Next build overwrites it. The annotation strings in the template are untouched. Silently wrong.

I'd accepted this as "working on class-level" and noted methods and fields for later. Later arrived.

## What I believed going in: a quick extension to substituteElementToRename

`substituteElementToRename()` in `AnnotationStringRenameProcessor` already handled `PsiClass`. Adding `PsiMethod` and `PsiField` felt like ten minutes — get the containing class, find the template, find the matching member by name. Return it.

The containing-class step had a wrinkle. `PsiParameter` doesn't extend `PsiMember`. `PsiMember.getContainingClass()` — the obvious call — isn't available for parameters. You go via `param.getDeclarationScope()` to get the method, then `.getContainingClass()` from there. The IntelliJ Platform docs don't mention this. GE-0146 in the garden.

## Three duplicates and one shared home

While planning the change, Claude flagged three private `stripTrailingDigits` methods across the codebase — one in `AnnotationStringRenameProcessor`, one in `PermuteFamilyFindUsagesAction`, one in `PermuteMethodNavigator`. All identical. A fourth copy of the PSI scan fallback logic existed in the rename processor.

I chose to extract a shared `PermuteElementResolver` in the `index` package rather than extend the rename processor in-place. `PermuteMethodNavigator` feature 2 — navigate from a generated element to its template — will need the same resolver later; adding it becomes trivial when the foundation is already there.

The matching logic strips trailing digits from both sides: `c3` → `"c"`, `c2` → `"c"`, match. First in declaration order wins.

`substituteElementToRename()` shrank from fifteen lines to three:

```java
@Override
public @NotNull PsiElement substituteElementToRename(@NotNull PsiElement element,
                                                       @Nullable Editor editor) {
    return PermuteElementResolver.resolveToTemplateElement(element, editor);
}
```

## Six tasks, two-stage review, two things caught

We ran the implementation through six subagent tasks — spec compliance review and code quality review after each. Both stages found real issues.

Task 1: the implementer removed the duplicate from `PermuteFamilyFindUsagesAction` correctly, but missed the copy in `PermuteMethodNavigator`. A fresh code quality reviewer caught it.

Task 3: `resolveToTemplateElement()` was declared `@Nullable` but never returns null — every code path returns the original element or a found template element. The reviewer flagged it; we changed it to `@NotNull`.

A wrong nullability annotation caught through code review is exactly what the process is for.

## Fifty-six tests; the graph-aware problem is still ahead

Rename redirect now works from class, method, field, and parameter in a generated file. 56 tests, all passing.

The redirect is a temporary solution. The real direction is graph-aware refactoring — rename from any node, propagate bidirectionally. That's a substantially bigger problem. For now it closes the worst gap.

Drools migration is still blocked on the droolsvol2 refactor.
