package io.quarkiverse.permuplate.example.drools;

/**
 * Two-input function matching Drools' custom Function2<A,B,C> type.
 * Used for OOPath traversal step functions:
 * (PathContext<T>, currentFact) -> Iterable<nextFact>.
 */
@FunctionalInterface
public interface Function2<A, B, C> {
    C apply(A a, B b);
}
