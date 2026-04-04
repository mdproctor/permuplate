package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Template generating Predicate2 through Predicate7.
 * Predicate1 itself (keepTemplate=true) provides the arity-1 version.
 *
 * <p>to=7: Join6First.filter() needs Predicate7 (DS + 6 facts = 7 total params).
 *
 * <p>Generated: Predicate2&lt;DS,A,B&gt; through Predicate7&lt;DS,A,B,C,D,E,F&gt;
 */
@Permute(varName = "i", from = 2, to = 7, className = "Predicate${i}",
         inline = false, keepTemplate = true)
public interface Predicate1<DS,
        @PermuteTypeParam(varName = "j", from = "1", to = "${i-1}", name = "${alpha(j)}") A> {
    boolean test(
            DS ctx,
            @PermuteParam(varName = "j", from = "1", to = "${i-1}",
                          type = "${alpha(j)}", name = "${lower(j)}") A a);
}
