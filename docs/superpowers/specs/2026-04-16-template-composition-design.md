# Template Composition — Design Spec

**Date:** 2026-04-16  
**Status:** Approved  
**Scope:** `permuplate-annotations`, `permuplate-core`, `permuplate-maven-plugin`, `permuplate-tests`

---

## Problem

Permuplate generates a class family from one template (Template A → N generated classes). But you often need a second family that mirrors the first — logged wrappers, synchronized adapters, builders, null-safe decorators. Today you must manually re-declare type parameters and keep the two families in sync. Template composition eliminates that overhead.

---

## Mode Constraint

**Template composition is Maven plugin only.** The Maven plugin runs at `generate-sources` before javac, guaranteeing Template A's output exists when Template B is processed.

In APT mode, Template B's sentinel class would need to reference Template A's generated class names (e.g. `implements Callable2<A,B,R>`) — but those don't exist at javac compile time, which violates Permuplate's core guarantee that templates are valid, compilable Java. APT mode emits a clear compile error: "Template composition requires the Maven plugin (`permuplate-maven-plugin`). Use `inline=true`."

---

## Core Mechanism — `@PermuteSource`

```java
@Repeatable(PermuteSources.class)
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteSource {
    /**
     * JEXL-evaluated name of the source generated class.
     * E.g. {@code "Callable${i}"} — resolved per permutation value.
     */
    String value();
}
```

`@PermuteSource("X${i}")` on a `@Permute` template does three things:

1. **Ordering** — guarantees the source template is fully generated before Template B starts
2. **Type inference** — reads the generated `X${i}.java` AST and automatically mirrors its type parameters into Template B (no `@PermuteTypeParam` needed)
3. **Structural access** — makes the generated class's fields, methods, record components available for builder synthesis and `@PermuteDelegate`

**Constraints:**
- Source and derived template must be in the same Maven module
- Single-level only — Template C sourcing Template B is deferred
- Circular dependencies are detected in the two-pass scan and reported as compile errors
- Source must resolve to a name in the known generated set; unresolvable source names are compile errors

---

## Three Capabilities

### Capability A — Ordering + type inference (user writes all bodies)

`@PermuteSource` alone. The processor infers type params and ensures ordering. The user writes the complete method bodies.

```java
@Permute(varName="i", from="2", to="6", className="TimedCallable${i}",
         inline=true, keepTemplate=false)
@PermuteSource("Callable${i}")
public class TimedCallable2 implements Callable2<A, B, R> {
    // A, B, R — inferred from Callable2, no @PermuteTypeParam needed

    private final Callable2<A, B, R> delegate;

    public R call(A a, B b) throws Exception {
        long t = System.nanoTime();
        try { return delegate.call(a, b); }
        finally { log(System.nanoTime() - t); }
    }
}
// Generates: TimedCallable3<A,B,C,R>, TimedCallable4<A,B,C,D,R>, ...
// Each with inferred type params and the correct call() signature
```

### Capability B — `@PermuteDelegate` (pure delegation synthesis)

`@PermuteDelegate` on a field synthesises all delegating method bodies from the source interface. The user overrides only what needs custom logic.

```java
@Permute(varName="i", from="2", to="6", className="SynchronizedCallable${i}",
         inline=true, keepTemplate=false)
@PermuteSource("Callable${i}")
public class SynchronizedCallable2 implements Callable2<A, B, R> {
    @PermuteDelegate(modifier = "synchronized")
    private final Callable2<A, B, R> delegate;
    // Processor generates per method:
    // public synchronized R call(A a, B b) throws Exception {
    //     return delegate.call(a, b);
    // }
}
```

`@PermuteDelegate` attributes:
- `modifier` (optional) — Java modifier to add to synthesised methods (e.g. `"synchronized"`)
- User-declared methods take precedence: any method already present in the template is not synthesised

### Capability C — Builder synthesis (empty body → complete fluent builder)

`@PermuteSource` on a record source with no template body. The processor reads the record's components and generates a complete fluent builder.

