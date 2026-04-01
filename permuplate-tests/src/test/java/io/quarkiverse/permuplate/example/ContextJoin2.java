package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;

@Permute(varName = "i", from = 3, to = 4, className = "ContextJoin${i}")
public class ContextJoin2 {

    private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
    private List<Object> right;

    public void join(
            String ctx,
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1,
            List<Object> results) {
        System.out.println("Starting join for: " + ctx);
        results.clear();
        for (@PermuteDeclr(type = "Object", name = "o${i}")
        Object o2 : right) {
            c2.call(o1, o2);
            results.add(o2);
        }
        System.out.println("Join complete: " + results.size() + " results for: " + ctx);
    }
}
