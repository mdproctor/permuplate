package io.quarkiverse.permuplate.ide;

import java.util.List;

/**
 * Result of {@link AnnotationStringAlgorithm#computeRename}.
 *
 * <ul>
 * <li>{@link Updated} — the rename could be computed; {@code newTemplate} is
 * the updated annotation string.</li>
 * <li>{@link NoMatch} — the string does not reference the renamed class at all;
 * leave it unchanged.</li>
 * <li>{@link NeedsDisambiguation} — the string references the class but the
 * algorithm cannot determine the new literal(s) automatically because the
 * prefix/suffix also changed. The IDE must show a dialog asking the user
 * what each affected literal should become. {@code affectedLiterals} lists
 * the old literal values in declaration order.</li>
 * </ul>
 */
public sealed interface RenameResult {

    record Updated(String newTemplate) implements RenameResult {
    }

    record NoMatch() implements RenameResult {
    }

    record NeedsDisambiguation(List<String> affectedLiterals) implements RenameResult {
    }
}
