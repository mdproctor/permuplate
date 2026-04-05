# Spec: Phase 3b — OOPath / pathN() Traversal

**Date:** 2026-04-05
**Status:** Approved
**Motivation:** Add OOPath (Object-Oriented Path) traversal to the Drools DSL sandbox. OOPath is XPath for Java object graphs — traverse from a root fact through nested collections with a filter predicate at each step, collecting the visited objects into a typed tuple. The result joins back into the main rule chain as a tuple-typed fact.

**Primary goal:** This sandbox is the API evolution testbed for Drools. External DSL signatures match Drools exactly for migration fidelity. Internal implementation is clean where Drools is incomplete (notably `PathContext` whose constructor is buggy in the current Drools codebase).

---

## Background: OOPath Pattern

```java
// Traverse Library → Rooms → Shelves → Books → Pages (5 elements)
builder.from(Ctx::libraries)
       .<Room, Shelf, Book, Page>path5((ctx, l) -> l.rooms(), (ctx, r) -> r.name() != null)
       .path((ctx, r) -> r.shelves(),  (ctx, s) -> s.name() != null)
       .path((ctx, s) -> s.books(),    (ctx, b) -> b.title() != null)
       .path((ctx, b) -> b.pages(),    (ctx, p) -> p.content() != null)
       .filter((ctx, lib, t) -> lib.name() != t.getE().content());
// t is Tuple5<Library,Room,Shelf,Book,Page> — t.getE() = Page
```

`pathJ()` traverses from the last accumulated fact and produces a `TupleJ` as a new fact in the outer chain. After the path chain, the outer rule has `N+1` facts: the original N facts plus the TupleJ.

### TupleJ naming convention

`pathJ()` produces a `TupleJ` result where J is the total number of elements including the root:
- `path2()` — root + 1 traversal step → `Tuple2<Root, Step1>`
- `path3()` — root + 2 steps → `Tuple3<Root, Step1, Step2>`
- `path5(fn, pred)` — root + 4 steps (first pre-consumed by call) → `Tuple5`

The root fact is always `alpha(i)` — the last accumulated fact in the outer chain.

### PathContext cross-referencing

Predicates at each traversal step receive `PathContext<T>` where `T` is the final tuple type. This allows filters to reference earlier path elements:

```java
// While filtering a Page, cross-reference the Library that started the path:
.path((ctx, b) -> b.pages(), (ctx, p) -> ctx.getTuple().getA().getRequiredAuthors() > 0)
//                                                         ↑ Library from getA()
```

The tuple is populated incrementally — `getA()` is available from step 1 onward, `getB()` from step 2, etc. The PathContext holds the partially-built tuple during traversal.

---

## Files Changed

| File | Action | Responsibility |
|---|---|---|
| `permuplate-mvn-examples/src/main/java/.../drools/BaseTuple.java` | **Create** | Abstract base + `Tuple0..Tuple6` inner classes with typed getters |
| `permuplate-mvn-examples/src/main/java/.../drools/PathContext.java` | **Create** | Holds tuple-in-progress for cross-path predicates during traversal |
| `permuplate-mvn-examples/src/main/java/.../drools/Function2.java` | **Create** | `(A, B) -> C` functional interface matching Drools' custom type |
| `permuplate-mvn-examples/src/main/java/.../drools/OOPathStep.java` | **Create** | One type-erased traversal step (fn + predicate) for the pipeline |
| `permuplate-mvn-examples/src/main/java/.../drools/RuleOOPathBuilder.java` | **Create** | Contains `Path2..Path6` builder classes |
| `permuplate-mvn-examples/src/main/java/.../drools/RuleDefinition.java` | Modify | Add OOPath pipeline fields; update `matchedTuples()` for correlated execution |
| `permuplate-mvn-examples/src/main/permuplate/.../drools/JoinBuilder.java` | Modify | Add `path2()..path6()` methods to `Join0Second` template |
| `permuplate-mvn-examples/src/main/java/.../drools/Ctx.java` | Modify | Add `Library` data source for OOPath tests |
| `permuplate-mvn-examples/src/test/java/.../drools/RuleBuilderTest.java` | Modify | Add 5 OOPath tests |

No Permuplate core changes required.

---

## BaseTuple

Inheritance chain matching Drools structure. Each `TupleN` extends `Tuple(N-1)`:

