package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Entry point for the Drools RuleBuilder DSL approximation.
 *
 * <pre>{@code
 * RuleBuilder<Ctx> builder = new RuleBuilder<>();
 * RuleDefinition<Ctx> rule = builder.from("adults", ctx -> ctx.persons())
 *         .filter((ctx, a) -> a.age() >= 18)
 *         .fn((ctx, a) -> System.out.println(a.name()));
 * rule.run(ctx);
 * }</pre>
 */
public class RuleBuilder<DS> {

    /**
     * Starts building a rule with its first fact source.
     *
     * @param name rule name (recorded in RuleDefinition for test assertions)
     * @param firstSource function from DS context to the first DataSource
     * @return Join1First — the start of the fluent chain
     */
    public <A> JoinBuilder.Join1First<DS, A> from(String name,
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addSource(firstSource);
        return new JoinBuilder.Join1First<>(rd);
    }
}
