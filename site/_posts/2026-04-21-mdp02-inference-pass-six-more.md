---
layout: post
title: "The inference pass — six more DSL improvements"
date: 2026-04-21
type: phase-update
entry_type: note
subtype: diary
projects: [permuplate]
tags: [permuplate, drools-dsl, annotation-processor]
---

The previous entry ended with twenty features across two passes. I wasn't ready to call that done.

Both those passes had started from the same position: look at what's there, apply what's new. I wanted to try the harder version — assume there's still something, and find it by coming from directions we hadn't used. What could the processor infer rather than require? What would JavaParser-level analysis reveal that annotation inspection misses?

I put that question to Claude directly. The research pass looked at every template, every hand-written DSL class, the vol2 reference tests, and the generated output — looking for patterns that resisted annotation, places where inference could replace explicit instruction.

The biggest find came back immediately. Every method in the DSL that returns a new generated-class instance had this structure:

```java
@PermuteReturn(className = "Join${i+1}First")
public Object join(...) {
    return cast(new @PermuteDeclr(type = "Join${i+1}First") Join1First<>(end(), rd));
}
```

The `@PermuteDeclr TYPE_USE` on the constructor duplicates the `@PermuteReturn` class name exactly. The processor already resolves the return type — it just wasn't propagating that knowledge into the method body. We added constructor-coherence inference: after `applyPermuteReturn` resolves the return class for a method, it scans the body for `ObjectCreationExpr` nodes in the same name family and renames them automatically. Four of these annotations are gone from `JoinBuilder` and `ExtendsRuleMixin`. The methods read as plain Java now.

One bug surfaced mid-implementation. The family-extraction used `replaceAll("\\d+$","")` — strip trailing digits to identify the structural family. This works for `Join1Second → JoinSecond`. It silently fails for `Join2First`: digits in the middle, nothing at the end, no match, no rename. The fix is `replaceAll("\\d+","")` — strip every digit sequence, not just trailing. An implementer subagent caught it during the build. Without that catch, the feature would have appeared to work while doing nothing for half the DSL family names.

The second structural find was `@PermuteMixin` on non-template classes. `RuleBuilder` and `ParametersFirst` carried `@Permute(varName="i", from="1", to="1", className="RuleBuilder")` — a dummy single-iteration permutation whose only purpose was to make `@PermuteMixin` fire. We extended `PermuteMojo` to handle classes in `src/main/permuplate/` with `@PermuteMixin` but no `@Permute`: synthesize a minimal config internally, run the standard pipeline. The dummy annotation is gone. A reviewer subagent caught the missing `coid.isInterface()` guard — `ClassOrInterfaceDeclaration` in JavaParser covers both classes and interfaces, and the guard needed to exclude the latter explicitly.

That opened `addVariableFilter` to the same treatment. It had two hand-coded overloads — m=2 and m=3. We moved `RuleDefinition` to `src/main/permuplate/`, added `@PermuteMixin(VariableFilterMixin.class)`, and the mixin generates typed overloads for m=2 through m=6. `filterVar` in `JoinBuilder` matches.

Three more items filled out the batch: `@PermuteNew(className="...")` for the edge cases where coherence inference encounters multiple generated-class constructors in one body; `createEmptyTuple` replaced with `Class.forName(BaseTuple.class.getName() + "$Tuple" + size)` so it scales as the Tuple family grows; and `@PermuteSealedFamily`, which generates the sealed marker interfaces in `JoinBuilder` that were previously hand-declared with their permits clauses.

305 tests throughout. Six features, fewer explicit annotations.
