package io.quarkiverse.permuplate.example.composition;

import io.quarkiverse.permuplate.*;

/**
 * Cohesive example: a typed event system built from one root template.
 * Demonstrates all three template composition capabilities together.
 *
 * Template A: Event${i} records (data types — root template)
 *   → Event3<A,B,C>, Event4<A,B,C,D>, Event5<A,B,C,D,E>
 *
 * Template B (Capability C — builder synthesis): Event${i}Builder
 *   Empty class body. Processor generates complete fluent builder from record components.
 *   → Event3Builder<A,B,C> with fields field1/field2/field3, setters, build()
 *
 * Template C (Capability A — type inference): EventFilter${i}
 *   User writes filter logic. Type params A, B, C... inferred from Event${i}.
 *   No @PermuteTypeParam needed.
 *
 * One root template → builders + filters — all type-safe, all in sync.
 * Zero type-parameter bookkeeping across the derived families.
 */
public class EventSystem {

    // ===== Template A: Event records (root template) =====
    @Permute(varName = "i", from = "3", to = "5", className = "Event${i}",
             inline = true, keepTemplate = false)
    public static record Event2<
            @PermuteTypeParam(varName = "k", from = "1", to = "${i}",
                              name = "${alpha(k)}") A
    >(
            @PermuteParam(varName = "j", from = "1", to = "${i}",
                          type = "${alpha(j)}", name = "field${j}")
            A field1
    ) {}

    // ===== Template B: Event builders (Capability C — builder synthesis) =====
    /**
     * Zero lines of builder code needed. @PermuteSource reads Event${i}'s
     * components and generates the complete fluent builder:
     *   private A field1; private B field2; ...
     *   public Event${i}Builder<A,...> field1(A field1) { ... return this; }
     *   public Event${i}<A,...> build() { return new Event${i}<>(field1, ...); }
     */
    @Permute(varName = "i", from = "3", to = "5", className = "Event${i}Builder",
             inline = true, keepTemplate = false)
    @PermuteSource("Event${i}")
    public static class Event3Builder {}

    // ===== Template C: EventFilter (Capability A — type inference) =====
    /**
     * User writes only the filter logic. Type params A, B, C... are inferred
     * from Event${i} via @PermuteSource — no @PermuteTypeParam needed.
     *
     * @PermuteSource("Event${i}") causes:
     *   1. Type params copied from Event${i} (A, B, ...) to EventFilter${i}.
     *   2. All Event3<Object> references rewritten to Event${i}<A, B, ...>.
     */
    @Permute(varName = "i", from = "3", to = "5", className = "EventFilter${i}",
             inline = true, keepTemplate = false)
    @PermuteSource("Event${i}")
    public abstract static class EventFilter3 {
        // type params A, B, C inferred from Event3 — no @PermuteTypeParam needed

        /** Override to decide whether an event should be handled. */
        public abstract boolean accept(Event3<Object> event);

        public final void onEvent(Event3<Object> event) {
            if (accept(event)) handleEvent(event);
        }

        protected abstract void handleEvent(Event3<Object> event);
    }
}
