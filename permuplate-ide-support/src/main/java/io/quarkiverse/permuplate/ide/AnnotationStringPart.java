package io.quarkiverse.permuplate.ide;

/**
 * One segment of a parsed annotation string. Either a variable placeholder
 * ({@code isVariable=true}, text is the variable name e.g. {@code "i"}) or a
 * static literal ({@code isVariable=false}, text is the literal text e.g.
 * {@code "Callable"}).
 */
public record AnnotationStringPart(boolean isVariable, String text) {

    /** Factory for a variable part, e.g. from {@code ${i}}. */
    public static AnnotationStringPart variable(String name) {
        return new AnnotationStringPart(true, name);
    }

    /** Factory for a static literal part. */
    public static AnnotationStringPart literal(String text) {
        return new AnnotationStringPart(false, text);
    }
}
