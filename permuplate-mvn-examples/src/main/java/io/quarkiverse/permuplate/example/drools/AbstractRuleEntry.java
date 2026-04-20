package io.quarkiverse.permuplate.example.drools;

/**
 * Common base for {@code RuleBuilder} and {@code ParametersFirst}.
 *
 * <p>
 * Provides the shared {@link #cast(Object)} helper and the {@link #ruleName()}
 * contract. The {@code extendsRule()} template method cannot be deduplicated here
 * because {@code @PermuteMethod} on a non-template base class is not processed by
 * the Permuplate inline generation pipeline — it remains in each template file as
 * the only intentional duplication.
 */
abstract class AbstractRuleEntry<DS> {

    @SuppressWarnings("unchecked")
    protected static <T> T cast(Object o) {
        return (T) o;
    }

    /** Returns the rule name to use when creating a child {@link RuleDefinition}. */
    protected abstract String ruleName();
}
