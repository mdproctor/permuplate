package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;
import io.quarkiverse.permuplate.PermuteVar;

@Permute(varName = "i", from = "2", to = "4", className = "BiCallable${i}x${k}", extraVars = {
        @PermuteVar(varName = "k", from = "2", to = "4") })
public interface BiCallable1x1<@PermuteTypeParam(varName = "j", from = "1", to = "${i}", name = "T${j}") T1, @PermuteTypeParam(varName = "m", from = "1", to = "${k}", name = "U${m}") U1> {
    void call(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "T${j}", name = "left${j}") T1 left1,
            @PermuteParam(varName = "m", from = "1", to = "${k}", type = "U${m}", name = "right${m}") U1 right1);
}
