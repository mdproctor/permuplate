package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

public class ConstructorCoherenceTest {

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
                        new Library("ScienceLib", java.util.List.of()),
                        new Library("ArtsLib", java.util.List.of())));
    }

    @Test
    public void join_chains_correctly_across_arities() {
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.persons())
                .fn((ctx, a, b) -> {
                });
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(4); // 2 persons × 2 persons
    }

    @Test
    public void extensionPoint_chains_correctly() {
        var ep = builder.from(ctx -> ctx.persons()).extensionPoint();
        var child = builder.extendsRule(ep).fn((ctx, a) -> {
        });
        child.run(ctx);
        assertThat(child.executionCount()).isEqualTo(2); // 2 persons
    }
}