```java
@Permute(varName="i", from="3", to="6", className="Tuple${i}Builder",
         inline=true, keepTemplate=false)
@PermuteSource("Tuple${i}")
public class Tuple3Builder {}
// Processor reads Tuple3<A,B,C>(A a, B b, C c) and generates:
// public class Tuple3Builder<A, B, C> {
//     private A a;  private B b;  private C c;
//     public Tuple3Builder<A,B,C> a(A a) { this.a = a; return this; }
//     public Tuple3Builder<A,B,C> b(B b) { this.b = b; return this; }
//     public Tuple3Builder<A,B,C> c(C c) { this.c = c; return this; }
//     public Tuple3<A,B,C> build() { return new Tuple3<>(a, b, c); }
// }
```

Builder synthesis triggers when: `@PermuteSource` references a `RecordDeclaration` source AND the template body is empty (or contains only field declarations with `@PermuteDelegate`).

---

## Tutorial Examples

### Individual concept demos

**Demo A** — `TimedCallable${i}` from `Callable${i}`  
Focus: ordering + type inference. User writes timing logic. Clear before/after showing type param relief.

**Demo B** — `SynchronizedCallable${i}` from `Callable${i}`  
Focus: `@PermuteDelegate`. Zero method bodies in template. Shows pure delegation synthesis in ~5 lines.

**Demo C** — `Tuple${i}Builder` from `Tuple${i}` record  
Focus: builder synthesis. Empty template class generates 4 complete builders with fluent API.

### Cohesive story — Typed Event System

All three capabilities used together to build a complete typed event processing infrastructure from one root template.

```
Event${i} records (Template A)
    │
    ├── Event${i}Builder         (Capability C — empty body, complete builder)
    │
    ├── EventFilter${i}          (Capability A — user writes predicate logic,
    │   implements EventListener${i}           type params inferred)
    │
    └── LoggingEventBus${i}      (Capability B — @PermuteDelegate,
        wraps EventBus${i}           all dispatch methods delegated + logged)
```

Narrative: "From one 20-line record template, generate typed event builders, write custom filters with zero type-param boilerplate, and wrap any event bus with logging — all staying in sync automatically."

---

## File Map

| File | Change |
|---|---|
| `permuplate-annotations/…/PermuteSource.java` | Create |
| `permuplate-annotations/…/PermuteSources.java` | Create (container) |
| `permuplate-annotations/…/PermuteDelegate.java` | Create |
| `permuplate-core/…/PermuteSourceTransformer.java` | Create — handles type inference and structural reading |
| `permuplate-core/…/PermuteDelegateTransformer.java` | Create — handles delegation synthesis |
| `permuplate-maven-plugin/…/InlineGenerator.java` | Update — two-pass ordering for @PermuteSource; builder synthesis path |
| `permuplate-processor/…/PermuteProcessor.java` | Update — emit error on @PermuteSource in APT mode |
| `permuplate-tests/…/TemplateCompositionTest.java` | Create — individual capability tests |
| `permuplate-tests/…/InlineGenerationTest.java` | Add event system cohesive test |
| `permuplate-apt-examples/…/` | Add individual demo templates |
| `permuplate-mvn-examples/…/` | Add event system cohesive example |

---

## Error Handling

| Situation | Error |
|---|---|
| `@PermuteSource` in APT mode | Compile error: "Template composition requires Maven plugin. Add `inline=true` and use `permuplate-maven-plugin`." |
| Source name not in generated set | Compile error on the `value` attribute: "Cannot resolve source 'X3' — not in generated class set" |
| Circular dependency A→B→A | Compile error: "Circular @PermuteSource dependency: A → B → A" |
| Builder synthesis on non-record source | Compile error: "Builder synthesis requires a record source; 'Callable3' is an interface" |
| `@PermuteDelegate` field type doesn't match `@PermuteSource` | Compile error: "Delegate field type 'Foo' does not match @PermuteSource 'Callable${i}'" |

---

## What Is Not In Scope

- APT mode (clear error only — "this is the way" for now)
- Cross-module template composition (Template A in module X, Template B in module Y)
- Deep chaining (C from B from A) — single-level only
- `@PermuteDelegate` with body injection (custom logic around delegation) — use Capability A for that
- `@PermuteSource` on methods (type-level only)
