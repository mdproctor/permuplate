package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteVar;

/**
 * Demonstrates {@code @PermuteVar(values={"sync","async"})} for string-set cross-product generation.
 *
 * <p>
 * Generates 4 classes: {@code SyncWidget1Factory}, {@code SyncWidget2Factory},
 * {@code AsyncWidget1Factory}, {@code AsyncWidget2Factory}.
 *
 * <p>
 * The {@code T} string variable is available in all JEXL expressions.
 * {@code capitalize(T)} converts the first character to uppercase — useful when
 * the {@code values} list uses lowercase strings to match Java naming conventions.
 */
@Permute(varName = "i", from = "1", to = "2", className = "${capitalize(T)}Widget${i}Factory", extraVars = {
        @PermuteVar(varName = "T", values = { "sync", "async" }) })
public class WidgetFactory1 {

    /** Returns the widget type name (e.g. {@code "sync"} or {@code "async"}). */
    public String type() {
        return "sync";
    }

    /** Returns the widget arity index. */
    public int index() {
        return 1;
    }
}
