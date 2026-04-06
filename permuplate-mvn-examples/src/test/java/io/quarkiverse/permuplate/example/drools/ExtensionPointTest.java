package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class ExtensionPointTest {

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
                DataSource.of(
                        new Library("ScienceLib", List.of()),
                        new Library("ArtsLib", List.of())));
    }

    @Test
    public void testExtends1FilterOnly() {
        // Base: 1 join. Child: adds filter on inherited facts, no new joins.
        var ep = builder.from("persons", ctx -> ctx.persons())
                .extensionPoint();

        var rule = builder.extendsRule(ep)
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }

    @Test
    public void testExtends2AddsJoin() {
        // Base: 1 join + filter. Child: adds a second join.
        var ep = builder.from("persons", ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .extensionPoint();

        var rule = builder.extendsRule(ep)
                .join(ctx -> ctx.accounts())
                .filter((ctx, p, a) -> a.balance() > 500.0)
                .fn((ctx, p, a) -> {
                });

        rule.run(ctx);
        // Alice(30) × ACC1(1000) only
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
        assertThat(((Account) rule.capturedFact(0, 1)).id()).isEqualTo("ACC1");
    }

    @Test
    public void testExtends3TwoBaseJoins() {
        // Base: 2 joins + filter. Child: adds a third join.
        var ep = builder.from("persons", ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0)
                .extensionPoint();

        var rule = builder.extendsRule(ep)
                .join(ctx -> ctx.orders())
                .filter((ctx, p, a, o) -> o.amount() > 100.0)
                .fn((ctx, p, a, o) -> {
                });

        rule.run(ctx);
        // Alice(30) × ACC1(1000) × ORD1(150)
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Order) rule.capturedFact(0, 2)).id()).isEqualTo("ORD1");
    }

    @Test
    public void testExtends4FanOut() {
        // Two child rules from the same extension point — each gets an independent copy.
        var ep = builder.from("persons", ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .extensionPoint();

        var rule1 = builder.extendsRule(ep)
                .join(ctx -> ctx.accounts())
                .filter((ctx, p, a) -> a.balance() > 500.0)
                .fn((ctx, p, a) -> {
                });

        var rule2 = builder.extendsRule(ep)
                .join(ctx -> ctx.orders())
                .filter((ctx, p, o) -> o.amount() > 100.0)
                .fn((ctx, p, o) -> {
                });

        rule1.run(ctx);
        rule2.run(ctx);

        // rule1: Alice × ACC1
        assertThat(rule1.executionCount()).isEqualTo(1);
        assertThat(((Account) rule1.capturedFact(0, 1)).id()).isEqualTo("ACC1");

        // rule2: Alice × ORD1
        assertThat(rule2.executionCount()).isEqualTo(1);
        assertThat(((Order) rule2.capturedFact(0, 1)).id()).isEqualTo("ORD1");
    }
}
