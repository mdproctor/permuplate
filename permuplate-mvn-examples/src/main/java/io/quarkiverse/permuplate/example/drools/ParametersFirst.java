package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Entry point returned by RuleBuilder.rule("name"). Supports four param styles
 * plus from() to skip params entirely, matching vol2's ParametersFirst API.
 *
 * Approach 1 — Typed: .<P3>params() — P3 varargs type-capture, P3 flows as first typed fact
 * Approach 2 — Individual: .param("name", String.class).param("age", int.class) → ArgList
 * Approach 3 — Map: .map().param("name", String.class) → ArgMap
 * Approach 4 — Typed individual: .<String>param("name").<Integer>param("age") → ArgList
 * No params: .from(source) — skip params, start join chain directly
 */
public class ParametersFirst<DS> {
    private final String name;

    public ParametersFirst(String name) {
        this.name = name;
    }

    /**
     * Typed params via varargs type-capture. P becomes the first fact type.
     * At run time: rule.run(ctx, new P(...)) injects the P instance as fact[0].
     * addParamsFact() ensures filter-trim logic accounts for the params slot.
     */
    @SuppressWarnings({ "unchecked", "varargs" })
    public <P> JoinBuilder.Join1First<Void, DS, P> params(P... cls) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new JoinBuilder.Join1First<>(null, rd);
    }

    /**
     * Individual typed param — varargs type capture per param, accumulates into ArgList.
     * Chain more .param() calls on returned ParametersSecond, then call .from().
     */
    @SuppressWarnings({ "unchecked", "varargs" })
    public <T> ParametersSecond<DS, ArgList> param(String paramName, T... cls) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new ParametersSecond<>(name, rd);
    }

    /**
     * Explicit list-based params. Chain .param() calls on returned ParametersSecond.
     * Access positionally in filters: (ctx, a, b) -> ((String) a.get(0))
     */
    public ParametersSecond<DS, ArgList> list() {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new ParametersSecond<>(name, rd);
    }

    /**
     * Map-based params. Chain .param() calls on returned ParametersSecond.
     * Access by name in filters: (ctx, a, b) -> ((String) a.get("name"))
     */
    public ParametersSecond<DS, ArgMap> map() {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new ParametersSecond<>(name, rd);
    }

    /**
     * Skip params — start the join chain directly. Uses the rule name in RuleDefinition.
     */
    public <A> JoinBuilder.Join1First<Void, DS, A> from(
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addSource(firstSource);
        return new JoinBuilder.Join1First<>(null, rd);
    }

    /** Zero-arg action — rule fires with only ctx, no facts or params. */
    public RuleResult<DS> ifn(Runnable action) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.setAction((java.util.function.Consumer<Object>) ctx -> action.run());
        return new RuleResult<>(rd);
    }

    // extendsRule() — all 6 overloads, one per base arity (RuleExtendsPoint2..7)

    public <A> JoinBuilder.Join1First<Void, DS, A> extendsRule(RuleExtendsPoint.RuleExtendsPoint2<DS, A> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join1First<>(null, child);
    }

    public <A, B> JoinBuilder.Join2First<Void, DS, A, B> extendsRule(RuleExtendsPoint.RuleExtendsPoint3<DS, A, B> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join2First<>(null, child);
    }

    public <A, B, C> JoinBuilder.Join3First<Void, DS, A, B, C> extendsRule(RuleExtendsPoint.RuleExtendsPoint4<DS, A, B, C> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join3First<>(null, child);
    }

    public <A, B, C, D> JoinBuilder.Join4First<Void, DS, A, B, C, D> extendsRule(
            RuleExtendsPoint.RuleExtendsPoint5<DS, A, B, C, D> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join4First<>(null, child);
    }

    public <A, B, C, D, E> JoinBuilder.Join5First<Void, DS, A, B, C, D, E> extendsRule(
            RuleExtendsPoint.RuleExtendsPoint6<DS, A, B, C, D, E> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join5First<>(null, child);
    }

    public <A, B, C, D, E, F> JoinBuilder.Join6First<Void, DS, A, B, C, D, E, F> extendsRule(
            RuleExtendsPoint.RuleExtendsPoint7<DS, A, B, C, D, E, F> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(name);
        ep.baseRd().copyInto(child);
        return new JoinBuilder.Join6First<>(null, child);
    }
}
