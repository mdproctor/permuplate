package io.quarkiverse.permuplate.example.drools;

/**
 * Common interface for all {@code RuleExtendsPointN} classes, allowing a single
 * {@code extendsRule()} template method to accept any arity extension point via
 * {@code @PermuteDeclr} rather than requiring six separate typed overloads.
 */
interface ExtendsPoint<DS> {
    RuleDefinition<DS> baseRd();
}
