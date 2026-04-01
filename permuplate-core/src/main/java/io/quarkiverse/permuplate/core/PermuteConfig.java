package io.quarkiverse.permuplate.core;

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
}
