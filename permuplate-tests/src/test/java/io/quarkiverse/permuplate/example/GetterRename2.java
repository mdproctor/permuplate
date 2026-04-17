package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "3", to = "4", className = "GetterRename${i}")
public class GetterRename2<A, @PermuteTypeParam(varName = "k", from = "2", to = "${i-1}", name = "${alpha(k)}") B> {

    @PermuteDeclr(type = "${alpha(i-1)}", name = "${lower(i-1)}")
    protected B b;

    @PermuteDeclr(type = "${alpha(i-1)}", name = "get${alpha(i-1)}")
    public B getB() {
        return b;
    }

    @PermuteDeclr(type = "", name = "set${alpha(i-1)}")
    public void setB(@PermuteDeclr(type = "${alpha(i-1)}", name = "${lower(i-1)}") B b) {
        this.b = b;
    }
}
