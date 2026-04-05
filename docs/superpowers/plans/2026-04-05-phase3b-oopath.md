# Phase 3b — OOPath Traversal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OOPath (object-graph traversal) to the Drools DSL sandbox — `pathN()` methods that traverse from a root fact through nested collections, collecting visited objects into a typed `TupleN` that joins back as a new fact.

**Architecture:** Six groups of changes: (1) support types (`BaseTuple`, `PathContext`, `Function2`, `OOPathStep`); (2) `RuleOOPathBuilder` with `Path2..Path6` builder classes; (3) `RuleDefinition` OOPath pipeline and correlated execution; (4) test domain records; (5) `Join0Second` template with `path2()..path6()` methods; (6) tests and full build. No Permuplate core changes.

**Tech Stack:** Java 21, JavaParser (Permuplate template processing), Maven (`/opt/homebrew/bin/mvn`). All new files in `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/`.

---

## File Map

| File | Action |
|---|---|
| `...drools/BaseTuple.java` | **Create** — abstract base + Tuple0..Tuple6 inner classes |
| `...drools/PathContext.java` | **Create** — tuple-in-progress holder for cross-path predicates |
| `...drools/Function2.java` | **Create** — `(A,B)->C` functional interface matching Drools |
| `...drools/OOPathStep.java` | **Create** — one type-erased traversal step |
| `...drools/RuleOOPathBuilder.java` | **Create** — Path2..Path6 builder classes |
| `...drools/RuleDefinition.java` | Modify — OOPath pipeline fields + `matchedTuples()` update |
| `...drools/Library.java`, `Room.java`, `Book.java` | **Create** — test domain records |
| `...drools/Ctx.java` | Modify — add `DataSource<Library> libraries` |
| `...permuplate/...drools/JoinBuilder.java` | Modify — add `path2()..path6()` to `Join0Second` |
| `...drools/RuleBuilderTest.java` | Modify — add 5 OOPath tests |

---

## Task 1: Support Types — BaseTuple, PathContext, Function2, OOPathStep

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/BaseTuple.java`
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/PathContext.java`
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Function2.java`
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/OOPathStep.java`