```java
public abstract class BaseTuple {
    protected int size;
    public abstract <T> T get(int index);
    public abstract <T> void set(int index, T t);

    // Converts this tuple to a record type via reflection (matching Drools as())
    @SuppressWarnings("unchecked")
    public <T> T as(T... v) { ... }

    public static class Tuple0 extends BaseTuple { ... }
    public static class Tuple1<A> extends BaseTuple { A a; public A getA(); ... }
    public static class Tuple2<A,B> extends Tuple1<A> { B b; public B getB(); ... }
    public static class Tuple3<A,B,C> extends Tuple2<A,B> { C c; public C getC(); ... }
    public static class Tuple4<A,B,C,D> extends Tuple3<A,B,C> { ... }
    public static class Tuple5<A,B,C,D,E> extends Tuple4<A,B,C,D> { ... }
    public static class Tuple6<A,B,C,D,E,F> extends Tuple5<A,B,C,D,E> { ... }
}
```

Each `TupleN` has:
- Constructor: `TupleN(A a, ..., N n)` populating all fields and setting `size = N`
- Typed getters: `getA()..getN()` (inherited from parent classes)
- `get(int index)` switch for reflective access
- `set(int index, T t)` switch for incremental population during traversal

**Why inheritance over records:** Incremental population during traversal — `set(index, value)` is called as each traversal step completes. Records are immutable; the inheritance chain with mutable fields allows the PathContext to populate the tuple step-by-step.

---

## PathContext

Clean implementation — the Drools version has a buggy constructor (fall-through switch without breaks, then overwritten by a loop):

```java
/**
 * Holds the tuple being assembled during an OOPath traversal. Passed to each
 * traversal step function and filter predicate, allowing cross-referencing of
 * earlier path elements while filtering the current one.
 *
 * <p>The tuple is populated incrementally: getA() is available from step 1,
 * getB() from step 2, etc. Earlier elements are immutable once set.
 *
 * <p>The Drools implementation has a buggy constructor (fall-through switch
 * without breaks, values overwritten). This implementation is clean.
 */
public class PathContext<T extends BaseTuple> {
    private final T tuple;

    public PathContext(T tuple) {
        this.tuple = tuple;
    }

    /** Returns the tuple-in-progress. Populated elements are accessible; future elements are null. */
    public T getTuple() {
        return tuple;
    }
}
```

---

## Function2

Matches Drools' `Function2<A, B, C>` — two inputs, one output. Used for traversal step functions `(PathContext<T>, currentFact) -> children`:

```java
@FunctionalInterface
public interface Function2<A, B, C> {
    C apply(A a, B b);
}
```

Alternative: use Java's `BiFunction<A,B,C>`. Using `Function2` keeps DSL signatures identical to Drools for maximum migration fidelity.

---

## OOPathStep

One type-erased traversal step stored in the pipeline:

```java
/**
 * One step in an OOPath traversal pipeline. Type-erased for storage in RuleDefinition.
 * Holds the traversal function (current fact → children collection) and filter predicate
 * (child → pass?). Both receive a PathContext holding the partial tuple-in-progress.
 */
public class OOPathStep {
    // (PathContext, currentFact) -> Iterable of children
    final java.util.function.BiFunction<Object, Object, Iterable<?>> traversal;
    // (PathContext, child) -> true if child passes filter
    final java.util.function.BiPredicate<Object, Object> filter;

    public OOPathStep(
            java.util.function.BiFunction<Object, Object, Iterable<?>> traversal,
            java.util.function.BiPredicate<Object, Object> filter) {
        this.traversal = traversal;
        this.filter = filter;
    }
}
```

---

## RuleOOPathBuilder

Contains `Path2..Path6` as public static inner classes. Matching Drools' structure but with simplified internals — no Rete network nodes, just the pipeline registration.

### Path2 (innermost — one step remaining)

```java
public static class Path2<END, T extends BaseTuple, A, B> {
    private final END end;
    private final RuleDefinition<?> rd;
    private final List<OOPathStep> steps;
    private final int rootIndex;

    public Path2(END end, RuleDefinition<?> rd, List<OOPathStep> steps, int rootIndex) { ... }

    /**
     * Completes the path chain. Registers the accumulated pipeline on the outer
     * RuleDefinition and returns END (the Join(N+1)First result).
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
```

### Path3..Path6

Same structure. `Path3.path()` adds the current step and returns `Path2`. `Path4.path()` returns `Path3`. Etc.

