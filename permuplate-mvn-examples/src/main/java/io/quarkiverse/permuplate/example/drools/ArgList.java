package io.quarkiverse.permuplate.example.drools;

import java.util.ArrayList;
import java.util.List;

/**
 * Positional parameter container for list-style rule params.
 * Built by the caller and passed to rule.run(ctx, argList).
 * Access params positionally in filters: (ctx, a, b) -> ((String) a.get(0))
 */
public class ArgList {
    private final List<Object> values = new ArrayList<>();

    public ArgList add(Object value) {
        values.add(value);
        return this;
    }

    public Object get(int index) {
        return values.get(index);
    }

    public int size() {
        return values.size();
    }
}
