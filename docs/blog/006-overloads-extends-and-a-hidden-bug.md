# Overloads, Extends Clauses, and a Bug That Hid in Plain Sight

*Part 6 of the Permuplate development series.*

---

## G3: The Multi-Join Problem

The Drools RuleBuilder DSL has a beautiful property: you can join facts not just one at a time, but in bulk. `Join1First.join(Join2Second)` takes a pre-built two-fact structure and advances you to arity 3 in one step. This means each `JoinNSecond` class has multiple `join()` overloads — one for each possible arity jump.

For `Join1Second` with max arity 5:

```java
class Join1Second<DS, A> {
    <B>       Join2First<DS,A,B>       join(Function<DS, DataSource<B>> src1)
    <B,C>     Join3First<DS,A,B,C>     join(Join2Second<DS,B,C> src)
    <B,C,D>   Join4First<DS,A,B,C,D>   join(Join3Second<DS,B,C,D> src)
    <B,C,D,E> Join5First<DS,A,B,C,D,E> join(Join4Second<DS,B,C,D,E> src)
}
```

For `Join2Second`:

```java
class Join2Second<DS, A, B> {
    <C>       Join3First<DS,A,B,C>     join(Function<DS, DataSource<C>> src1)
    <C,D>     Join4First<DS,A,B,C,D>   join(Join3Second<DS,B,C,D> src)
    <C,D,E>   Join5First<DS,A,B,C,D,E> join(Join4Second<DS,B,C,D,E> src)
}
```

The pattern is clear: at arity `i`, you have `max_arity - i` overloads, each adding a different number of additional facts. At the maximum arity (the leaf node), you have zero overloads.

This requires a nested loop: an outer loop over `i` (generating `Join1Second`, `Join2Second`, etc.) and an inner loop over `j` (generating the overloads within each class). The number of inner iterations decreases as `i` increases: at i=1, j goes from 1 to 4; at i=4, j goes from 1 to 1; at i=5 (the max), j has no iterations.

---

## @PermuteMethod: The Inner Loop

G3 introduces `@PermuteMethod` to express this inner loop:

```java
@Permute(varName = "i", from = 1, to = 5, className = "Join${i}Second")
public class Join0Second<DS, @PermuteTypeParam(...) A> {

    @PermuteMethod(varName = "j")
    @PermuteReturn(className = "Join${i+j}First", typeArgs = "DS, ${typeArgList(1, i+j, 'alpha')}")
    public Object join(@PermuteDeclr(type = "JoinNSecond<DS, ...>") Object source) {
        rd.addSource(source);
        return rd.asNext();
    }
}
```

`@PermuteMethod(varName = "j")` triggers the inner loop. For each value of `j`, a separate overload is generated. The `to` bound is optional — when omitted, it's inferred as `@Permute.to - i`. This gives the "decreasing inner loop" behavior automatically: at i=1, `to = 5 - 1 = 4`; at i=5, `to = 5 - 5 = 0` (empty range, no overloads).

The empty range is the leaf node mechanism. When `from > to` after evaluation, the method is silently omitted. No annotation, no error. This is consistent with how `@PermuteReturn` boundary omission works.

`@PermuteMethod` runs early in the pipeline — before `PermuteDeclrTransformer` — so each generated overload clone gets its own `@PermuteDeclr` and other annotations processed independently with the (i, j) combined context.

---

## G4: Method-Level Type Parameters

G3 also uncovered a gap in type parameter expansion: the method-level type parameters for `join()` overloads. Each overload needs its own method-level generics — `<B>`, `<B,C>`, `<B,C,D>` — which aren't class-level type params. They're method-level.

G4 extends `@PermuteTypeParam` to work on **method type parameters**, driven by the `@PermuteMethod` inner variable:

```java
@PermuteMethod(varName = "j")
@PermuteReturn(className = "Join${i+j}First")
public <@PermuteTypeParam(varName = "k", from = "1", to = "${j}", name = "${alpha(i+k)}") PB>
        Object join(...) { ... }
```

At (i=2, j=2): `PB` expands to `C, D` (alpha(2+1)=C, alpha(2+2)=D).

One design decision: the R3 prefix check (which validates that the annotation's name prefix matches the sentinel name's prefix) is intentionally NOT applied to method-level type parameters. The sentinel (`PB`, or whatever placeholder you use) is an arbitrary name that doesn't need to match the generated names (`T1`, `B`, `C`). The check would always fail, so it's skipped.

---

## Extends Clause Expansion: The Automatic Part

In the Drools DSL, every `JoinNFirst` extends the corresponding `JoinNSecond`. This relationship encodes the design: `First` inherits all the `Second` behaviors (including `join()`) and adds the arity-preserving `filter()`.

Writing `extends Join0Second` on the template class and expecting it to become `extends Join1Second` on the generated class, `extends Join2Second` on the next, and so on — this is exactly what `applyExtendsExpansion()` handles.

The detection algorithm:
1. Find extends clauses where the base class name shares the same prefix-before-digit as the template class (`"Join"` from `"Join0First"`)
2. Verify the embedded number in the extends class matches the template's embedded number (`0` in both `Join0First` and `Join0Second`)
3. Expand the type args to match the generated class's current type params
4. Replace the embedded number with the current arity

