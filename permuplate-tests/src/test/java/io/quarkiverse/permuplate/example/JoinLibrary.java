package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

public class JoinLibrary {

    @Permute(varName = "i", from = "3", to = "5", className = "FilterJoin${i}")
    public static class FilterJoin2<A, @PermuteTypeParam(varName = "k", from = "2", to = "${i}", name = "${alpha(k)}") B> {

        private @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>", name = "c${i}") Callable2<A, B> c2;
        private @PermuteDeclr(type = "List<${alpha(i)}>") List<B> right;
        private String label;

        public void run(
                @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "${alpha(j)}", name = "${lower(j)}") A a) {
            System.out.println("Running: " + label);
            for (@PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
            B b : right) {
                c2.call(a, b);
            }
            System.out.println("Done: " + label);
        }
    }
}
