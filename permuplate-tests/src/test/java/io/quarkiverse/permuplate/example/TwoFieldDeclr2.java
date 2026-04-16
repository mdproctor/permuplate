package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "3", to = "3", className = "TwoFieldDeclr${i}")
public class TwoFieldDeclr2<A, @PermuteTypeParam(varName = "k", from = "2", to = "${i}", name = "${alpha(k)}") B> {

    private @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>", name = "primary${i}") Callable2<A, B> primary2;
    private @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>", name = "fallback${i}") Callable2<A, B> fallback2;

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
