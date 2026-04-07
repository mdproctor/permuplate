# The First/Second Split

*Part 9 of the Permuplate development series.*

---

## Why It Took Two Phases

Blog 007 explained the deferral: the First/Second split required G3's extends clause auto-expansion, and G3's expansion required `T+number` naming to detect siblings. Alpha naming — `A, B, C, D` — had no numeric suffix. The auto-expansion wouldn't fire.

Blog 006 fixed that. The alpha branch was added to `applyExtendsExpansion()`, and the disconnected `postG1TypeParams` wire was connected. With those fixes in place, there was nothing blocking Phase 2.

---

## Two Templates, One Container Class

The split means each `JoinNFirst` extends its peer `JoinNSecond`. In Permuplate terms, that's one template extending another — and G3's extends expansion handling the promotion automatically.

```java
// Generates Join1Second..Join6Second
@Permute(varName = "i", from = 1, to = 6, className = "Join${i}Second", inline = true)
public static class Join0Second<END, DS,
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
        extends BaseRuleBuilder<END> { ... }

// Generates Join1First..Join6First — each extending its peer Second
@Permute(varName = "i", from = 1, to = 6, className = "Join${i}First", inline = true)
public static class Join0First<END, DS,
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
        extends Join0Second<END, DS, A> { ... }
```

The `extends Join0Second<END, DS, A>` clause is the key. G3 detects the `"Join"` prefix and embedded `0` in both class names, confirms they match, then expands the clause per arity: `Join1First extends Join1Second<END, DS, A>`, `Join2First extends Join2Second<END, DS, A, B>`, and so on. Declaration order in JoinBuilder matters: `Join0Second` must come first so PermuteMojo builds the full generated-class set before processing `Join0First`.

Second holds `join()`, `not()`, `exists()`, `fn()`, and the OOPath methods. First inherits all of it and adds `filter()`. The split is clean.

---

## The END Phantom Type

Adding the First/Second split forced a timing decision: thread the `END` phantom type through now, or defer it until Phase 3 when `not()`/`exists()` actually needed it.

I pushed for adding it in Phase 2. Deferring it would have meant revisiting every class signature and every call site in Phase 3 to add a new type parameter. Better to pay the cost once, with the template small and clear, than retroactively.

`END` lives in `BaseRuleBuilder`:

```java
public class BaseRuleBuilder<END> {
    private final END end;
    public END end() { return end; }
}
```

For top-level chains, `END = Void` and `end()` is never called. For nested scopes — `not()`, `exists()` — `END` is the outer builder type. Calling `not()` on a `Join2Second<Void, DS, A, B>` creates a `NegationScope` that captures `this` as its END. When `end()` is called inside the scope, it returns to `Join2Second<Void, DS, A, B>` — the outer chain fully restored at its original arity.

---

## Fifteen Overloads from One Template

The First/Second split also enabled bi-linear joins. In real Drools, you can join not just a fresh data source but a pre-built fact sub-network — a `JoinNSecond` from a separate chain. The right chain executes independently; its matched tuples cross-product with the current chain's tuples.

`@PermuteMethod` generates all valid bi-linear overloads from one template method:

```java
@PermuteMethod(varName = "j", from = "1", name = "join")
@PermuteReturn(className = "Join${i+j}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i+j, 'alpha')")
public <@PermuteTypeParam(varName = "k", from = "${i+1}", to = "${i+j}",
                           name = "${alpha(k)}") C> Object joinBilinear(
        @PermuteDeclr(type = "Join${j}Second<Void, DS, ${typeArgList(i+1, i+j, 'alpha')}>")
        Object secondChain) { ... }
```

At i=1, j runs 1 to 5: five overloads. At i=2, four. At i=5, one. At i=6, the range is empty — no bi-linear joins on the leaf node. Fifteen overloads total. All from this one method.

The `to` bound is omitted from `@PermuteMethod` — inferred as `@Permute.to - i = 6 - i`. The decreasing inner loop falls out of the inference automatically. No hardcoded count anywhere.

32 tests passing.
