package io.quarkiverse.permuplate.ide;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A parsed annotation string template, consisting of alternating literal and
 * variable parts. For example {@code "${v1}Callable${v2}"} parses to:
 * {@code [Variable("v1"), Literal("Callable"), Variable("v2")]}.
 *
 * <p>
 * All static literals are anchors — they must appear as substrings within the
 * target class name (in declaration order) for the string to be considered a
 * reference to that class.
 */
public record AnnotationStringTemplate(List<AnnotationStringPart> parts) {

    /**
     * Returns the full template string reconstructed from its parts.
     * e.g. {@code "${v1}Callable${v2}"}.
     */
    public String toLiteral() {
        return parts.stream()
                .map(p -> p.isVariable() ? "${" + p.text() + "}" : p.text())
                .collect(Collectors.joining());
    }

    /**
     * Returns the non-empty static literal segments in declaration order.
     */
    public List<String> staticLiterals() {
        return parts.stream()
                .filter(p -> !p.isVariable() && !p.text().isEmpty())
                .map(AnnotationStringPart::text)
                .toList();
    }

    /** Returns {@code true} if the string contains no {@code ${...}} variables at all. */
    public boolean hasNoVariables() {
        return parts.stream().noneMatch(AnnotationStringPart::isVariable);
    }

    /** Returns {@code true} if the string has no non-empty static literal. */
    public boolean hasNoLiteral() {
        return staticLiterals().isEmpty();
    }
}