- [ ] **Step 1: Create BaseTuple.java**

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Abstract base for the typed tuple hierarchy used by OOPath traversal.
 *
 * <p>Tuple1..Tuple6 are static inner classes forming an inheritance chain
 * (Tuple2 extends Tuple1, Tuple3 extends Tuple2, etc.). Each adds one field
 * with a typed getter and populates via mutable {@link #set(int, Object)} so
 * the tuple can be built incrementally during traversal without creating
 * intermediate copies.
 *
 * <p>After traversal completes, {@link #executePipeline} copies the tuple
 * (see RuleDefinition) to avoid sibling-branch mutation.
 */
public abstract class BaseTuple {
    protected int size;

    public abstract <T> T get(int index);
    public abstract <T> void set(int index, T t);

    public int size() { return size; }

    public static class Tuple0 extends BaseTuple {
        public Tuple0() { this.size = 0; }
        @Override public <T> T get(int index) { throw new IndexOutOfBoundsException(index); }
        @Override public <T> void set(int index, T t) { throw new IndexOutOfBoundsException(index); }
    }

    public static class Tuple1<A> extends BaseTuple {
        protected A a;
        public Tuple1() { this.size = 1; }
        public Tuple1(A a) { this.a = a; this.size = 1; }
        public A getA() { return a; }
        public void setA(A a) { this.a = a; }
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0) return (T) a;
            throw new IndexOutOfBoundsException(index);
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) { a = (A) t; return; }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple2<A, B> extends Tuple1<A> {
        protected B b;
        public Tuple2() { super(); this.size = 2; }
        public Tuple2(A a, B b) { super(a); this.b = b; this.size = 2; }
        public B getB() { return b; }
        public void setB(B b) { this.b = b; }
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0) return (T) a;
            if (index == 1) return (T) b;
            throw new IndexOutOfBoundsException(index);
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) { a = (A) t; return; }
            if (index == 1) { b = (B) t; return; }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple3<A, B, C> extends Tuple2<A, B> {
        protected C c;
        public Tuple3() { super(); this.size = 3; }
        public Tuple3(A a, B b, C c) { super(a, b); this.c = c; this.size = 3; }
        public C getC() { return c; }
        public void setC(C c) { this.c = c; }
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0) return (T) a;
            if (index == 1) return (T) b;
            if (index == 2) return (T) c;
            throw new IndexOutOfBoundsException(index);
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) { a = (A) t; return; }
            if (index == 1) { b = (B) t; return; }
            if (index == 2) { c = (C) t; return; }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple4<A, B, C, D> extends Tuple3<A, B, C> {
        protected D d;
        public Tuple4() { super(); this.size = 4; }
        public Tuple4(A a, B b, C c, D d) { super(a, b, c); this.d = d; this.size = 4; }
        public D getD() { return d; }
        public void setD(D d) { this.d = d; }
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0) return (T) a;
            if (index == 1) return (T) b;
            if (index == 2) return (T) c;
            if (index == 3) return (T) d;
            throw new IndexOutOfBoundsException(index);
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) { a = (A) t; return; }
            if (index == 1) { b = (B) t; return; }
            if (index == 2) { c = (C) t; return; }
            if (index == 3) { d = (D) t; return; }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple5<A, B, C, D, E> extends Tuple4<A, B, C, D> {
        protected E e;
        public Tuple5() { super(); this.size = 5; }
        public Tuple5(A a, B b, C c, D d, E e) { super(a,b,c,d); this.e = e; this.size = 5; }
        public E getE() { return e; }
        public void setE(E e) { this.e = e; }
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0) return (T) a;
            if (index == 1) return (T) b;
            if (index == 2) return (T) c;
            if (index == 3) return (T) d;
            if (index == 4) return (T) e;
            throw new IndexOutOfBoundsException(index);
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) { a = (A) t; return; }
            if (index == 1) { b = (B) t; return; }
            if (index == 2) { c = (C) t; return; }
            if (index == 3) { d = (D) t; return; }
            if (index == 4) { e = (E) t; return; }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple6<A, B, C, D, E, F> extends Tuple5<A, B, C, D, E> {
        protected F f;
        public Tuple6() { super(); this.size = 6; }
        public Tuple6(A a, B b, C c, D d, E e, F f) { super(a,b,c,d,e); this.f = f; this.size = 6; }
        public F getF() { return f; }
        public void setF(F f) { this.f = f; }
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0) return (T) a;
            if (index == 1) return (T) b;
            if (index == 2) return (T) c;
            if (index == 3) return (T) d;
            if (index == 4) return (T) e;
            if (index == 5) return (T) f;
            throw new IndexOutOfBoundsException(index);
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) { a = (A) t; return; }
            if (index == 1) { b = (B) t; return; }
            if (index == 2) { c = (C) t; return; }
            if (index == 3) { d = (D) t; return; }
            if (index == 4) { e = (E) t; return; }
            if (index == 5) { f = (F) t; return; }
            throw new IndexOutOfBoundsException(index);
        }
    }
}
```

- [ ] **Step 2: Create PathContext.java**

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Holds the tuple being assembled during an OOPath traversal.
 *
 * <p>Passed to each traversal step function and filter predicate, allowing
 * cross-referencing of earlier path elements while filtering the current one.
 * For example, while filtering a Book, a predicate can call
 * {@code ctx.getTuple().getA()} to access the Library that started the path.
 *
 * <p>The tuple is populated incrementally as traversal proceeds:
 * {@code getA()} is available from step 1, {@code getB()} from step 2, etc.
 * Earlier elements are immutable once set within a given path branch.
 *
 * <p>Note: the Drools implementation of PathContext has a buggy constructor
 * (fall-through switch without breaks, values then overwritten by a loop).
 * This implementation is clean.
 */
public class PathContext<T extends BaseTuple> {
    private final T tuple;

    public PathContext(T tuple) {
        this.tuple = tuple;
    }

    /**
     * Returns the tuple-in-progress. Elements up to the current traversal
     * depth are populated; deeper elements are null.
     */
    public T getTuple() {
        return tuple;
    }
}
```

- [ ] **Step 3: Create Function2.java**

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * Two-input function matching Drools' custom {@code Function2<A,B,C>} type.
 * Used for OOPath traversal step functions:
 * {@code (PathContext<T>, currentFact) -> Iterable<nextFact>}.
 *
 * <p>Using Drools' custom interface (rather than {@code java.util.function.BiFunction})
 * keeps DSL signatures identical for migration fidelity.
 */
@FunctionalInterface
public interface Function2<A, B, C> {
    C apply(A a, B b);
}
```

- [ ] **Step 4: Create OOPathStep.java**

```java
package io.quarkiverse.permuplate.example.drools;

/**
 * One type-erased traversal step in an OOPath pipeline.
 *
 * <p>Stores the traversal function ({@code (PathContext, currentFact) -> children})
 * and filter predicate ({@code (PathContext, child) -> pass?}) in type-erased form
 * for uniform storage in {@link RuleDefinition}. The typed versions are captured
 * via lambda at construction time (in {@code RuleOOPathBuilder.PathN.path()}).
 */
public class OOPathStep {
    /** Type-erased: {@code (PathContext<T>, currentFact) -> Iterable<?>} */
    final java.util.function.BiFunction<Object, Object, Iterable<?>> traversal;
    /** Type-erased: {@code (PathContext<T>, child) -> boolean} */
    final java.util.function.BiPredicate<Object, Object> filter;

