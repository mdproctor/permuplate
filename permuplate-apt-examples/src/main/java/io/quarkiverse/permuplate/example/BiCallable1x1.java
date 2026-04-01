package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteVar;

/**
 * Template that generates a family of two-sided functional interfaces using
 * cross-product permutation via {@code extraVars}.
 *
 * <p>
 * {@code BiCallable${i}x${k}} accepts {@code i} arguments from the left group
 * and {@code k} arguments from the right group. With {@code i∈[2,4]} and
 * {@code k∈[2,4]}, 9 interfaces are generated: {@code BiCallable2x2} through
 * {@code BiCallable4x4}.
 *
 * <p>
 * This demonstrates the key value of {@code extraVars}: a two-sided interface family
 * covering all combinations of two independent arities — something that would
 * otherwise require N×M separate templates.
 *
 * <p>
 * Example usage of a generated interface:
 *
 * <pre>{@code
 * BiCallable3x2 handler = (a1, a2, a3, b1, b2) -> process(a1, a2, a3, b1, b2);
 * }</pre>
 */
@Permute(varName = "i", from = 2, to = 4, className = "BiCallable${i}x${k}", extraVars = {
        @PermuteVar(varName = "k", from = 2, to = 4) })
public interface BiCallable1x1 {

    /**
     * Invoked with {@code i} left-side arguments followed by {@code k} right-side
     * arguments. All arguments are {@code Object} for maximum flexibility.
     */
    void call(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "left${j}") Object left1,
            @PermuteParam(varName = "m", from = "1", to = "${k}", type = "Object", name = "right${m}") Object right1);
}
