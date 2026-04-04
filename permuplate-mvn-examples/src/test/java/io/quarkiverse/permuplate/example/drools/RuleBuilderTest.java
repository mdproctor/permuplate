package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.util.function.Function;

import org.junit.Before;
import org.junit.Test;

public class RuleBuilderTest {

    private RuleBuilder<Ctx> builder;
    private Ctx ctx;

    // Pre-typed source functions — used for join() args to avoid raw-type inference issues
    // (join() parameters are erased to Function<Object, DataSource<?>> in raw context,
    // so lambdas like `c -> c.accounts()` won't compile; pre-typed variables do)
    private static final Function<Ctx, DataSource<?>> PERSONS = c -> c.persons();
    private static final Function<Ctx, DataSource<?>> ACCOUNTS = c -> c.accounts();
    private static final Function<Ctx, DataSource<?>> ORDERS = c -> c.orders();
    private static final Function<Ctx, DataSource<?>> PRODUCTS = c -> c.products();
    private static final Function<Ctx, DataSource<?>> TRANSACTIONS = c -> c.transactions();

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
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testArity2CrossProductNoFilter() {
        // join() returns raw Join2First via asNext() unchecked cast; use var + pre-typed source
        var rule = builder.from("all-pairs", ctx -> ctx.persons())
                .join(ACCOUNTS)
                .fn((ctx, a, b) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(4); // 2 persons × 2 accounts
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testArity2FilterOnBothFacts() {
        // In raw context, fact params are Object; use explicit casts in the lambda
        var rule = builder.from("adult-high-balance", ctx -> ctx.persons())
                .join(ACCOUNTS)
                .filter((ctx, a, b) -> ((Person) a).age() >= 18 && ((Account) b).balance() > 500.0)
                .fn((ctx, a, b) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testArity2MultipleFilters() {
        // Chained .filter().filter() — verifies filterCount=2 is structurally recorded
        var rule = builder.from("two-filters", ctx -> ctx.persons())
                .join(ACCOUNTS)
                .filter((ctx, a, b) -> ((Person) a).age() >= 18)
                .filter((ctx, a, b) -> ((Account) b).balance() > 500.0)
                .fn((ctx, a, b) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testArity2CapturedFactsDistinguishByType() {
        var rule = builder.from("typed", ctx -> ctx.persons())
                .join(ACCOUNTS)
                .fn((ctx, a, b) -> {
                });

        rule.run(ctx);

        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Account.class);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testArity3ThreeFacts() {
        // Unfiltered three-way cross-product: 2×2×2 = 8 combinations
        var rule = builder.from("three-facts", ctx -> ctx.persons())
                .join(ACCOUNTS)
                .join(ORDERS)
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
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testArity3IntermediateFilter() {
        var rule = builder.from("filtered-triple", ctx -> ctx.persons())
                .join(ACCOUNTS)
                .filter((ctx, a, b) -> ((Person) a).age() >= 18 && ((Account) b).balance() > 500.0)
                .join(ORDERS)
                .filter((ctx, a, b, c) -> ((Order) c).amount() > 100.0)
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
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testArity4AllTypesDistinct() {
        var rule = builder.from("four-facts", ctx -> ctx.persons())
                .join(ACCOUNTS)
                .join(ORDERS)
                .join(PRODUCTS)
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
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testArity5AllTypesDistinct() {
        var rule = builder.from("five-facts", ctx -> ctx.persons())
                .join(ACCOUNTS)
                .join(ORDERS)
                .join(PRODUCTS)
                .join(TRANSACTIONS)
                .fn((ctx, a, b, c, d, e) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(5);
        rule.run(ctx);

        assertThat(rule.capturedFact(0, 4)).isInstanceOf(Transaction.class);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testArity6LeafNodeCompiles() {
        var rule = builder.from("six-facts", ctx -> ctx.persons())
                .join(ACCOUNTS)
                .join(ORDERS)
                .join(PRODUCTS)
                .join(TRANSACTIONS)
                .join(PERSONS)
                .fn((ctx, a, b, c, d, e, f) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(6);
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
}
