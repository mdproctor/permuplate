package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteBody;
import io.quarkiverse.permuplate.PermuteMethod;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Mixin providing typed {@code addVariableFilter} overloads for m=2..6 variables.
 * Injected into {@code RuleDefinition} via {@code @PermuteMixin}.
 * Each overload delegates to {@code addVariableFilterGeneric} which uses reflection
 * for runtime invocation — consistent with wrapPredicate/wrapConsumer in the sandbox.
 */
class VariableFilterMixin<DS> {

    @PermuteMethod(varName = "m", from = "2", to = "6", name = "addVariableFilter",
                   macros = {"vArgs=typeArgList(1,m,'V')"})
    @PermuteBody(body = "{ addVariableFilterGeneric(predicate, ${typeArgList(1, m, 'v')}); }")
    public <@PermuteTypeParam(varName = "k", from = "1", to = "${m}", name = "V${k}") V1>
            void addVariableFilter(
            @PermuteParam(varName = "k", from = "1", to = "${m}",
                          type = "Variable<V${k}>", name = "v${k}") Variable<V1> v1,
            @PermuteDeclr(type = "Predicate${m+1}<DS, ${vArgs}>") Object predicate) {
    }
}
