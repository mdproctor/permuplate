package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Host class for a nested static interface template.
 *
 * <p>
 * Demonstrates that {@code @Permute} on a nested static interface works the same
 * as on a nested static class: the generated types are emitted as top-level
 * interfaces in the same package, with the {@code static} modifier stripped.
 */
public class InterfaceLibrary {

    @Permute(varName = "i", from = "3", to = "4", className = "Merger${i}")
    public static interface Merger2<@PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A> {
        void merge(
                @PermuteParam(varName = "j", from = "1", to = "${i}", type = "${alpha(j)}", name = "${lower(j)}") A a);
    }
}
