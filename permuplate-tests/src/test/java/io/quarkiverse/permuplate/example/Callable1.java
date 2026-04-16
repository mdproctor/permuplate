package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "2", to = "10", className = "Callable${i}")
public interface Callable1<@PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A> {
    void call(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "${alpha(j)}", name = "${lower(j)}") A a);
}
