package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;

@Permute(varName = "i", from = "3", to = "3", className = "FieldDeclr${i}")
public class FieldDeclr2 {

    private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;

    public String describe() {
        return "handler: " + c2;
    }

    public boolean isReady() {
        return c2 != null;
    }

    public void execute(Object o1, Object o2) {
        if (c2 == null) {
            return;
        }
        System.out.println(c2 + " processing " + o1 + " and " + o2);
    }
}
