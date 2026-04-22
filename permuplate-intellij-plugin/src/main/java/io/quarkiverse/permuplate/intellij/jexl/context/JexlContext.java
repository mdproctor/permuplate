package io.quarkiverse.permuplate.intellij.jexl.context;

import org.jetbrains.annotations.Nullable;

import java.util.*;

public record JexlContext(Set<String> variables, @Nullable String innerVariable) {

    public Set<String> allVariables() {
        if (innerVariable == null) return Collections.unmodifiableSet(variables);
        Set<String> all = new LinkedHashSet<>(variables);
        all.add(innerVariable);
        return Collections.unmodifiableSet(all);
    }
}
