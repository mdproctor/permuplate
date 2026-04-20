package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data representation of a {@code @Permute} annotation, usable without javac's
 * annotation processing infrastructure. Both the APT processor (which reads from
 * {@code javax.lang.model} elements) and the Maven plugin (which reads from
 * JavaParser AST nodes) convert their native representations into this class
 * before calling the shared transformation engine.
 *
 * <h2>External property injection</h2>
 *
 * <p>
 * {@code from} and {@code to} are JEXL expression strings evaluated against a
 * <em>base context</em> built from external properties plus {@code strings} constants.
 * External properties are resolved by stripping the {@code permuplate.} prefix and
 * are provided by the caller:
 *
 * <ul>
 * <li><b>APT mode</b> — annotation processor options ({@code -Apermuplate.max=10})
 * take priority, with system properties ({@code -Dpermuplate.max=10}) as fallback.
 * Build the external map in the APT processor via
 * {@link #buildExternalProperties(Map, boolean)}.
 * <li><b>Maven plugin mode</b> — only system properties ({@code -Dpermuplate.max=10})
 * are available. Pass {@code null} for APT options.
 * </ul>
 *
 * <p>
 * Resolution order for named constants (later overrides earlier):
 * <ol>
 * <li>System properties with {@code permuplate.} prefix
 * <li>APT options with {@code permuplate.} prefix (APT mode only)
 * <li>{@code strings} constants defined in the annotation
 * </ol>
 */
public final class PermuteConfig {

    public final String varName;
    /** JEXL expression string for the lower bound (e.g. {@code "3"} or {@code "${start}"}). */
    public final String from;
    /** JEXL expression string for the upper bound (e.g. {@code "10"} or {@code "${max}"}). */
    public final String to;
    /** String values to iterate over instead of an integer range. Empty when from/to is used. */
    public final String[] values;
    public final String className;
    public final String[] strings;
    /**
     * Named JEXL expression macros in {@code "name=jexlExpression"} format.
     * Evaluated after all loop variables are bound; later macros may reference earlier ones.
     */
    public final String[] macros;
    public final PermuteVarConfig[] extraVars;
    public final boolean inline;
    public final boolean keepTemplate;

    public PermuteConfig(String varName, String from, String to, String className,
            String[] strings, PermuteVarConfig[] extraVars,
            boolean inline, boolean keepTemplate) {
        this(varName, from, to, null, className, strings, null, extraVars, inline, keepTemplate);
    }

    public PermuteConfig(String varName, String from, String to, String[] values, String className,
            String[] strings, PermuteVarConfig[] extraVars,
            boolean inline, boolean keepTemplate) {
        this(varName, from, to, values, className, strings, null, extraVars, inline, keepTemplate);
    }

    public PermuteConfig(String varName, String from, String to, String[] values, String className,
            String[] strings, String[] macros, PermuteVarConfig[] extraVars,
            boolean inline, boolean keepTemplate) {
        this.varName = varName;
        this.from = from;
        this.to = to;
        this.values = values == null ? new String[0] : values.clone();
        this.className = className;
        this.strings = strings != null ? strings : new String[0];
        this.macros = macros != null ? macros : new String[0];
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
                permute.varName(), permute.from(), permute.to(), permute.values(),
                permute.className(), permute.strings(), permute.macros(), extraVars,
                permute.inline(), permute.keepTemplate());
    }

    /**
     * Builds the external properties map from APT options and/or system properties.
     * All entries whose key starts with {@code permuplate.} are included, with the
     * prefix stripped. APT options (if provided) override system properties for the
     * same stripped key.
     *
     * <p>
     * <b>APT mode:</b> pass {@code processingEnv.getOptions()} as {@code aptOptions}.
     * <b>Maven plugin mode:</b> pass {@code null} for {@code aptOptions} — only
     * system properties are used.
     *
     * @param aptOptions annotation processor options (may be {@code null})
     * @param includeApt {@code true} in APT mode, {@code false} in Maven plugin mode
     * @return map of stripped-key → value, ready to pass to
     *         {@link #buildAllCombinations(PermuteConfig, Map)}
     */
    public static Map<String, Object> buildExternalProperties(
            Map<String, String> aptOptions, boolean includeApt) {
        Map<String, Object> props = new LinkedHashMap<>();
        // 1. System properties (lowest priority)
        System.getProperties().forEach((k, v) -> {
            String key = k.toString();
            if (key.startsWith("permuplate."))
                props.put(key.substring("permuplate.".length()), v.toString());
        });
        // 2. APT options override system properties (APT mode only)
        if (includeApt && aptOptions != null) {
            aptOptions.forEach((k, v) -> {
                if (k.startsWith("permuplate."))
                    props.put(k.substring("permuplate.".length()), v);
            });
        }
        return props;
    }

    /**
     * Builds the base variable map used to evaluate {@code from}/{@code to} expressions:
     * external properties merged with the annotation's {@code strings} constants
     * (strings override external properties for the same key).
     *
     * <p>
     * This is the same map used internally by {@link #buildAllCombinations}, exposed
     * here so callers can construct an {@link EvaluationContext} to evaluate
     * {@code from}/{@code to} independently for validation.
     */
    public static Map<String, Object> buildBaseVars(PermuteConfig config, Map<String, Object> externalProps) {
        Map<String, Object> base = new LinkedHashMap<>(externalProps);
        for (String entry : config.strings) {
            int sep = entry.indexOf('=');
            if (sep >= 0)
                base.put(entry.substring(0, sep).trim(), entry.substring(sep + 1));
        }
        return base;
    }

    /**
     * Builds the full cross-product of variable bindings using the provided external
     * properties as the base context for evaluating {@code from} and {@code to}.
     *
     * <p>
     * The {@code strings} constants from the annotation are merged into the evaluation
     * context and override any external property with the same stripped key. This is
     * the standard resolution order: system props → APT options → annotation strings.
     *
     * @param config the parsed {@code @Permute} annotation
     * @param externalProps external properties built by
     *        {@link #buildExternalProperties(Map, boolean)};
     *        use {@link Collections#emptyMap()} if none
     */
    public static List<Map<String, Object>> buildAllCombinations(
            PermuteConfig config, Map<String, Object> externalProps) {
        // Build the base context: external props + annotation strings (strings win)
        Map<String, Object> baseVars = new LinkedHashMap<>(externalProps);
        for (String entry : config.strings) {
            int sep = entry.indexOf('=');
            if (sep >= 0)
                baseVars.put(entry.substring(0, sep).trim(), entry.substring(sep + 1));
        }
        // Start with the primary variable's range (string-set or integer)
        List<Map<String, Object>> result = new ArrayList<>();
        if (config.values.length > 0) {
            // String-set path: bind varName to each string value (no JEXL needed)
            for (String value : config.values) {
                Map<String, Object> vars = new HashMap<>(baseVars);
                vars.put(config.varName, value);
                result.add(vars);
            }
        } else {
            // Integer path: evaluate from/to as JEXL and iterate
            if (config.from == null || config.from.isEmpty() || config.to == null || config.to.isEmpty()) {
                throw new IllegalArgumentException(
                        "@Permute without 'values' must specify non-empty 'from' and 'to' — " +
                                "varName='" + config.varName + "', className='" + config.className + "'");
            }
            EvaluationContext baseCtx = new EvaluationContext(baseVars);
            int fromVal = baseCtx.evaluateInt(config.from);
            int toVal = baseCtx.evaluateInt(config.to);
            for (int i = fromVal; i <= toVal; i++) {
                Map<String, Object> vars = new HashMap<>(baseVars);
                vars.put(config.varName, i);
                result.add(vars);
            }
        }

        // Expand by each extraVar (cross-product — each extraVar can be integer or string-set)
        for (PermuteVarConfig extra : config.extraVars) {
            List<Map<String, Object>> expanded = new ArrayList<>();
            for (Map<String, Object> base : result) {
                if (extra.values.length > 0) {
                    for (String value : extra.values) {
                        Map<String, Object> copy = new HashMap<>(base);
                        copy.put(extra.varName, value);
                        expanded.add(copy);
                    }
                } else {
                    EvaluationContext innerCtx = new EvaluationContext(base);
                    int extraFrom = innerCtx.evaluateInt(extra.from);
                    int extraTo = innerCtx.evaluateInt(extra.to);
                    for (int v = extraFrom; v <= extraTo; v++) {
                        Map<String, Object> copy = new HashMap<>(base);
                        copy.put(extra.varName, v);
                        expanded.add(copy);
                    }
                }
            }
            result = expanded;
        }

        // Apply macros: evaluate each "name=jexlExpr" in declaration order per combination.
        // Later macros may reference earlier ones since vars is mutated in place.
        if (config.macros.length > 0) {
            for (Map<String, Object> vars : result) {
                for (String macro : config.macros) {
                    int eq = macro.indexOf('=');
                    if (eq < 0)
                        continue; // malformed — skip
                    String name = macro.substring(0, eq).trim();
                    String expr = macro.substring(eq + 1).trim();
                    try {
                        // Wrap bare expressions in ${} so EvaluationContext handles them;
                        // if already contains ${}, evaluate as-is (it's an interpolated string).
                        String template = expr.contains("${") ? expr : "${" + expr + "}";
                        Object value = new EvaluationContext(vars).evaluate(template);
                        vars.put(name, value);
                    } catch (Exception ignored) {
                        // Malformed or unevaluatable macro — skip silently
                    }
                }
            }
        }

        return result;
    }

    /**
     * Convenience overload with no external properties.
     * Equivalent to {@code buildAllCombinations(config, Collections.emptyMap())}.
     */
    public static List<Map<String, Object>> buildAllCombinations(PermuteConfig config) {
        return buildAllCombinations(config, Collections.emptyMap());
    }
}
