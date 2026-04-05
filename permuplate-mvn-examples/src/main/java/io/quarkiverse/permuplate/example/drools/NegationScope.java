package io.quarkiverse.permuplate.example.drools;

/**
 * Scope builder returned by {@code JoinNSecond.not()}.
 *
 * <p>
 * Accumulates sources and filters into a private {@link RuleDefinition} representing
 * the negation sub-network. The sub-network is registered with the outer chain's
 * RuleDefinition upfront (at {@code not()} call time), so it accumulates in-place as
 * the scope is built. {@code end()} returns the outer builder — restoring the outer
 * arity and type chain.
 *
 * <p>
 * Inside the scope, {@code join()} and {@code filter()} are intentionally untyped —
 * the scope is a constraint, not a typed fact chain the caller accesses by name.
 * The outer chain remains fully typed throughout.
 *
 * <p>
 * Sandbox execution: the scope evaluates independently of the outer facts
 * (it runs against {@code ctx} only). Cross-referencing outer facts inside scope
 * filters requires {@code Variable<T>} binding (Phase 3+ feature not yet implemented).
 */
public class NegationScope<OUTER, DS> {

    private final OUTER outer;
    private final RuleDefinition<DS> notRd;

    public NegationScope(OUTER outer, RuleDefinition<DS> notRd) {
        this.outer = outer;
        this.notRd = notRd;
    }

    /**
     * Adds a data source to the negation sub-network. Takes {@code Object} to accept
     * both {@code Function<DS, DataSource<?>>} lambdas and pre-built {@code JoinNFirst}
     * instances (bi-linear scope source). {@link RuleDefinition#addSource} handles casting.
     */
    public NegationScope<OUTER, DS> join(Object source) {
        notRd.addSource(source);
        return this;
    }

    /**
     * Adds a filter predicate to the negation sub-network.
     */
    public NegationScope<OUTER, DS> filter(Object predicate) {
        notRd.addFilter(predicate);
        return this;
    }

    /**
     * Ends the negation scope and returns to the outer builder at its original arity.
     * The outer chain's type and arity are fully restored — the scope facts do NOT
     * accumulate into the outer chain.
     */
    public OUTER end() {
        return outer;
    }
}
