package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteMethod;
import io.quarkiverse.permuplate.PermuteReturn;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Mixin providing the six {@code extendsRule()} overloads shared by
 * {@code RuleBuilder} and {@code ParametersFirst}. Injected at generation time
 * via {@code @PermuteMixin} — not a generated or compiled class.
 */
class ExtendsRuleMixin<DS> extends AbstractRuleEntry<DS> {

    @Override
    protected String ruleName() { return ""; }

    @PermuteMethod(varName = "j", from = "2", to = "7", name = "extendsRule",
                   macros = {"prevAlpha=typeArgList(1,j-1,'alpha')"})
    @PermuteReturn(className = "JoinBuilder.Join${j-1}First",
                   typeArgs = "'Void, DS, ' + prevAlpha",
                   alwaysEmit = true)
    public <@PermuteTypeParam(varName = "k", from = "1", to = "${j-1}",
                               name = "${alpha(k)}") A>
            Object extendsRule(
            @PermuteDeclr(type = "RuleExtendsPoint.RuleExtendsPoint${j}<DS, ${prevAlpha}>")
            ExtendsPoint<DS> ep) {
        RuleDefinition<DS> child = new RuleDefinition<>(ruleName());
        ep.baseRd().copyInto(child);
        return cast(new JoinBuilder.Join1First<>(null, child));
    }
}
