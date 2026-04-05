package io.quarkiverse.permuplate.example.drools;

/**
 * Holds the tuple being assembled during an OOPath traversal.
 * Passed to each traversal step function and filter predicate, allowing
 * cross-referencing of earlier path elements while filtering the current one.
 * Note: the Drools implementation has a buggy constructor (fall-through switch
 * without breaks). This implementation is clean.
 */
public class PathContext<T extends BaseTuple> {
    private final T tuple;

    public PathContext(T tuple) {
        this.tuple = tuple;
    }

    public T getTuple() {
        return tuple;
    }
}
