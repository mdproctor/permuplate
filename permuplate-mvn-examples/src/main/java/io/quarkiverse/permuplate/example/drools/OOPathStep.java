package io.quarkiverse.permuplate.example.drools;

/**
 * One type-erased traversal step in an OOPath pipeline.
 * Stores traversal function and filter in type-erased form for uniform
 * storage in RuleDefinition. Typed versions are captured via lambda in
 * RuleOOPathBuilder.PathN.path().
 */
public class OOPathStep {
    /** Type-erased: (PathContext<T>, currentFact) -> Iterable<?> */
    final java.util.function.BiFunction<Object, Object, Iterable<?>> traversal;
    /** Type-erased: (PathContext<T>, child) -> boolean */
    final java.util.function.BiPredicate<Object, Object> filter;

    public OOPathStep(
            java.util.function.BiFunction<Object, Object, Iterable<?>> traversal,
            java.util.function.BiPredicate<Object, Object> filter) {
        this.traversal = traversal;
        this.filter = filter;
    }
}
