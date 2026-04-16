package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "3", to = "3", className = "RichJoin${i}")
public class RichJoin2<A, @PermuteTypeParam(varName = "k", from = "2", to = "${i}", name = "${alpha(k)}") B> {

    private @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>", name = "c${i}") Callable2<A, B> c2;
    private @PermuteDeclr(type = "List<${alpha(i)}>") List<B> right;
    private List<Object> skipped;

    public void process(
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "${alpha(j)}", name = "${lower(j)}") A a,
            String label) {
        System.out.println("Processor: " + c2);
        if (c2 == null) {
            return;
        }
        int count = 0;
        for (@PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
        B b : right) {
            if (b == null) {
                skipped.add(b);
                continue;
            }
            c2.call(a, b);
            System.out.println("Processed: " + b + " with " + label);
            count++;
        }
        System.out.println("Done with " + c2 + ": " + count + " items");
    }
}
