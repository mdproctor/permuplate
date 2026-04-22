package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class ConstructorCoherenceTest extends DroolsDslTestBase {

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
