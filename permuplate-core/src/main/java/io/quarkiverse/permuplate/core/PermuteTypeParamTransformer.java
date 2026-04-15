package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
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
    public static Set<String> transform(TypeDeclaration<?> classDecl,
            EvaluationContext ctx,
            Messager messager,
            Element element) {
        return transform(classDecl, ctx, messager, element, java.util.Collections.emptySet());
    }

    /**
     * Entry point. Expands class type parameters and validates constraints.
     *
     * @param classDecl the cloned class declaration being transformed
     * @param ctx the outer permutation context (contains {@code i}, string vars, etc.)
     * @param messager for error reporting; {@code null} in Maven plugin mode
     * @param element the annotated element for error location; {@code null} in Maven plugin mode
     * @param implicitInferenceNames class names that implicit return type inference will handle —
     *        methods whose return type base class is in this set are excluded from R1 validation
     * @return names of the sentinel type parameters that were expanded
     */
    public static Set<String> transform(TypeDeclaration<?> classDecl,
            EvaluationContext ctx,
            Messager messager,
            Element element,
            Set<String> implicitInferenceNames) {

        // Detect which type params will expand — needed for R1 pre-check
        Set<String> willExpand = detectExpansions(classDecl, ctx);

        // R1: return type must not reference expanding type params.
        // Skipped for methods that implicit inference will handle (return type base class
        // is in implicitInferenceNames — those methods will be updated later in the pipeline).
        if (messager != null && !willExpand.isEmpty()) {
            validateR1(classDecl, willExpand, messager, element, implicitInferenceNames);
        }

        // Expand explicit @PermuteTypeParam annotations
        Set<String> expanded = new HashSet<>(expandExplicit(classDecl, ctx, messager, element));

        // Expand implicit (from @PermuteParam with T${j} type referencing a class type param)
        expanded.addAll(expandImplicit(classDecl, ctx, expanded));

        // Step 5: process @PermuteTypeParam placed directly on a method (ElementType.METHOD placement).
        // This is the "standalone" usage: @PermuteTypeParam on a non-@PermuteMethod method renames
        // the method's type parameter and propagates the rename into parameter types.
        //
        // @PermuteMethod methods are guarded here — they are processed in applyPermuteMethod()
        // with the combined (i,j) context. Processing them here (outer context only) would
        // evaluate expressions like "${j}" with j undefined, corrupting output.
        //
        // Convention: one method-level @PermuteTypeParam annotation applies to the first
        // unannotated type parameter in the method's type parameter list.
        for (MethodDeclaration method : new ArrayList<>(classDecl.getMethods())) {
            if (isPermuteMethodAnnotated(method))
                continue;
            if (method.getTypeParameters().isEmpty())
                continue;

            Optional<NormalAnnotationExpr> methodAnn = method.getAnnotations().stream()
                    .filter(a -> (a.getNameAsString().equals(ANNOTATION_SIMPLE)
                            || a.getNameAsString().equals(ANNOTATION_FQ))
                            && a instanceof NormalAnnotationExpr)
                    .map(a -> (NormalAnnotationExpr) a)
                    .findFirst();
            if (methodAnn.isEmpty())
                continue;

            NormalAnnotationExpr ann = methodAnn.get();
            String varName = getAttr(ann, "varName");
            String fromStr = getAttr(ann, "from");
            String toStr = getAttr(ann, "to");
            String nameTemplate = getAttr(ann, "name");
            if (varName == null || fromStr == null || toStr == null || nameTemplate == null)
                continue;

            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(fromStr);
                toVal = ctx.evaluateInt(toStr);
            } catch (Exception e) {
                if (messager != null)
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@PermuteTypeParam on method: cannot evaluate range: " + e.getMessage(),
                            element);
                continue;
            }
            if (fromVal > toVal)
                continue; // empty range — method silently skipped

            // Find the first type parameter (the sentinel) to rename
            TypeParameter sentinel = method.getTypeParameters().get(0);
            String sentinelName = sentinel.getNameAsString();

            // Expand the sentinel into one or more type parameters
            NodeList<TypeParameter> current = method.getTypeParameters();
            NodeList<TypeParameter> result = new NodeList<>();
            Map<String, String> step5Renames = new LinkedHashMap<>();

            // Expand the sentinel
            for (int j = fromVal; j <= toVal; j++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, j);
                String newName = innerCtx.evaluate(nameTemplate);
                result.add(buildTypeParam(newName, sentinel, sentinelName));
                if (fromVal == toVal) {
                    step5Renames.put(sentinelName, newName);
                }
            }
            // Add any remaining type params after the sentinel
            for (int i = 1; i < current.size(); i++) {
                result.add(current.get(i));
            }
            method.setTypeParameters(result);

            // Remove the method-level @PermuteTypeParam annotation (it's been consumed)
            method.getAnnotations().removeIf(a -> a.getNameAsString().equals(ANNOTATION_SIMPLE)
                    || a.getNameAsString().equals(ANNOTATION_FQ));

            // Propagate single-value renames into parameter types and the return type
            for (Map.Entry<String, String> entry : step5Renames.entrySet()) {
                String oldName = entry.getKey();
                String newName = entry.getValue();
                for (Parameter param : method.getParameters()) {
                    if (hasPermuteDeclrAnnotation(param))
                        continue;
                    String paramType = param.getTypeAsString();
                    String updated = replaceTypeIdentifier(paramType, oldName, newName);
                    if (!updated.equals(paramType)) {
                        param.setType(StaticJavaParser.parseType(updated));
                    }
                }
                // Also propagate into the return type
                String returnType = method.getTypeAsString();
                String updatedReturn = replaceTypeIdentifier(returnType, oldName, newName);
                if (!updatedReturn.equals(returnType)) {
                    method.setType(StaticJavaParser.parseType(updatedReturn));
                }
            }
        }

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
        // Maps sentinel name → new name for single-value expansions (from == to).
        // Multi-value expansions (from < to) expand one sentinel to N names — no single
        // target to propagate — so they are excluded.
        Map<String, String> singleValueRenames = new LinkedHashMap<>();

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

            // Expand: generate one TypeParameter per j value.
            // Note: no R3 prefix check here — for method-level sentinels the placeholder
            // name (e.g. "A") is arbitrary and need not match the generated names ("T1", "B", …).
            //
            // Note: propagation is intentionally skipped for multi-value expansions (fromVal < toVal).
            // When a sentinel expands to N type parameters (e.g., A → P1, P2, P3), there is no single
            // rename target for parameter types referencing A. Callers that expand to multiple names
            // must use @PermuteDeclr explicitly on any parameters that reference the sentinel.
            expanded.add(sentinelName);
            for (int j = fromVal; j <= toVal; j++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, j);
                String newName = innerCtx.evaluate(nameTemplate);
                if (fromVal == toVal) {
                    singleValueRenames.put(sentinelName, newName);
                }
                result.add(buildTypeParam(newName, tp, sentinelName));
            }
        }

        method.setTypeParameters(result);

        // Propagate single-value renames into parameter types.
        // @PermuteDeclr-annotated parameters are skipped — explicit annotation wins.
        for (Map.Entry<String, String> entry : singleValueRenames.entrySet()) {
            String oldName = entry.getKey();
            String newName = entry.getValue();
            for (Parameter param : method.getParameters()) {
                if (hasPermuteDeclrAnnotation(param))
                    continue;
                String current2 = param.getTypeAsString();
                String updated = replaceTypeIdentifier(current2, oldName, newName);
                if (!updated.equals(current2)) {
                    param.setType(StaticJavaParser.parseType(updated));
                }
            }
        }

        return expanded;
    }

    // -------------------------------------------------------------------------
    // Detection (dry run — finds which type params will expand for R1 check)
    // -------------------------------------------------------------------------

    private static Set<String> detectExpansions(TypeDeclaration<?> classDecl,
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

    private static void validateR1(TypeDeclaration<?> classDecl,
            Set<String> expandingSentinels,
            Messager messager,
            Element element) {
        validateR1(classDecl, expandingSentinels, messager, element, java.util.Collections.emptySet());
    }

    private static void validateR1(TypeDeclaration<?> classDecl,
            Set<String> expandingSentinels,
            Messager messager,
            Element element,
            Set<String> implicitInferenceNames) {
        for (MethodDeclaration method : classDecl.getMethods()) {
            // @PermuteReturn explicitly handles return type transformation — R1 does not apply.
            if (hasPermuteReturn(method))
                continue;
            String returnType = method.getTypeAsString();
            // Implicit inference will handle methods whose return type is from the same
            // generated class family. The template return type (e.g. "Chain2<T1,T2>") has base
            // class "Chain2" — same prefix as generated "Chain3", "Chain4". Skip R1 when the
            // return type base class prefix matches any generated name prefix in the family.
            if (!implicitInferenceNames.isEmpty()) {
                String baseClass = returnType.contains("<")
                        ? returnType.substring(0, returnType.indexOf('<')).trim()
                        : returnType.trim();
                String basePrefix = prefixBeforeDigits(baseClass);
                if (!basePrefix.isEmpty()) {
                    boolean familyMatch = implicitInferenceNames.stream()
                            .anyMatch(n -> prefixBeforeDigits(n).equals(basePrefix));
                    if (familyMatch)
                        continue;
                }
            }
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

    private static boolean hasPermuteReturn(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.equals("PermuteReturn") || n.equals("io.quarkiverse.permuplate.PermuteReturn");
        });
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

    private static Set<String> expandExplicit(TypeDeclaration<?> classDecl,
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

    private static Set<String> expandImplicit(TypeDeclaration<?> classDecl,
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
            String boundStr = replaceTypeIdentifier(bound.toString(), sentinelName, newName);
            newBounds.add(StaticJavaParser.parseClassOrInterfaceType(boundStr));
        }
        newTp.setTypeBound(newBounds);
        return newTp;
    }

    private static Set<String> typeParamNames(TypeDeclaration<?> classDecl) {
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

    /**
     * Replaces occurrences of {@code oldName} with {@code newName} in a Java type string,
     * respecting word boundaries so that "B" does not match inside "Boolean" or "Builder".
     * Used by propagation to rename type parameter references in parameter type strings.
     */
    private static String replaceTypeIdentifier(String type, String oldName, String newName) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < type.length()) {
            int found = type.indexOf(oldName, idx);
            if (found < 0) {
                sb.append(type.substring(idx));
                break;
            }
            boolean before = found == 0 || !Character.isJavaIdentifierPart(type.charAt(found - 1));
            int end = found + oldName.length();
            boolean after = end >= type.length() || !Character.isJavaIdentifierPart(type.charAt(end));
            if (before && after) {
                sb.append(type, idx, found);
                sb.append(newName);
                idx = end;
            } else {
                sb.append(type.charAt(found));
                idx = found + 1;
            }
        }
        return sb.toString();
    }

    /** Returns the substring of name up to (but not including) its first digit. */
    private static String prefixBeforeDigits(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i)))
                return name.substring(0, i);
        }
        return name;
    }

    /**
     * Returns true if the parameter carries a {@code @PermuteDeclr} annotation.
     * Used during propagation: explicit {@code @PermuteDeclr} takes precedence over
     * the automatic rename propagated from a {@code @PermuteTypeParam} expansion.
     */
    private static boolean hasPermuteDeclrAnnotation(Parameter param) {
        return param.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.equals("PermuteDeclr") || n.equals("io.quarkiverse.permuplate.PermuteDeclr");
        });
    }

    /**
     * Returns true if the method carries a {@code @PermuteMethod} annotation.
     * Used in Step 5 of {@link #transform} to guard against double-processing:
     * {@code @PermuteMethod} methods are handled later in {@code applyPermuteMethod()}
     * with the inner {@code (i,j)} context, so processing them here (outer context only)
     * would produce incorrect output.
     */
    private static boolean isPermuteMethodAnnotated(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.equals("PermuteMethod") || n.equals("io.quarkiverse.permuplate.PermuteMethod");
        });
    }
}
