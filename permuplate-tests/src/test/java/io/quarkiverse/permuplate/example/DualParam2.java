package io.quarkiverse.permuplate.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;

@Permute(varName = "i", from = 3, to = 4, className = "DualParam${i}")
public class DualParam2 {

    public final List<Object> merged = new ArrayList<>();

    public void merge(
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "left${j}") Object left1,
            @PermuteParam(varName = "k", from = "1", to = "${i-1}", type = "Object", name = "right${k}") Object right1) {
        Collections.addAll(merged, left1, right1);
    }
}
