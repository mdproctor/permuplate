package io.quarkiverse.permuplate.maven;

import java.util.ArrayList;
import java.util.List;

import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.core.PermuteVarConfig;

/**
 * Reads {@code @Permute} and {@code @PermuteVar} annotation attribute values
 * from a JavaParser {@link AnnotationExpr} AST node, producing a {@link PermuteConfig}.
 *
 * <p>
 * This is the Maven plugin's equivalent of {@code typeElement.getAnnotation(Permute.class)}
 * in the APT path — both produce a {@link PermuteConfig} that the shared core engine consumes.
 */
public class AnnotationReader {

    public AnnotationReader() {
    }

    /**
     * Convenience method: finds the {@code @Permute} annotation on the given type
     * declaration (class, interface, or record) and converts it to a {@link PermuteConfig}.
     *
     * @throws MojoAnnotationException if the annotation is missing or malformed
     */
    public PermuteConfig readPermuteConfig(TypeDeclaration<?> typeDecl)
            throws MojoAnnotationException {
        AnnotationExpr ann = typeDecl.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("Permute")
                        || a.getNameAsString().equals("io.quarkiverse.permuplate.Permute"))
                .findFirst()
                .orElseThrow(() -> new MojoAnnotationException(
                        "Type " + typeDecl.getNameAsString() + " has no @Permute annotation"));
        return readPermute(ann);
    }

    /**
     * Converts a {@code @Permute} annotation expression to a {@link PermuteConfig}.
     *
     * @throws MojoAnnotationException if a required attribute is missing or malformed
     */
    public static PermuteConfig readPermute(AnnotationExpr ann) throws MojoAnnotationException {
        if (!(ann instanceof NormalAnnotationExpr)) {
            throw new MojoAnnotationException(
                    "@Permute must use named parameters (e.g. varName=\"i\", from=\"2\", to=\"4\", ...)");
        }
        NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;

        String varName = requireString(normal, "varName");
        String[] values = readStringArray(normal, "values");
        String from = values.length > 0 ? "" : optionalStringOrInt(normal, "from");
        String to = values.length > 0 ? "" : optionalStringOrInt(normal, "to");
        String className = requireString(normal, "className");
        String[] strings = readStringArray(normal, "strings");
        PermuteVarConfig[] extraVars = readExtraVars(normal);
        boolean inline = readBoolean(normal, "inline", false);
        boolean keepTemplate = readBoolean(normal, "keepTemplate", false);

        return new PermuteConfig(varName, from, to, values, className, strings, extraVars, inline, keepTemplate);
    }

    private static String requireString(NormalAnnotationExpr ann, String name)
            throws MojoAnnotationException {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(name)) {
                return PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            }
        }
        throw new MojoAnnotationException("@Permute is missing required attribute: " + name);
    }

    /**
     * Reads a string or int attribute from the annotation; returns empty string if absent.
     * Used for {@code from}/{@code to} which are optional when {@code values} is specified.
     */
    private static String optionalStringOrInt(NormalAnnotationExpr ann, String name) {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(name)) {
                String raw = pair.getValue().toString().trim();
                return PermuteDeclrTransformer.stripQuotes(raw);
            }
        }
        return "";
    }

    private static boolean readBoolean(NormalAnnotationExpr ann, String name, boolean defaultValue) {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(name)) {
                return Boolean.parseBoolean(pair.getValue().toString().trim());
            }
        }
        return defaultValue;
    }

    private static String[] readStringArray(NormalAnnotationExpr ann, String name) {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(name)) {
                Expression val = pair.getValue();
                if (val instanceof ArrayInitializerExpr) {
                    List<String> result = new ArrayList<>();
                    for (Expression e : ((ArrayInitializerExpr) val).getValues()) {
                        result.add(PermuteDeclrTransformer.stripQuotes(e.toString()));
                    }
                    return result.toArray(new String[0]);
                }
                return new String[] { PermuteDeclrTransformer.stripQuotes(val.toString()) };
            }
        }
        return new String[0];
    }

    private static PermuteVarConfig[] readExtraVars(NormalAnnotationExpr ann)
            throws MojoAnnotationException {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals("extraVars")) {
                Expression val = pair.getValue();
                List<PermuteVarConfig> result = new ArrayList<>();
                List<Expression> exprs = val instanceof ArrayInitializerExpr
                        ? ((ArrayInitializerExpr) val).getValues()
                        : List.of(val);
                for (Expression e : exprs) {
                    if (e instanceof NormalAnnotationExpr) {
                        NormalAnnotationExpr varAnn = (NormalAnnotationExpr) e;
                        String varName = requireString(varAnn, "varName");
                        String[] extraValues = readStringArray(varAnn, "values");
                        String from = extraValues.length > 0 ? "" : optionalStringOrInt(varAnn, "from");
                        String to = extraValues.length > 0 ? "" : optionalStringOrInt(varAnn, "to");
                        result.add(new PermuteVarConfig(varName, from, to, extraValues));
                    }
                }
                return result.toArray(new PermuteVarConfig[0]);
            }
        }
        return new PermuteVarConfig[0];
    }

    /** Signals a malformed or missing annotation attribute found during source scanning. */
    public static class MojoAnnotationException extends Exception {
        public MojoAnnotationException(String message) {
            super(message);
        }
    }

    /** Parsed @PermuteReturn configuration read from JavaParser AST. */
    public record PermuteReturnConfig(
            String className,
            String typeArgVarName,
            String typeArgFrom,
            String typeArgTo,
            String typeArgName,
            String typeArgs,
            String when) {

        public boolean hasTypeArgLoop() {
            return typeArgVarName != null && !typeArgVarName.isEmpty();
        }

        public boolean hasTypeArgsExpr() {
            return typeArgs != null && !typeArgs.isEmpty();
        }
    }

    /**
     * Reads a {@code @PermuteReturn} annotation from a JavaParser {@link AnnotationExpr}.
     * Returns {@code null} if the annotation is not a {@link NormalAnnotationExpr}
     * or if {@code className} is missing.
     */
    public static PermuteReturnConfig readPermuteReturn(AnnotationExpr ann) {
        if (!(ann instanceof NormalAnnotationExpr))
            return null;
        NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;

        String className = null, typeArgVarName = "", typeArgFrom = "1",
                typeArgTo = "", typeArgName = "", typeArgs = "", when = "";

        for (MemberValuePair pair : normal.getPairs()) {
            String val = PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            switch (pair.getNameAsString()) {
                case "className" -> className = val;
                case "typeArgVarName" -> typeArgVarName = val;
                case "typeArgFrom" -> typeArgFrom = val;
                case "typeArgTo" -> typeArgTo = val;
                case "typeArgName" -> typeArgName = val;
                case "typeArgs" -> typeArgs = val;
                case "when" -> when = val;
            }
        }
        if (className == null)
            return null;
        return new PermuteReturnConfig(className, typeArgVarName, typeArgFrom,
                typeArgTo, typeArgName, typeArgs, when);
    }

    /** Parsed @PermuteMethod configuration. */
    public record PermuteMethodConfig(String varName, String from, String to, String name) {
        public boolean hasExplicitTo() {
            return to != null && !to.isEmpty();
        }

        public boolean hasName() {
            return name != null && !name.isEmpty();
        }
    }

    /**
     * Reads a {@code @PermuteMethod} annotation from a JavaParser {@link AnnotationExpr}.
     * Returns {@code null} if not a {@link NormalAnnotationExpr} or {@code varName} absent.
     */
    public static PermuteMethodConfig readPermuteMethod(AnnotationExpr ann) {
        if (!(ann instanceof NormalAnnotationExpr))
            return null;
        NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;
        String varName = null, from = "1", to = "", name = "";
        for (MemberValuePair pair : normal.getPairs()) {
            String val = PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            switch (pair.getNameAsString()) {
                case "varName" -> varName = val;
                case "from" -> from = val;
                case "to" -> to = val;
                case "name" -> name = val;
            }
        }
        return varName == null ? null : new PermuteMethodConfig(varName, from, to, name);
    }

    /** Parsed {@code @PermuteExtends} configuration. */
    public record PermuteExtendsConfig(
            String className,
            String typeArgVarName,
            String typeArgFrom,
            String typeArgTo,
            String typeArgName,
            String typeArgs,
            int interfaceIndex) {

        public boolean hasTypeArgLoop() {
            return typeArgVarName != null && !typeArgVarName.isEmpty()
                    && typeArgTo != null && !typeArgTo.isEmpty();
        }

        public boolean hasTypeArgsExpr() {
            return typeArgs != null && !typeArgs.isEmpty();
        }
    }

    /**
     * Reads a {@code @PermuteExtends} annotation from a JavaParser {@link AnnotationExpr}.
     * Returns {@code null} if not a {@link NormalAnnotationExpr} or {@code className} absent.
     */
    public static PermuteExtendsConfig readPermuteExtends(AnnotationExpr ann) {
        if (!(ann instanceof NormalAnnotationExpr))
            return null;
        NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;

        String className = null, typeArgVarName = "", typeArgFrom = "1",
                typeArgTo = "", typeArgName = "", typeArgs = "";
        int interfaceIndex = 0;

        for (MemberValuePair pair : normal.getPairs()) {
            String name = pair.getNameAsString();
            if (name.equals("interfaceIndex")) {
                try {
                    interfaceIndex = Integer.parseInt(pair.getValue().toString().trim());
                } catch (NumberFormatException ignored) {
                }
            } else {
                String val = PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
                switch (name) {
                    case "className" -> className = val;
                    case "typeArgVarName" -> typeArgVarName = val;
                    case "typeArgFrom" -> typeArgFrom = val;
                    case "typeArgTo" -> typeArgTo = val;
                    case "typeArgName" -> typeArgName = val;
                    case "typeArgs" -> typeArgs = val;
                }
            }
        }
        if (className == null)
            return null;
        return new PermuteExtendsConfig(className, typeArgVarName, typeArgFrom,
                typeArgTo, typeArgName, typeArgs, interfaceIndex);
    }
}
