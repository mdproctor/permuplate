package io.quarkiverse.permuplate.example.drools;

/**
 * Container for typed extension point handles produced by JoinNSecond.extensionPoint().
 * Each inner class captures the base rule's RuleDefinition and encodes the accumulated
 * fact types in its type parameters.
 *
 * Naming convention matches Drools vol2: the number = DS + fact count.
 * RuleExtendsPoint2 has 1 fact (A), RuleExtendsPoint7 has 6 facts (A..F).
 *
 * Design: extension is authoring-time deduplication only — extendsRule() copies
 * the base rule's sources and filters into the child at build time. The Rete network
 * handles node sharing automatically; no special runtime concept is needed.
 */
public class RuleExtendsPoint {

    public static class RuleExtendsPoint2<DS, A> {
        private final RuleDefinition<DS> baseRd;

        public RuleExtendsPoint2(RuleDefinition<DS> baseRd) {
            this.baseRd = baseRd;
        }

        RuleDefinition<DS> baseRd() {
            return baseRd;
        }
    }

    public static class RuleExtendsPoint3<DS, A, B> {
        private final RuleDefinition<DS> baseRd;

        public RuleExtendsPoint3(RuleDefinition<DS> baseRd) {
            this.baseRd = baseRd;
        }

        RuleDefinition<DS> baseRd() {
            return baseRd;
        }
    }

    public static class RuleExtendsPoint4<DS, A, B, C> {
        private final RuleDefinition<DS> baseRd;

        public RuleExtendsPoint4(RuleDefinition<DS> baseRd) {
            this.baseRd = baseRd;
        }

        RuleDefinition<DS> baseRd() {
            return baseRd;
        }
    }

    public static class RuleExtendsPoint5<DS, A, B, C, D> {
        private final RuleDefinition<DS> baseRd;

        public RuleExtendsPoint5(RuleDefinition<DS> baseRd) {
            this.baseRd = baseRd;
        }

        RuleDefinition<DS> baseRd() {
            return baseRd;
        }
    }

    public static class RuleExtendsPoint6<DS, A, B, C, D, E> {
        private final RuleDefinition<DS> baseRd;

        public RuleExtendsPoint6(RuleDefinition<DS> baseRd) {
            this.baseRd = baseRd;
        }

        RuleDefinition<DS> baseRd() {
            return baseRd;
        }
    }

    public static class RuleExtendsPoint7<DS, A, B, C, D, E, F> {
        private final RuleDefinition<DS> baseRd;

        public RuleExtendsPoint7(RuleDefinition<DS> baseRd) {
            this.baseRd = baseRd;
        }

        RuleDefinition<DS> baseRd() {
            return baseRd;
        }
    }
}
