package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "3", to = "3", className = "CtorDeclr${i}")
public class CtorDeclr2<A, @PermuteTypeParam(varName = "k", from = "2", to = "${i}", name = "${alpha(k)}") B> {

    private String label;
    private String record;

    public CtorDeclr2(
            @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>", name = "c${i}") Callable2<A, B> c2,
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
