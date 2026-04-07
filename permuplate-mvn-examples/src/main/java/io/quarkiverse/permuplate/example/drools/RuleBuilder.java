package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Entry point for the Drools RuleBuilder DSL approximation.
 *
 * <p>
 * {@code from()} creates the initial {@code Join1First<Void, DS, A>}. The {@code Void}
 * END type means no outer scope exists — {@code end()} on top-level chains returns null
 * and is never called. When nested scopes ({@code not()}, {@code exists()}) arrive in
 * Phase 3, they will capture the outer builder type as END and restore it via {@code end()}.
 *
 * <pre>{@code
 * RuleBuilder<Ctx> builder = new RuleBuilder<>();
 * RuleDefinition<Ctx> rule = builder.from("adults", ctx -> ctx.persons())
 *         .filter((ctx, a) -> a.age() >= 18)
 *         .fn((ctx, a) -> System.out.println(a.name()));
 * rule.run(ctx);
 * }</pre>
 */
public class RuleBuilder<DS> {

    /**
     * Starts building a rule with its first fact source.
     * Returns {@code Join1First<Void, DS, A>} — Void indicates no outer scope.
     */
    public <A> JoinBuilder.Join1First<Void, DS, A> from(String name,
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addSource(firstSource);
        return new JoinBuilder.Join1First<>(null, rd);
    }

    /**
     * Starts building a rule with its first fact source using a method reference.
     * Shorthand for {@code from("rule", source)} — preferred when a descriptive
     * name is not needed.
     *
     * <pre>{@code
     * builder.from(Ctx::persons)
     *         .filter((ctx, p) -> p.age() >= 18)
     *         .fn((ctx, p) -> System.out.println(p.name()));
     * }</pre>
     */
    public <A> JoinBuilder.Join1First<Void, DS, A> from(
            java.util.function.Function<DS, DataSource<A>> firstSource) {
        return from("rule", firstSource);
    }

    /**
     * Starts building a named rule. Returns ParametersFirst which supports all
     * four param styles plus from() to skip params.
     *
     * <pre>{@code
     * builder.rule("findAdults")
     *         .from(ctx -> ctx.persons())
     *         .filter((ctx, p) -> p.age() >= 18)
     *         .fn((ctx, p) -> {
     *         });
     * }</pre>
     */
    public ParametersFirst<DS> rule(String name) {
        return new ParametersFirst<>(name);
    }

    public <A> JoinBuilder.Join1First<Void, DS, A> extendsRule(RuleExtendsPoint.RuleExtendsPoint2<DS, A> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>("extends");
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join1First<>(null, child);
    }

    public <A, B> JoinBuilder.Join2First<Void, DS, A, B> extendsRule(RuleExtendsPoint.RuleExtendsPoint3<DS, A, B> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>("extends");
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join2First<>(null, child);
    }

    public <A, B, C> JoinBuilder.Join3First<Void, DS, A, B, C> extendsRule(RuleExtendsPoint.RuleExtendsPoint4<DS, A, B, C> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>("extends");
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join3First<>(null, child);
    }

    public <A, B, C, D> JoinBuilder.Join4First<Void, DS, A, B, C, D> extendsRule(
            RuleExtendsPoint.RuleExtendsPoint5<DS, A, B, C, D> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>("extends");
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join4First<>(null, child);
    }

    public <A, B, C, D, E> JoinBuilder.Join5First<Void, DS, A, B, C, D, E> extendsRule(
            RuleExtendsPoint.RuleExtendsPoint6<DS, A, B, C, D, E> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>("extends");
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join5First<>(null, child);
    }

    public <A, B, C, D, E, F> JoinBuilder.Join6First<Void, DS, A, B, C, D, E, F> extendsRule(
            RuleExtendsPoint.RuleExtendsPoint7<DS, A, B, C, D, E, F> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>("extends");
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join6First<>(null, child);
    }
}
