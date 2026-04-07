package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

/**
 * Returned after param()/list()/map() on ParametersFirst. Allows chaining
 * additional param() calls then starting joins with from().
 *
 * B is either ArgList (positional access) or ArgMap (named access).
 * The params value (an ArgList or ArgMap instance) is passed at run time:
 * rule.run(ctx, new ArgList().add("Alice").add(18))
 */
public class ParametersSecond<DS, B> {
    private final String name;
    private final RuleDefinition<DS> rd;

    public ParametersSecond(String name, RuleDefinition<DS> rd) {
        this.name = name;
        this.rd = rd;
    }

    /**
     * Adds another named param declaration. Returns this for chaining.
     * The actual param value is provided by the caller at run time in the ArgList/ArgMap.
     */
    @SuppressWarnings({ "unchecked", "varargs" })
    public <T> ParametersSecond<DS, B> param(String paramName, T... cls) {
        return this;
    }

    /**
     * Starts the join chain. B (ArgList or ArgMap) is fact[0] at runtime;
     * the source's facts start at fact[1].
     */
    public <A> JoinBuilder.Join2First<Void, DS, B, A> from(
            Function<DS, DataSource<A>> firstSource) {
        rd.addSource(firstSource);
        return new JoinBuilder.Join2First<>(null, rd);
    }

    /**
     * Terminates with a consumer — params only, no joins.
     * BiConsumer.accept(DS, B) has 2 params; wrapConsumer sees buildArgs = [ctx, facts[0]].
     */
    public RuleResult<DS> fn(java.util.function.BiConsumer<DS, B> action) {
        rd.setAction(action);
        return new RuleResult<>(rd);
    }
}