    public OOPathStep(
            java.util.function.BiFunction<Object, Object, Iterable<?>> traversal,
            java.util.function.BiPredicate<Object, Object> filter) {
        this.traversal = traversal;
        this.filter = filter;
    }
}
```

- [ ] **Step 5: Verify compilation**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/BaseTuple.java \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/PathContext.java \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Function2.java \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/OOPathStep.java
git commit -m "$(cat <<'EOF'
feat(drools-example): add OOPath support types — BaseTuple, PathContext, Function2, OOPathStep

BaseTuple: mutable inheritance chain Tuple1..Tuple6 with typed getters
(getA()..getF()) and generic get/set for incremental population during traversal.

PathContext: clean implementation of Drools' buggy PathContext — holds the
tuple-in-progress for cross-path predicate cross-referencing.

Function2: (A,B)->C matching Drools custom type for traversal step functions.
OOPathStep: type-erased (traversal, filter) pair for the pipeline.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: RuleOOPathBuilder — Path2..Path6

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleOOPathBuilder.java`

- [ ] **Step 1: Create RuleOOPathBuilder.java**

```java
package io.quarkiverse.permuplate.example.drools;

import java.util.List;

/**
 * Contains the Path2..Path6 builder classes for OOPath traversal chains.
 *
 * <p>PathN builders chain from outer to inner. Each {@code path(fn, flt)} call
 * adds one traversal step and returns the next lower PathN builder. The terminal
 * call is always {@code Path2.path()} which registers the complete pipeline on
 * the outer {@link RuleDefinition} and returns the END type (a JoinNFirst).
 *
 * <p>Matching Drools' {@code RuleOOPathBuilder} structure for migration fidelity.
 * Internal implementation is simplified — no Rete network nodes; the pipeline
 * is stored as {@link OOPathStep} list and executed in {@code matchedTuples()}.
 */
public class RuleOOPathBuilder {

    /**
     * Two-element path builder (root + 1 step → Tuple2). Terminal builder —
     * {@code path()} registers the accumulated pipeline and returns the JoinFirst.
     *
     * @param <END> the JoinNFirst type to return after pipeline registration
     * @param <T>   the full final tuple type (Tuple2 here)
     * @param <A>   the type being traversed FROM (the root or last element)
     * @param <B>   the type being traversed TO (new element added at this step)
     */
    public static class Path2<END, T extends BaseTuple, A, B> {
        private final END end;
        @SuppressWarnings("rawtypes")
        private final RuleDefinition rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        @SuppressWarnings("rawtypes")
        public Path2(END end, RuleDefinition rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        /**
         * Completes the path chain. Adds this step to the pipeline, registers the
         * pipeline on the outer RuleDefinition, and returns the JoinFirst result.
         */
        @SuppressWarnings("unchecked")
        public END path(Function2<PathContext<T>, A, ?> fn2,
                        Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            rd.addOOPathPipeline(rootIndex, steps);
            return end;
        }
    }

    /**
     * Three-element path builder. {@code path()} adds this step and returns a {@link Path2}
     * for the remaining step.
     */
    public static class Path3<END, T extends BaseTuple, A, B, C> {
        private final END end;
        @SuppressWarnings("rawtypes")
        private final RuleDefinition rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        @SuppressWarnings("rawtypes")
        public Path3(END end, RuleDefinition rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        @SuppressWarnings("unchecked")
        public Path2<END, T, B, C> path(Function2<PathContext<T>, A, ?> fn2,
                                         Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            return new Path2<>(end, rd, steps, rootIndex);
        }
    }

    /** Four-element path builder. */
    public static class Path4<END, T extends BaseTuple, A, B, C, D> {
        private final END end;
        @SuppressWarnings("rawtypes")
        private final RuleDefinition rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        @SuppressWarnings("rawtypes")
        public Path4(END end, RuleDefinition rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        @SuppressWarnings("unchecked")
        public Path3<END, T, B, C, D> path(Function2<PathContext<T>, A, ?> fn2,
                                             Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            return new Path3<>(end, rd, steps, rootIndex);
        }
    }

    /** Five-element path builder. */
    public static class Path5<END, T extends BaseTuple, A, B, C, D, E> {
        private final END end;
        @SuppressWarnings("rawtypes")
        private final RuleDefinition rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        @SuppressWarnings("rawtypes")
        public Path5(END end, RuleDefinition rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        @SuppressWarnings("unchecked")
        public Path4<END, T, B, C, D, E> path(Function2<PathContext<T>, A, ?> fn2,
                                               Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            return new Path4<>(end, rd, steps, rootIndex);
        }
    }

    /** Six-element path builder. */
    public static class Path6<END, T extends BaseTuple, A, B, C, D, E, F> {
        private final END end;
        @SuppressWarnings("rawtypes")
        private final RuleDefinition rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        @SuppressWarnings("rawtypes")
        public Path6(END end, RuleDefinition rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        @SuppressWarnings("unchecked")
        public Path5<END, T, B, C, D, E, F> path(Function2<PathContext<T>, A, ?> fn2,
                                                   Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            return new Path5<>(end, rd, steps, rootIndex);
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleOOPathBuilder.java
git commit -m "$(cat <<'EOF'
feat(drools-example): add RuleOOPathBuilder with Path2..Path6 builders

Each PathN.path(fn2, flt2) adds a type-erased OOPathStep to the pipeline
and returns the next lower PathN. Path2.path() is terminal — registers
the complete pipeline on RuleDefinition and returns the JoinFirst END type.

Matching Drools RuleOOPathBuilder structure; simplified internals (no Rete
network nodes).

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: RuleDefinition — OOPath Pipeline and Correlated Execution

**Files:**
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java`