```java
public static class Path3<END, T extends BaseTuple, A, B, C> {
    // ...
    public Path2<END, T, B, C> path(Function2<PathContext<T>, A, ?> fn2,
                                     Predicate2<PathContext<T>, B> flt2) {
        steps.add(new OOPathStep(...));
        return new Path2<>(end, rd, steps, rootIndex);
    }
}
```

---

## RuleDefinition Changes

### New fields

```java
private int ooPathRootIndex = -1;       // index of the root fact; -1 = no OOPath
private final List<OOPathStep> ooPathSteps = new ArrayList<>();
```

### New method

```java
/**
 * Registers an OOPath traversal pipeline. Called by PathN builders when the
 * chain completes (Path2.path() is the terminal call).
 *
 * @param rootIndex the index of the fact that starts the traversal (alpha(i) from the outer chain)
 * @param steps     the ordered list of traversal steps, outermost first
 */
public void addOOPathPipeline(int rootIndex, List<OOPathStep> steps) {
    this.ooPathRootIndex = rootIndex;
    this.ooPathSteps.addAll(steps);
}
```

### Updated matchedTuples()

After the existing cross-product + filters + not/exists stages, if an OOPath pipeline is registered:

```java
private List<Object[]> applyOOPath(List<Object[]> combinations, Object ctx) {
    if (ooPathRootIndex < 0) return combinations;
    List<Object[]> expanded = new ArrayList<>();
    for (Object[] facts : combinations) {
        Object rootFact = facts[ooPathRootIndex];
        // Create an empty tuple of the right size for the pipeline depth
        BaseTuple emptyTuple = createEmptyTuple(ooPathSteps.size() + 1);
        emptyTuple.set(0, rootFact);  // root is always element 0
        PathContext<BaseTuple> pathCtx = new PathContext<>(emptyTuple);
        // Recursively execute the pipeline
        List<BaseTuple> tuples = executePipeline(rootFact, pathCtx, 0);
        for (BaseTuple t : tuples) {
            Object[] extended = Arrays.copyOf(facts, facts.length + 1);
            extended[facts.length] = t;
            expanded.add(extended);
        }
    }
    return expanded;
}

private List<BaseTuple> executePipeline(Object currentFact, PathContext<BaseTuple> pathCtx, int stepIndex) {
    if (stepIndex >= ooPathSteps.size()) {
        // IMPORTANT: copy the tuple — the pathCtx tuple is shared/mutable across sibling branches.
        // Returning a reference would cause subsequent siblings to mutate already-collected results.
        return java.util.Collections.singletonList(copyTuple(pathCtx.getTuple()));
    }
    OOPathStep step = ooPathSteps.get(stepIndex);
    List<BaseTuple> results = new ArrayList<>();
    for (Object child : step.traversal.apply(pathCtx, currentFact)) {
        if (step.filter.test(pathCtx, child)) {
            pathCtx.getTuple().set(stepIndex + 1, child);  // populate next slot
            results.addAll(executePipeline(child, pathCtx, stepIndex + 1));
        }
    }
    return results;
}

private BaseTuple copyTuple(BaseTuple source) {
    int size = source.size();
    BaseTuple copy = createEmptyTuple(size);
    for (int i = 0; i < size; i++) {
        copy.set(i, source.get(i));
    }
    return copy;
}

private BaseTuple createEmptyTuple(int size) {
    return switch (size) {
        case 1 -> new BaseTuple.Tuple1<>();
        case 2 -> new BaseTuple.Tuple2<>();
        case 3 -> new BaseTuple.Tuple3<>();
        case 4 -> new BaseTuple.Tuple4<>();
        case 5 -> new BaseTuple.Tuple5<>();
        case 6 -> new BaseTuple.Tuple6<>();
        default -> throw new IllegalArgumentException("OOPath depth > 6 not supported");
    };
}
```

**Note:** `executePipeline` mutates the tuple-in-progress (setting elements via `set(index, value)`). When backtracking occurs (multiple qualifying children at a given step), earlier elements remain correct — only the current-depth slot is overwritten. Deeper recursion is entered only after the filter passes, so the PathContext always reflects the current valid path.

---

## JoinBuilder Template Changes

Five new methods in `Join0Second`. Each uses a standalone `@PermuteTypeParam` + `@PermuteReturn` combination — the same pattern as `join()` but with path-depth-specific type args.

### path2() — 1 new type param

