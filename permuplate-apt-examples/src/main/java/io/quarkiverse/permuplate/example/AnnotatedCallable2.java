package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteAnnotation;
import io.quarkiverse.permuplate.PermuteCase;
import io.quarkiverse.permuplate.PermuteImport;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteThrows;

/**
 * Demonstrates @PermuteAnnotation, @PermuteThrows, @PermuteCase, and @PermuteImport.
 *
 * Generates AnnotatedCallable3 through AnnotatedCallable5. Each generated class:
 * - has {@code @SuppressWarnings("unchecked")} added via @PermuteAnnotation
 * - has {@code import java.io.IOException} added via @PermuteImport (used by the throws clause)
 * - declares {@code throws IOException} on execute() via @PermuteThrows
 * - has additional switch cases on arity(int) generated via @PermuteCase
 */
@Permute(varName = "i", from = "3", to = "5", className = "AnnotatedCallable${i}")
@PermuteAnnotation("@SuppressWarnings(\"unchecked\")")
@PermuteImport("java.io.IOException")
public class AnnotatedCallable2 {

    @PermuteThrows("java.io.IOException")
    public void execute(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "arg${j}") Object arg1) {
    }

    @PermuteCase(varName = "k", from = "2", to = "${i}", index = "${k}", body = "return ${k};")
    public int arity(int index) {
        switch (index) {
            case 1:
                return 1;
            default:
                throw new IllegalArgumentException("No arity at index: " + index);
        }
    }
}
