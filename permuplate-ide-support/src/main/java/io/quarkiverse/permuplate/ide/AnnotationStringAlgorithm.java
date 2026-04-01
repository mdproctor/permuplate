package io.quarkiverse.permuplate.ide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java algorithm for annotation string matching, rename computation,
 * and validation. No external dependencies.
 *
 * <p>
 * <strong>Java is the source of truth.</strong> The TypeScript port in
 * {@code permuplate-vscode/src/algorithm.ts} must be kept exactly in sync.
 * Any bug fix or behaviour change here must be ported to TypeScript in the
 * same commit with a matching Jest test. See CLAUDE.md.
 */
public class AnnotationStringAlgorithm {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    private AnnotationStringAlgorithm() {
    }

    // =========================================================
    // parse()
    // =========================================================

    /**
     * Parses an annotation string template into a sequence of literal and variable parts.
     *
     * <p>
     * {@code "${v1}Callable${v2}"} → {@code [Var("v1"), Lit("Callable"), Var("v2")]}
     */
    public static AnnotationStringTemplate parse(String template) {
        List<AnnotationStringPart> parts = new ArrayList<>();
        Matcher m = VARIABLE.matcher(template);
        int lastEnd = 0;
        while (m.find()) {
            parts.add(AnnotationStringPart.literal(template.substring(lastEnd, m.start())));
            parts.add(AnnotationStringPart.variable(m.group(1)));
            lastEnd = m.end();
        }
        parts.add(AnnotationStringPart.literal(template.substring(lastEnd)));
        return new AnnotationStringTemplate(parts);
    }

    // =========================================================
    // expandStringConstants()
    // =========================================================

    /**
     * Expands string constants from {@code @Permute strings} into the template.
     * Variable parts whose name exists in {@code constants} are replaced with
     * literal parts. Adjacent literal parts produced by expansion are merged.
     */
    public static AnnotationStringTemplate expandStringConstants(
            AnnotationStringTemplate t, Map<String, String> constants) {
        List<AnnotationStringPart> expanded = new ArrayList<>();
        for (AnnotationStringPart part : t.parts()) {
            if (part.isVariable() && constants.containsKey(part.text())) {
                expanded.add(AnnotationStringPart.literal(constants.get(part.text())));
            } else {
                expanded.add(part);
            }
        }
        // Merge adjacent literals
        List<AnnotationStringPart> merged = new ArrayList<>();
        for (AnnotationStringPart p : expanded) {
            if (!merged.isEmpty() && !merged.get(merged.size() - 1).isVariable() && !p.isVariable()) {
                String combined = merged.get(merged.size() - 1).text() + p.text();
                merged.set(merged.size() - 1, AnnotationStringPart.literal(combined));
            } else {
                merged.add(p);
            }
        }
        return new AnnotationStringTemplate(merged);
    }
}
