package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;

@Permute(varName = "i", from = "3", to = "3", className = "CtorDeclr${i}")
public class CtorDeclr2 {

    private String label;
    private String record;

    public CtorDeclr2(
            @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2,
            String label) {
        this.label = label;
        this.record = "arity=" + c2;
    }

    public String getLabel() {
        return label;
    }

    public String getRecord() {
        return record;
    }
}
