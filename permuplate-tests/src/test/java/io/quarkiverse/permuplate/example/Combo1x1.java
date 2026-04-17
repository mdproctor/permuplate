package io.quarkiverse.permuplate.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;
import io.quarkiverse.permuplate.PermuteVar;

@Permute(varName = "i", from = "2", to = "3", className = "Combo${i}x${k}", extraVars = {
        @PermuteVar(varName = "k", from = "2", to = "3") })
public class Combo1x1<@PermuteTypeParam(varName = "j", from = "1", to = "${i}", name = "L${j}") L1, @PermuteTypeParam(varName = "m", from = "1", to = "${k}", name = "R${m}") R1> {

    public final List<Object> results = new ArrayList<>();

    public void combine(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "L${j}", name = "left${j}") L1 left1,
            @PermuteParam(varName = "m", from = "1", to = "${k}", type = "R${m}", name = "right${m}") R1 right1) {
        Collections.addAll(results, left1, right1);
    }
}
