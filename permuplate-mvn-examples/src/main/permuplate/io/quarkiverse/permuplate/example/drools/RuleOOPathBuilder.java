package io.quarkiverse.permuplate.example.drools;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteReturn;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Contains the Path2..Path6 builder classes for OOPath traversal chains.
 * Each PathN.path(fn, flt) adds one traversal step and returns the next lower
 * PathN builder. Path2.path() is terminal — registers the complete pipeline on
 * RuleDefinition and returns the JoinFirst END type.
 *
 * <p>
 * Path2 is hand-written (terminal: returns END, structurally distinct).
 * Path3 is the template ({@code keepTemplate=true}); Path4..Path6 are generated.
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

    @Permute(varName = "i", from = "4", to = "6", className = "Path${i}",
             inline = true, keepTemplate = true)
    public static class Path3<END, T extends BaseTuple, A, B,
            @PermuteTypeParam(varName = "k", from = "3", to = "${i}", name = "${alpha(k)}") C> {
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
        @PermuteReturn(className = "RuleOOPathBuilder.Path${i-1}",
                       typeArgs = "'END, T, ' + typeArgList(2, i, 'alpha')",
                       alwaysEmit = true)
        public Path2<END, T, B, C> path(Function2<PathContext<T>, A, Iterable<B>> fn2,
                Predicate2<PathContext<T>, B> flt2) {
            steps.add(new OOPathStep(
                    (ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact),
                    (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child)));
            return new @PermuteDeclr(type = "RuleOOPathBuilder.Path${i-1}") Path2<>(end, rd, steps, rootIndex);
        }
    }
}
