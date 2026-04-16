package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Template with TWO fixed parameters before the sentinel and TWO fixed parameters
 * after it. Verifies that all four fixed params are preserved in position regardless
 * of arity, and that only the sentinel is replaced by the expanded sequence.
 */
@Permute(varName = "i", from = "3", to = "4", className = "MultiFixed${i}")
public class MultiFixed2<A, @PermuteTypeParam(varName = "k", from = "2", to = "${i}", name = "${alpha(k)}") B> {

    private @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>", name = "c${i}") Callable2<A, B> c2;
    private @PermuteDeclr(type = "List<${alpha(i)}>") List<B> right;

    public void process(
            String tag,
            String source,
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "${alpha(j)}", name = "${lower(j)}") A a,
            List<Object> results,
            String label) {
        for (@PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
        B b : right) {
            c2.call(a, b);
            results.add(b);
        }
    }
}