Read the file first. Add three new fields, one new public method, and three private methods. Update `matchedTuples()` to call `applyOOPath()` after the existing pipeline.

- [ ] **Step 1: Add new fields after the `existences` list**

After the line `private final List<RuleDefinition<DS>> existences = new ArrayList<>();`, add:

```java
private int ooPathRootIndex = -1;      // index of the root fact; -1 = no OOPath pipeline
private final List<OOPathStep> ooPathSteps = new ArrayList<>();
```

- [ ] **Step 2: Add addOOPathPipeline() after addExistence()**

```java
/**
 * Registers an OOPath traversal pipeline. Called by {@link RuleOOPathBuilder.Path2#path}
 * when the chain completes (terminal builder).
 *
 * @param rootIndex index of the root fact in the outer chain (0-based, = factArity()-1
 *                  at the time pathN() was called)
 * @param steps     ordered traversal steps, outermost first
 */
public void addOOPathPipeline(int rootIndex, List<OOPathStep> steps) {
    this.ooPathRootIndex = rootIndex;
    this.ooPathSteps.addAll(steps);
}
```

- [ ] **Step 3: Update matchedTuples() to call applyOOPath()**

In `matchedTuples()`, find the final `.collect(Collectors.toList())` line. Replace the full return statement with:

```java
List<Object[]> filtered = combinations.stream()
        .filter(facts -> filters.stream().allMatch(f -> f.test(ctx, facts)))
        .filter(facts -> negations.stream()
                .allMatch(neg -> neg.matchedTuples(ctx).isEmpty()))
        .filter(facts -> existences.stream()
                .allMatch(ex -> !ex.matchedTuples(ctx).isEmpty()))
        .collect(Collectors.toList());
return applyOOPath(filtered, ctx);
```

- [ ] **Step 4: Add applyOOPath(), executePipeline(), copyTuple(), createEmptyTuple() private methods**

Add these four private methods after `matchedTuples()`:

```java
/**
 * If an OOPath pipeline is registered, expands each combination by running
 * the pipeline from the root fact. Each qualifying complete path produces a
 * BaseTuple that is appended as a new element to the fact array.
 * If no pipeline is registered, returns the input unchanged.
 */
@SuppressWarnings("unchecked")
private List<Object[]> applyOOPath(List<Object[]> combinations, DS ctx) {
    if (ooPathRootIndex < 0) return combinations;
    List<Object[]> expanded = new ArrayList<>();
    for (Object[] facts : combinations) {
        Object rootFact = facts[ooPathRootIndex];
        BaseTuple emptyTuple = createEmptyTuple(ooPathSteps.size() + 1);
        emptyTuple.set(0, rootFact);
        PathContext<BaseTuple> pathCtx = new PathContext<>(emptyTuple);
        for (BaseTuple t : executePipeline(rootFact, pathCtx, 0)) {
            Object[] extended = Arrays.copyOf(facts, facts.length + 1);
            extended[facts.length] = t;
            expanded.add(extended);
        }
    }
    return expanded;
}

/**
 * Recursively applies OOPath steps starting from currentFact.
 * At the leaf (all steps consumed), copies the tuple to prevent sibling-branch
 * mutation from corrupting already-collected results.
 *
 * <p>Mutation safety: pathCtx.getTuple() is shared across all branches at a
 * given depth. Each step writes only its own slot (stepIndex + 1). When we
 * backtrack to process a sibling, that slot is overwritten correctly.
 * The copy at the leaf ensures each collected tuple is an independent snapshot.
 */
private List<BaseTuple> executePipeline(Object currentFact,
        PathContext<BaseTuple> pathCtx, int stepIndex) {
    if (stepIndex >= ooPathSteps.size()) {
        // Copy the tuple — the pathCtx tuple is shared/mutable across sibling branches.
        return java.util.Collections.singletonList(copyTuple(pathCtx.getTuple()));
    }
    OOPathStep step = ooPathSteps.get(stepIndex);
    List<BaseTuple> results = new ArrayList<>();
    for (Object child : step.traversal.apply(pathCtx, currentFact)) {
        if (step.filter.test(pathCtx, child)) {
            pathCtx.getTuple().set(stepIndex + 1, child);
            results.addAll(executePipeline(child, pathCtx, stepIndex + 1));
        }
    }
    return results;
}

/** Creates a deep copy of a BaseTuple (preserves all populated elements). */
private BaseTuple copyTuple(BaseTuple source) {
    BaseTuple copy = createEmptyTuple(source.size());
    for (int i = 0; i < source.size(); i++) {
        copy.set(i, source.get(i));
    }
    return copy;
}

/**
 * Creates an empty (all-null) BaseTuple of the given size for incremental
 * population during traversal.
 */
private BaseTuple createEmptyTuple(int size) {
    return switch (size) {
        case 1 -> new BaseTuple.Tuple1<>();
        case 2 -> new BaseTuple.Tuple2<>();
        case 3 -> new BaseTuple.Tuple3<>();
        case 4 -> new BaseTuple.Tuple4<>();
        case 5 -> new BaseTuple.Tuple5<>();
        case 6 -> new BaseTuple.Tuple6<>();
        default -> throw new IllegalArgumentException(
                "OOPath depth " + size + " exceeds maximum of 6");
    };
}
```

