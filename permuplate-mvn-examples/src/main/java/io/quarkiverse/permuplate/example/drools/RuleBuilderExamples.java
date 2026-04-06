package io.quarkiverse.permuplate.example.drools;

/**
 * Demonstrates the two styles for cross-fact predicates in the rule DSL.
 *
 * <p>
 * <b>Compact positional</b> — facts are referenced by their lambda parameter
 * position. Preferred for straightforward rules where all referenced facts are
 * adjacent in the join chain.
 *
 * <p>
 * <b>Named variable binding</b> — facts are bound to {@link Variable} instances
 * via {@code var()} and referenced by name in the filter. Preferred when
 * referencing non-adjacent facts, or when DRL {@code $}-prefixed naming aids
 * readability during migration.
 */
public class RuleBuilderExamples {

    /**
     * Compact positional form: find adults with a high-balance account.
     *
     * <p>
     * The cross-fact predicate uses the lambda parameters directly — no
     * variable declarations or {@code var()} calls needed.
     */
    public static RuleDefinition<Ctx> adultHighBalanceCompact(RuleBuilder<Ctx> builder) {
        return builder.from("persons", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0)
                .fn((ctx, p, a) -> {
                });
    }

    /**
     * Named variable binding form: same rule using {@link Variable}.
     *
     * <p>
     * Variables are declared upfront and bound via {@code var()} after each
     * join. The filter references them by name rather than by position. Produces
     * identical results to the compact form above.
     */
    public static RuleDefinition<Ctx> adultHighBalanceNamed(RuleBuilder<Ctx> builder) {
        Variable<Person> $person = new Variable<>();
        Variable<Account> $account = new Variable<>();

        return builder.from("persons", ctx -> ctx.persons()).var($person)
                .join(ctx -> ctx.accounts()).var($account)
                .filter($person, $account, (ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0)
                .fn((ctx, p, a) -> {
                });
    }
}
