package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

import io.quarkiverse.permuplate.PermuteMixin;

/**
 * Entry point returned by RuleBuilder.rule("name"). Supports four param styles
 * plus from() to skip params entirely, matching vol2's ParametersFirst API.
 * {@code @PermuteMixin} expands the six {@code extendsRule()} overloads from
 * a single template method in {@code ExtendsRuleMixin}.
 */
@PermuteMixin(ExtendsRuleMixin.class)
public class ParametersFirst<DS> extends AbstractRuleEntry<DS> {
    private final String name;

    public ParametersFirst(String name) {
        this.name = name;
    }

    @Override
    protected String ruleName() {
        return name;
    }

    @SuppressWarnings({ "unchecked", "varargs" })
    public <P> JoinBuilder.Join1First<Void, DS, P> params(P... cls) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new JoinBuilder.Join1First<>(null, rd);
    }

    @SuppressWarnings({ "unchecked", "varargs" })
    public <T> ParametersSecond<DS, ArgList> param(String paramName, T... cls) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new ParametersSecond<>(name, rd);
    }

    public ParametersSecond<DS, ArgList> list() {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new ParametersSecond<>(name, rd);
    }

    public ParametersSecond<DS, ArgMap> map() {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addParamsFact();
        return new ParametersSecond<>(name, rd);
    }

    public <A> JoinBuilder.Join1First<Void, DS, A> from(
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.addSource(firstSource);
        return new JoinBuilder.Join1First<>(null, rd);
    }

    public RuleResult<DS> ifn(Runnable action) {
        RuleDefinition<DS> rd = new RuleDefinition<>(name);
        rd.setAction((java.util.function.Consumer<Object>) ctx -> action.run());
        return new RuleResult<>(rd);
    }

}
