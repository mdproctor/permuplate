package io.quarkiverse.permuplate.example.composition;

import io.quarkiverse.permuplate.*;

/**
 * Demonstrates @PermuteSource Capability C: builder synthesis from records.
 *
 * Template A generates Tuple${i} records (Tuple3 through Tuple5).
 * Template B has an EMPTY class body — the processor reads Tuple${i}'s components
 * and generates a complete fluent builder automatically:
 * - Private fields per component
 * - Fluent setters (returns this)
 * - build() method returning the record
 *
 * 28 methods generated across 3 builder classes — zero written by hand.
 */
public class TupleBuilders {

    // ===== Template A: Tuple records =====
    @Permute(varName = "i", from = "3", to = "5", className = "Tuple${i}",
             inline = true, keepTemplate = false)
    public static record Tuple2<
            @PermuteTypeParam(varName = "k", from = "1", to = "${i}",
                              name = "${alpha(k)}") A
    >(
            @PermuteParam(varName = "j", from = "1", to = "${i}",
                          type = "${alpha(j)}", name = "${lower(j)}")
            A a
    ) {}

    // ===== Template B: Builders — Capability C =====
    /**
     * Empty body — processor generates the entire builder from Tuple${i}'s components:
     *   private A a; private B b; ...
     *   public Tuple${i}Builder<A,B,...> a(A a) { this.a = a; return this; }
     *   public Tuple${i}<A,B,...> build() { return new Tuple${i}<>(a, b, ...); }
     */
    @Permute(varName = "i", from = "3", to = "5", className = "Tuple${i}Builder",
             inline = true, keepTemplate = false)
    @PermuteSource("Tuple${i}")
    public static class Tuple3Builder {}
}
