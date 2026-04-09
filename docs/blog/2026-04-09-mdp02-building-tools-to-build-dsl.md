# Permuplate — Building the Tools to Build the DSL

**Date:** 2026-04-09
**Type:** phase-update

---

## What I was trying to achieve: write the first @Permute templates for droolsvol2

droolsvol2 compiles. That's been the blocker for weeks — ~14 files still referencing `drools-core` symbols that vol2 is removing. Someone finished the refactor. The Drools migration branch in Permuplate has been waiting.

I wanted to start with the functional interfaces. Consumer, Predicate, Function are already hand-written in droolsvol2's `function` package, up to arity 4 or 5. Low surface area, no dependencies, clean patterns. Get Permuplate generating them first, then tackle the JoinNFirst/Second hierarchy.

## Two obstacles I hadn't thought through: a literal and a lambda

I thought the functional interfaces would be quick. Write Consumer2 as a template, add `@PermuteTypeParam` and `@PermuteParam`, generate Consumer3 through Consumer10, delete the hand-written files.

Two things stopped me: `getArity()` and `negate()`.

## The literal that Permuplate can't touch

`Consumer2.getArity()` returns `2`. `Consumer3.getArity()` must return `3`. Permuplate can expand type parameters and method parameters — it renames identifiers, inserts generated sequences, propagates anchors — but it has no way to substitute an integer literal in a method body. `return 2;` is an `IntegerLiteralExpr` in the AST, not a `NameExpr`. The renaming machinery doesn't see it.

I went through the options. Move arity to reflection in the base interface: rejected, no reflection for performance reasons. Accept wrong `getArity()` values: rejected, `LinearTuplePredicateCache` uses `p.getArity()` in switch statements. Add literal substitution to Permuplate.

The new annotation: `@PermuteConst`. Place it on a field or local variable and Permuplate replaces the initializer with the evaluated JEXL expression.

```java
@PermuteConst("${i}") int ARITY = 2;

default int getArity() { return ARITY; }
```

`Consumer3` gets `int ARITY = 3;`. The `return ARITY;` is a `NameExpr` — it stays, unchanged, correct.

`@PermuteConst` also combines cleanly with `@PermuteDeclr` on the same declaration. One annotation renames the field and propagates the new name through the class body; the other updates the initializer value. They touch different parts of the AST and don't interfere.

## Lambda params: already legal in Java, just not wired up

`Predicate2.negate()` returns a lambda that re-calls `test()`. For `Predicate3`, both the lambda parameter list and the `test()` call inside it need to expand. The obstacle felt symmetric to `@PermuteParam` on method parameters — except the targets are inside a lambda expression.

Java allows annotations on typed lambda parameters. `@PermuteParam` is already `@Target(ElementType.PARAMETER)`, which covers them. The processor just wasn't walking `LambdaExpr` nodes.

```java
@PermuteReturn(className="Predicate${i}", typeArgVarName="j", typeArgFrom="1", typeArgTo="${i}", typeArgName="${alpha(j)}")
default Predicate2<A, B> negate() {
    return (A a, @PermuteParam(varName="j", from="2", to="${i}", type="${alpha(j)}", name="${lower(j)}") B b) -> !test(a, b);
}
```

For `Predicate3` this generates `(A a, B b, C c) -> !test(a, b, c)`. Anchor expansion is scoped to the lambda body only — the outer method's anchor doesn't bleed in. `@PermuteReturn` handles the return type separately.

## Seven tasks, 145 tests, and a validation gap

Claude implemented both features across seven subagent tasks — spec compliance and code quality review after each. 145 tests passing.

Then I tried `name="${lower(j)}"` for single-letter param names — `a, b, c, d` — and R4 validation fired. R4 rejects templates with no static literal, because without one there's nothing to match against the sentinel's actual name.

The rule makes sense for `@PermuteDeclr`: `name="c${i}"` on sentinel `c2` — the literal `c` is the anchor. For `@PermuteParam` it's a category error. The sentinel is a placeholder; `B b` has no obligation to share a prefix with the generated names `b, c, d`. They're completely new identifiers.

One-line fix in `validatePrefixes()`: if the name template strips to nothing after removing `${...}` blocks, skip R2/R3/R4 entirely. Templates with literals still get full validation.

## The templates are next

The tools exist. Consumer, Predicate, and Function templates in droolsvol2 can now use clean single-letter naming. The Drools migration actually starts next session.
