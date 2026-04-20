package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteSwitchArm;

/**
 * Demonstrates {@code @PermuteSwitchArm} — Java 21+ arrow-switch pattern arm generation.
 *
 * <p>
 * Template {@code ShapeDispatch2} generates {@code ShapeDispatch3..5}.
 * Each generated class adds one {@code Callable} dispatch arm per arity.
 * The seed arm ({@code case Callable1 c -> c.hashCode()}) is preserved unchanged
 * in all generated classes; generated arms match {@code Callable2}, {@code Callable3}, etc.
 *
 * <p>
 * Note: generated source uses Java 21 pattern matching syntax ({@code case Type var ->}).
 * The consuming project must compile with {@code --release 21} or later.
 */
@Permute(varName = "i", from = "3", to = "5", className = "ShapeDispatch${i}")
public class ShapeDispatch2 {

    /**
     * Dispatches on the runtime type of {@code obj}, returning a representative int.
     * The seed arm handles {@code Callable1}; generated arms handle higher-arity callables.
     */
    @PermuteSwitchArm(varName = "k", from = "2", to = "${i-1}", pattern = "Callable${k} c${k}", body = "yield c${k}.hashCode();")
    public int dispatch(Object obj) {
        return switch (obj) {
            case Callable1 c -> c.hashCode();
            default -> -1;
        };
    }
}