Also add `import java.util.List;` to RuleDefinition's imports if not already present (check the top of the file — it likely already has it).

- [ ] **Step 5: Verify compilation**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/RuleDefinition.java
git commit -m "$(cat <<'EOF'
feat(drools-example): add OOPath pipeline and correlated execution to RuleDefinition

addOOPathPipeline(rootIndex, steps) registers the traversal steps.
matchedTuples() calls applyOOPath() after existing filter/not/exists stages.

applyOOPath(): for each outer tuple, traverses from the root fact through the
pipeline, yielding [outer_facts..., BaseTuple] for each qualifying path.

executePipeline(): recursive depth-first traversal; copies tuple at the leaf
to prevent sibling-branch mutation of already-collected results.

copyTuple() / createEmptyTuple(): tuple lifecycle helpers.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Test Domain Records and Ctx Update

**Files:**
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Library.java`
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Room.java`
- Create: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Book.java`
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Ctx.java`

- [ ] **Step 1: Create Library.java, Room.java, Book.java**

```java
// Library.java
package io.quarkiverse.permuplate.example.drools;

import java.util.List;

public record Library(String name, List<Room> rooms) {}
```

```java
// Room.java
package io.quarkiverse.permuplate.example.drools;

import java.util.List;

public record Room(String name, List<Book> books) {}
```

```java
// Book.java
package io.quarkiverse.permuplate.example.drools;

public record Book(String title, boolean published) {}
```

- [ ] **Step 2: Update Ctx.java to add libraries**

Replace the entire file:

```java
package io.quarkiverse.permuplate.example.drools;

public record Ctx(
        DataSource<Person> persons,
        DataSource<Account> accounts,
        DataSource<Order> orders,
        DataSource<Product> products,
        DataSource<Transaction> transactions,
        DataSource<Library> libraries) {
}
```

- [ ] **Step 3: Verify compilation**

```bash
/opt/homebrew/bin/mvn compile -pl permuplate-mvn-examples -am 2>&1 | tail -5
```

