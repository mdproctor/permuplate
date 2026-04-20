package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteCase;

/**
 * Demonstrates {@code @PermuteCase} with Java 21+ arrow-switch expressions.
 *
 * <p>
 * Template {@code ArrowDispatch2} generates {@code ArrowDispatch3..5}.
 * Each generated class adds integer case arms in arrow form to the switch expression.
 * The seed arm ({@code case 0 -> 0}) is preserved unchanged in all generated classes.
 *
 * <p>
 * Note: generated source uses Java 21 switch expression syntax.
 * The consuming project must compile with {@code --release 21} or later.
 */
@Permute(varName = "i", from = "3", to = "5", className = "ArrowDispatch${i}")
public class ArrowDispatch2 {

    /**
     * Returns {@code k * 10} for case {@code k}, or 0 for case 0, or -1 for all other inputs.
     * Generates arities 3–5 with one more case arm each.
     */
    @PermuteCase(varName = "k", from = "1", to = "${i-1}", index = "${k}", body = "yield ${k * 10};")
    public int dispatch(int n) {
        return switch (n) {
            case 0 -> 0;
            default -> -1;
        };
    }
}
