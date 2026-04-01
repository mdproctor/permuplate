package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;

/**
 * Template with TWO fixed parameters before the sentinel and TWO fixed parameters
 * after it. Verifies that all four fixed params are preserved in position regardless
 * of arity, and that only the sentinel is replaced by the expanded sequence.
 */
@Permute(varName = "i", from = 3, to = 4, className = "MultiFixed${i}")
public class MultiFixed2 {

    private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
    private List<Object> right;

    public void process(
            String tag,
            String source,
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1,
            List<Object> results,
            String label) {
        for (@PermuteDeclr(type = "Object", name = "o${i}")
        Object o2 : right) {
            c2.call(o1, o2);
            results.add(o2);
        }
    }
}
