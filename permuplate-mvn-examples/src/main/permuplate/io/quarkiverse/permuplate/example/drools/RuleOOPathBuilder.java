package io.quarkiverse.permuplate.example.drools;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteBody;
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
 * All classes Path2..Path6 are generated from the single Path1 template
 * ({@code keepTemplate=false}). Path2 is the i=2 case (terminal: returns END via
 * {@code @PermuteReturn(typeParam="END")}). Path3..6 are non-terminal and chain
 * back to the next lower class via {@code @PermuteReturn(className="Path${i-1}")}.
 */
public class RuleOOPathBuilder {

    @Permute(varName = "i", from = "2", to = "6", className = "Path${i}",
             inline = true, keepTemplate = false)
    public static class Path1<END, T extends BaseTuple, A, B,
            @PermuteTypeParam(varName = "k", from = "3", to = "${i}", name = "${alpha(k)}") C> {
        private final END end;
        private final RuleDefinition<?> rd;
        private final List<OOPathStep> steps;
        private final int rootIndex;

        public Path1(END end, RuleDefinition<?> rd, List<OOPathStep> steps, int rootIndex) {
            this.end = end;
            this.rd = rd;
            this.steps = steps;
            this.rootIndex = rootIndex;
        }

        @SuppressWarnings("unchecked")
        @PermuteReturn(when = "i == 2", typeParam = "END")
        @PermuteReturn(when = "i > 2", className = "RuleOOPathBuilder.Path${i-1}",
                       typeArgs = "'END, T, ' + typeArgList(2, i, 'alpha')",
                       alwaysEmit = true)
        @PermuteBody(when = "i == 2",
                     body = "{ steps.add(new OOPathStep((ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact), (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child))); rd.addOOPathPipeline(rootIndex, steps); return end; }")
        @PermuteBody(when = "i > 2",
                     body = "{ steps.add(new OOPathStep((ctx, fact) -> (Iterable<?>) fn2.apply((PathContext<T>) ctx, (A) fact), (ctx, child) -> flt2.test((PathContext<T>) ctx, (B) child))); return new RuleOOPathBuilder.Path${i-1}<>(end, rd, steps, rootIndex); }")
        public Object path(Function2<PathContext<T>, A, Iterable<B>> fn2,
                Predicate2<PathContext<T>, B> flt2) {
            return null; // replaced by @PermuteBody
        }
    }
}
