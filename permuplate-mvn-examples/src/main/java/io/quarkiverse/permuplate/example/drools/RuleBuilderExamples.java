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
        Variable<Person> $person = Variable.of("$person");
        Variable<Account> $account = Variable.of("$account");

        return builder.from("persons", ctx -> ctx.persons()).var($person)
                .join(ctx -> ctx.accounts()).var($account)
                .filter($person, $account, (ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0)
                .fn((ctx, p, a) -> {
                });
    }

    /**
     * from() shorthand — method reference, no string name required.
     * Equivalent to from("rule", Ctx::persons).
     */
    public static RuleDefinition<Ctx> adultHighBalanceShorthand(RuleBuilder<Ctx> builder) {
        return builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0)
                .fn((ctx, p, a) -> {
                });
    }

    /**
     * extensionPoint() / extendsRule() — authoring-time pattern reuse.
     * Both child rules inherit Person + the age filter from the base.
     * The Rete network handles node sharing automatically; no special
     * runtime inheritance concept exists.
     */
    public static void extendsExample(RuleBuilder<Ctx> builder) {
        var ep = builder.from("persons", ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .extensionPoint();

        // Child 1: high-balance accounts for adult persons
        var highBalance = builder.extendsRule(ep)
                .join(ctx -> ctx.accounts())
                .filter((ctx, p, a) -> a.balance() > 500.0)
                .fn((ctx, p, a) -> {
                });

        // Child 2: large orders for adult persons
        var largeOrders = builder.extendsRule(ep)
                .join(ctx -> ctx.orders())
                .filter((ctx, p, o) -> o.amount() > 100.0)
                .fn((ctx, p, o) -> {
                });
    }
}
