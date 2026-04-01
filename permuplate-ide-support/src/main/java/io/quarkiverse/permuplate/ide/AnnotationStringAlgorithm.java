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

    // =========================================================
    // matches()
    // =========================================================

    /**
     * Returns {@code true} if all static literals in {@code t} appear as substrings
     * within {@code className}, in declaration order.
     *
     * <p>
     * A template with no static literals (all variables) never matches.
     */
    public static boolean matches(AnnotationStringTemplate t, String className) {
        List<String> literals = t.staticLiterals();
        if (literals.isEmpty())
            return false;

        int searchFrom = 0;
        for (String literal : literals) {
            int pos = className.indexOf(literal, searchFrom);
            if (pos < 0)
                return false;
            searchFrom = pos + literal.length();
        }
        return true;
    }

    // =========================================================
    // computeRename()
    // =========================================================

    /**
     * Computes the updated annotation string after renaming a class from
     * {@code oldClassName} to {@code newClassName}.
     *
     * <ul>
     * <li>{@link RenameResult.Updated} — new template computed successfully</li>
     * <li>{@link RenameResult.NoMatch} — string doesn't reference this class,
     * or the literals didn't change (no update needed)</li>
     * <li>{@link RenameResult.NeedsDisambiguation} — references the class but
     * prefix/suffix also changed; IDE must show a dialog for each affected literal</li>
     * </ul>
     */
    public static RenameResult computeRename(AnnotationStringTemplate t,
            String oldClassName, String newClassName) {
        if (!matches(t, oldClassName))
            return new RenameResult.NoMatch();

        List<String> literals = t.staticLiterals();

        // Find each literal's position in oldClassName (matches() guarantees they exist in order)
        List<int[]> positions = new ArrayList<>();
        int searchFrom = 0;
        for (String literal : literals) {
            int pos = oldClassName.indexOf(literal, searchFrom);
            positions.add(new int[] { pos, pos + literal.length() });
            searchFrom = pos + literal.length();
        }

        boolean hasTrailingVar = hasTrailingVariable(t, literals.get(literals.size() - 1));

        List<String> newLiterals = new ArrayList<>();
        List<String> ambiguousLiterals = new ArrayList<>();
        int newSearchFrom = 0;

        for (int i = 0; i < literals.size(); i++) {
            String literal = literals.get(i);
            int[] pos = positions.get(i);
            boolean isLast = (i == literals.size() - 1);

            // old_prefix: text from previous literal's end (or 0) to this literal's start
            int prevEnd = (i == 0) ? 0 : positions.get(i - 1)[1];
            String oldPrefix = oldClassName.substring(prevEnd, pos[0]);

            // old_suffix: text from this literal's end to next literal's start (or end of oldClassName)
            int nextStart = isLast ? oldClassName.length() : positions.get(i + 1)[0];
            String oldSuffix = oldClassName.substring(pos[1], nextStart);

            // Strip old_prefix from the current position in newClassName
            String remaining = newClassName.substring(newSearchFrom);
            if (!oldPrefix.isEmpty() && !remaining.startsWith(oldPrefix)) {
                ambiguousLiterals.add(literal);
                newLiterals.add(null);
                continue;
            }
            String afterPrefix = remaining.substring(oldPrefix.length());

            String newLiteral;
            if (isLast) {
                // Last literal: strip old_suffix from the end of afterPrefix
                if (oldSuffix.isEmpty()) {
                    if (hasTrailingVar) {
                        // Trailing variable captures the number — strip trailing digits
                        newLiteral = stripTrailingDigits(afterPrefix);
                    } else {
                        newLiteral = afterPrefix;
                    }
                } else if (afterPrefix.endsWith(oldSuffix)) {
                    newLiteral = afterPrefix.substring(0, afterPrefix.length() - oldSuffix.length());
                } else if (hasTrailingVar && isNumeric(oldSuffix)) {
                    // Suffix is numeric and captured by trailing variable — strip trailing digits
                    newLiteral = stripTrailingDigits(afterPrefix);
                } else {
                    ambiguousLiterals.add(literal);
                    newLiterals.add(null);
                    continue;
                }
                newLiterals.add(newLiteral);
                newSearchFrom += oldPrefix.length() + newLiteral.length();
            } else {
                // Intermediate literal: old_suffix is the gap to the next literal.
                // Find it in afterPrefix to delimit the new literal.
                int suffixIdx = afterPrefix.indexOf(oldSuffix);
                if (suffixIdx < 0) {
                    ambiguousLiterals.add(literal);
                    newLiterals.add(null);
                    continue;
                }
                newLiteral = afterPrefix.substring(0, suffixIdx);
                newLiterals.add(newLiteral);
                // Advance past the prefix and the new literal only — NOT past the separator.
                // The separator (old_suffix) becomes the old_prefix for the next literal, which
                // will strip it from newClassName in the next iteration.
                newSearchFrom += oldPrefix.length() + newLiteral.length();
            }
        }

        if (!ambiguousLiterals.isEmpty()) {
            return new RenameResult.NeedsDisambiguation(List.copyOf(ambiguousLiterals));
        }

        // Check if anything actually changed
        boolean anyChanged = false;
        for (int i = 0; i < literals.size(); i++) {
            if (!literals.get(i).equals(newLiterals.get(i))) {
                anyChanged = true;
                break;
            }
        }
        if (!anyChanged)
            return new RenameResult.NoMatch();

        // Rebuild template string by replacing each changed literal (first occurrence only)
        String newTemplate = t.toLiteral();
        for (int i = 0; i < literals.size(); i++) {
            String oldLit = literals.get(i);
            String newLit = newLiterals.get(i);
            if (!oldLit.equals(newLit)) {
                newTemplate = newTemplate.replaceFirst(
                        Pattern.quote(oldLit), Matcher.quoteReplacement(newLit));
            }
        }
        return new RenameResult.Updated(newTemplate);
    }

    /**
     * Returns {@code true} if there is a variable part anywhere after the last literal
     * in the template's parts list.
     */
    private static boolean hasTrailingVariable(AnnotationStringTemplate t, String lastLiteral) {
        boolean seenLastLiteral = false;
        for (AnnotationStringPart p : t.parts()) {
            if (!p.isVariable() && p.text().equals(lastLiteral)) {
                seenLastLiteral = true;
            }
            if (seenLastLiteral && p.isVariable()) {
                return true;
            }
        }
        return false;
    }

    /** Returns {@code true} if every character in {@code s} is a decimal digit. */
    private static boolean isNumeric(String s) {
        if (s.isEmpty())
            return false;
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c))
                return false;
        }
        return true;
    }

    /** Removes all trailing decimal digits from {@code s}. */
    private static String stripTrailingDigits(String s) {
        int j = s.length() - 1;
        while (j >= 0 && Character.isDigit(s.charAt(j))) {
            j--;
        }
        return s.substring(0, j + 1);
    }

    // =========================================================
    // validate()
    // =========================================================

    /**
     * Validates an annotation string template against a target name (class name,
     * field name, parameter name, etc.) after expanding string constants.
     *
     * <p>
     * Rules checked (in order, R2 short-circuits R3):
     * <ul>
     * <li><b>R4:</b> no static literal after expansion → NO_ANCHOR error</li>
     * <li><b>R2:</b> each literal must appear as a substring in targetName in order;
     * first mismatch short-circuits and skips R3</li>
     * <li><b>R3:</b> adjacent variable groups covering an empty region are orphan</li>
     * </ul>
     *
     * <p>
     * <b>R1 (no variables in className) is NOT checked here.</b>
     * It is enforced by the processor for {@code @Permute.className} only.
     *
     * @param t the already-expanded annotation string template
     * @param targetName the actual class/field/param name to validate against
     * @return empty list if valid; otherwise one or more errors
     */
    public static List<ValidationError> validate(AnnotationStringTemplate t,
            String targetName) {
        List<String> literals = t.staticLiterals();

        // R4: no anchor
        if (literals.isEmpty()) {
            return List.of(ValidationError.noAnchor(
                    "add a literal portion or define the variable in @Permute strings"));
        }

        // R2: find each literal in order — first failure short-circuits R3
        List<int[]> positions = new ArrayList<>();
        int searchFrom = 0;
        for (String literal : literals) {
            int pos = targetName.indexOf(literal, searchFrom);
            if (pos < 0) {
                return List.of(ValidationError.unmatchedLiteral(literal, targetName));
            }
            positions.add(new int[] { pos, pos + literal.length() });
            searchFrom = pos + literal.length();
        }

        // R3: check orphan variables
        List<ValidationError> errors = new ArrayList<>();
        checkOrphans(t, targetName, literals, positions, errors);
        return errors;
    }

    private static void checkOrphans(AnnotationStringTemplate t, String targetName,
            List<String> literals, List<int[]> positions, List<ValidationError> errors) {
        // Walk the parts list, collecting variable groups between literals.
        // Each group collectively covers one text region in the target name.
        List<AnnotationStringPart> parts = t.parts();
        int literalIdx = 0;
        List<String> currentGroup = new ArrayList<>();

        for (AnnotationStringPart part : parts) {
            if (part.isVariable()) {
                currentGroup.add(part.text());
            } else {
                String literal = part.text();
                if (literal.isEmpty())
                    continue; // empty filler from parse()

                // Flush current group — they cover the region BEFORE this literal
                if (!currentGroup.isEmpty()) {
                    int litStart = positions.get(literalIdx)[0];
                    int prevEnd = (literalIdx == 0) ? 0 : positions.get(literalIdx - 1)[1];
                    String region = targetName.substring(prevEnd, litStart);
                    if (region.isEmpty()) {
                        for (String varName : currentGroup) {
                            errors.add(ValidationError.orphanVariable(varName, literal, false));
                        }
                    }
                    currentGroup.clear();
                }
                literalIdx++;
            }
        }

        // Final group: variables AFTER the last literal
        if (!currentGroup.isEmpty() && !literals.isEmpty()) {
            int lastEnd = positions.get(positions.size() - 1)[1];
            String region = targetName.substring(lastEnd);
            if (region.isEmpty()) {
                String lastLiteral = literals.get(literals.size() - 1);
                for (String varName : currentGroup) {
                    errors.add(ValidationError.orphanVariable(varName, lastLiteral, true));
                }
            }
        }
    }
}
