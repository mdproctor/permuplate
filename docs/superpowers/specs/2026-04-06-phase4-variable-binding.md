# Spec: Phase 4 — Variable<T> Cross-Fact Binding

**Date:** 2026-04-06
**Status:** Approved
**Motivation:** Add named variable bindings to the Drools DSL sandbox. DRL rules use
named bindings (`$person : Person(...)`) extensively; the Java DSL needs an equivalent
mechanism for migration fidelity and readability in complex multi-join rules.

---

## Background: DRL Named Bindings

In DRL:
```
$person  : Person( age >= 18 )
$account : Account( holder == $person.name )
```

The Java DSL equivalent (matching Drools vol2 API exactly):
```java
Variable<Person>  personVar  = new Variable<>();
Variable<Account> accountVar = new Variable<>();

builder.from("persons", ctx -> ctx.persons())
       .var(personVar)
       .join(ctx -> ctx.accounts())
       .var(accountVar)
       .filter(personVar, accountVar,
               (ctx, person, account) -> person.name().equals(account.holder()))
       .fn((ctx, p, a) -> { });
```

The positional filter API (`filter(Predicate3<DS, A, B>)`) already works and isn't
removed. Variable-based filters are additive — they overload `filter()` with a
different first-argument type. Both styles are valid; named bindings are preferred
when facts need to be referenced by name for clarity.

---

## Files Changed

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/.../drools/Variable.java` | **Create** | Typed index holder; binds to a fact position at `var()` call time |
| `src/main/java/.../drools/RuleDefinition.java` | Modify | Add `bindVariable()` and `addVariableFilter()` |
| `src/main/permuplate/.../drools/JoinBuilder.java` | Modify | Add `var()` and variable-based `filter()` overloads to `Join0First` |
| `src/test/java/.../drools/RuleBuilderTest.java` | Modify | Add 4 variable binding tests |

No new Permuplate core changes required.

---

## Variable<T>

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Typed named binding for cross-fact predicates in the rule DSL.
 *
 * <p>Created before the rule builder chain and passed to {@code var()} to bind
 * to a specific accumulated fact. Once bound, passed to variable-based
 * {@code filter()} overloads to reference that fact by name rather than position.
 *
 * <p>Matches the {@code Variable<T>} pattern in Drools vol2 for migration fidelity.
 */
public class Variable<T> {
    private int index = -1;

    /** Binds this variable to the given fact index. Called by RuleDefinition.bindVariable(). */
    public void bind(int index) {
        this.index = index;
    }

    /** Returns the 0-based index of the bound fact in the accumulated facts array. */
    public int index() {
        return index;
    }

    /** Returns true if this variable has been bound via var(). */
    public boolean isBound() {
        return index >= 0;
    }
}
```

User creates instances before the builder chain:
```java
Variable<Person>  personVar  = new Variable<>();  // unbound; index = -1
```

After `.var(personVar)`, `personVar.index()` returns the 0-based position of that
fact in the accumulated fact array. This binding is set once and not changed.

---

## RuleDefinition Changes

### bindVariable()

```java
/**
 * Binds a variable to a specific fact position. Called by JoinFirst.var().
 * factIndex is the 0-based index of the fact in the accumulated array —
 * typically rd.factArity() - 1 (the most recently accumulated fact).
 */
public void bindVariable(Variable<?> v, int factIndex) {
    v.bind(factIndex);
}
```

### addVariableFilter() — 2-variable overload

```java
/**
 * Registers a variable-based cross-fact filter. Unlike addFilter(), this method
 * does NOT go through wrapPredicate() — it constructs the NaryPredicate directly
 * using the variable indices, bypassing reflection entirely.
 *
 * <p>Validation: both variables must be bound before this is called. A variable
 * with index -1 indicates var() was never called for it.
 */
@SuppressWarnings("unchecked")
public <V1, V2> void addVariableFilter(Variable<V1> v1, Variable<V2> v2,
                                        Predicate3<DS, V1, V2> predicate) {
    if (!v1.isBound() || !v2.isBound())
        throw new IllegalStateException(
            "Variable not bound — call var() before using it in filter()");
    int i1 = v1.index(), i2 = v2.index();
    filters.add((ctx, facts) ->
        predicate.test((DS) ctx, (V1) facts[i1], (V2) facts[i2]));
}
```

### addVariableFilter() — 3-variable overload

