package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;

/**
 * Scope builder returned by {@code JoinNSecond.not()}.
 * Template (keepTemplate=true) generates {@link ExistsScope} via string-set permutation.
 *
 * <p>
 * Accumulates sources and filters into a private {@link RuleDefinition} representing
 * the sub-network. {@code end()} returns the outer builder, restoring the outer
 * arity and type chain.
 *
 * <p>
 * Inside the scope, {@code join()} and {@code filter()} are intentionally untyped —
 * the scope is a constraint, not a typed fact chain the caller accesses by name.
 */
@Permute(varName = "T", values = {"Exists"}, className = "${T}Scope",
         inline = false, keepTemplate = true)
public class NotScope<OUTER, DS> {

    private final OUTER outer;
    private final RuleDefinition<DS> scopeRd;

    public NotScope(OUTER outer, RuleDefinition<DS> scopeRd) {
        this.outer = outer;
        this.scopeRd = scopeRd;
    }

    /**
     * Adds a data source to the sub-network.
     */
    @PermuteDeclr(type = "${T}Scope<OUTER, DS>")
    public NotScope<OUTER, DS> join(java.util.function.Function<DS, DataSource<?>> source) {
        scopeRd.addSource(source);
        return this;
    }

    /**
     * Adds a filter predicate to the sub-network.
     */
    @PermuteDeclr(type = "${T}Scope<OUTER, DS>")
    public NotScope<OUTER, DS> filter(Object predicate) {
        scopeRd.addFilter(predicate);
        return this;
    }

    /**
     * Ends the scope and returns to the outer builder at its original arity.
     */
    public OUTER end() {
        return outer;
    }
}
