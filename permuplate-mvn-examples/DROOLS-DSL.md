# Drools RuleBuilder DSL — Design Document

**Location:** `permuplate-mvn-examples/src/main/permuplate/.../drools/` and `src/main/java/.../drools/`  
**Package:** `io.quarkiverse.permuplate.example.drools`  
**Purpose:** A self-contained, testable approximation of the Drools RuleBuilder DSL, built using Permuplate. This is the **primary playground** for evolving the Drools DSL using Permuplate's annotation-driven permutation — validate ideas here before applying them in the Drools codebase.

---

## Why This Exists

The Drools RuleBuilder DSL (`droolsvol2/src/main/java/org/drools/core/RuleBuilder.java`) is a fluent, type-safe rule-construction API that accumulates fact types as you chain calls. The hand-written version has classes like `Join2First`, `Join3First`, ... `Join5First` — all nearly identical, differing only in arity. Permuplate is designed to eliminate exactly this boilerplate.

Rather than modifying the Drools codebase directly as the first experiment, this module provides:

1. **A safe sandbox** — break, rethink, and rebuild without touching production Drools code
2. **A regression suite** — tests verify that the DSL constructs rules correctly and that permuted arities behave identically
3. **A feature testbed** — new Permuplate features (G1–G4) can be validated here before being relied upon in Drools
4. **Living documentation** — the templates themselves show exactly how Permuplate generates the DSL

---

## The Core DSL Pattern

The DSL builds a rule by fluent chaining. Each step accumulates one more fact type into the rule's type signature:

```java
RuleDefinition rule = builder.rule("age-comparison")
        .from(ctx -> ctx.persons())                          // 1 fact: Person
        .join(ctx -> ctx.colleagues())                       // 2 facts: Person, Person
        .filter((ctx, p1, p2) -> p1.age() > p2.age())       // still 2 facts
        .filter((ctx, p1, p2) -> p2.age() > 18)             // still 2 facts
        .fn((ctx, p1, p2) -> System.out.println("match"));  // terminal: produces RuleDefinition
```

The type parameter list grows with each `join()`: `Join1First<DS,T1>` → `Join2First<DS,T1,T2>` → `Join3First<DS,T1,T2,T3>`. The compiler enforces that your lambda signatures match the accumulated fact types exactly.

---

## The First / Second Split — The Key Design Decision

This is the most important and subtle aspect of the DSL design. Every arity level has **two classes**:

- **`JoinNSecond<DS, T1,...,TN>`** — the "gateway" class. Its job is to accept operations that **advance the arity** — specifically `join()`.
- **`JoinNFirst<DS, T1,...,TN> extends JoinNSecond<DS, T1,...,TN>`** — the "full" class. Its job is to hold all operations that **preserve the arity** — `filter()`, and terminal operations like `fn()`.

### Why split them?

**Type-safe API guidance.** The user always holds a `JoinNFirst`. Because `First extends Second`, they can call both arity-preserving and arity-advancing operations. But the *parameter type* of multi-step join overloads (Phase 2) accepts `JoinNSecond` — not `JoinNFirst`. This means:

```java
// Phase 2 multi-step join:
join1First.join(existingJoin2First)   // OK: Join2First satisfies Join2Second
```

`JoinNSecond` acts as the **input contract** for pre-built N-arity structures. `JoinNFirst` is the **output** of every chain step. The split ensures:

- You can always filter repeatedly (stay on First, same type)
- You can always advance arity via join() (move from First to (N+1)First)
- Pre-built structures can be used as join inputs via the Second supertype
- `fn()` is on First — you can only terminate when holding a fully-assembled structure

### What the user never sees directly

Users hold `JoinNFirst`. They never explicitly hold a bare `JoinNSecond` in normal usage. Second is an implementation detail of the inheritance hierarchy and the Phase 2 multi-step join parameter types. But its presence is what makes the design extensible.

### The leaf node

`Join6Second` has no `join()` methods — `@PermuteMethod` with inferred `to = @Permute.to - i = 6 - 6 = 0` produces an empty range, silently generating zero overloads. This is automatic and correct: you cannot join beyond 6 facts. `Join6First` still has `filter()` and `fn()` — you can still refine and terminate at arity 6.

---

## Naming Convention: Why `T${j}` not `A, B, C`

This example uses `T1, T2, T3,...` (the `T${j}` convention) throughout. This is a deliberate choice:

**`T${j}` enables zero-annotation implicit inference.** Permuplate's Maven plugin can automatically infer return types, parameter types, and type parameter expansions when names follow the `T+number` pattern. No `@PermuteReturn`, no `@PermuteDeclr` — just `@Permute` and `@PermuteMethod`.

The original Drools code uses `A, B, C, D, E` (single-letter names). That naming convention requires explicit `@PermuteReturn` and `@PermuteDeclr` annotations throughout because the processor cannot identify the growing tip from letter names. For a hand-maintained template that will evolve over time, the T${j} implicit path is significantly easier to maintain.

**Rule:** when evolving this example, prefer `T${j}` naming to keep the templates annotation-minimal. If alpha naming is genuinely required for some reason, document why.

---

## What Permuplate Features Each Phase Exercises

### Phase 1 (implemented here)
Sequential join chain: `from → join → filter → fn`

