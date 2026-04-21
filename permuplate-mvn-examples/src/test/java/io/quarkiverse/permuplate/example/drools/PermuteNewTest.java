package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

/**
 * Validates that {@code @PermuteNew(className=...)} renames constructor types correctly.
 * The DSL chain still compiles and produces correct runtime results — the same outcome
 * as constructor-coherence inference but driven by the explicit annotation.
 */
public class PermuteNewTest {

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
    public void join_chain_compiles_and_runs_correctly() {
        // The join() method uses @PermuteNew (or coherence inference) to rename constructors.
        // Verify the generated chain works at runtime.
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .fn((ctx, p, a) -> {
                });
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(4); // 2 persons × 2 accounts
    }

    @Test
    public void three_way_join_chain_compiles_and_runs_correctly() {
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .fn((ctx, p, a, o) -> {
                });
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(8); // 2 × 2 × 2
    }
}
