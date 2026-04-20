# 0006 — extendsRule() duplication in RuleBuilder and ParametersFirst

Date: 2026-04-20
Status: Accepted

## Context and Problem Statement

`extendsRule()` appears in both `RuleBuilderTemplate` and `ParametersFirstTemplate`, differing in
exactly one line: the `ruleName()` call, which returns `"extends"` vs the `name` field value.
Both templates extend `AbstractRuleEntry<DS>`, which provides `protected abstract String ruleName()`
and the shared `cast()` helper. The structural duplication of `extendsRule()` itself is unavoidable
given current Permuplate capabilities.

Annotations investigated as potential deduplicators: `@PermuteSelf` and `@PermuteDefaultReturn`.

## Why @PermuteSelf does not apply

`@PermuteSelf` marks a method that returns `this` — the current generated class. `extendsRule()`
returns `JoinBuilder.Join${j-1}First<Void, DS, ...>`, not `this`. Self-return inference does not
fire here.

## Why @PermuteDefaultReturn does not apply

`@PermuteDefaultReturn` collapses repetitive `@PermuteReturn` annotations across multiple methods
in a class that share the same return type. Each template has exactly one `@PermuteReturn` (on
`extendsRule()`). With only one instance there is nothing to collapse.

## Why full structural deduplication is impossible

**Constraint 1 — `@PermuteMethod` on base class methods is not processed.**
The Permuplate inline generation pipeline only processes `@PermuteMethod` annotations on methods
directly declared in the template class. Annotations on inherited methods from `AbstractRuleEntry`
are invisible to the transformer. Moving `extendsRule()` to `AbstractRuleEntry` and annotating it
there would produce no generated overloads.

**Constraint 2 — Different method sets make a single string-set template infeasible.**
`RuleBuilderTemplate` generates: `from()`, `rule()`, `extendsRule()` + `@PermuteMethod` overloads.
`ParametersFirstTemplate` generates: `params()`, `param()`, `list()`, `map()`, `from()`, `ifn()`,
`extendsRule()` + the same `@PermuteMethod` overloads. These method sets are completely different —
a single `@Permute(values={"RuleBuilder","ParametersFirst"})` template cannot generate both because
the surrounding non-generated methods differ fundamentally.

**Constraint 3 — `@PermuteMethod(values=...)` cannot differentiate method body content.**
Even if method names were parameterised, both would be named `extendsRule` — the values variation
would need to affect the body content (specifically `ruleName()` returning `"extends"` vs `name`).
That behavioural difference is already abstracted via `ruleName()` and amounts to a single abstract
method implementation. Using a string-set `@PermuteMethod` here would add annotation complexity
without reducing duplication.

## Decision

Accept the duplication. The shared infrastructure (`AbstractRuleEntry<DS>`, `ruleName()`) isolates
the meaningful behavioural difference to a single abstract method override. The `extendsRule()`
template body is byte-for-byte identical in both files; only the `ruleName()` implementation
differs — and that is expressed once per concrete class, not once per overload.

## Consequences for real Drools integration

When applying this DSL pattern to the real Drools codebase:

- Each entry-point class (`RuleBuilder`, `ParametersFirst`, any future additions such as
  `NamedRuleBuilder`) will carry its own `extendsRule()` template with `@PermuteMethod`.
- The body will always be:
  `ep.baseRd().copyInto(child); return cast(new JoinBuilder.@PermuteDeclr(...) Join1First<>(null, child));`
- Only the `new RuleDefinition<>(ruleName())` call varies, and that variation is already expressed
  as an abstract method.
- This is 8–10 lines of understood, stable duplication.
- Document it here so future engineers do not spend time trying to eliminate it.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Move `extendsRule()` to `AbstractRuleEntry` | `@PermuteMethod` on base-class methods is invisible to the pipeline — no overloads generated |
| Single container class with both method sets | Does not solve the `@PermuteMethod` base-class constraint; method sets are too different for one template |
| `@PermuteStatements` to inject `ruleName()` result | Adds annotation complexity for a 1-line variation; rejected |
| `@PermuteSelf` / `@PermuteDefaultReturn` | Not applicable — `extendsRule()` does not return `this`, and there is only one `@PermuteReturn` per class |

## Links

- [ADR-0001](0001-standalone-method-level-permutetypeparam-with-propagation.md) — standalone `@PermuteTypeParam` on methods
- `permuplate-mvn-examples/src/main/permuplate/.../RuleBuilder.java` — affected template
- `permuplate-mvn-examples/src/main/permuplate/.../ParametersFirst.java` — affected template
- `permuplate-mvn-examples/src/main/java/.../AbstractRuleEntry.java` — shared base class
