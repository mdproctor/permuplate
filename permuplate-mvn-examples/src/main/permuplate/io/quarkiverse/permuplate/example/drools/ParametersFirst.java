package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteMethod;
import io.quarkiverse.permuplate.PermuteReturn;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Entry point returned by RuleBuilder.rule("name"). Supports four param styles
 * plus from() to skip params entirely, matching vol2's ParametersFirst API.
 * Template class ({@code ParametersFirstTemplate}) generates {@code ParametersFirst}.
 */
@Permute(varName = "i", from = "1", to = "1", className = "ParametersFirst",
         inline = true, keepTemplate = false)
public class ParametersFirstTemplate<DS> extends AbstractRuleEntry<DS> {
    private final String name;

    public ParametersFirstTemplate(String name) {
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

    @PermuteMethod(varName = "j", from = "2", to = "7", name = "extendsRule")
    @PermuteReturn(className = "JoinBuilder.Join${j-1}First",
                   typeArgs = "'Void, DS, ' + typeArgList(1, j-1, 'alpha')",
                   alwaysEmit = true)
    public <@PermuteTypeParam(varName = "k", from = "1", to = "${j-1}", name = "${alpha(k)}") A>
            Object extendsRule(
            @PermuteDeclr(type = "RuleExtendsPoint.RuleExtendsPoint${j}<DS, ${typeArgList(1, j-1, 'alpha')}>")
            ExtendsPoint<DS> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(ruleName());
        ep.baseRd().copyInto(child);
        return cast(new JoinBuilder.@PermuteDeclr(type = "JoinBuilder.Join${j-1}First") Join1First<>(null, child));
    }
}
