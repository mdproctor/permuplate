package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;

@Permute(varName = "i", from = 3, to = 3, className = "ForEachDeclr${i}")
public class ForEachDeclr2 {

    private List<Object> items;
    private List<Object> results;
    private List<Object> skipped;

    public void collect() {
        for (@PermuteDeclr(type = "Object", name = "o${i}")
        Object o2 : items) {
            if (o2 == null) {
                skipped.add(o2);
                continue;
            }
            results.add(o2);
            System.out.println("collected: " + o2);
        }
    }
}
