package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;

@Permute(varName = "i", from = "2", to = "10", className = "Callable${i}")
public interface Callable1 {
    void call(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "o${j}") Object o1);
}
