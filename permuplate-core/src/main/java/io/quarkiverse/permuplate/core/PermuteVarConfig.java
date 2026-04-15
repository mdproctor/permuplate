package io.quarkiverse.permuplate.core;

/**
 * Data representation of a {@code @PermuteVar} annotation.
 *
 * @see PermuteConfig
 */
public final class PermuteVarConfig {

    public final String varName;
    /** JEXL expression string for the lower bound (e.g. {@code "2"} or {@code "${start}"}). */
    public final String from;
    /** JEXL expression string for the upper bound (e.g. {@code "4"} or {@code "${i}"}). */
    public final String to;
    /** String values to iterate over instead of an integer range. Empty when from/to is used. */
    public final String[] values;

    public PermuteVarConfig(String varName, String from, String to) {
        this(varName, from, to, null);
    }

    public PermuteVarConfig(String varName, String from, String to, String[] values) {
        this.varName = varName;
        this.from = from;
        this.to = to;
        this.values = values == null ? new String[0] : values.clone();
    }

    /** Constructs from the javac annotation proxy (APT path). */
    public static PermuteVarConfig from(io.quarkiverse.permuplate.PermuteVar extra) {
        return new PermuteVarConfig(extra.varName(), extra.from(), extra.to(), extra.values());
    }
}
