package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Expands a sentinel method parameter into a generated sequence, and automatically
 * rewrites call sites in the method body where the sentinel's name appears as an argument.
 *
 * <p>
 * Place this annotation on one or more sentinel parameters in the same method. Multiple
 * sentinels are expanded left-to-right in declaration order, each with its own inner
 * loop variable and range. Parameters not annotated with {@code @PermuteParam} are
 * preserved in their original positions around the expanded sequences.
 *
 * <p>
 * The inner variable {@code varName} is scoped to its own annotation and is not
 * visible to other annotations. The {@code from} and {@code to} strings are evaluated
 * as JEXL expressions and may reference the outer {@code @Permute} variable.
 *
 * <p>
 * <b>Call-site inference:</b> the sentinel's original name is registered as an anchor.
 * Every call expression in the method body where the anchor appears as an argument has
 * it replaced by the full generated argument sequence — no annotation is needed at the
 * call site. When multiple sentinels are present, each anchor is expanded in turn,
 * building up the final call-site argument list sequentially.
 *
 * <p>
 * <b>Prefix rule:</b> the static (non-{@code ${...}}) part of {@code name} must be a
 * prefix of the sentinel parameter's name. A mismatch is reported as a compile error.
 *
 * <p>
 * Single-sentinel example:
 *
 * <pre>{@code
 * public void join(
 *         @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1) {
 *     c2.call(o1, o2); // "o1" is the anchor → expands to full generated sequence
 * }
 * }</pre>
 *
 * <p>
 * Dual-sentinel example (two independent ranges in one method):
 *
 * <pre>
 * {@code
 * public void merge(
 *         &#64;PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "left${j}") Object left1,
 *         @PermuteParam(varName = "k", from = "1", to = "${i-1}", type = "Object", name = "right${k}") Object right1) {
 *     c2.call(left1, right1); // both anchors expanded in sequence
 * }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface PermuteParam {
    String varName();

    String from();

    String to();

    String type();

    String name();
}
