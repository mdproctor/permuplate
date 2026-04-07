package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class NamedRuleTest {

    private RuleBuilder<Ctx> builder;
    private Ctx ctx;

    record Params3(String p1, String p2, String p3) {
    }

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
    public void testNamedRuleFromShorthand() {
        // builder.rule("name").from() works identically to builder.from("name", source)
        var rule = builder.rule("findAdults")
                .from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }

    @Test
    public void testFnEndCompilesAsNoOp() {
        // .fn(...).end() compiles — end() is a no-op returning null (Void)
        builder.rule("r1")
                .from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                })
                .end(); // compiles; returns null — no assertion needed
    }

    @Test
    public void testTypedParams() {
        // Approach 1: .<P>params() — typed capture, P3 injected as fact[0]
        Params3 params = new Params3("Alice", "world", "foo");

        var rule = builder.rule("withTypedParams")
                .<Params3> params()
                .join(ctx -> ctx.persons())
                .filter((ctx, p, person) -> person.name().equals(p.p1()))
                .fn((ctx, p, person) -> {
                });

        rule.run(ctx, params);
        assertThat(rule.executionCount()).isEqualTo(1);
        Params3 captured = (Params3) rule.capturedFact(0, 0);
        assertThat(captured.p1()).isEqualTo("Alice");
        assertThat(((Person) rule.capturedFact(0, 1)).name()).isEqualTo("Alice");
    }

    @Test
    public void testListParams() {
        // Approach 2: .param() → ArgList, access positionally via get(int)
        ArgList argList = new ArgList().add("Alice").add(18);

        var rule = builder.rule("withListParams")
                .param("name", String.class)
                .param("minAge", int.class)
                .from(ctx -> ctx.persons())
                .filter((ctx, a, p) -> p.name().equals((String) a.get(0))
                        && p.age() >= (int) a.get(1))
                .fn((ctx, a, p) -> {
                });

        rule.run(ctx, argList);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 1)).name()).isEqualTo("Alice");
    }

    @Test
    public void testMapParams() {
        // Approach 3: .map().param() → ArgMap, access by name via get(String)
        ArgMap argMap = new ArgMap().put("name", "Alice").put("minAge", 18);

        var rule = builder.rule("withMapParams")
                .map()
                .param("name", String.class)
                .param("minAge", int.class)
                .from(ctx -> ctx.persons())
                .filter((ctx, a, p) -> p.name().equals((String) a.get("name"))
                        && p.age() >= (int) a.get("minAge"))
                .fn((ctx, a, p) -> {
                });

        rule.run(ctx, argMap);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 1)).name()).isEqualTo("Alice");
    }

    @Test
    public void testIndividualTypedParams() {
        // Approach 4: .<T>param("name") — typed per-param, accumulates into ArgList
        ArgList args = new ArgList().add("Alice").add(18);

        var rule = builder.rule("withIndividualParams")
                .<String> param("name")
                .<Integer> param("minAge")
                .from(ctx -> ctx.persons())
                .filter((ctx, a, p) -> p.name().equals((String) a.get(0)))
                .fn((ctx, a, p) -> {
                });

        rule.run(ctx, args);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 1)).name()).isEqualTo("Alice");
    }

    @Test
    public void testNamedRuleWithExtensionPoint() {
        // rule("name") integrates with extensionPoint()/extendsRule()
        var ep = builder.rule("base")
                .from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .extensionPoint();

        var rule = builder.rule("child")
                .extendsRule(ep)
                .join(ctx -> ctx.accounts())
                .filter((ctx, p, a) -> a.balance() > 500.0)
                .fn((ctx, p, a) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
        assertThat(((Account) rule.capturedFact(0, 1)).id()).isEqualTo("ACC1");
    }
}
