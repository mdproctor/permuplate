package io.quarkiverse.permuplate.intellij.shared;

import java.util.Set;

public final class PermuteAnnotations {
    private PermuteAnnotations() {}

    public static final Set<String> ALL_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.Permute",
            "io.quarkiverse.permuplate.PermuteDeclr",
            "io.quarkiverse.permuplate.PermuteParam",
            "io.quarkiverse.permuplate.PermuteTypeParam",
            "io.quarkiverse.permuplate.PermuteMethod",
            "io.quarkiverse.permuplate.PermuteSource",
            "io.quarkiverse.permuplate.PermuteAnnotation",
            "io.quarkiverse.permuplate.PermuteThrows",
            "io.quarkiverse.permuplate.PermuteSwitchArm",
            "io.quarkiverse.permuplate.PermuteReturn",
            "io.quarkiverse.permuplate.PermuteDefaultReturn"
    );

    public static final Set<String> JEXL_BEARING_ATTRIBUTES = Set.of(
            "from", "to", "className", "name", "type", "when",
            "pattern", "body", "macros", "typeArgs", "value"
    );

    public static boolean isPermuteAnnotation(String fqn) {
        if (fqn == null) return false;
        return ALL_ANNOTATION_FQNS.contains(fqn)
                || ALL_ANNOTATION_FQNS.stream().anyMatch(f -> f.endsWith("." + fqn));
    }
}