This is "safe" for third-party classes — `BaseHandler` has no embedded digit and would be skipped. `ExternalJoin1Base` has the digit but different prefix (`"ExternalJoin"` ≠ `"Join"`) and would be skipped.

---

## The Bug That Hid in Plain Sight

There's a confession to make about G3.

The extends expansion formula was wrong from the beginning. The code used:

```java
int newNum = currentEmbeddedNum + 1;
```

This means at `i=2` (generating `Join2First`), the extends clause became `extends Join3Second` — not `Join2Second`. Every generated `JoinNFirst` extended `Join(N+1)Second` instead of `JoinNSecond`.

The existing test didn't catch it because the test was also wrong:

```java
// The test comment said:
// "At i=2: Join3First<T1,T2,T3> extends Join3Second<T1,T2,T3>"
// But at forI=2, the generated class is Join2First, not Join3First.
// The comment was incorrect, and the assertion validated the wrong behavior.
```

The test asserted `contains("extends Join3Second")` at `forI=2` — which is the forward-reference output. The assertion was checking that the bug produced the expected buggy output, and passing. The comment on the test claimed the generated class was "Join3First" when it was actually "Join2First".

This bug lived in the codebase for the entire duration of G3's development. Claude caught it when we started wiring the First/Second split — `Join2First extends Join3Second` is semantically wrong; each JoinFirst should extend its peer, not the next-arity class.

The fix was a one-character change: `currentEmbeddedNum + 1` → `currentEmbeddedNum`. Then the existing test had to be updated to assert the correct behavior, and `doesNotContain` assertions were added to guard against the forward-reference regression.

The lesson: assertions that validate buggy output are worse than no assertions. An assertion that checks "this produces the expected wrong output" gives you green tests and a false sense of correctness. The real defense would have been checking `doesNotContain("Join3Second")` at `forI=2` — asserting what should NOT be there.

---

## The Alpha Naming Gap in Extends Expansion

Fixing the formula revealed a second problem: the extends expansion only worked with `T+number` type arguments.

The check:

```java
boolean allTNumber = extArgNames.stream().allMatch(InlineGenerator::isTNumberVar);
if (!allTNumber) continue;
```

`isTNumberVar` returns true for `T1`, `T2`, `T23` — anything matching `T` followed by digits. For alpha naming, the extends clause has `<DS, A>` — neither `DS` nor `A` passes the check. The expansion is silently skipped. No error. The extends clause stays as the template wrote it.

Then Claude spotted something: the code already had a `postG1TypeParams` variable captured just above the call to `applyExtendsExpansion`:

```java
// Capture post-G1 type parameter names for extends expansion (used in Task 5)
Set<String> postG1TypeParams = new LinkedHashSet<>();
generated.getTypeParameters().forEach(tp -> postG1TypeParams.add(tp.getNameAsString()));
```

The comment said "used in Task 5." The variable was captured. It was just never passed to `applyExtendsExpansion`. It was wired up for the purpose but the wire was never connected.

The fix: add an alpha branch after the `allTNumber` check:

```java
} else {
    // Alpha case: extends type args must be a prefix of postG1TypeParams
    boolean isPrefix = extArgNames.size() <= postG1TypeParams.size()
            && IntStream.range(0, extArgNames.size())
                       .allMatch(k -> extArgNames.get(k).equals(postG1TypeParams.get(k)));
    if (!isPrefix) continue;
    newTypeArgNames = postG1TypeParams;
}
```

After G1 has expanded the class type params from `<DS, A>` to `<DS, A, B, C>` (at i=3), `postG1TypeParams` is `[DS, A, B, C]`. The extends clause has `[DS, A]`. Is `[DS, A]` a prefix of `[DS, A, B, C]`? Yes. Use `[DS, A, B, C]` as the new type args. Expand the class name. Done.

The two bugs — the `+1` formula and the missing alpha branch — were both fixed in the same session, since they were both blocking the same feature: the Drools Phase 2 First/Second split.

---

## 131 Tests, All Green

After fixing both G3 bugs and adding two new tests (`testExtendsClauseAlphaNaming` and `testExtendsClauseAlphaNamingNoFixedPrefix`), the test suite was at 131 tests with no failures.

The two new tests were also interesting design choices. `testExtendsClauseAlphaNamingNoFixedPrefix` specifically covers the case where the extends clause has *only* alpha type params — `<A>` with no fixed prefix like `DS`. This exercises the `extArgNames.size() <= postG1TypeParams.size()` boundary where sizes are equal (full match rather than strict prefix). That edge case — where the extends clause at arity 1 exactly matches the post-G1 type params — passes correctly, as it should: the class name gets updated even when no expansion is needed.

The G3 story is a good illustration of the value of assertion completeness. The forward-reference bug was invisible until you looked at the generated code directly and asked "does `Join2First extends Join3Second` actually make sense?" Green tests aren't a substitute for understanding what you're asserting.

---

