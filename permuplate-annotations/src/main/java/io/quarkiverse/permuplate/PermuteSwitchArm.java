package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates Java 21+ arrow-switch pattern arms for the annotated method.
 *
 * <p>
 * The annotated method must contain exactly one {@code switch} statement or
 * switch expression. For each value of the inner loop variable, one arm of the form
 * {@code case <pattern> [when <guard>] -> { <body> }} is inserted before the
 * {@code default} arm.
 *
 * <p>
 * All attributes are JEXL expression strings evaluated in the inner loop context.
 * The outer permutation variable (e.g. {@code i}) is also available.
 *
 * <p>
 * Example — dispatch over a generated sealed hierarchy:
 *
 * <pre>{@code
 * @PermuteSwitchArm(varName = "k", from = "1", to = "${i}", pattern = "Shape${k} s", body = "yield s.area();")
 * public double area(Shape shape) {
 *     return switch (shape) {
 *         case Circle c -> c.radius() * c.radius() * Math.PI; // seed arm
 *         default -> throw new IllegalArgumentException(shape.toString());
 *     };
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteSwitchArm {

    /** Inner loop variable name (e.g. {@code "k"}). */
    String varName();

    /** Inclusive lower bound (JEXL expression, e.g. {@code "1"}). */
    String from();

    /**
     * Inclusive upper bound (JEXL expression, e.g. {@code "${i}"}).
     * When {@code from > to}, no arms are inserted (empty range).
     */
    String to();

    /**
     * JEXL template for the type pattern (e.g. {@code "Shape${k} s"}).
     * Produces the left side of {@code case <pattern> ->}.
     * May reference generated class names — rename propagation tracks this attribute.
     */
    String pattern();

    /**
     * JEXL template for the arm body — the right side of {@code ->}.
     * Use {@code yield expr;} for switch expressions, or plain statements for
     * switch statements. Block syntax ({@code { ... }}) is always valid.
     */
    String body();

    /**
     * Optional JEXL guard condition (e.g. {@code "${k} > 1"}).
     * When non-empty, generates {@code case <pattern> when <guard> -> <body>}.
     */
    String when() default "";
}
