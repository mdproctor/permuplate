package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;

/**
 * Demonstrates inline permutation: {@code Handler1} through {@code Handler5}
 * are generated as nested classes directly inside {@code Handlers} — no
 * top-level clutter, one clean entry point for the whole family.
 *
 * <p>
 * This template lives in {@code src/main/permuplate/} rather than
 * {@code src/main/java/} because it contains an inline template class that
 * the Permuplate Maven plugin must process before javac compiles anything.
 * Mark {@code src/main/permuplate} as a source root in your IDE for
 * navigation and refactoring support.
 *
 * <p>
 * Usage of a generated handler:
 * <pre>{@code
 * Handlers.Handler3 h = (a1, a2, a3) -> System.out.println(a1 + " " + a2 + " " + a3);
 * h.handle("hello", "world", "!");
 * }</pre>
 */
public class Handlers {

    /**
     * Template for a type-safe N-argument handler. Generates {@code Handler2}
     * through {@code Handler5} as nested siblings inside {@code Handlers}.
     *
     * <p>
     * {@code keepTemplate = true} retains {@code Handler1} in the output
     * alongside the generated classes — a single-argument handler is useful
     * in its own right and not merely a scaffold.
     */
    @Permute(varName = "i", from = 2, to = 5,
            className = "Handler${i}",
            inline = true,
            keepTemplate = true)
    public static class Handler1 {

        private @PermuteDeclr(type = "Callable${i}", name = "delegate${i}") Callable1 delegate1;

        /**
         * Handles the given arguments by forwarding them to the underlying
         * callable delegate.
         */
        public void handle(
                @PermuteParam(varName = "j", from = "1", to = "${i}",
                              type = "Object", name = "arg${j}") Object arg1) {
            delegate1.call(arg1);
        }
    }
}
