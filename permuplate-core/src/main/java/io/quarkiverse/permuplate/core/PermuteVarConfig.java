package io.quarkiverse.permuplate.core;

/**
 * Data representation of a {@code @PermuteVar} annotation.
 *
 * @see PermuteConfig
 */
public final class PermuteVarConfig {

    public final String varName;
    public final int from;
    public final int to;

    public PermuteVarConfig(String varName, int from, int to) {
        this.varName = varName;
        this.from = from;
        this.to = to;
    }

    /** Constructs from the javac annotation proxy (APT path). */
    public static PermuteVarConfig from(io.quarkiverse.permuplate.PermuteVar extra) {
        return new PermuteVarConfig(extra.varName(), extra.from(), extra.to());
    }
}
