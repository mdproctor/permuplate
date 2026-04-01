package io.quarkiverse.permuplate.ide;

/**
 * A validation error found by {@link AnnotationStringAlgorithm#validate}.
 *
 * @param kind the type of error
 * @param varName the variable name involved (for {@code ORPHAN_VARIABLE}); empty otherwise
 * @param suggestion a human-readable fix suggestion
 */
public record ValidationError(ErrorKind kind, String varName, String suggestion) {

    public enum ErrorKind {
        /** String contains no {@code ${...}} variables — every permutation produces the same value. */
        NO_VARIABLES,
        /** A static literal does not appear as a substring of the target name. */
        UNMATCHED_LITERAL,
        /** A variable's corresponding text region in the target name is empty. */
        ORPHAN_VARIABLE,
        /** After expanding string constants, the string has no static literal anchor. */
        NO_ANCHOR
    }

    public static ValidationError noVariables(String suggestion) {
        return new ValidationError(ErrorKind.NO_VARIABLES, "", suggestion);
    }

    public static ValidationError unmatchedLiteral(String literal, String targetName) {
        return new ValidationError(ErrorKind.UNMATCHED_LITERAL, "",
                "literal \"" + literal + "\" does not appear in \"" + targetName + "\"");
    }

    public static ValidationError orphanVariable(String varName, String literal, boolean isSuffix) {
        String pos = isSuffix ? "after" : "before";
        return new ValidationError(ErrorKind.ORPHAN_VARIABLE, varName,
                "${" + varName + "} has no corresponding text " + pos + " \"" + literal
                        + "\" — remove it");
    }

    public static ValidationError noAnchor(String suggestion) {
        return new ValidationError(ErrorKind.NO_ANCHOR, "", suggestion);
    }
}
