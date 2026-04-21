package io.quarkiverse.permuplate.example.drools;

import java.util.function.Function;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteMixin;

/**
 * Entry point for the Drools RuleBuilder DSL approximation.
 * Template class ({@code RuleBuilderTemplate}) generates {@code RuleBuilder} via
 * {@code @Permute(from="1",to="1")} so that {@code @PermuteMethod} can expand the
 * six {@code extendsRule()} overloads from a single template method.
 *
 * <pre>{@code
 * RuleBuilder<Ctx> builder = new RuleBuilder<>();
 * RuleDefinition<Ctx> rule = builder.from(ctx -> ctx.persons())
 *         .filter((ctx, a) -> a.age() >= 18)
 *         .fn((ctx, a) -> System.out.println(a.name()));
 * rule.run(ctx);
 * }</pre>
 */
@Permute(varName = "i", from = "1", to = "1", className = "RuleBuilder",
         inline = true, keepTemplate = false)
@PermuteMixin(ExtendsRuleMixin.class)
public class RuleBuilderTemplate<DS> extends AbstractRuleEntry<DS> {

    @Override
    protected String ruleName() {
        return "extends";
    }

    /**
     * Starts building a rule with its first fact source using a method reference.
     */
    public <A> JoinBuilder.Join1First<Void, DS, A> from(
            Function<DS, DataSource<A>> firstSource) {
        RuleDefinition<DS> rd = new RuleDefinition<>("rule");
        rd.addSource(firstSource);
        return new JoinBuilder.Join1First<>(null, rd);
    }

    /**
     * Starts building a named rule. Returns ParametersFirst which supports all
     * four param styles plus from() to skip params.
     */
    public ParametersFirst<DS> rule(String name) {
        return new ParametersFirst<>(name);
    }

}
