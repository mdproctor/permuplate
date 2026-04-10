package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;

public class MultiArityJoin {

    @Permute(varName = "i", from = "2", to = "4", className = "MultiArityJoins")
    public static void join(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "o${j}") Object o1) {
        System.out.println("joining " + o1);
    }
}
