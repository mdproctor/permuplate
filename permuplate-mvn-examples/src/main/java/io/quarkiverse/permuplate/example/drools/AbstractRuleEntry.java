package io.quarkiverse.permuplate.example.drools;

/**
 * Common base for {@code RuleBuilder} and {@code ParametersFirst}.
 *
 * <p>
 * Provides the shared {@link #cast(Object)} helper and the {@link #ruleName()}
 * contract. The {@code extendsRule()} overloads are shared via {@code ExtendsRuleMixin}
 * injected with {@code @PermuteMixin} — eliminating the duplication documented in ADR-0006.
 */
abstract class AbstractRuleEntry<DS> {

    @SuppressWarnings("unchecked")
    protected static <T> T cast(Object o) {
        return (T) o;
    }

    /** Returns the rule name to use when creating a child {@link RuleDefinition}. */
    protected abstract String ruleName();
}
