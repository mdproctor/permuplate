package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;

@Permute(varName = "i", from = "3", to = "6", className = "Join${i}")
public class Join2 {

    private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
    private List<Object> right;

    public void left(
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1) {
        System.out.println("before logic");
        for (@PermuteDeclr(type = "Object", name = "o${i}")
        Object o2 : right) {
            c2.call(o1, o2);
        }
        System.out.println("after logic");
    }
}
