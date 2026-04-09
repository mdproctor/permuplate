package io.quarkiverse.permuplate.intellij.index;

import org.jetbrains.annotations.NotNull;

public final class PermuteElementResolver {

    private PermuteElementResolver() {}

    /**
     * Strip trailing digits. "c3" → "c", "join2" → "join", "Join10" → "Join".
     */
    public static String stripTrailingDigits(@NotNull String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return name.substring(0, i);
    }
}
