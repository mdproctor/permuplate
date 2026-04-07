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
        var ep = builder.from(ctx -> ctx.persons())
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
        var ep = builder.from(ctx -> ctx.persons())
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
        var ep = builder.from(ctx -> ctx.persons())
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
        var ep = builder.from(ctx -> ctx.persons())
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

    @Test
    public void testExtendsChaining() {
        // Extend-of-extend: ep1 captures 1 join; ep2 extends ep1 and captures 2 joins;
        // rule extends ep2 and adds a third join.
        // extensionPoint() is available on JoinNFirst because JoinNFirst extends JoinNSecond.
        var ep1 = builder.from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .extensionPoint();

        var ep2 = builder.extendsRule(ep1)
                .join(ctx -> ctx.accounts())
                .filter((ctx, p, a) -> a.balance() > 500.0)
                .extensionPoint();

        var rule = builder.extendsRule(ep2)
                .join(ctx -> ctx.orders())
                .filter((ctx, p, a, o) -> o.amount() > 100.0)
                .fn((ctx, p, a, o) -> {
                });

        rule.run(ctx);
        // Alice(30) × ACC1(1000) × ORD1(150) — only one combination passes all three layers
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
        assertThat(((Account) rule.capturedFact(0, 1)).id()).isEqualTo("ACC1");
        assertThat(((Order) rule.capturedFact(0, 2)).id()).isEqualTo("ORD1");
    }

    @Test
    public void testExtendsInheritsNotScope() {
        // Base has a not() scope. copyInto() must transfer negations to the child.
        // not().join(accounts).filter(balance > 500).end():
        // ACC1 has balance=1000 > 500, so the not() is globally UNSATISFIED → 0 persons survive.
        var ep = builder.from(ctx -> ctx.persons())
                .not()
                .join(ctx -> ctx.accounts())
                .filter((Object) (Predicate2<Ctx, Account>) (ctx, a) -> a.balance() > 500.0)
                .end()
                .extensionPoint();

        var rule = builder.extendsRule(ep)
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(0);
    }

    @Test
    public void testExtendsInheritsExistsScope() {
        // Base has an exists() scope. copyInto() must transfer existences to the child.
        // exists().join(orders).filter(amount > 100).end():
        // ORD1 has amount=150 > 100, so the exists() is globally SATISFIED → all 2 persons survive.
        // Child adds age filter → only Alice (age=30 >= 18) passes.
        var ep = builder.from(ctx -> ctx.persons())
                .exists()
                .join(ctx -> ctx.orders())
                .filter((Object) (Predicate2<Ctx, Order>) (ctx, o) -> o.amount() > 100.0)
                .end()
                .extensionPoint();

        var rule = builder.extendsRule(ep)
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }
}
