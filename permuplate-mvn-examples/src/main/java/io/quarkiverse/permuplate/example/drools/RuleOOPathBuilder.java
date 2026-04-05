package io.quarkiverse.permuplate.example.drools;

import java.util.List;

/**
 * Contains the Path2..Path6 builder classes for OOPath traversal chains.
 * Each PathN.path(fn, flt) adds one traversal step and returns the next lower
 * PathN builder. Path2.path() is terminal — registers the complete pipeline on
 * RuleDefinition and returns the JoinFirst END type.
 */
public class RuleOOPathBuilder {

    public static class Path2<END, T extends BaseTuple, A, B> {
        private final END end;
        private final RuleDefinition<?> rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        public Path2(END end, RuleDefinition<?> rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        @SuppressWarnings("unchecked")
        public END path(Function2<PathContext<T>, A, Iterable<B>> fn2,
                Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                    (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                    (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            rd.addOOPathPipeline(rootIndex, steps);
            return end;
        }
    }

    public static class Path3<END, T extends BaseTuple, A, B, C> {
        private final END end;
        private final RuleDefinition<?> rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        public Path3(END end, RuleDefinition<?> rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        @SuppressWarnings("unchecked")
        public Path2<END, T, B, C> path(Function2<PathContext<T>, A, Iterable<B>> fn2,
                Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                    (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                    (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            return new Path2<>(end, rd, steps, rootIndex);
        }
    }

    public static class Path4<END, T extends BaseTuple, A, B, C, D> {
        private final END end;
        private final RuleDefinition<?> rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        public Path4(END end, RuleDefinition<?> rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        @SuppressWarnings("unchecked")
        public Path3<END, T, B, C, D> path(Function2<PathContext<T>, A, Iterable<B>> fn2,
                Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                    (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                    (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            return new Path3<>(end, rd, steps, rootIndex);
        }
    }

    public static class Path5<END, T extends BaseTuple, A, B, C, D, E> {
        private final END end;
        private final RuleDefinition<?> rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        public Path5(END end, RuleDefinition<?> rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        @SuppressWarnings("unchecked")
        public Path4<END, T, B, C, D, E> path(Function2<PathContext<T>, A, Iterable<B>> fn2,
                Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                    (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                    (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            return new Path4<>(end, rd, steps, rootIndex);
        }
    }

    public static class Path6<END, T extends BaseTuple, A, B, C, D, E, F> {
        private final END end;
        private final RuleDefinition<?> rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        public Path6(END end, RuleDefinition<?> rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        @SuppressWarnings("unchecked")
        public Path5<END, T, B, C, D, E, F> path(Function2<PathContext<T>, A, Iterable<B>> fn2,
                Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                    (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                    (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            return new Path5<>(end, rd, steps, rootIndex);
        }
    }
}
