package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data representation of a {@code @Permute} annotation, usable without javac's
 * annotation processing infrastructure. Both the APT processor (which reads from
 * {@code javax.lang.model} elements) and the Maven plugin (which reads from
 * JavaParser AST nodes) convert their native representations into this class
 * before calling the shared transformation engine.
 */
public final class PermuteConfig {

    public final String varName;
    public final int from;
    public final int to;
    public final String className;
    public final String[] strings;
    public final PermuteVarConfig[] extraVars;
    public final boolean inline;
    public final boolean keepTemplate;

    public PermuteConfig(String varName, int from, int to, String className,
            String[] strings, PermuteVarConfig[] extraVars,
            boolean inline, boolean keepTemplate) {
        this.varName = varName;
        this.from = from;
        this.to = to;
        this.className = className;
        this.strings = strings != null ? strings : new String[0];
        this.extraVars = extraVars != null ? extraVars : new PermuteVarConfig[0];
        this.inline = inline;
        this.keepTemplate = keepTemplate;
    }

    /** Constructs from the javac annotation proxy (APT path). */
    public static PermuteConfig from(io.quarkiverse.permuplate.Permute permute) {
        PermuteVarConfig[] extraVars = new PermuteVarConfig[permute.extraVars().length];
        for (int i = 0; i < permute.extraVars().length; i++) {
            extraVars[i] = PermuteVarConfig.from(permute.extraVars()[i]);
        }
        return new PermuteConfig(
                permute.varName(), permute.from(), permute.to(), permute.className(),
                permute.strings(), extraVars, permute.inline(), permute.keepTemplate());
    }

    /**
     * Builds the full cross-product of variable bindings across the primary variable
     * and all {@link PermuteConfig#extraVars}, merged with {@link PermuteConfig#strings} constants.
     *
     * <p>
     * The primary variable is the outermost loop; {@code extraVars} are inner loops in
     * declaration order. For primary i∈[2,3] and k∈[2,3], the result is:
     * [{i=2,k=2}, {i=2,k=3}, {i=3,k=2}, {i=3,k=3}].
     */
    public static List<Map<String, Object>> buildAllCombinations(PermuteConfig config) {
        // Start with the primary variable's range
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = config.from; i <= config.to; i++) {
            Map<String, Object> vars = new HashMap<>();
            vars.put(config.varName, i);
            result.add(vars);
        }

        // Expand by each extraVar (cross-product)
        for (PermuteVarConfig extra : config.extraVars) {
            List<Map<String, Object>> expanded = new ArrayList<>();
            for (Map<String, Object> base : result) {
                for (int v = extra.from; v <= extra.to; v++) {
                    Map<String, Object> copy = new HashMap<>(base);
                    copy.put(extra.varName, v);
                    expanded.add(copy);
                }
            }
            result = expanded;
        }

        // Merge string constants into every combination
        for (Map<String, Object> vars : result) {
            for (String entry : config.strings) {
                int sep = entry.indexOf('=');
                vars.put(entry.substring(0, sep).trim(), entry.substring(sep + 1));
            }
        }
        return result;
    }
}
