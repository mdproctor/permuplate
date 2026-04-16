package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "3", to = "3", className = "TwoMethodParam${i}")
public class TwoMethodParam2<A, @PermuteTypeParam(varName = "k", from = "2", to = "${i}", name = "${alpha(k)}") B> {

    private @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>", name = "c${i}") Callable2<A, B> c2;
    private @PermuteDeclr(type = "List<${alpha(i)}>") List<B> right;

    public void processLeft(
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "${alpha(j)}", name = "${lower(j)}") A a) {
        for (@PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
        B b : right) {
            c2.call(a, b);
        }
    }

    public void processRight(
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "${alpha(j)}", name = "a${j}") A a1) {
        for (@PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
        B b : right) {
            c2.call(a1, b);
        }
    }
}
