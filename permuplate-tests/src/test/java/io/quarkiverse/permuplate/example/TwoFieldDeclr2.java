package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;

@Permute(varName = "i", from = 3, to = 3, className = "TwoFieldDeclr${i}")
public class TwoFieldDeclr2 {

    private @PermuteDeclr(type = "Callable${i}", name = "primary${i}") Callable2 primary2;
    private @PermuteDeclr(type = "Callable${i}", name = "fallback${i}") Callable2 fallback2;

    public boolean isPrimaryReady() {
        return primary2 != null;
    }

    public boolean isFallbackReady() {
        return fallback2 != null;
    }

    public String describe() {
        return "primary: " + primary2 + ", fallback: " + fallback2;
    }
}
