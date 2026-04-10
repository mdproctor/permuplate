package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Template generating Consumer2 through Consumer7.
 * Consumer1 itself (keepTemplate=true) provides the arity-1 version.
 *
 * <p>to=7: Join6Second.fn() needs Consumer7 (DS + 6 facts = 7 total params).
 *
 * <p>Generated: Consumer2&lt;DS,A&gt; through Consumer7&lt;DS,A,B,C,D,E,F&gt;
 * Alpha naming (A,B,C...) matches Drools conventions.
 */
@Permute(varName = "i", from = "2", to = "7", className = "Consumer${i}",
         inline = false, keepTemplate = true)
public interface Consumer1<DS,
        @PermuteTypeParam(varName = "j", from = "1", to = "${i-1}", name = "${alpha(j)}") A> {
    void accept(
            DS ctx,
            @PermuteParam(varName = "j", from = "1", to = "${i-1}",
                          type = "${alpha(j)}", name = "${lower(j)}") A a);
}
