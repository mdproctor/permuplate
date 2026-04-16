package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "3", to = "4", className = "ContextJoin${i}")
public class ContextJoin2<A, @PermuteTypeParam(varName = "k", from = "2", to = "${i}", name = "${alpha(k)}") B> {

    private @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>", name = "c${i}") Callable2<A, B> c2;
    private @PermuteDeclr(type = "List<${alpha(i)}>") List<B> right;

    public void join(
            String ctx,
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "${alpha(j)}", name = "${lower(j)}") A a,
            List<Object> results) {
        System.out.println("Starting join for: " + ctx);
        results.clear();
        for (@PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
        B b : right) {
            c2.call(a, b);
            results.add(b);
        }
        System.out.println("Join complete: " + results.size() + " results for: " + ctx);
    }
}