```java
/**
 * Starts a 2-element OOPath traversal (root + 1 step → Tuple2).
 * Traversal starts from the last accumulated fact (alpha(i)).
 * Returns a Path2 builder; call .path(fn, pred) to provide the single traversal step.
 */
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "${alpha(m)}")
@PermuteReturn(
    className = "Path2",
    typeArgs = "'Join' + (i+1) + 'First<END, DS, ' + typeArgList(1, i+1, 'alpha')"
             + " + '>, Tuple1<' + alpha(i) + '>, ' + alpha(i) + ', ' + alpha(i+1)",
    when = "i < 6")
public <B> Object path2() {
    List<OOPathStep> steps = new ArrayList<>();
    return new RuleOOPathBuilder.Path2<>(this, rd, steps, accumulatedFacts - 1);
}
```

Generated for `Join1Second<END,DS,A>` (i=1):
```java
public <B> Path2<Join2First<END,DS,A,B>, Tuple1<A>, A, B> path2()
```

Generated for `Join2Second<END,DS,A,B>` (i=2):
```java
public <C> Path2<Join3First<END,DS,A,B,C>, Tuple1<B>, B, C> path2()
```

`when="i < 6"` — boundary omission removes `path2()` from `Join6Second` (no `Join7First`).

### path3() — 2 new type params

```java
@PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+2}", name = "${alpha(m)}")
@PermuteReturn(
    className = "Path3",
    typeArgs = "'Join' + (i+1) + 'First<END, DS, ' + typeArgList(1, i+1, 'alpha')"
             + " + '>, Tuple2<' + alpha(i) + ', ' + alpha(i+1) + '>, '"
             + " + alpha(i) + ', ' + alpha(i+1) + ', ' + alpha(i+2)",
    when = "i < 6")
public <B> Object path3() {
    List<OOPathStep> steps = new ArrayList<>();
    return new RuleOOPathBuilder.Path3<>(this, rd, steps, accumulatedFacts - 1);
}
```

### path4()..path6()

Follow the same pattern with increasing `to="${i+J-1}"` and correspondingly longer `typeArgs` expressions. The `when` condition remains `"i < 6"` for all (since the result is always `Join(i+1)First`).

### RuleDefinition.accumulatedFacts usage

`path2()` body uses `accumulatedFacts - 1` as the root index (the last fact added). Since `RuleDefinition.accumulatedFacts` is package-private, the generated Join0Second can access it via `rd.factArity() - 1` (`factArity()` is already public).

---

## Domain Setup for Tests

Add to `Ctx`:
```java
record Ctx(DataSource<Person> persons,
           DataSource<Account> accounts,
           DataSource<Order> orders,
           DataSource<Product> products,
           DataSource<Transaction> transactions,
           DataSource<Library> libraries) {}
```

New domain records:
```java
record Library(String name, List<Room> rooms) {}
record Room(String name, List<Book> books) {}
record Book(String title, boolean published) {}
```

Test data in `setUp()`: 2 libraries, each with 2 rooms, each with 2 books (some filtered).

---

## Tests

