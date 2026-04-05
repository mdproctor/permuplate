package io.quarkiverse.permuplate.example.drools;

/**
 * Scope builder returned by {@code JoinNSecond.exists()}.
 *
 * <p>
 * Accumulates sources and filters into a private {@link RuleDefinition} representing
 * the existence sub-network. The sub-network is registered with the outer chain's
 * RuleDefinition upfront (at {@code exists()} call time). {@code end()} returns the
 * outer builder — restoring the outer arity and type chain.
 *
 * <p>
 * Semantics: the outer tuple passes only when this scope produces AT LEAST ONE
 * matching result. Dual of {@link NegationScope} which requires ZERO results.
 *
 * <p>
 * Inside the scope, {@code join()} and {@code filter()} are intentionally untyped.
 * Sandbox execution: scope evaluates independently of the outer facts.
 */
public class ExistenceScope<OUTER, DS> {

    private final OUTER outer;
    private final RuleDefinition<DS> existsRd;

    public ExistenceScope(OUTER outer, RuleDefinition<DS> existsRd) {
        this.outer = outer;
        this.existsRd = existsRd;
    }

    /**
     * Adds a data source to the existence sub-network.
     */
    public ExistenceScope<OUTER, DS> join(Object source) {
        existsRd.addSource(source);
        return this;
    }

    /**
     * Adds a filter predicate to the existence sub-network.
     */
    public ExistenceScope<OUTER, DS> filter(Object predicate) {
        existsRd.addFilter(predicate);
        return this;
    }

    /**
     * Ends the existence scope and returns to the outer builder at its original arity.
     */
    public OUTER end() {
        return outer;
    }
}
