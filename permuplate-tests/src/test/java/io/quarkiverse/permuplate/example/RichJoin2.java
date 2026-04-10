package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;

@Permute(varName = "i", from = "3", to = "3", className = "RichJoin${i}")
public class RichJoin2 {

    private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
    private List<Object> right;
    private List<Object> skipped;

    public void process(
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1,
            String label) {
        System.out.println("Processor: " + c2);
        if (c2 == null) {
            return;
        }
        int count = 0;
        for (@PermuteDeclr(type = "Object", name = "o${i}")
        Object o2 : right) {
            if (o2 == null) {
                skipped.add(o2);
                continue;
            }
            c2.call(o1, o2);
            System.out.println("Processed: " + o2 + " with " + label);
            count++;
        }
        System.out.println("Done with " + c2 + ": " + count + " items");
    }
}
