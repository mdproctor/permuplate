package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that the generated {@code addVariableFilter} overloads for m=4..6 work
 * correctly via the DSL {@code filter(v1..vm, predicate)} call chain.
 * m=2 and m=3 are already covered in {@code RuleBuilderTest}.
 */
public class VariableFilterExtTest {

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
                DataSource.of(new Transaction("TXN1", 200.0), new Transaction("TXN2", 15.0)),
                DataSource.of());
    }

    /**
     * m=4: filter on persons × accounts × orders × products.
     * Passing condition: Alice + ACC1 + ORD1 + PRD1 (all premium values) = 1 result.
     */
    @Test
    public void filter_four_variables() {
        Variable<Person> vP = new Variable<>();
        Variable<Account> vA = new Variable<>();
        Variable<Order> vO = new Variable<>();
        Variable<Product> vPr = new Variable<>();

        var rule = builder.from(ctx -> ctx.persons())
                .var(vP)
                .join(ctx -> ctx.accounts())
                .var(vA)
                .join(ctx -> ctx.orders())
                .var(vO)
                .join(ctx -> ctx.products())
                .var(vPr)
                .filter(vP, vA, vO, vPr,
                        (c, p, a, o, pr) -> p.age() >= 18 && a.balance() > 500 && o.amount() > 100 && pr.price() > 50)
                .fn((c, p, a, o, pr) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
        assertThat(((Account) rule.capturedFact(0, 1)).id()).isEqualTo("ACC1");
    }

    /**
     * m=5: filter on persons × accounts × orders × products × transactions.
     * Passing condition: Alice + ACC1 + ORD1 + PRD1 + TXN1 = 1 result.
     */
    @Test
    public void filter_five_variables() {
        Variable<Person> vP = new Variable<>();
        Variable<Account> vA = new Variable<>();
        Variable<Order> vO = new Variable<>();
        Variable<Product> vPr = new Variable<>();
        Variable<Transaction> vT = new Variable<>();

        var rule = builder.from(ctx -> ctx.persons())
                .var(vP)
                .join(ctx -> ctx.accounts())
                .var(vA)
                .join(ctx -> ctx.orders())
                .var(vO)
                .join(ctx -> ctx.products())
                .var(vPr)
                .join(ctx -> ctx.transactions())
                .var(vT)
                .filter(vP, vA, vO, vPr, vT,
                        (c, p, a, o, pr, t) -> p.age() >= 18 && a.balance() > 500
                                && o.amount() > 100 && pr.price() > 50 && t.amount() > 100)
                .fn((c, p, a, o, pr, t) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }

    /**
     * m=6: filter on persons × persons × persons × accounts × accounts × accounts.
     * Passing condition: all three persons are adults and all three accounts are premium.
     * Only (Alice, Alice, Alice, ACC1, ACC1, ACC1) satisfies all constraints = 1 result.
     * Confirms the m=6 filter chain executes without ClassCastException or other errors.
     */
    @Test
    public void filter_six_variables() {
        Variable<Person> vP1 = new Variable<>();
        Variable<Person> vP2 = new Variable<>();
        Variable<Person> vP3 = new Variable<>();
        Variable<Account> vA1 = new Variable<>();
        Variable<Account> vA2 = new Variable<>();
        Variable<Account> vA3 = new Variable<>();

        var rule = builder.from(ctx -> ctx.persons())
                .var(vP1)
                .join(ctx -> ctx.persons())
                .var(vP2)
                .join(ctx -> ctx.persons())
                .var(vP3)
                .join(ctx -> ctx.accounts())
                .var(vA1)
                .join(ctx -> ctx.accounts())
                .var(vA2)
                .join(ctx -> ctx.accounts())
                .var(vA3)
                .filter(vP1, vP2, vP3, vA1, vA2, vA3,
                        (c, p1, p2, p3, a1, a2, a3) -> p1.age() >= 18 && p2.age() >= 18
                                && p3.age() >= 18 && a1.balance() > 500 && a2.balance() > 500
                                && a3.balance() > 500)
                .fn((c, p1, p2, p3, a1, a2, a3) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
        assertThat(((Account) rule.capturedFact(0, 3)).id()).isEqualTo("ACC1");
    }

    /**
     * Verifies the number of generated addVariableFilter overloads on RuleDefinition:
     * one per m in 2..6 = 5 overloads total.
     */
    @Test
    public void ruleDefinition_has_five_addVariableFilter_overloads() {
        long count = Arrays.stream(RuleDefinition.class.getMethods())
                .filter(m -> m.getName().equals("addVariableFilter"))
                .count();
        assertThat(count).isEqualTo(5);
    }

    /**
     * Verifies that filter() overloads for m=2..6 are present on JoinNFirst classes.
     * We test on Join4First as a representative (needs 4 fact slots; mixin generates m=2..6).
     */
    @Test
    public void join4First_has_five_filter_variable_overloads() {
        long count = Arrays.stream(JoinBuilder.Join4First.class.getMethods())
                .filter(m -> m.getName().equals("filter"))
                .count();
        // All-facts overload + single-fact (filterLatest) + 5 variable overloads (m=2..6) = 7
        assertThat(count).isEqualTo(7);
    }

    /**
     * Calling filter with an unbound variable should throw with a descriptive message.
     */
    @Test
    public void filter_four_variables_unbound_throws() {
        Variable<Person> vP = new Variable<>();
        Variable<Account> vA = new Variable<>();
        Variable<Order> vO = new Variable<>();
        Variable<Product> vPr = Variable.of("$unbound");

        try {
            builder.from(ctx -> ctx.persons())
                    .var(vP)
                    .join(ctx -> ctx.accounts())
                    .var(vA)
                    .join(ctx -> ctx.orders())
                    .var(vO)
                    .join(ctx -> ctx.products())
                    // vPr NOT bound via .var() — should throw
                    .filter(vP, vA, vO, vPr,
                            (c, p, a, o, pr) -> true);
            org.junit.Assert.fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("$unbound");
        }
    }
}
