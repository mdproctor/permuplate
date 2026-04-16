package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "3", to = "3", className = "FieldDeclr${i}")
public class FieldDeclr2<A, @PermuteTypeParam(varName = "k", from = "2", to = "${i}", name = "${alpha(k)}") B> {

    private @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>", name = "c${i}") Callable2<A, B> c2;

    public String describe() {
        return "handler: " + c2;
    }

    public boolean isReady() {
        return c2 != null;
    }

    public void execute(A o1, B o2) {
        if (c2 == null) {
            return;
        }
        System.out.println(c2 + " processing " + o1 + " and " + o2);
    }
}
