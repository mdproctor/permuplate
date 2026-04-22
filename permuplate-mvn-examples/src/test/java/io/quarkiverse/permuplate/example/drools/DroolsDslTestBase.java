package io.quarkiverse.permuplate.example.drools;

import java.util.List;

import org.junit.Before;

/**
 * Shared base class for Drools DSL sandbox tests.
 * Provides a standard {@link Ctx} with two of each domain object,
 * and a fresh {@link RuleBuilder} per test.
 */
public abstract class DroolsDslTestBase {

    protected RuleBuilder<Ctx> builder;
    protected Ctx ctx;

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
}
