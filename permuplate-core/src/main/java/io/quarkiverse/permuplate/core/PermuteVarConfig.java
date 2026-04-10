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

    public PermuteVarConfig(String varName, String from, String to) {
        this.varName = varName;
        this.from = from;
        this.to = to;
    }

    /** Constructs from the javac annotation proxy (APT path). */
    public static PermuteVarConfig from(io.quarkiverse.permuplate.PermuteVar extra) {
        return new PermuteVarConfig(extra.varName(), extra.from(), extra.to());
    }
}
