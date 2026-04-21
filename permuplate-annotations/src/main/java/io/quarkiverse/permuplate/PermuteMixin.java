package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects methods from the listed mixin class(es) into this template class before
 * the Permuplate transform pipeline runs. Injected methods participate fully in
 * {@code @PermuteMethod}, {@code @PermuteReturn}, and all other transformers.
 *
 * <p>
 * The mixin class itself is not added to generated output — it is a source-only
 * helper. It must be in the same Maven source root as the template class.
 *
 * <p>
 * Only methods are injected (not fields or constructors). The mixin class name
 * is resolved by simple name within the same compiled source set.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {@code
 * &#64;Permute(...)
 * &#64;PermuteMixin(ExtendsRuleMixin.class)
 * public class RuleBuilderTemplate<DS> extends AbstractRuleEntry<DS> { ... }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteMixin {
    /** The mixin class(es) whose annotated methods should be injected into this template. */
    Class<?>[] value();
}
