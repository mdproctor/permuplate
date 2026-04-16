package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "3", to = "3", className = "ForEachDeclr${i}")
public class ForEachDeclr2<@PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A> {

    private @PermuteDeclr(type = "List<${alpha(i)}>") List<A> items;
    private List<Object> results;
    private List<Object> skipped;

    public void collect() {
        for (@PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
        A a2 : items) {
            if (a2 == null) {
                skipped.add(a2);
                continue;
            }
            results.add(a2);
            System.out.println("collected: " + a2);
        }
    }
}
