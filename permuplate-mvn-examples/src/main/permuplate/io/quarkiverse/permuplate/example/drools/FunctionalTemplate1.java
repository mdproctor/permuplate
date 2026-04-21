package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteAnnotation;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;
import io.quarkiverse.permuplate.PermuteVar;

/**
 * Single cross-product template generating the Consumer2..7 and Predicate2..7
 * functional interface families.
 *
 * <p>{@code extraVars = @PermuteVar(varName="F", values={"Consumer","Predicate"})}
 * cross-products with {@code i=2..7}, producing 12 interfaces total. Method name
 * and return type vary with {@code F} via JEXL ternary macros.
 *
 * <p>Consumer1 and Predicate1 are separate minimal hand-written arity-1 interfaces.
 */
@Permute(varName = "i", from = "2", to = "7", className = "${F}${i}",
         inline = false, keepTemplate = false,
         extraVars = {@PermuteVar(varName = "F", values = {"Consumer", "Predicate"})},
         macros = {"method=${F == 'Consumer' ? 'accept' : 'test'}",
                   "ret=${F == 'Consumer' ? 'void' : 'boolean'}"})
@PermuteAnnotation("@FunctionalInterface")
public interface FunctionalTemplate1<DS,
        @PermuteTypeParam(varName = "j", from = "1", to = "${i-1}", name = "${alpha(j)}") A> {

    @PermuteDeclr(type = "${ret}", name = "${method}")
    void accept(DS ctx,
            @PermuteParam(varName = "j", from = "1", to = "${i-1}",
                          type = "${alpha(j)}", name = "${lower(j)}") A a);
}
