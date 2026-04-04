package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Entry point for the Drools RuleBuilder DSL approximation.
 *
 * <p>
 * {@code from()} creates the initial {@code Join1First<Void, DS, A>}. The {@code Void}
 * END type means no outer scope exists — {@code end()} on top-level chains returns null
 * and is never called. When nested scopes ({@code not()}, {@code exists()}) arrive in
 * Phase 3, they will capture the outer builder type as END and restore it via {@code end()}.
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
     * Returns {@code Join1First<Void, DS, A>} — Void indicates no outer scope.
     */
    public <A> JoinBuilder.Join1First<Void, DS, A> from(String name,
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addSource(firstSource);
        return new JoinBuilder.Join1First<>(null, rd);
    }
}
