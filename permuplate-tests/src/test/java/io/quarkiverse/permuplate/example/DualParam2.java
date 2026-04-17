package io.quarkiverse.permuplate.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "3", to = "4", className = "DualParam${i}")
public class DualParam2<@PermuteTypeParam(varName = "j", from = "1", to = "${i-1}", name = "L${j}") L1, @PermuteTypeParam(varName = "k", from = "1", to = "${i-1}", name = "R${k}") R1> {

    public final List<Object> merged = new ArrayList<>();

    public void merge(
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "L${j}", name = "left${j}") L1 left1,
            @PermuteParam(varName = "k", from = "1", to = "${i-1}", type = "R${k}", name = "right${k}") R1 right1) {
        Collections.addAll(merged, left1, right1);
    }
}
