# Building the Drools DSL: When the Rubber Meets the Road

*Part 7 of the Permuplate development series.*

---

## The Moment of Truth

There's a specific kind of anxiety that comes when you've built a tool and it's time to use it on the thing it was built for. All the unit tests pass. The generated code looks right in isolation. But does it actually work when you assemble the pieces into a real DSL?

The Drools RuleBuilder DSL was always the motivation for Permuplate. A self-contained approximation of it — using only Permuplate annotations, Java records, and a test suite — would be the proof of concept that the whole project needed.

Build a fluent chain:

```java
var rule = builder.from("adults", ctx -> ctx.persons())
        .join(ctx -> ctx.accounts())
        .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
        .fn((ctx, a, b) -> {});
rule.run(ctx);
assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
```

The chain advances through arity levels. At arity 1 (after `.from()`), you hold a `Join1First<Ctx, Person>`. After `.join()`, you hold a `Join2First<Ctx, Person, Account>`. The type system enforces correctness at each step.

---

## The Design Pivots Before a Line of Code Was Written

Claude and I hit the first pivot during the design phase. The original plan had two template families: `Join0Second` and `Join0First extends Join0Second`. The First/Second split is the real Drools design — `Second` as the input contract type for multi-step joins, `First extends Second` to inherit all those overloads.

Implementing this split requires G3's extends clause auto-expansion. And G3's expansion requires `T+number` naming to detect siblings via the numeric suffix pattern. Alpha naming has no numeric suffix. The auto-expansion wouldn't fire.

This was a real constraint. The Drools DSL uses single-letter type parameter names (`A, B, C, D`) — that's the convention the codebase follows. Switching to `T1, T2, T3` naming would fix the G3 issue but betray the project's purpose.

The decision: implement Phase 1 as a single `JoinFirst` family with no `Second` split. Defer the split to Phase 2 once G3 was fixed to handle alpha naming. This was the right call — it let us validate the full DSL chain first, with the architectural refinement coming later.

The second pivot was about the container class. `@PermuteReturn` boundary omission only works in InlineGenerator (inline mode). The plan had specified `inline=false` top-level templates, but that path doesn't support boundary omission — the leaf node (`Join6First` with no `join()` method) is the whole point. So we switched to `inline=true` inside a `JoinBuilder` container class.

The consequence: generated classes are `JoinBuilder.Join1First`, `JoinBuilder.Join2First`, etc. — qualified names rather than top-level. In practice this doesn't matter — `var` handles the inference, and explicit type declarations are rare.

---

## The Hand-Written Foundation

The hand-written infrastructure took shape cleanly. Five domain records:

```java
public record Person(String name, int age) {}
public record Account(String id, double balance) {}
public record Order(String id, double amount) {}
public record Product(String id, double price) {}
public record Transaction(String id, double amount) {}
```

`DataSource<T>` is a simple list wrapper with `add()`, `asList()`, `stream()`, and a static `of()` factory.

`Ctx` is a record holding one `DataSource<T>` per domain type — `persons()`, `accounts()`, `orders()`, `products()`, `transactions()`.

`RuleDefinition<DS>` is where the interesting logic lives. It records sources (lambda references to DataSources), filters (typed predicates), and a terminal action (a typed consumer). When `run(ctx)` is called, it evaluates the cross-product of all sources, applies all filters, and invokes the action for each matching combination.

The reflection wrappers in `RuleDefinition` are the implementation detail that makes the typed lambda API work. You pass a lambda with signature `(Ctx ctx, Person a) -> a.age() >= 18` — typed, type-safe — but internally it's stored as an `NaryPredicate` (a functional interface taking `Object ctx, Object[] facts`). The wrapper is created once at rule-build time:

```java
static NaryPredicate wrapPredicate(Object typed) {
    Method m = findMethod(typed, "test");
    int factArity = m.getParameterCount() - 1;  // -1 for ctx
    return (ctx, facts) -> {
        Object[] trimmed = facts.length > factArity 
            ? Arrays.copyOf(facts, factArity) : facts;
        return (Boolean) m.invoke(typed, buildArgs(ctx, trimmed));
    };
}
```

---

## The Runtime Bugs

Claude came back from the first test run with two failures. Both were instructive.

**Bug 1: `rd.asNext()` causes `ClassCastException`.**

The `join()` method was written as:

```java
public Object join(java.util.function.Function<DS, DataSource<?>> source) {
    rd.addSource(source);
    return rd.asNext();
}
```

where `asNext()` is `@SuppressWarnings("unchecked") public <T> T asNext() { return (T) this; }`. The idea: type erasure means the cast is never checked at runtime, so returning `RuleDefinition` as `JoinNFirst` is safe.

It's not safe. When the generated code has a declared return type of `JoinNFirst` (after `@PermuteReturn` rewrites the signature from `Object`), the Java compiler inserts a `checkcast JoinNFirst` bytecode instruction at every call site — not at the `asNext()` call, but wherever the return value is used. Since `RuleDefinition` doesn't extend `JoinNFirst`, the cast fails with `ClassCastException` when `.filter()` or `.fn()` is called on the result.

The fix: don't cast — create a real instance. Each generated `JoinFirst` wraps the same `RuleDefinition`. Use reflection to instantiate the next class by name-parsing:

```java
String cn = getClass().getSimpleName();
int n = Integer.parseInt(cn.replaceAll("[^0-9]", ""));
String nextName = getClass().getEnclosingClass().getName() + "$Join" + (n + 1) + "First";
return (T) Class.forName(nextName).getConstructor(RuleDefinition.class).newInstance(rd);
```

