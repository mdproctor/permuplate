package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteVar;

@Permute(varName = "i", from = "2", to = "4", className = "BiCallable${i}x${k}", extraVars = {
        @PermuteVar(varName = "k", from = "2", to = "4") })
public interface BiCallable1x1 {
    void call(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "left${j}") Object left1,
            @PermuteParam(varName = "m", from = "1", to = "${k}", type = "Object", name = "right${m}") Object right1);
}
