# Permuplate Gap Analysis

**Source:** Analysis of the [Drools RuleBuilder DSL POC](https://github.com/mdproctor/drools/blob/droolsvol2/droolsvol2/src/test/java/org/drools/core/RuleBuilderTest.java)  
**Status:** Working document — items to be addressed in priority order

---

## Summary

Three categories of findings:

1. **Hard gaps** — things Permuplate fundamentally cannot do today and would require new features
2. **Soft gaps** — things that almost work but have an awkward UX or a missing edge case
3. **Newly identified patterns** — patterns we can already support but haven't demonstrated or tested

---

## Hard Gaps

### G1 — Generic type parameter arity

**What the Drools DSL needs:**
```java
// Type-safe filter: only compiles if fact types match
builder.filter((ctx, Person p, Account a) -> p.age() > 18);

// Underlying interface generated per arity:
interface Condition2<T1, T2> { boolean test(T1 fact1, T2 fact2); }
interface Condition3<T1, T2, T3> { boolean test(T1 fact1, T2 fact2, T3 fact3); }
```

**What Permuplate can generate:**
```java
// Erased version only — all facts are Object:
interface Condition2 { boolean test(Object fact1, Object fact2); }
```

**The gap:** Permuplate's variables are integers and strings. There is no mechanism to introduce Java generic type parameters that vary with the permutation variable. `Condition${i}<T1, T2, ..., T${i}>` cannot be expressed.

**Impact:** Generated interfaces are not type-safe. Users must cast. Viable for internal/scripting use cases, not for published type-safe APIs.

**Possible approach:** A new `@PermuteTypeParam` annotation or a `typeParams` attribute on `@Permute` that injects a comma-separated list of type parameter names. Needs design work.

---

### G2 — Return type narrowing by arity (stateful builder types)

**What the Drools DSL needs:**
```java
// After 1 join: builder knows it holds 1 fact
RuleBuilder<Ctx>.Step1<T1> step1 = builder.from(src);

// After 2 joins: builder knows it holds 2 facts
RuleBuilder<Ctx>.Step2<T1, T2> step2 = step1.join(src2);

// filter() on Step2 accepts Condition2<T1, T2>:
step2.filter((a, b) -> a.age() > b.age());
```

**The gap:** Each `.join()` call returns a different **type** that encodes the accumulated fact count in its generic signature. Generating `Step1`, `Step2`, `Step3`, ... is feasible with Permuplate (top-level classes), but the **return types** of the methods also need to reference the correct `Step${i+1}` type — which requires expressing "the next arity" as a type reference inside the template.

**Impact:** The fluent DSL cannot be type-safe end-to-end. The builder must erase to a raw type or use `Object`, losing compile-time safety on the final `filter()` and `fn()` call.

**Possible approach:** Allow `className` expressions to reference each other (`Step${i+1}`) or introduce a `nextClassName` attribute. Complex — needs design.

---

### G3 — Overload selection by lambda arity (implicit dispatch)

**What the Drools DSL ideally wants:**
```java
// User writes:
builder.filter((ctx, p) -> p.age() > 50);          // infers 2-arg filter
builder.filter((ctx, p1, p2) -> p1.age() > 18);    // infers 3-arg filter

// Both are named "filter" — Java resolves by SAM arity
```

**The gap:** Java resolves method overloads by parameter type, not by lambda arity. If `filter(Condition2)` and `filter(Condition3)` both exist, the compiler **cannot** infer which to call from the lambda alone unless the functional interface types are unambiguous. This means overloads with erased `Object` parameters are **not resolvable** — the user must either cast or use different method names (`filter2()`, `filter3()`).

**Impact:** The DSL must use either:
- Differently-named methods (`filter2`, `filter3`, ...) — ugly but works
- Typed functional interfaces (`Condition2<Person, Account>`) — requires G1 to be solved first

**Note:** This is a Java language limitation, not specifically a Permuplate limitation. Permuplate generates the correct overloads; Java's type inference is what fails.

---

## Soft Gaps

### S1 — `@Permute(inline=true)` on interfaces not tested

**Status:** Implemented and should work, but not explicitly tested.

**The issue:** All existing `inline=true` examples use `class` templates (e.g., `Handlers.Handler1`). The Maven plugin's `InlineGenerator` works on `ClassOrInterfaceDeclaration` which covers both — but a test with an `interface` template has never been written.

**Risk:** Low. The JavaParser AST handles `class` and `interface` uniformly. But there may be edge cases (e.g., interface methods are implicitly abstract, no constructor to rename, modifier handling may differ).

**Action needed:** Add a test or build the RuleInterfaces example (see proposal doc) and confirm it generates correctly.

---

### S2 — Two `@Permute(inline=true)` templates in the same parent class

**Status:** Theoretically supported but never tested.

**The issue:** The Maven plugin scans the template file, finds all `@Permute`-annotated members, and processes each. If two nested interfaces in the same parent both have `@Permute(inline=true)`, does the plugin:
- Process them sequentially and merge into one augmented parent? ✓ (expected)
- Process them independently and produce two conflicting output files? ✗ (would be a bug)

**Action needed:** Test with the RuleInterfaces example (two templates in one parent).

---

### S3 — `PermuteMojo` still uses old `leadingLiteral` prefix check

**Status:** Identified in project-health pass, not yet fixed.

**The issue:** `PermuteProcessor` (APT) now uses `AnnotationStringAlgorithm.matches()` for the `className` prefix check (full substring matching). `PermuteMojo` still uses the old `leadingLiteral` approach — extracting the text before the first `${` and checking `startsWith`. This means the validation is weaker and inconsistent between the two processing paths.

**Impact:** Low severity for now (the old check catches the most common mistakes). But as users write more complex `className` patterns (e.g., multi-literal like `"Foo${i}Bar${j}"`), the Mojo would silently accept things the APT would reject, and vice versa.

**Action needed:** Update `PermuteMojo.generateTopLevel()` to use `AnnotationStringAlgorithm.matches()` instead of `leadingLiteral`.

---

### S4 — `@PermuteParam` with `to="${i}"` generates 0 params when `i=1`

**Status:** Untested edge case.

**The issue:** When using `@PermuteParam(from="1", to="${i}")` and the outer `@Permute` has `from=1`, the inner range is `[1, 1]` — one parameter. But if someone writes `from=0` on the outer loop and `to="${i}"` on the inner, the range would be `[1, 0]` — empty. The `from > to` check fires in the processor... but what does the inner loop do?

**Action needed:** Add a degenerate test for `@PermuteParam` where `to` evaluates to a value less than `from`.

---

### S5 — The remaining `@Ignore` test in `PrefixValidationTest`

**Status:** Test correctly `@Ignored` but the underlying case is unresolved.

**The issue:** `testAdjacentVariablesOnNonEmptyPrefixAreNotOrphan` validates correctly (no orphan errors) but then fails when JEXL tries to evaluate `${v1}`, `${v2}`, `${v3}` during generation — they are undeclared variables. The test demonstrates the correct validation behaviour but cannot run end-to-end.

**Two possible fixes:**
- Redesign the test to test validation in isolation (call `AnnotationStringAlgorithm.validate()` directly, not through the full compile-testing pipeline)
- Declare `v1`, `v2`, `v3` as string constants via `strings = {"v1=prefix", "v2=", "v3=suffix"}` so JEXL can evaluate them

**Action needed:** Redesign the test using one of the approaches above and remove `@Ignore`.

---

## Newly Identified Patterns (We Can Do, Haven't Demonstrated)

### N1 — Two independent permutation templates in one parent class

**What it is:** Two `@Permute`-annotated nested types inside the same parent, each generating a different family of siblings.

**Example:**
```java
public class RuleInterfaces {
    @Permute(varName="i", from=2, to=5, className="Condition${i}", inline=true, keepTemplate=true)
    public interface Condition1 { boolean test(@PermuteParam(...) Object fact1); }

    @Permute(varName="i", from=2, to=5, className="Action${i}", inline=true, keepTemplate=true)
    public interface Action1 { void execute(@PermuteParam(...) Object fact1); }
}
```

**Status:** Should work — the Maven plugin processes each `@Permute` annotation independently. **Not tested.** Needs the RuleInterfaces example to validate.

---

### N2 — `@Permute` on a method to generate depth-named overloads

**What it is:** The Drools DSL has `path2()`, `path3()`, `path5()` as static factory methods with different names encoding their depth. This is the `@Permute on a method` pattern — generating N method overloads in one class.

**Example:**
```java
public class PathBuilder {
    @Permute(varName="i", from=2, to=10, className="PathBuilders")
    public static PathStep path${i}(Object... sources) { ... }
}
```

Wait — `@Permute` on a method uses `className` for the generated **class**, not the method name. The generated class contains all overloads. So `path2()` through `path10()` would need different method **names**, not just different parameter lists. Currently `@Permute` on a method generates a single class with N overloads of the **same method name** (differing in parameter count via `@PermuteParam`).

**Gap refinement:** To generate `path2()`, `path3()`, etc. with DIFFERENT names, we would need either:
- `@Permute` on a method where the method name itself is templated (e.g., `methodName = "path${i}"`)
- Or: one class per arity (the standard type permutation path), but that produces `PathBuilder2.java`, `PathBuilder3.java`, etc. as separate top-level classes

**Status:** This specific pattern (varying method name by arity) is **not currently supported**. It would need a new `methodName` attribute on `@Permute` when placed on a method.

---

### N3 — `@PermuteParam` on interface abstract methods (not just class methods)

**What it is:** Using `@PermuteParam` to expand parameters on an interface method (abstract, no body). This is implicit in the RuleInterfaces example but hasn't been explicitly demonstrated.

**Example:**
```java
public interface Condition1 {
    boolean test(@PermuteParam(varName="j", from="1", to="${i}", 
                               type="Object", name="fact${j}") Object fact1);
}
```

**Status:** Should work — `PermuteParamTransformer` operates on `MethodDeclaration` regardless of whether it has a body. But abstract methods have no body, so the anchor expansion at call sites (the part that rewrites `c.test(fact1)` → `c.test(fact1, fact2, ...)`) is **not applicable** — there are no call sites inside the interface. This should silently no-op on the call-site expansion.

**Action needed:** Verify that `@PermuteParam` on an interface abstract method doesn't error when there are no call sites to expand.

---

## Priority Order for Working Through Gaps

| # | Item | Type | Effort | Impact |
|---|------|------|--------|--------|
| 1 | S3 — Mojo uses old prefix check | Soft gap | Low | Consistency |
| 2 | S5 — Redesign `@Ignore` test | Soft gap | Low | Test completeness |
| 3 | N1+S1+S2 — Test inline=true on interfaces + two templates | New pattern + soft gaps | Medium (needs RuleInterfaces example) | Validates real use case |
| 4 | N3 — Verify @PermuteParam on abstract interface method | New pattern | Low | Needed for N1 |
| 5 | S4 — Degenerate test for empty @PermuteParam range | Soft gap | Low | Edge case safety |
| 6 | N2 — Method name templating (`path${i}()`) | New pattern → requires new feature | High | New capability |
| 7 | G3 — Overload selection by lambda arity | Hard gap | N/A (Java limitation) | Document only |
| 8 | G1 — Generic type parameter arity | Hard gap | Very high | New capability |
| 9 | G2 — Return type narrowing by arity | Hard gap | Very high | New capability |