```java
@SuppressWarnings("unchecked")
public <V1, V2, V3> void addVariableFilter(Variable<V1> v1, Variable<V2> v2,
                                             Variable<V3> v3,
                                             Predicate4<DS, V1, V2, V3> predicate) {
    if (!v1.isBound() || !v2.isBound() || !v3.isBound())
        throw new IllegalStateException(
            "Variable not bound — call var() before using it in filter()");
    int i1 = v1.index(), i2 = v2.index(), i3 = v3.index();
    filters.add((ctx, facts) ->
        predicate.test((DS) ctx, (V1) facts[i1], (V2) facts[i2], (V3) facts[i3]));
}
```

**Why bypass wrapPredicate():** `wrapPredicate` uses reflection to find the `test` method
and infers `factArity` from its parameter count. A `NaryPredicate` has `test(Object, Object[])` —
parameter count 2, so `factArity = 1`. This triggers the single-fact trim logic (line 300),
which would incorrectly extract `facts[registeredFactCount - 1]` and pass only that to the
predicate instead of the two variable-indexed facts. Direct `NaryPredicate` construction
bypasses this entirely.

---

## JoinBuilder Template Changes

All three new methods go on `Join0First`. All use `@PermuteReturn` with `when="true"` to
appear on every arity without boundary omission.

### var()

```java
/**
 * Binds a variable to the most recently accumulated fact.
 * After this call, the variable's index() equals rd.factArity() - 1.
 */
@PermuteReturn(className = "Join${i}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i, 'alpha')",
               when = "true")
public <T> Object var(Variable<T> v) {
    rd.bindVariable(v, rd.factArity() - 1);
    return this;
}
```

### filter(v1, v2, pred) — 2-variable

```java
/**
 * Cross-fact filter using two named variable bindings.
 * Predicate types are V1, V2 — the types of the bound variables,
 * independent of the enclosing class's arity type parameters.
 */
@PermuteReturn(className = "Join${i}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i, 'alpha')",
               when = "true")
public <V1, V2> Object filter(Variable<V1> v1, Variable<V2> v2,
                               Predicate3<DS, V1, V2> predicate) {
    rd.addVariableFilter(v1, v2, predicate);
    return this;
}
```

### filter(v1, v2, v3, pred) — 3-variable

```java
@PermuteReturn(className = "Join${i}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i, 'alpha')",
               when = "true")
public <V1, V2, V3> Object filter(Variable<V1> v1, Variable<V2> v2, Variable<V3> v3,
                                   Predicate4<DS, V1, V2, V3> predicate) {
    rd.addVariableFilter(v1, v2, v3, predicate);
    return this;
}
```

**Method-level type parameters:** `<T>`, `<V1, V2>`, `<V1, V2, V3>` are method-level
generics, not class-level. They are resolved by the caller's type context. No
`@PermuteTypeParam` annotation is needed — these type params do not expand the
enclosing class's type parameter list.

**Overload disambiguation:** Java resolves `filter(Variable<V1>, Variable<V2>, Predicate3)`
vs `filter(Predicate${i+1})` by first-argument type: `Variable` vs `PredicateN`. No
ambiguity at any arity.

---

## Generated Output Example

On `Join2First<END, DS, A, B>` (i=2):

```java
// var() — binds to most recently accumulated fact (index 1 = B)
public <T> Join2First<END, DS, A, B> var(Variable<T> v) {
    rd.bindVariable(v, rd.factArity() - 1);
    return this;
}

// 2-variable filter
public <V1, V2> Join2First<END, DS, A, B> filter(
        Variable<V1> v1, Variable<V2> v2,
        Predicate3<DS, V1, V2> predicate) {
    rd.addVariableFilter(v1, v2, predicate);
    return this;
}

// 3-variable filter
public <V1, V2, V3> Join2First<END, DS, A, B> filter(
        Variable<V1> v1, Variable<V2> v2, Variable<V3> v3,
        Predicate4<DS, V1, V2, V3> predicate) {
    rd.addVariableFilter(v1, v2, v3, predicate);
    return this;
}
```

The same three methods appear identically on `Join1First` through `Join6First`, differing
only in the return type.

---

## Tests

