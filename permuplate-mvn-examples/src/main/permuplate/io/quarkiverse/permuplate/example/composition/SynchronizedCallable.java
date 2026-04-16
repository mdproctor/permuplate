package io.quarkiverse.permuplate.example.composition;

import io.quarkiverse.permuplate.*;

/**
 * Demonstrates @PermuteSource Capability B: @PermuteDelegate synthesis.
 *
 * @PermuteDelegate generates synchronized delegating methods for ALL SyncCallable${i}
 * interface methods — zero method bodies written by hand.
 *
 * Generated: SynchronizedCallable2..SynchronizedCallable4.
 */
public class SynchronizedCallable {

    // ===== Template A: SyncCallable interface =====
    @Permute(varName = "i", from = "2", to = "4", className = "SyncCallable${i}",
             inline = true, keepTemplate = false)
    public interface SyncCallable1<
            @PermuteTypeParam(varName = "k", from = "1", to = "${i}",
                              name = "${alpha(k)}") A> {
        Object result(
                @PermuteParam(varName = "j", from = "1", to = "${i}",
                              type = "${alpha(j)}", name = "${lower(j)}")
                Object o1) throws Exception;
    }

    // ===== Template B: SynchronizedCallable — Capability B =====
    /**
     * Zero method bodies needed. @PermuteDelegate synthesises all SyncCallable${i}
     * methods as synchronized delegating calls automatically.
     *
     * @PermuteSource("SyncCallable${i}") causes:
     *   1. Type params copied from SyncCallable${i} (A, B, ...) to SynchronizedCallable${i}.
     *   2. All SyncCallable2<Object> references rewritten to SyncCallable${i}<A, B, ...>.
     *   3. @PermuteDelegate synthesises synchronized result(A a, B b, ...) methods.
     */
    @Permute(varName = "i", from = "2", to = "4", className = "SynchronizedCallable${i}",
             inline = true, keepTemplate = false)
    @PermuteSource("SyncCallable${i}")
    public static class SynchronizedCallable2 implements SyncCallable2<Object> {
        /**
         * All SyncCallable${i} methods are generated as synchronized delegating calls.
         * This class body contains only the field and constructor — no method bodies.
         */
        @PermuteDelegate(modifier = "synchronized")
        private final SyncCallable2<Object> delegate;

        public SynchronizedCallable2(SyncCallable2<Object> delegate) {
            this.delegate = delegate;
        }
    }
}
