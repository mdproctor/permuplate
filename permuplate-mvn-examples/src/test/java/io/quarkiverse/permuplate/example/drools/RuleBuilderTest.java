package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

public class RuleBuilderTest {

    private RuleBuilder<Ctx> builder;
    private Ctx ctx;

    @Before
    public void setUp() {
        builder = new RuleBuilder<>();
        ctx = new Ctx(
                DataSource.of(new Person("Alice", 30), new Person("Bob", 17)),
                DataSource.of(new Account("ACC1", 1000.0), new Account("ACC2", 50.0)),
                DataSource.of(new Order("ORD1", 150.0), new Order("ORD2", 25.0)),
                DataSource.of(new Product("PRD1", 99.0), new Product("PRD2", 9.0)),
                DataSource.of(new Transaction("TXN1", 200.0), new Transaction("TXN2", 15.0)));
    }

    @Test
    public void testArity1Structural() {
        var rule = builder.from("structural", ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() >= 18)
                .fn((ctx, a) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(1);
        assertThat(rule.filterCount()).isEqualTo(1);
        assertThat(rule.hasAction()).isTrue();
    }

    @Test
    public void testArity1AdultFilterMatchesOnlyAlice() {
        var rule = builder.from("adults", ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() >= 18)
                .fn((ctx, a) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
    }

    @Test
    public void testArity1NoFilterMatchesAll() {
        var rule = builder.from("all", ctx -> ctx.persons())
                .fn((ctx, a) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(2);
    }

    @Test
    public void testArity1MultipleFiltersChained() {
        var rule = builder.from("chained", ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() >= 18)
                .filter((ctx, a) -> a.name().startsWith("A"))
                .fn((ctx, a) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
    }

    @Test
    public void testArity2CrossProductNoFilter() {
        var rule = builder.from("all-pairs", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .fn((ctx, a, b) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(4); // 2 persons × 2 accounts
    }

    @Test
    public void testArity2FilterOnBothFacts() {
        var rule = builder.from("adult-high-balance", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
                .fn((ctx, a, b) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testArity2MultipleFilters() {
        // Chained .filter().filter() — verifies filterCount=2 is structurally recorded
        var rule = builder.from("two-filters", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18)
                .filter((ctx, a, b) -> b.balance() > 500.0)
                .fn((ctx, a, b) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testArity2CapturedFactsDistinguishByType() {
        var rule = builder.from("typed", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .fn((ctx, a, b) -> {
                });

        rule.run(ctx);

        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Account.class);
    }

    @Test
    public void testArity3ThreeFacts() {
        // Unfiltered three-way cross-product: 2×2×2 = 8 combinations
        var rule = builder.from("three-facts", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .fn((ctx, a, b, c) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(3);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(8);
        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Account.class);
        assertThat(rule.capturedFact(0, 2)).isInstanceOf(Order.class);
    }

    @Test
    public void testArity3IntermediateFilter() {
        var rule = builder.from("filtered-triple", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
                .join(ctx -> ctx.orders())
                .filter((ctx, a, b, c) -> c.amount() > 100.0)
                .fn((ctx, a, b, c) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(3);
        assertThat(rule.filterCount()).isEqualTo(2);

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
        assertThat(rule.capturedFact(0, 2)).isEqualTo(new Order("ORD1", 150.0));
    }

    @Test
    public void testArity4AllTypesDistinct() {
        var rule = builder.from("four-facts", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .join(ctx -> ctx.products())
                .fn((ctx, a, b, c, d) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(4);
        rule.run(ctx);

        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Account.class);
        assertThat(rule.capturedFact(0, 2)).isInstanceOf(Order.class);
        assertThat(rule.capturedFact(0, 3)).isInstanceOf(Product.class);
    }

    @Test
    public void testArity5AllTypesDistinct() {
        var rule = builder.from("five-facts", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .join(ctx -> ctx.products())
                .join(ctx -> ctx.transactions())
                .fn((ctx, a, b, c, d, e) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(5);
        rule.run(ctx);

        assertThat(rule.capturedFact(0, 4)).isInstanceOf(Transaction.class);
    }

    @Test
    public void testArity6LeafNodeCompiles() {
        var rule = builder.from("six-facts", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .join(ctx -> ctx.products())
                .join(ctx -> ctx.transactions())
                .join(ctx -> ctx.persons())
                .fn((ctx, a, b, c, d, e, f) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(6);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(64); // 2^6 = 64 combinations
    }

    @Test
    public void testFilterRejectsAllCombinations() {
        var rule = builder.from("impossible", ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() > 200)
                .fn((ctx, a) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(0);
    }

    @Test
    public void testMultipleExecutionsRecordedSeparately() {
        var rule = builder.from("multi", ctx -> ctx.persons())
                .fn((ctx, a) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(2);
        assertThat(rule.capturedFacts(0)).containsExactly(new Person("Alice", 30));
        assertThat(rule.capturedFacts(1)).containsExactly(new Person("Bob", 17));
    }

    // =========================================================================
    // Typed join() — fully typed chain without casts or pre-typed constants
    // =========================================================================

    @Test
    public void testArity2FullyTyped() {
        // Compile-time proof: the typed join() chain resolves B=Account from the lambda.
        // a is Person, b is Account — the compiler enforces types, no casts needed.
        // Runtime behaviour is equivalent to testArity2FilterOnBothFacts.
        var rule = builder.from("persons", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
                .fn((ctx, a, b) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testArity3FullyTyped() {
        // Compile-time proof: three-way join resolves A=Person, B=Account, C=Order.
        // Runtime behaviour is equivalent to testArity3IntermediateFilter (single combined filter).
        var rule = builder.from("persons", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .filter((ctx, a, b, c) -> a.age() >= 18 && b.balance() > 500.0 && c.amount() > 100.0)
                .fn((ctx, a, b, c) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
        assertThat(rule.capturedFact(0, 2)).isEqualTo(new Order("ORD1", 150.0));
    }

    // =========================================================================
    // Dual filter() — single-fact and all-facts overloads
    // =========================================================================

    @Test
    public void testArity2SingleFactFilter() {
        // filter(Predicate2<DS, Account>) — tests only the most recently joined fact.
        // 2 persons × 1 high-balance account = 2 executions (person not filtered here).
        var rule = builder.from("persons", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, b) -> b.balance() > 500.0)
                .fn((ctx, a, b) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(1);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(2); // Alice+ACC1, Bob+ACC1
    }

    @Test
    public void testArity2BothFilterTypesChained() {
        // Chain single-fact filter (on Account) then cross-fact filter (Person + Account).
        // Only Alice(30) + ACC1(1000) satisfies both.
        var rule = builder.from("persons", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, b) -> b.balance() > 500.0)
                .filter((ctx, a, b) -> a.age() >= 18)
                .fn((ctx, a, b) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testArity3SingleFactFilterOnLatestFact() {
        // After joining persons + accounts + orders, single-fact filter tests only Order.
        // 2 persons × 2 accounts × 1 qualifying order (ORD1 amount>100) = 4 combos.
        var rule = builder.from("persons", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .filter((ctx, c) -> c.amount() > 100.0)
                .fn((ctx, a, b, c) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(1);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(4);
        assertThat(rule.capturedFact(0, 2)).isInstanceOf(Order.class);
    }
}
