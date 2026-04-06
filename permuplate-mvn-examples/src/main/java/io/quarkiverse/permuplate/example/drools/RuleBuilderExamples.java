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

    /**
     * type() — compile-time type narrowing for untyped or base-typed sources.
     * A no-op at runtime; exists solely to give the compiler correct type information.
     */
    public static RuleDefinition<Ctx> typeNarrowingExample(RuleBuilder<Ctx> builder) {
        // Simulate an untyped source (DataSource<Object>) narrowed to Person.
        // In practice, use this when a shared/generic source needs type-safe access.
        @SuppressWarnings("unchecked")
        DataSource<Object> untypedPersons = (DataSource<Object>) (DataSource<?>) DataSource.of(new Person("Alice", 30));

        return builder.from(ctx -> untypedPersons)
                .<Person> type()
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });
    }

    /**
     * BaseTuple.as() — projects OOPath tuple results into typed records.
     * The record's fields must be in the same positional order as the tuple elements.
     * Useful for readable access to path traversal results.
     *
     * <p>
     * Note: the explicit typed variable for path2() anchors B=Room for the compiler —
     * the same pattern required by the OOPath test suite.
     */
    public static void tupleAsExample(RuleBuilder<Ctx> builder) {
        record LibRoom(Library library, Room room) {
        }

        // Typed variable anchors B=Room for path2() — required for lambda type inference
        RuleOOPathBuilder.Path2<JoinBuilder.Join2First<Void, Ctx, Library, BaseTuple.Tuple2<Library, Room>>, BaseTuple.Tuple1<Library>, Library, Room> path2 = builder
                .from(ctx -> ctx.libraries()).path2();

        // path2() produces Tuple2<Library, Room> — project it into LibRoom for named access
        path2.path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> room.name() != null)
                .fn((ctx, lib, t) -> {
                    LibRoom lr = t.as(); // named access: lr.library(), lr.room()
                    System.out.println(lr.library().name() + " / " + lr.room().name());
                });
    }
}
