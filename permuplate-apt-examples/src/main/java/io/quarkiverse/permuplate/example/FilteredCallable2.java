package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteFilter;
import io.quarkiverse.permuplate.PermuteParam;

/**
 * Demonstrates {@code @PermuteFilter} to skip specific permutation values.
 *
 * <p>
 * This template generates callable interfaces for arities 3, 5, 6, and 7 —
 * skipping arity 4 via {@code @PermuteFilter("${i} != 4")}.
 *
 * <p>
 * Without the filter, the range {@code from="3" to="7"} would produce five
 * interfaces (arities 3–7). The filter excludes arity 4, yielding four:
 * FilteredCallable3, FilteredCallable5, FilteredCallable6, FilteredCallable7.
 *
 * <p>
 * A common use case: arity 4 (or any specific value) is hand-written
 * elsewhere with special behavior, while the remaining arities are generated
 * uniformly from this template.
 */
@Permute(varName = "i", from = "3", to = "7", className = "FilteredCallable${i}")
@PermuteFilter("${i} != 4")
public interface FilteredCallable2 {

    /**
     * Invokes the callable with the given arguments.
     *
     * <p>
     * {@code @PermuteParam} expands this single sentinel parameter into i positional
     * arguments (o1, o2, ..., o{i}) in each generated interface.
     */
    void call(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "o${j}") Object o1);
}
