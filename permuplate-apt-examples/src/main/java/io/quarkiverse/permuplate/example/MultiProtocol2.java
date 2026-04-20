package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteBody;
import io.quarkiverse.permuplate.PermuteMethod;

/**
 * Demonstrates {@code @PermuteMethod(values={"sync","async"})} — string-set method overloads.
 *
 * <p>
 * Template {@code MultiProtocol2} generates {@code MultiProtocol3..4}.
 * Each generated class has {@code syncExecute()} and {@code asyncExecute()} overloads
 * produced by the string-set {@code @PermuteMethod}.
 */
@Permute(varName = "i", from = "3", to = "4", className = "MultiProtocol${i}")
public class MultiProtocol2 {

    @PermuteMethod(varName = "T", values = { "sync", "async" }, name = "${T}Execute")
    @PermuteBody(body = "{ return \"${T}-${i}\"; }")
    public String executeTemplate() {
        return "";
    }
}