```java
@Test
public void testVarTwoFactCrossFilter() {
    // Basic case: filter where person.name equals account.holder
    Variable<Person>  personVar  = new Variable<>();
    Variable<Account> accountVar = new Variable<>();

    var rule = builder.from("persons", ctx -> ctx.persons())
            .var(personVar)
            .join(ctx -> ctx.accounts())
            .var(accountVar)
            .filter(personVar, accountVar,
                    (ctx, p, a) -> p.name().equals(a.holder()))
            .fn((ctx, p, a) -> { });

    rule.run(ctx);
    // Verify only matched pairs execute
    assertThat(rule.executionCount()).isGreaterThan(0);
    for (int i = 0; i < rule.executionCount(); i++) {
        Person  p = (Person)  rule.capturedFact(i, 0);
        Account a = (Account) rule.capturedFact(i, 1);
        assertThat(p.name()).isEqualTo(a.holder());
    }
}

@Test
public void testVarIndexCapturedAtBindTime() {
    // Bind personVar to fact[0], join two more facts, verify the variable still
    // resolves to index 0 in a filter that skips the intermediate fact (account).
    Variable<Person> personVar = new Variable<>();
    Variable<Order>  orderVar  = new Variable<>();

    var rule = builder.from("persons", ctx -> ctx.persons())
            .var(personVar)                          // personVar → index 0
            .join(ctx -> ctx.accounts())             // index 1 — no var bound
            .join(ctx -> ctx.orders())
            .var(orderVar)                           // orderVar → index 2
            .filter(personVar, orderVar,
                    (ctx, p, o) -> o.amount() > 0)  // cross-references index 0 and 2
            .fn((ctx, p, a, o) -> { });

    rule.run(ctx);
    for (int i = 0; i < rule.executionCount(); i++) {
        Order o = (Order) rule.capturedFact(i, 2);
        assertThat(o.amount()).isGreaterThan(0);
    }
}

@Test
public void testVarThreeVariableFilter() {
    Variable<Person>  pVar = new Variable<>();
    Variable<Account> aVar = new Variable<>();
    Variable<Order>   oVar = new Variable<>();

    var rule = builder.from("persons", ctx -> ctx.persons())
            .var(pVar)
            .join(ctx -> ctx.accounts())
            .var(aVar)
            .join(ctx -> ctx.orders())
            .var(oVar)
            .filter(pVar, aVar, oVar,
                    (ctx, p, a, o) -> p.name().equals(a.holder()) && o.amount() > 0)
            .fn((ctx, p, a, o) -> { });

    rule.run(ctx);
    for (int i = 0; i < rule.executionCount(); i++) {
        Person  p = (Person)  rule.capturedFact(i, 0);
        Account a = (Account) rule.capturedFact(i, 1);
        assertThat(p.name()).isEqualTo(a.holder());
    }
}

@Test
public void testUnboundVariableThrows() {
    Variable<Person> personVar = new Variable<>();  // never bound via var()

    assertThatThrownBy(() ->
        builder.from("persons", ctx -> ctx.persons())
               .join(ctx -> ctx.accounts())
               .filter(personVar, new Variable<Account>(),
                       (ctx, p, a) -> true)
    ).isInstanceOf(IllegalStateException.class)
     .hasMessageContaining("Variable not bound");
}
```

**Note on index capture:** `testVarIndexCapturedAtBindTime` verifies that the binding index
is captured at `var()` call time, not at `filter()` call time. `personVar.index() == 0`
regardless of how many additional facts are joined after it.

---

## What This Does Not Include

- **1-variable `filter(v1, pred2)`** — references a single named fact that isn't the latest.
  The existing `filterLatest` covers single-latest-fact filtering; DRL equivalent is an
  inlined pattern constraint (`$p : Person(age >= 18)`), not a cross-pattern beta constraint.
  YAGNI for current use cases.
- **Variable equality constraints** — `filter(v1, v2, (ctx, p, a) -> p == a)` would detect
  same-object identity. Supported naturally through the existing predicate; no special handling.
- **Variable<T> outside JoinFirst** — `var()` is not on `JoinNSecond`, `NegationScope`, or
  `ExistenceScope`. Variables are bound at the outer chain level only.
- **Compile-time unbound detection** — the unbound check is at `filter()` registration time
  (runtime), not at compile time. Java's type system cannot enforce call order.
- **`index()` overload on `from()`/`join()`** — the Drools vol2 also has an `index()` method
  on `JoinNFirst`; out of scope for this phase.
