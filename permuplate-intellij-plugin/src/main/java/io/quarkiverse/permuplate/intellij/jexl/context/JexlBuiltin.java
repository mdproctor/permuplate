package io.quarkiverse.permuplate.intellij.jexl.context;

import java.util.Map;

public record JexlBuiltin(String name, String[] paramNames,
                           String[] paramTypes, String returnType) {

    public static final Map<String, JexlBuiltin> ALL = Map.of(
            "alpha",        new JexlBuiltin("alpha",        new String[]{"n"},
                                            new String[]{"int"},          "String"),
            "lower",        new JexlBuiltin("lower",        new String[]{"n"},
                                            new String[]{"int"},          "String"),
            "typeArgList",  new JexlBuiltin("typeArgList",  new String[]{"from","to","style"},
                                            new String[]{"int","int","String"}, "String"),
            "capitalize",   new JexlBuiltin("capitalize",   new String[]{"s"},
                                            new String[]{"String"},       "String"),
            "decapitalize", new JexlBuiltin("decapitalize", new String[]{"s"},
                                            new String[]{"String"},       "String"),
            "max",          new JexlBuiltin("max",          new String[]{"a","b"},
                                            new String[]{"int","int"},    "int"),
            "min",          new JexlBuiltin("min",          new String[]{"a","b"},
                                            new String[]{"int","int"},    "int")
    );

    public String signature() {
        StringBuilder sb = new StringBuilder(name).append("(");
        for (int i = 0; i < paramNames.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes[i]).append(" ").append(paramNames[i]);
        }
        return sb.append(") → ").append(returnType).toString();
    }
}
