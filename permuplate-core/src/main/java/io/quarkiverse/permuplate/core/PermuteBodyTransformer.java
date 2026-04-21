package io.quarkiverse.permuplate.core;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;

/**
 * Handles {@code @PermuteBody} (and the {@code @PermuteBodies} container) on method and
 * constructor declarations.
 *
 * <p>
 * Replaces the entire method or constructor body with a JEXL-evaluated template.
 * The {@code body} attribute must include surrounding braces, e.g. {@code "{ return ${i}; }"}.
 *
 * <p>
 * When multiple {@code @PermuteBody} annotations are present (via {@code @PermuteBodies}
 * container or directly), the first one whose {@code when=} condition evaluates to
 * {@code true} is applied. An annotation with no {@code when=} always matches.
 *
 * <p>
 * Applied AFTER {@link PermuteStatementsTransformer} so that any @PermuteStatements
 * insertions are overridden if both annotations are present (unusual but allowed).
 */
public class PermuteBodyTransformer {

    private static final String SIMPLE = "PermuteBody";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteBody";
    private static final String CONTAINER_SIMPLE = "PermuteBodies";
    private static final String CONTAINER_FQ = "io.quarkiverse.permuplate.PermuteBodies";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            BodyEntry selected = selectBody(method.getAnnotations(), ctx);
            if (selected == null)
                return;

            String evaluated = ctx.evaluate(selected.bodyTemplate());
            BlockStmt newBody = StaticJavaParser.parseBlock(evaluated);
            method.setBody(newBody);
            // Remove all @PermuteBody / @PermuteBodies annotations
            method.getAnnotations().removeIf(PermuteBodyTransformer::isPermuteBodyOrContainer);
        });

        classDecl.findAll(ConstructorDeclaration.class).forEach(constructor -> {
            BodyEntry selected = selectBody(constructor.getAnnotations(), ctx);
            if (selected == null)
                return;

            String evaluated = ctx.evaluate(selected.bodyTemplate());
            BlockStmt newBody = StaticJavaParser.parseBlock(evaluated);
            constructor.setBody(newBody);
            // Remove all @PermuteBody / @PermuteBodies annotations
            constructor.getAnnotations().removeIf(PermuteBodyTransformer::isPermuteBodyOrContainer);
        });
    }

    /**
     * Selects the first matching {@code @PermuteBody} entry from the annotation list.
     * Returns {@code null} if no {@code @PermuteBody} or {@code @PermuteBodies} is present,
     * or if none of the {@code when=} conditions is satisfied.
     */
    private static BodyEntry selectBody(com.github.javaparser.ast.NodeList<AnnotationExpr> annotations,
            EvaluationContext ctx) {
        for (AnnotationExpr ann : annotations) {
            String n = ann.getNameAsString();
            if (n.equals(CONTAINER_SIMPLE) || n.equals(CONTAINER_FQ)) {
                // Unwrap @PermuteBodies container
                if (!(ann instanceof NormalAnnotationExpr normal))
                    continue;
                for (MemberValuePair p : normal.getPairs()) {
                    if (!p.getNameAsString().equals("value"))
                        continue;
                    if (!(p.getValue() instanceof ArrayInitializerExpr arr))
                        continue;
                    for (Expression v : arr.getValues()) {
                        if (!(v instanceof AnnotationExpr inner))
                            continue;
                        BodyEntry entry = extractBodyEntry(inner, ctx);
                        if (entry != null)
                            return entry;
                    }
                }
            } else if (n.equals(SIMPLE) || n.equals(FQ)) {
                BodyEntry entry = extractBodyEntry(ann, ctx);
                if (entry != null)
                    return entry;
            }
        }
        return null;
    }

    /**
     * Extracts body template from a single {@code @PermuteBody} annotation, evaluating
     * the {@code when=} guard. Returns {@code null} if the guard is false or the body
     * attribute is missing.
     */
    private static BodyEntry extractBodyEntry(AnnotationExpr ann, EvaluationContext ctx) {
        if (!(ann instanceof NormalAnnotationExpr normal))
            return null;

        String bodyTemplate = null;
        String when = "";
        for (MemberValuePair pair : normal.getPairs()) {
            switch (pair.getNameAsString()) {
                case "body" -> bodyTemplate = pair.getValue().asStringLiteralExpr().asString();
                case "when" -> when = pair.getValue().asStringLiteralExpr().asString();
            }
        }

        if (bodyTemplate == null)
            return null;

        // No when= condition means always match
        if (when.isEmpty())
            return new BodyEntry(bodyTemplate);

        // Evaluate when= condition
        try {
            boolean matches = Boolean.parseBoolean(ctx.evaluate("${" + when + "}"));
            return matches ? new BodyEntry(bodyTemplate) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isPermuteBody(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(SIMPLE) || name.equals(FQ);
    }

    private static boolean isPermuteBodyOrContainer(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(SIMPLE) || name.equals(FQ)
                || name.equals(CONTAINER_SIMPLE) || name.equals(CONTAINER_FQ);
    }

    private record BodyEntry(String bodyTemplate) {
    }
}
