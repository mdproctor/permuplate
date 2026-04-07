package io.quarkiverse.permuplate.example.drools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named parameter container for map-style rule params.
 * Built by the caller and passed to rule.run(ctx, argMap).
 * Access params by name in filters: (ctx, a, b) -> ((String) a.get("name"))
 */
public class ArgMap {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public ArgMap put(String name, Object value) {
        values.put(name, value);
        return this;
    }

    public Object get(String name) {
        return values.get(name);
    }

    public int size() {
        return values.size();
    }
}
