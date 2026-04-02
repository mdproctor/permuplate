package io.quarkiverse.permuplate.core;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;

/**
 * Expands class type parameters annotated with {@code @PermuteTypeParam}, or implicitly
 * when {@code @PermuteParam} references a class type parameter via the {@code T${j}} naming
 * convention.
 *
 * <p>
 * Transformation is done in-place on the cloned {@link ClassOrInterfaceDeclaration}.
 * This transformer runs <em>before</em> {@link PermuteDeclrTransformer} and
 * {@link PermuteParamTransformer} in the pipeline.
 *
 * <p>
 * <b>Pipeline position:</b>
 * <ol>
 * <li>Clone + rename class</li>
 * <li><b>PermuteTypeParamTransformer.transform()</b> ← this class</li>
 * <li>PermuteDeclrTransformer.transform()</li>
 * <li>PermuteParamTransformer.transform()</li>
 * <li>Strip annotations</li>
 * </ol>
 */
public class PermuteTypeParamTransformer {

    private static final String ANNOTATION_SIMPLE = "PermuteTypeParam";
    private static final String ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteTypeParam";
    private static final String PARAM_ANNOTATION_SIMPLE = "PermuteParam";
    private static final String PARAM_ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteParam";

    /**
     * Entry point. Expands class type parameters and validates constraints.
     *
     * @param classDecl the cloned class declaration being transformed
     * @param ctx the outer permutation context (contains {@code i}, string vars, etc.)
     * @param messager for error reporting; {@code null} in Maven plugin mode
     * @param element the annotated element for error location; {@code null} in Maven plugin mode
     * @return names of the sentinel type parameters that were expanded
     */
    public static Set<String> transform(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager,
            Element element) {

        // Detect which type params will expand — needed for R1 pre-check
        Set<String> willExpand = detectExpansions(classDecl, ctx);

        // R1: return type must not reference expanding type params
        if (messager != null && !willExpand.isEmpty()) {
            validateR1(classDecl, willExpand, messager, element);
        }

        // Expand explicit @PermuteTypeParam annotations
        Set<String> expanded = new HashSet<>(expandExplicit(classDecl, ctx, messager, element));

        // Expand implicit (from @PermuteParam with T${j} type referencing a class type param)
        expanded.addAll(expandImplicit(classDecl, ctx, expanded));

        return expanded;
    }

    /**
     * Expands method-level type parameters annotated with {@code @PermuteTypeParam}.
     * Used by {@code applyPermuteMethod()} in both InlineGenerator and PermuteProcessor
     * to expand method type parameters with the inner {@code (i,k)} context.
     *
     * <p>
     * This is the method-level equivalent of {@link #transform} for class-level type params.
     * The {@code ctx} must contain the {@code @PermuteMethod} inner variable so expressions
     * like {@code to="${k-1}"} evaluate correctly.
     *
     * <p>
     * The sentinel type parameter (which had {@code @PermuteTypeParam}) is replaced by
     * freshly constructed {@link TypeParameter} nodes with no annotations — the annotation
     * disappears by construction.
     *
     * @param method the cloned method declaration (modified in-place)
     * @param ctx the inner context containing both outer {@code i} and @PermuteMethod {@code k}
     * @param messager for error reporting; {@code null} in Maven plugin mode
     * @param element the annotated element; {@code null} in Maven plugin mode
     * @return names of the sentinel type parameters that were expanded
     */
    public static Set<String> transformMethod(MethodDeclaration method,
            EvaluationContext ctx,
            Messager messager,
            Element element) {

        NodeList<TypeParameter> current = method.getTypeParameters();
        NodeList<TypeParameter> result = new NodeList<>();
        Set<String> expanded = new HashSet<>();

        for (TypeParameter tp : current) {
            Optional<NormalAnnotationExpr> ann = findTypeParamAnnotation(tp);
            if (ann.isEmpty()) {
                result.add(tp);
                continue;
            }

            NormalAnnotationExpr normal = ann.get();
            String varName = getAttr(normal, "varName");
            String fromStr = getAttr(normal, "from");
            String toStr = getAttr(normal, "to");
            String nameTemplate = getAttr(normal, "name");
            String sentinelName = tp.getNameAsString();

            // R4: evaluate range
            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(fromStr);
                toVal = ctx.evaluateInt(toStr);
            } catch (Exception e) {
                if (messager != null)
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@PermuteTypeParam: cannot evaluate range: " + e.getMessage(), element);
                result.add(tp);
                continue;
            }
            if (fromVal > toVal) {
                if (messager != null)
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@PermuteTypeParam has invalid range: from=" + fromVal
                                    + " is greater than to=" + toVal,
                            element);
                result.add(tp);
                continue;
            }

