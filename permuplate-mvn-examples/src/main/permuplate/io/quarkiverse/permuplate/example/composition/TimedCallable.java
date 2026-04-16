package io.quarkiverse.permuplate.example.composition;

import io.quarkiverse.permuplate.*;

/**
 * Demonstrates @PermuteSource Capability A: ordering + type parameter inference.
 *
 * Template A generates Callable${i} interfaces (arities 2-4).
 * Template B generates TimedCallable${i} — type params A..N are inferred
 * automatically from Callable${i} via @PermuteSource. No @PermuteTypeParam needed.
 *
 * Generated: Callable2..Callable4, TimedCallable2..TimedCallable4.
 */
public class TimedCallable {

    // ===== Template A: Callable interface =====
    @Permute(varName = "i", from = "2", to = "4", className = "Callable${i}",
             inline = true, keepTemplate = false)
    public interface Callable1<
            @PermuteTypeParam(varName = "k", from = "1", to = "${i}",
                              name = "${alpha(k)}") A
    > {
        Object result(
                @PermuteParam(varName = "j", from = "1", to = "${i}",
                              type = "${alpha(j)}", name = "${lower(j)}")
                Object o1) throws Exception;
    }

    // ===== Template B: TimedCallable — Capability A =====
    /**
     * Wraps any Callable with timing. Type params A, B, ... are inferred from
     * Callable${i} — no @PermuteTypeParam needed on this template.
     *
     * @PermuteSource("Callable${i}") causes:
     *   1. Type params copied from Callable${i} (A, B, ...) to TimedCallable${i}.
     *   2. All Callable2<Object> references rewritten to Callable${i}<A, B, ...>.
     *   3. @PermuteParam on result() expands the parameter list and the delegate call.
     */
    @Permute(varName = "i", from = "2", to = "4", className = "TimedCallable${i}",
             inline = true, keepTemplate = false)
    @PermuteSource("Callable${i}")
    public static class TimedCallable2 implements Callable2<Object> {
        private final Callable2<Object> delegate;

        public TimedCallable2(Callable2<Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object result(
                @PermuteParam(varName = "j", from = "1", to = "${i}",
                              type = "${alpha(j)}", name = "${lower(j)}")
                Object o1) throws Exception {
            long start = System.nanoTime();
            try {
                return delegate.result(o1);
            } finally {
                System.out.println(getClass().getSimpleName() + " took "
                        + (System.nanoTime() - start) + "ns");
            }
        }
    }
}
