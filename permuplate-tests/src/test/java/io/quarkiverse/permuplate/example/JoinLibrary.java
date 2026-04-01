package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;

public class JoinLibrary {

    @Permute(varName = "i", from = 3, to = 5, className = "FilterJoin${i}")
    public static class FilterJoin2 {

        private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
        private List<Object> right;
        private String label;

        public void run(
                @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1) {
            System.out.println("Running: " + label);
            for (@PermuteDeclr(type = "Object", name = "o${i}")
            Object o2 : right) {
                c2.call(o1, o2);
            }
            System.out.println("Done: " + label);
        }
    }
}