Expected: `BUILD SUCCESS` (RuleBuilderTest setUp() will fail at runtime when it constructs Ctx without the 6th arg — that's fine until we fix the tests in Task 6).

- [ ] **Step 4: Commit**

```bash
git add \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Library.java \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Room.java \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Book.java \
  permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/Ctx.java
git commit -m "$(cat <<'EOF'
feat(drools-example): add Library/Room/Book domain records and libraries to Ctx

Library(name, rooms), Room(name, books), Book(title, published) provide
the nested object graph for OOPath traversal tests.

Ctx gains a 6th field: DataSource<Library> libraries.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: JoinBuilder Template — path2()..path6() Methods

**Files:**
- Modify: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`

Read the file first. Add five new methods to `Join0Second`, after the `exists()` method and before the closing `}` of `Join0Second`.

**How these methods work:**
- `@PermuteTypeParam` expands the sentinel `<B>` to J-1 new alpha-named method type params
- `@PermuteReturn` sets the declared return type (a PathJ parameterised by JoinFirst + TupleJ)
- `when = "i < 6"` prevents generation on `Join6Second` (no Join7First exists)
- `rd.factArity() - 1` gives the root fact index (last accumulated fact, 0-based)
- The body uses raw/Object types to avoid generic complications; @PermuteReturn provides the typed declaration

- [ ] **Step 1: Add path2()..path6() to Join0Second in JoinBuilder.java**

Add the following five methods inside `Join0Second`, after the `exists()` method:

```java
/**
 * Starts a 2-element OOPath traversal (root + 1 step → Tuple2).
 * Traversal starts from the last accumulated fact (alpha(i)).
 * Call {@code .path(fn, pred)} on the returned builder to provide
 * the single traversal step and complete the chain.
 *
 * <p>Suppressed at i=6 (when="i < 6") — no Join7First exists.
 */
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
@PermuteReturn(
        className = "Path2",
        typeArgs = "'Join' + (i+1) + 'First<END, DS, '"
                 + " + typeArgList(1, i, 'alpha') + ', Tuple2<'"
                 + " + typeArgList(i, i+1, 'alpha') + '>>, Tuple1<'"
                 + " + alpha(i) + '>, ' + typeArgList(i, i+1, 'alpha')",
        when = "i < 6")
@SuppressWarnings("unchecked")
public <B> Object path2() {
    java.util.List<OOPathStep> steps = new java.util.ArrayList<>();
    return new RuleOOPathBuilder.Path2<>(this, rd, steps, rd.factArity() - 1);
}

/**
 * Starts a 3-element OOPath traversal (root + 2 steps → Tuple3).
 * Call {@code .path(fn,pred).path(fn,pred)} to complete the chain.
 */
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+2}", name = "${alpha(m)}")
@PermuteReturn(
        className = "Path3",
        typeArgs = "'Join' + (i+1) + 'First<END, DS, '"
                 + " + typeArgList(1, i, 'alpha') + ', Tuple3<'"
                 + " + typeArgList(i, i+2, 'alpha') + '>>, Tuple2<'"
                 + " + typeArgList(i, i+1, 'alpha') + '>, '"
                 + " + typeArgList(i, i+2, 'alpha')",
        when = "i < 6")
@SuppressWarnings("unchecked")
public <B> Object path3() {
    java.util.List<OOPathStep> steps = new java.util.ArrayList<>();
    return new RuleOOPathBuilder.Path3<>(this, rd, steps, rd.factArity() - 1);
}

/**
 * Starts a 4-element OOPath traversal (root + 3 steps → Tuple4).
 * Call {@code .path(fn,pred)} three times to complete the chain.
 */
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+3}", name = "${alpha(m)}")
@PermuteReturn(
        className = "Path4",
        typeArgs = "'Join' + (i+1) + 'First<END, DS, '"
                 + " + typeArgList(1, i, 'alpha') + ', Tuple4<'"
                 + " + typeArgList(i, i+3, 'alpha') + '>>, Tuple3<'"
                 + " + typeArgList(i, i+2, 'alpha') + '>, '"
                 + " + typeArgList(i, i+3, 'alpha')",
        when = "i < 6")
@SuppressWarnings("unchecked")
public <B> Object path4() {
    java.util.List<OOPathStep> steps = new java.util.ArrayList<>();
    return new RuleOOPathBuilder.Path4<>(this, rd, steps, rd.factArity() - 1);
}

/**
 * Starts a 5-element OOPath traversal (root + 4 steps → Tuple5).
 * Call {@code .path(fn,pred)} four times to complete the chain.
 */
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+4}", name = "${alpha(m)}")
@PermuteReturn(
        className = "Path5",
        typeArgs = "'Join' + (i+1) + 'First<END, DS, '"
                 + " + typeArgList(1, i, 'alpha') + ', Tuple5<'"
                 + " + typeArgList(i, i+4, 'alpha') + '>>, Tuple4<'"
                 + " + typeArgList(i, i+3, 'alpha') + '>, '"
                 + " + typeArgList(i, i+4, 'alpha')",
        when = "i < 6")
@SuppressWarnings("unchecked")
public <B> Object path5() {
    java.util.List<OOPathStep> steps = new java.util.ArrayList<>();
    return new RuleOOPathBuilder.Path5<>(this, rd, steps, rd.factArity() - 1);
}

/**
 * Starts a 6-element OOPath traversal (root + 5 steps → Tuple6).
 * Call {@code .path(fn,pred)} five times to complete the chain.
 */
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+5}", name = "${alpha(m)}")
@PermuteReturn(
        className = "Path6",
        typeArgs = "'Join' + (i+1) + 'First<END, DS, '"
                 + " + typeArgList(1, i, 'alpha') + ', Tuple6<'"
                 + " + typeArgList(i, i+5, 'alpha') + '>>, Tuple5<'"
                 + " + typeArgList(i, i+4, 'alpha') + '>, '"
                 + " + typeArgList(i, i+5, 'alpha')",
        when = "i < 6")
@SuppressWarnings("unchecked")
public <B> Object path6() {
    java.util.List<OOPathStep> steps = new java.util.ArrayList<>();
    return new RuleOOPathBuilder.Path6<>(this, rd, steps, rd.factArity() - 1);
}
```

- [ ] **Step 2: Run the full build**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | tail -30
```

Expected: **compile succeeds** for the Permuplate generation. Tests will fail because `Ctx` constructor in `setUp()` needs updating (6th arg). Check the generated output if the build fails for a different reason:

```bash
cat permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java | grep -A 3 "path2()"
```

For i=1, verify you see: `public <B> Path2<Join2First<END, DS, A, Tuple2<A, B>>, Tuple1<A>, A, B> path2()`

For i=2, verify: `public <C> Path2<Join3First<END, DS, A, B, Tuple2<B, C>>, Tuple1<B>, B, C> path2()`

If the typeArgs expression is wrong, adjust the JEXL string. The key is: `typeArgList(i, i+1, 'alpha')` for i=1 gives `"A, B"` (from=1, to=2 inclusive).

- [ ] **Step 3: Commit**

```bash
git add permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java
git commit -m "$(cat <<'EOF'
feat(drools-example): add path2()..path6() to Join0Second template

Each pathN() creates a PathN builder registered to the outer RuleDefinition.
@PermuteTypeParam expands the sentinel <B> to J-1 new alpha-named method type
params. @PermuteReturn generates the typed PathN<JoinFirst<...TupleJ...>,...>
return using typeArgList() and alpha() JEXL expressions.

when="i < 6" suppresses all pathN() methods on Join6Second (no Join7First).

Generated for Join1Second(i=1):
  public <B> Path2<Join2First<END,DS,A,Tuple2<A,B>>,Tuple1<A>,A,B> path2()
Generated for Join2Second(i=2):
  public <C> Path2<Join3First<END,DS,A,B,Tuple2<B,C>>,Tuple1<B>,B,C> path2()

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Tests and Full Build

**Files:**
- Modify: `permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java`

Read the file first. Two changes: (1) fix `setUp()` to pass the 6th library source to `Ctx`; (2) add 5 OOPath tests.

**Test data:** Two libraries, each with 2 rooms, each room with 2 books (1 published, 1 unpublished):
```
ScienceLib: [Physics(Relativity/true, Draft/false), Biology(Evolution/true, Notes/false)]
ArtsLib:    [History(Waterloo/true, Sketch/false), Literature(Hamlet/true, Outline/false)]
```

- [ ] **Step 1: Update setUp() in RuleBuilderTest to add libraries**

Find the `setUp()` method. The current `ctx = new Ctx(...)` call has 5 args. Add the 6th:

```java
DataSource<Library> libraries = DataSource.of(
    new Library("ScienceLib", java.util.List.of(
        new Room("Physics", java.util.List.of(
            new Book("Relativity", true),
            new Book("Draft", false))),
        new Room("Biology", java.util.List.of(
            new Book("Evolution", true),
            new Book("Notes", false))))),
    new Library("ArtsLib", java.util.List.of(
        new Room("History", java.util.List.of(
            new Book("Waterloo", true),
            new Book("Sketch", false))),
        new Room("Literature", java.util.List.of(
            new Book("Hamlet", true),
            new Book("Outline", false))))));

ctx = new Ctx(
        DataSource.of(new Person("Alice", 30), new Person("Bob", 17)),
        DataSource.of(new Account("ACC1", 1000.0), new Account("ACC2", 50.0)),
        DataSource.of(new Order("ORD1", 150.0), new Order("ORD2", 25.0)),
        DataSource.of(new Product("PRD1", 99.0), new Product("PRD2", 9.0)),
        DataSource.of(new Transaction("TXN1", 200.0), new Transaction("TXN2", 15.0)),
        libraries);
```

- [ ] **Step 2: Add 5 OOPath tests before the final `}`**

```java
// =========================================================================
// OOPath — pathN() traversal
// =========================================================================

@Test
public void testPath2TraversesOneLevel() {
    // path2(): Library → Rooms — produces Tuple2<Library, Room> per matching room.
    // 2 libraries × 2 rooms each × all rooms pass name != null = 4 combinations.
    var rule = builder.from("libs", ctx -> ctx.libraries())
            .path2()
            .path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> room.name() != null)
            .fn((ctx, lib, t) -> {});

    rule.run(ctx);

    assertThat(rule.executionCount()).isEqualTo(4); // 2 libs × 2 rooms
    // fact[0] = Library, fact[1] = Tuple2<Library, Room>
    assertThat(rule.capturedFact(0, 0)).isInstanceOf(Library.class);
    assertThat(rule.capturedFact(0, 1)).isInstanceOf(BaseTuple.Tuple2.class);
    // Tuple's getA() = root Library, getB() = Room
    @SuppressWarnings("unchecked")
    BaseTuple.Tuple2<Library, Room> t = (BaseTuple.Tuple2<Library, Room>) rule.capturedFact(0, 1);
    assertThat(t.getA()).isInstanceOf(Library.class);
    assertThat(t.getB()).isInstanceOf(Room.class);
}

@Test
public void testPath3TraversesTwoLevels() {
    // path3(): Library → Room → Book — produces Tuple3<Library, Room, Book>.
    // 2 libs × 2 rooms × 2 books = 8 combinations (no filters).
    var rule = builder.from("libs", ctx -> ctx.libraries())
            .path3()
            .path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> true)
            .path(
                (pathCtx, room) -> room.books(),
                (pathCtx, book) -> true)
            .fn((ctx, lib, t) -> {});

    rule.run(ctx);

    assertThat(rule.executionCount()).isEqualTo(8); // 2 libs × 2 rooms × 2 books
    assertThat(rule.capturedFact(0, 1)).isInstanceOf(BaseTuple.Tuple3.class);
}

@Test
public void testPathFilterAppliedAtEachStep() {
    // Only published books pass the second-step predicate.
    // 2 libs × 2 rooms × 1 published book each = 4 combinations.
    var rule = builder.from("libs", ctx -> ctx.libraries())
            .path3()
            .path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> true)
            .path(
                (pathCtx, room) -> room.books(),
                (pathCtx, book) -> book.published())  // only published books
            .fn((ctx, lib, t) -> {});

    rule.run(ctx);

    assertThat(rule.executionCount()).isEqualTo(4); // 2 libs × 2 rooms × 1 published
    for (int i = 0; i < rule.executionCount(); i++) {
        @SuppressWarnings("unchecked")
        BaseTuple.Tuple3<Library, Room, Book> t =
            (BaseTuple.Tuple3<Library, Room, Book>) rule.capturedFact(i, 1);
        assertThat(t.getC().published()).isTrue();  // getC() = Book
    }
}

@Test
public void testPathContextCrossReference() {
    // Second-step predicate uses PathContext.getTuple().getA() to access the Library
    // while filtering a Book. Only books in ScienceLib rooms pass.
    var rule = builder.from("libs", ctx -> ctx.libraries())
            .path3()
            .path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> true)
            .path(
                (pathCtx, room) -> room.books(),
                // Cross-reference: getA() = Library (set at depth 0)
                (pathCtx, book) -> pathCtx.getTuple().getA().name().startsWith("Science"))
            .fn((ctx, lib, t) -> {});

    rule.run(ctx);

    // ScienceLib has 2 rooms × 2 books = 4 combinations (no publishing filter here)
    assertThat(rule.executionCount()).isEqualTo(4);
    for (int i = 0; i < rule.executionCount(); i++) {
        @SuppressWarnings("unchecked")
        BaseTuple.Tuple3<Library, Room, Book> t =
            (BaseTuple.Tuple3<Library, Room, Book>) rule.capturedFact(i, 1);
        assertThat(t.getA().name()).startsWith("Science");
    }
}

@Test
public void testPathCombinedWithOuterJoin() {
    // persons.join(libraries).path2(Library → Room)
    // Outer facts: [Person, Library, Tuple2<Library,Room>]
    // 2 persons × 2 libraries × 2 rooms = 8 combinations
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.libraries())
            .path2()
            .path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> room.name() != null)
            .fn((ctx, p, lib, t) -> {});

    rule.run(ctx);

    assertThat(rule.executionCount()).isEqualTo(8); // 2p × 2libs × 2rooms
    assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
    assertThat(rule.capturedFact(0, 1)).isInstanceOf(Library.class);
    assertThat(rule.capturedFact(0, 2)).isInstanceOf(BaseTuple.Tuple2.class);
}
```

- [ ] **Step 3: Run the full build**

```bash
/opt/homebrew/bin/mvn clean install 2>&1 | tail -20
```

Expected: `BUILD SUCCESS` with 32 tests passing (27 existing + 5 new).

If `testPath2TraversesOneLevel` fails with wrong count, verify the libraries DataSource in setUp() has exactly 2 libraries with 2 rooms each. If a path test fails with ClassCastException, check that the `fn()` lambda parameter count matches the outer arity + 1 (for the tuple).

- [ ] **Step 4: Commit**

```bash
git add permuplate-mvn-examples/src/test/java/io/quarkiverse/permuplate/example/drools/RuleBuilderTest.java
git commit -m "$(cat <<'EOF'
test(drools-example): add 5 OOPath tests + library domain in setUp()

testPath2TraversesOneLevel: 2 libs × 2 rooms = 4 Tuple2<Library,Room> results.
testPath3TraversesTwoLevels: 2 libs × 2 rooms × 2 books = 8 Tuple3 results.
testPathFilterAppliedAtEachStep: only published books pass; 4 results.
testPathContextCrossReference: predicate uses getTuple().getA() to filter by
  library name while visiting a book — only ScienceLib books pass.
testPathCombinedWithOuterJoin: persons × libraries × rooms = 8 results;
  fact[2] is Tuple2 alongside Person (fact[0]) and Library (fact[1]).

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- ✅ `BaseTuple` + `Tuple0..Tuple6` — Task 1
- ✅ `PathContext` (clean impl) — Task 1
- ✅ `Function2` matching Drools — Task 1
- ✅ `OOPathStep` type-erased — Task 1
- ✅ `Path2..Path6` builders — Task 2
- ✅ `addOOPathPipeline()` — Task 3
- ✅ `applyOOPath()` + `executePipeline()` + `copyTuple()` — Task 3
- ✅ `Library`, `Room`, `Book` domain + `Ctx` update — Task 4
- ✅ `path2()..path6()` template methods — Task 5
- ✅ 5 OOPath tests — Task 6

**Type consistency:** `OOPathStep` defined in Task 1, used in `Path2.path()` (Task 2) and `RuleDefinition` (Task 3). `BaseTuple` defined in Task 1, used in `PathContext` (Task 1), `RuleDefinition` helper methods (Task 3). `Function2` defined in Task 1, used in `RuleOOPathBuilder.path()` signatures (Task 2). `RuleOOPathBuilder.Path2` created in Task 2, referenced in `path2()` body (Task 5). Consistent throughout. ✓

**Mutation safety:** `executePipeline` copies the tuple at the leaf (not the reference). Sibling branches at the same depth write the same slot (stepIndex+1) sequentially, not concurrently. Each completed path produces an independent copy. ✓
