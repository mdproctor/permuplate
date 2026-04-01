package io.quarkiverse.permuplate.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteVar;

@Permute(varName = "i", from = 2, to = 3, className = "Combo${i}x${k}", extraVars = {
        @PermuteVar(varName = "k", from = 2, to = 3) })
public class Combo1x1 {

    public final List<Object> results = new ArrayList<>();

    public void combine(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "left${j}") Object left1,
            @PermuteParam(varName = "m", from = "1", to = "${k}", type = "Object", name = "right${m}") Object right1) {
        Collections.addAll(results, left1, right1);
    }
}