This is inelegant — it relies on the naming convention — but it works. The alternative (having each `JoinNFirst` subclass `RuleDefinition`) would require RuleDefinition to be aware of all generated classes, which is worse.

**Bug 2: Intermediate filters receive too many facts.**

A filter registered at arity 2 (checking Person and Account) uses a `Predicate3<DS, A, B>` with `test(DS ctx, A a, B b)` — three parameters. But `run()` applies all filters to the full cross-product fact tuple. At arity 3 (after joining a third source), the full tuple has three facts. `Predicate3.test` receives four arguments (ctx + 3 facts) instead of three (ctx + 2 facts). Reflection throws `IllegalArgumentException: wrong number of arguments`.

The fix is in `wrapPredicate`: truncate the facts array to the predicate's expected arity:

```java
int factArity = m.getParameterCount() - 1;
Object[] trimmed = facts.length > factArity ? Arrays.copyOf(facts, factArity) : facts;
```

Semantically this is correct — a filter registered at arity 2 should only see facts 1 and 2, regardless of how many facts the rule ultimately accumulates. The first filter in a multi-stage chain sees the world at the arity where it was registered.

---

## The Type Safety Compromise

After fixing the runtime bugs, the tests ran. But there was a visible quality issue in the test code: at arity 2 and above, the type system broke down.

After `.join()` returns a raw `JoinNFirst` (no generic parameters — the next arity's type params aren't in scope in the template), all subsequent `.filter()` and `.fn()` lambdas receive `Object`-typed parameters. To filter on `a.age()` you need to write `((Person) a).age()`. To join a new source you need a pre-typed constant:

```java
private static final Function<Ctx, DataSource<?>> ACCOUNTS = c -> c.accounts();
// ...
builder.from("adults", ctx -> ctx.persons())
       .join(ACCOUNTS)   // ← can't use inline lambda; no target type
       .filter((ctx, a, b) -> ((Person) a).age() >= 18 && ((Account) b).balance() > 500.0)
```

The arity-1 chain is fully type-safe — `.from()` returns a properly typed `Join1First<Ctx, Person>`, and the filter/fn lambdas have typed parameters. After the first `.join()`, type information is lost because `join()` returns a raw type.

This is a known limitation of Phase 1. It's inherent to the constraint that the next arity's type params aren't in scope when writing the template. The fix requires either:
1. Generating typed method bodies (Permuplate doesn't transform method bodies)
2. Implementing the First/Second split with typed join overloads (Phase 2)

The test code uses `@SuppressWarnings({"unchecked", "rawtypes"})` on the arity-2+ tests and pre-typed source constants. Ugly, but honest — the limitation is visible rather than hidden.

---

## Fifteen Tests, All Green

The final test suite covers:

- **Structural assertions** (sourceCount, filterCount, hasAction): verifying the rule is configured correctly without running it
- **Behavioural assertions** (executionCount, capturedFact): verifying the cross-product evaluator produces correct results
- **Arities 1 through 6**: systematic coverage of every arity level
- **Leaf node** (testArity6LeafNodeCompiles): a compile-time test — if `Join6First` accidentally had a `join()` method, this test would fail to compile

The leaf node test is worth explaining. It builds a 6-join chain and calls `fn()`. This compiles correctly because `Join6First` has no `join()` method — boundary omission removed it automatically when the `@PermuteReturn` evaluation produced `Join7First`, which isn't in the generated set.

If the boundary omission broke, the generated `Join6First` would have a `join()` method returning `Join7First` — a non-existent class — and the compilation would fail. The test's success is proof that the generated code is structurally correct.

---

## Phase 2 Becomes Straightforward

With the Drools Phase 1 example in place, the G3 alpha naming fix became the obvious next step. The First/Second split — the Drools architectural cornerstone — was blocked by the alpha naming gap in extends expansion. The formula bug (`+1` instead of `currentEmbeddedNum`) was blocking the correct `JoinNFirst extends JoinNSecond` relationship. Both needed to be fixed.

With those fixes done, Phase 2 becomes straightforward:

- `Join0Second` template generates `Join1Second..Join6Second` with a `join()` method (arity-advancing, leaf node at 6)
- `Join0First extends Join0Second` template generates `Join1First..Join6First` with a `filter()` method (arity-preserving)
- G3's alpha-aware extends expansion automatically generates `Join2First<DS, A, B> extends Join2Second<DS, A, B>`

In Phase 2, `join()` on `JoinNSecond` can be typed as accepting `JoinNSecond` parameters — enabling multi-step joins like `.join(existingJoin2First)` since `Join2First extends Join2Second`. The type safety that Phase 1 couldn't provide comes from the inheritance hierarchy rather than from the template's own scope.

That's where the project stands. The infrastructure is proven. The template language is expressive enough for the target use case. The gap that blocked Phase 2 is closed. The next iteration builds on everything here.

---

The other thing the journey showed: the value of testing generated *behavior*, not just generated *syntax*. The `PermuteReturnTest` and `PermuteTypeParamTest` suites check that the generated source contains the right strings. They're valuable, but they don't catch everything. The `RuleBuilderTest` runs the generated DSL against actual data with actual predicates. That's where the `rd.asNext()` bug and the `wrapPredicate` arity bug were found — because the code actually *ran*, not just compiled.

If there's one piece of advice for building tools like Permuplate: don't stop at checking that the output looks right. Check that it *works*.

---

*The project continues. Phase 2 (First/Second split), self-return inference, and eventually the real Drools migration are next. The foundation is solid.*