            // R3: name leading literal must be a prefix of sentinel name
            String leadingLiteral = nameTemplate.contains("${")
                    ? nameTemplate.substring(0, nameTemplate.indexOf("${"))
                    : nameTemplate;
            if (!leadingLiteral.isEmpty() && !sentinelName.startsWith(leadingLiteral)) {
                if (messager != null)
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@PermuteTypeParam name literal part \"" + leadingLiteral
                                    + "\" is not a prefix of type parameter \"" + sentinelName + "\"",
                            element);
                result.add(tp);
                continue;
            }

            // Expand: generate one TypeParameter per j value
            expanded.add(sentinelName);
            for (int j = fromVal; j <= toVal; j++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, j);
                String newName = innerCtx.evaluate(nameTemplate);
                result.add(buildTypeParam(newName, tp, sentinelName));
            }
        }

        method.setTypeParameters(result);
        return expanded;
    }

    // -------------------------------------------------------------------------
    // Detection (dry run — finds which type params will expand for R1 check)
    // -------------------------------------------------------------------------

    private static Set<String> detectExpansions(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx) {
        Set<String> sentinels = new HashSet<>();

        // Explicit: type params with @PermuteTypeParam annotation
        for (TypeParameter tp : classDecl.getTypeParameters()) {
            if (hasAnnotation(tp.getAnnotations(), ANNOTATION_SIMPLE)) {
                sentinels.add(tp.getNameAsString());
            }
        }

        // Implicit: @PermuteParam whose type="T${j}" resolves to a class type param at j=from
        Set<String> classTypeParamNames = typeParamNames(classDecl);
        for (MethodDeclaration method : classDecl.getMethods()) {
            for (Parameter param : method.getParameters()) {
                Optional<NormalAnnotationExpr> ann = findParamAnnotation(param);
                if (ann.isEmpty())
                    continue;
                String typeAtFrom = resolveImplicitSentinel(ann.get(), ctx);
                if (typeAtFrom != null && classTypeParamNames.contains(typeAtFrom)) {
                    sentinels.add(typeAtFrom);
                }
            }
        }
        return sentinels;
    }

    // -------------------------------------------------------------------------
    // R1 validation — return type must not reference expanding type params
    // -------------------------------------------------------------------------

    private static void validateR1(ClassOrInterfaceDeclaration classDecl,
            Set<String> expandingSentinels,
            Messager messager,
            Element element) {
        for (MethodDeclaration method : classDecl.getMethods()) {
            String returnType = method.getTypeAsString();
            for (String sentinel : expandingSentinels) {
                if (containsTypeRef(returnType, sentinel)) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@PermuteParam implicit type expansion: return type \"" + returnType +
                                    "\" references an expanding type parameter — ambiguous across" +
                                    " permutations. Use Object or a fixed container type.",
                            element);
                }
            }
        }
    }

    /** True if {@code text} contains {@code typeName} as a standalone Java identifier. */
    private static boolean containsTypeRef(String text, String typeName) {
        if (typeName.isEmpty())
            return false;
        int idx = text.indexOf(typeName);
        while (idx >= 0) {
            boolean before = idx == 0 || !Character.isJavaIdentifierPart(text.charAt(idx - 1));
            int end = idx + typeName.length();
            boolean after = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
            if (before && after)
                return true;
            idx = text.indexOf(typeName, idx + 1);
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Explicit expansion — @PermuteTypeParam on a type parameter
    // -------------------------------------------------------------------------

    private static Set<String> expandExplicit(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager,
            Element element) {
        Set<String> expanded = new HashSet<>();
        NodeList<TypeParameter> current = classDecl.getTypeParameters();
        NodeList<TypeParameter> result = new NodeList<>();

        for (TypeParameter tp : current) {
            Optional<NormalAnnotationExpr> ann = findTypeParamAnnotation(tp);
            if (ann.isEmpty()) {
                result.add(tp);
                continue;
            }

            NormalAnnotationExpr normal = ann.get();
            String varName = getAttr(normal, "varName");
            String fromStr = getAttr(normal, "from");
            String toStr = getAttr(normal, "to");
            String nameTemplate = getAttr(normal, "name");
            String sentinelName = tp.getNameAsString();

            // R4: evaluate range
            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(fromStr);
                toVal = ctx.evaluateInt(toStr);
            } catch (Exception e) {
                if (messager != null)
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@PermuteTypeParam: cannot evaluate range: " + e.getMessage(), element);
                result.add(tp);
                continue;
            }
            if (fromVal > toVal) {
                if (messager != null)
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@PermuteTypeParam has invalid range: from=" + fromVal +
                                    " is greater than to=" + toVal,
                            element);
                result.add(tp);
                continue;
            }

            // R3: leading literal in name must be a prefix of the sentinel name
            String leadingLiteral = nameTemplate.contains("${")
                    ? nameTemplate.substring(0, nameTemplate.indexOf("${"))
                    : nameTemplate;
            if (!leadingLiteral.isEmpty() && !sentinelName.startsWith(leadingLiteral)) {
                if (messager != null)
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@PermuteTypeParam name literal part \"" + leadingLiteral +
                                    "\" is not a prefix of type parameter \"" + sentinelName + "\"",
                            element);
                result.add(tp);
                continue;
            }

            // Expand: generate one TypeParameter per j value
            expanded.add(sentinelName);
            for (int j = fromVal; j <= toVal; j++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, j);
                String newName = innerCtx.evaluate(nameTemplate);
                result.add(buildTypeParam(newName, tp, sentinelName));
            }
        }

        classDecl.setTypeParameters(result);
        return expanded;
    }

    // -------------------------------------------------------------------------
    // Implicit expansion — triggered by @PermuteParam with type="T${j}"
    // -------------------------------------------------------------------------

    private static Set<String> expandImplicit(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Set<String> alreadyExpanded) {
        Set<String> expanded = new HashSet<>();
        // Capture the original type param names ONCE before the loop.
        // Generated names (T2, T3, …) must not re-enter sentinel detection.
        Set<String> classTypeParamNames = typeParamNames(classDecl);

        for (MethodDeclaration method : classDecl.getMethods()) {
            for (Parameter param : method.getParameters()) {
                Optional<NormalAnnotationExpr> ann = findParamAnnotation(param);
                if (ann.isEmpty())
                    continue;

                NormalAnnotationExpr normal = ann.get();
                String typeAtFrom = resolveImplicitSentinel(normal, ctx);
                if (typeAtFrom == null)
                    continue;

                // Note: R2 (duplicate expansion: both @PermuteTypeParam and @PermuteParam
                // target the same type param) is intentionally not an error here — it is
                // silently skipped. R2 validation is deferred (rare edge case).
                if (!classTypeParamNames.contains(typeAtFrom)
                        || alreadyExpanded.contains(typeAtFrom)
                        || expanded.contains(typeAtFrom))
                    continue;

                String varName = getAttr(normal, "varName");
                String typeTemplate = getAttr(normal, "type");
                String fromStr = getAttr(normal, "from");
                String toStr = getAttr(normal, "to");
                if (varName == null || typeTemplate == null || fromStr == null || toStr == null)
                    continue;

                int fromVal;
                try {
                    fromVal = ctx.evaluateInt(fromStr);
                } catch (Exception ignored) {
                    continue;
                }

                int toVal;
                try {
                    toVal = ctx.evaluateInt(toStr);
                } catch (Exception ignored) {
                    continue;
                }

                String sentinelName = typeAtFrom;
                expanded.add(sentinelName);

                // Replace the sentinel with j expanded type params
                NodeList<TypeParameter> current = classDecl.getTypeParameters();
                NodeList<TypeParameter> result = new NodeList<>();
                for (TypeParameter tp : current) {
                    if (!tp.getNameAsString().equals(sentinelName)) {
                        result.add(tp);
                        continue;
                    }
                    for (int j = fromVal; j <= toVal; j++) {
                        String newName = ctx.withVariable(varName, j).evaluate(typeTemplate);
                        result.add(buildTypeParam(newName, tp, sentinelName));
                    }
                }
                classDecl.setTypeParameters(result);
            }
        }
        return expanded;
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /**
     * Evaluates the @PermuteParam type template at j=from to determine which class
     * type parameter the sentinel would expand. Returns null if evaluation fails.
     */
    private static String resolveImplicitSentinel(NormalAnnotationExpr paramAnn,
            EvaluationContext ctx) {
        String typeTemplate = getAttr(paramAnn, "type");
        String varName = getAttr(paramAnn, "varName");
        String fromStr = getAttr(paramAnn, "from");
        if (typeTemplate == null || varName == null || fromStr == null)
            return null;
        try {
            int fromVal = ctx.evaluateInt(fromStr);
            return ctx.withVariable(varName, fromVal).evaluate(typeTemplate);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Builds a new TypeParameter with the given name, copying and substituting bounds
     * from the sentinel. The sentinel name is replaced with newName in all bound text.
     */
    private static TypeParameter buildTypeParam(String newName, TypeParameter sentinel,
            String sentinelName) {
        TypeParameter newTp = new TypeParameter(newName);
        NodeList<ClassOrInterfaceType> newBounds = new NodeList<>();
        for (ClassOrInterfaceType bound : sentinel.getTypeBound()) {
            String boundStr = bound.toString().replace(sentinelName, newName);
            newBounds.add(StaticJavaParser.parseClassOrInterfaceType(boundStr));
        }
        newTp.setTypeBound(newBounds);
        return newTp;
    }

    private static Set<String> typeParamNames(ClassOrInterfaceDeclaration classDecl) {
        Set<String> names = new HashSet<>();
        for (TypeParameter tp : classDecl.getTypeParameters()) {
            names.add(tp.getNameAsString());
        }
        return names;
    }

    private static boolean hasAnnotation(NodeList<AnnotationExpr> annotations, String simpleName) {
        for (AnnotationExpr ann : annotations) {
            String n = ann.getNameAsString();
            if (n.equals(simpleName) || n.equals(ANNOTATION_FQ))
                return true;
        }
        return false;
    }

    private static Optional<NormalAnnotationExpr> findTypeParamAnnotation(TypeParameter tp) {
        for (AnnotationExpr ann : tp.getAnnotations()) {
            String n = ann.getNameAsString();
            if ((n.equals(ANNOTATION_SIMPLE) || n.equals(ANNOTATION_FQ))
                    && ann instanceof NormalAnnotationExpr) {
                return Optional.of((NormalAnnotationExpr) ann);
            }
        }
        return Optional.empty();
    }

    private static Optional<NormalAnnotationExpr> findParamAnnotation(Parameter param) {
        for (AnnotationExpr ann : param.getAnnotations()) {
            String n = ann.getNameAsString();
            if ((n.equals(PARAM_ANNOTATION_SIMPLE) || n.equals(PARAM_ANNOTATION_FQ))
                    && ann instanceof NormalAnnotationExpr) {
                return Optional.of((NormalAnnotationExpr) ann);
            }
        }
        return Optional.empty();
    }

    private static String getAttr(NormalAnnotationExpr ann, String attrName) {
        for (MemberValuePair pair : ann.getPairs()) {
            if (pair.getNameAsString().equals(attrName)) {
                return PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            }
        }
        return null;
    }
}