```java
// Test data setup: 2 libraries with 2 rooms each, each room with 2 books (1 published, 1 not)
// ScienceLib: [Room("Physics", [Book("Relativity",true), Book("Draft",false)]),
//              Room("Biology", [Book("Evolution",true), Book("Notes",false)])]
// ArtsLib:    [Room("History", ...), Room("Literature", ...)]

@Test
public void testPath2TraversesOneLevel() {
    // path2(): Library → Rooms — produces Tuple2<Library, Room> per matching room
    // fn: (PathContext<Tuple2<Library,Room>>, Library) → Iterable<Room>
    // pred: (PathContext<Tuple2<Library,Room>>, Room) → bool
    var rule = builder.from("libs", ctx -> ctx.libraries())
            .path2()
            .path(
                (pathCtx, lib) -> lib.rooms(),                // traverse Library → rooms
                (pathCtx, room) -> room.name() != null)       // filter: name must be non-null
            .fn((ctx, lib, t) -> {});

    rule.run(ctx);
    // 2 libraries × 2 rooms each = 4 combinations (all rooms have names)
    assertThat(rule.executionCount()).isEqualTo(4);
    // fact[0] = Library, fact[1] = Tuple2<Library,Room>
    assertThat(rule.capturedFact(0, 0)).isInstanceOf(Library.class);
    assertThat(rule.capturedFact(0, 1)).isInstanceOf(BaseTuple.Tuple2.class);
}

@Test
public void testPath3TraversesTwoLevels() {
    // path3(): Library → Room → Book — produces Tuple3<Library, Room, Book>
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
    // 2 libraries × 2 rooms × 2 books = 8 combinations
    assertThat(rule.executionCount()).isEqualTo(8);
    assertThat(rule.capturedFact(0, 1)).isInstanceOf(BaseTuple.Tuple3.class);
}

@Test
public void testPathFilterAppliedAtEachStep() {
    // Only published books pass the second-step predicate
    var rule = builder.from("libs", ctx -> ctx.libraries())
            .path3()
            .path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> true)                           // all rooms pass
            .path(
                (pathCtx, room) -> room.books(),
                (pathCtx, book) -> book.published())               // only published books
            .fn((ctx, lib, t) -> {});

    rule.run(ctx);
    // 2 libraries × 2 rooms × 1 published book each = 4 combinations
    assertThat(rule.executionCount()).isEqualTo(4);
    for (int i = 0; i < rule.executionCount(); i++) {
        @SuppressWarnings("unchecked")
        BaseTuple.Tuple3<Library, Room, Book> t =
            (BaseTuple.Tuple3<Library, Room, Book>) rule.capturedFact(i, 1);
        assertThat(t.getC().published()).isTrue();  // getC() = Book
    }
}

@Test
public void testPathContextCrossReference() {
    // Second-step predicate references the Library (getA()) while filtering a Book
    var rule = builder.from("libs", ctx -> ctx.libraries())
            .path3()
            .path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> true)
            .path(
                (pathCtx, room) -> room.books(),
                // Cross-reference: only books in Science library pass
                (pathCtx, book) -> pathCtx.getTuple().getA().name().startsWith("Science"))
            .fn((ctx, lib, t) -> {});

    rule.run(ctx);
    // Only ScienceLib books qualify — 2 rooms × 2 books = 4 combinations
    assertThat(rule.executionCount()).isEqualTo(4);
    for (int i = 0; i < rule.executionCount(); i++) {
        @SuppressWarnings("unchecked")
        BaseTuple.Tuple3<Library, Room, Book> t =
            (BaseTuple.Tuple3<Library, Room, Book>) rule.capturedFact(i, 1);
        assertThat(t.getA().name()).startsWith("Science");  // getA() = Library
    }
}

@Test
public void testPathCombinedWithOuterJoin() {
    // persons.join(libraries).path2(Library → Room)
    // Outer facts: [Person, Library, Tuple2<Library,Room>]
    var rule = builder.from("persons", ctx -> ctx.persons())
            .join(ctx -> ctx.libraries())
            .path2()
            .path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> room.name() != null)
            .fn((ctx, p, lib, t) -> {});

    rule.run(ctx);
    // 2 persons × 2 libraries × 2 rooms = 8 combinations
    assertThat(rule.executionCount()).isEqualTo(8);
    assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
    assertThat(rule.capturedFact(0, 1)).isInstanceOf(Library.class);
    assertThat(rule.capturedFact(0, 2)).isInstanceOf(BaseTuple.Tuple2.class);
}
```

---

## Spec Self-Review Notes

- `accumulatedFacts - 1` gives the root index because the root is the last fact added to the outer chain before `pathN()` is called. `factArity()` returns `accumulatedFacts` which is the total fact column count — subtract 1 for 0-based index.
- The `executePipeline` mutation of the shared `pathCtx.getTuple()` could produce incorrect results if children at a given step share the same tuple reference. Since we recurse BEFORE processing siblings, and each recursion overwrites only the current slot, earlier elements are never disturbed. ✓
- `path6()` on `Join1Second` produces `Tuple6` — the maximum depth. The PathN builder classes only go to Path6. This is consistent with the maximum arity of 6 throughout the DSL.
- `Function2` could be replaced with `java.util.function.BiFunction` to avoid a new file. Using `Function2` keeps DSL signatures identical to Drools.

---

## What This Does NOT Include

- `path5(fn, pred)` shorthand overload (pre-consuming the first step) — YAGNI; `path5().path()` achieves the same result
- `AccessType` (LIST, OBJECT, etc.) — Drools uses this for OOPath semantics around single vs multiple children; sandbox assumes all traversals return `Iterable<?>` (list semantics)
- `OOPath<R,L,T>` / `OOPathFinisher<R,L,T>` — Drools uses these for Rete node wiring; sandbox uses `addOOPathPipeline()` directly
- `PathNode` / `ListPathNode` — Rete network nodes; not needed in sandbox execution model