| Feature | Used by |
|---|---|
| **G1** `@PermuteTypeParam` (implicit) | `Consumer1`, `Predicate1` — type params expand with `@PermuteParam` |
| **G2** `@PermuteReturn` (implicit) | `JoinNSecond.join()` — return type inferred from T${j} convention |
| **G2** `@PermuteReturn` (explicit) | `JoinNFirst.filter()` — self-return; `fn()` — fixed RuleDefinition return |
| **G2** Boundary omission | `Join6Second` — leaf node, no `join()` methods generated |
| **G3** `@PermuteMethod` | `JoinNSecond` — multiple `join()` overloads (Phase 1: `to` inferred, but just 1 overload per class for sequential chain) |
| **G3** Extends expansion | `JoinNFirst extends JoinNSecond` — extends clause auto-expanded per arity |
| **N4** `typeArgList()` | `filter()` and `fn()` parameter types — `Predicate${i+1}<DS, ${typeArgList(1,i,'T')}>` |

### Phase 2 (future)
Multi-step joins: `join(Join2First)` to add 2+ facts at once

- `@PermuteMethod` with j>1 on `JoinNSecond` — overloads that accept `JoinNSecond` parameters
- `@PermuteDeclr` on parameters for the parameter types

### Phase 3+ (future)
- `not()` / negation groups (`Not2`, `Group2` pattern)
- `path()` traversal (OOPath — `path2()`, `path3()`, ... via G4 `@PermuteMethod.name`)
- Variable-based cross-fact filters (`Variable<T>` pattern)
- `params()` typed parameter binding

---

## The `RuleDefinition` Recording Structure

`RuleDefinition` is what the DSL produces. It serves two purposes:

1. **Record the configured rule** — sources (DataSource references), predicates (filter lambdas), and the terminal action. These support *structural assertions*: "this rule has 2 sources and 1 filter."

2. **Execute the rule** — `run(DS ctx)` evaluates the rule against actual data, recording which fact combinations were matched. These support *behavioural assertions*: "running this rule against [Alice(30), Bob(25)] matched exactly one pair."

```java
// Structural assertion
assertThat(rule.sourceCount()).isEqualTo(2);
assertThat(rule.filterCount()).isEqualTo(1);

// Behavioural assertion
ctx.persons().add(new Person("Alice", 30));
ctx.colleagues().add(new Person("Bob", 25));
rule.run(ctx);
assertThat(rule.executionCount()).isEqualTo(1);
assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
assertThat(rule.capturedFact(0, 1)).isEqualTo(new Person("Bob", 25));
```

The behavioural tests are the most valuable long-term: they verify that the type-safe permuted API correctly routes facts through the filter chain to the action, regardless of arity.

---

## The Permutation Correctness Tests

The key assertion this example makes about Permuplate: **every generated arity behaves identically in structure, varying only in fact count**. Tests should cover:

- Arity 1 (from → fn directly)
- Arity 2 (one join)
- Arity 3 (two joins)
- Arity 6 (maximum — leaf node tests that no join() exists)
- Multiple filters at the same arity level
- Filter predicates that reject some combinations

If any arity behaves differently from the others in equivalent scenarios, that is a Permuplate regression.

---

## Known Limitations and Deferred Items

| Limitation | Root cause | Workaround |
|---|---|---|
| `filter()` requires explicit `@PermuteReturn` | Self-return inference (TODO-2) not yet implemented | Explicit `@PermuteReturn(typeArgs="DS, ${typeArgList(1,i,'T')}")` |
| `fn()` requires explicit `@PermuteReturn(when="true")` | `RuleDefinition` not in the generated set — boundary omission would wrongly omit it | `when="true"` overrides boundary check |
| Alpha naming (`A,B,C`) not used | Would disable implicit inference, requiring explicit annotations everywhere | Use `T${j}` convention; alpha is tested separately in `PermuteReturnTest` |
| Maximum arity is 6 | Practical limit for the example | Change `to=6` on all templates to extend; tests should scale automatically |

---

## How to Evolve This DSL

When adding Phase 2 (multi-step joins) or Phase 3 features:

1. **Add to the template, not by hand.** If you're adding a new method to `JoinNSecond`, it goes in the `Join0Second` template. Permuplate generates the rest.

2. **Prefer implicit over explicit.** Before adding `@PermuteReturn`, check if the return type uses `T${j}` convention — if so, inference fires automatically.

3. **Write the test first.** Add a failing test in `RuleBuilderTest` that demonstrates the new DSL usage, then evolve the templates to make it pass.

4. **Check the leaf node.** Any new method on `JoinNSecond` that uses `@PermuteMethod` must verify that `Join6Second` correctly gets zero overloads (empty range, leaf).

5. **Run the full permutation matrix.** Tests should cover arities 1–6 for any new method, not just the typical 2–3 case.

6. **Validate in permuplate-tests first.** If a new Permuplate feature is needed, add a focused test in `permuplate-tests/` (as we did for alpha naming) before relying on it here.

---

## Connection to the Real Drools Codebase

The real Drools code is at `/Users/mdproctor/dev/droolsoct2025/droolsvol2/`. Key files:

- `RuleBuilder.java` — the hand-written builder with `Join2First..Join5First`, `Join2Second..Join5Second`, `From1First`
- `RuleBuilderTest.java` — usage examples that this Permuplate example approximates
- `function/` directory — `Consumer1..4`, `Predicate1..10`, `Function1..5` (all hand-written, all G1 candidates)

Once this Permuplate example is validated and tests are comprehensive, the Drools classes can be replaced with Permuplate-generated equivalents one family at a time:
1. Consumer family (simplest — pure G1)
2. Predicate family (G1)
3. Join chain (G1 + G2 + G3)
