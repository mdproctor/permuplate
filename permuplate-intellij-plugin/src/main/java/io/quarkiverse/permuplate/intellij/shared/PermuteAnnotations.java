package io.quarkiverse.permuplate.intellij.shared;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared registry of all Permuplate annotation FQNs and JEXL-bearing attribute names.
 * Used by both the rename pipeline (AnnotationStringRenameProcessor) and the JEXL
 * language injection feature (JexlLanguageInjector).
 */
public final class PermuteAnnotations {
    private PermuteAnnotations() {}

    /** FQNs of all Permuplate annotations — used by both rename and JEXL injection. */
    public static final Set<String> ALL_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.Permute",
            "io.quarkiverse.permuplate.PermuteVar",
            "io.quarkiverse.permuplate.PermuteDeclr",
            "io.quarkiverse.permuplate.PermuteParam",
            "io.quarkiverse.permuplate.PermuteTypeParam",
            "io.quarkiverse.permuplate.PermuteMethod",
            "io.quarkiverse.permuplate.PermuteValue",
            "io.quarkiverse.permuplate.PermuteStatements",
            "io.quarkiverse.permuplate.PermuteCase",
            "io.quarkiverse.permuplate.PermuteSwitchArm",
            "io.quarkiverse.permuplate.PermuteImport",
            "io.quarkiverse.permuplate.PermuteReturn",
            "io.quarkiverse.permuplate.PermuteReturns",
            "io.quarkiverse.permuplate.PermuteSelf",
            "io.quarkiverse.permuplate.PermuteExtends",
            "io.quarkiverse.permuplate.PermuteExtendsChain",
            "io.quarkiverse.permuplate.PermuteFilter",
            "io.quarkiverse.permuplate.PermuteMacros",
            "io.quarkiverse.permuplate.PermuteAnnotation",
            "io.quarkiverse.permuplate.PermuteThrows",
            "io.quarkiverse.permuplate.PermuteSource",
            "io.quarkiverse.permuplate.PermuteDelegate",
            "io.quarkiverse.permuplate.PermuteEnumConst",
            "io.quarkiverse.permuplate.PermuteDefaultReturn",
            "io.quarkiverse.permuplate.PermuteBody",
            "io.quarkiverse.permuplate.PermuteBodies",
            "io.quarkiverse.permuplate.PermuteBodyFragment",
            "io.quarkiverse.permuplate.PermuteMixin",
            "io.quarkiverse.permuplate.PermuteSealedFamily"
    );

    /**
     * Annotation attribute names that may carry JEXL ${...} expressions.
     * The "value" attribute is included for @PermuteAnnotation and @PermuteThrows
     * which use it as their primary JEXL-evaluated string.
     */
    public static final Set<String> JEXL_BEARING_ATTRIBUTES = Set.of(
            "from", "to", "className", "name", "type", "when",
            "pattern", "body", "macros", "typeArgs", "value"
    );

    // Pre-computed simple names for O(1) lookup in isPermuteAnnotation()
    private static final Set<String> SIMPLE_NAMES = ALL_ANNOTATION_FQNS.stream()
            .map(f -> f.substring(f.lastIndexOf('.') + 1))
            .collect(Collectors.toUnmodifiableSet());

    /** Returns true if {@code fqn} is a Permuplate annotation FQN or simple name. */
    public static boolean isPermuteAnnotation(String fqn) {
        if (fqn == null) return false;
        return ALL_ANNOTATION_FQNS.contains(fqn) || SIMPLE_NAMES.contains(fqn);
    }
}
