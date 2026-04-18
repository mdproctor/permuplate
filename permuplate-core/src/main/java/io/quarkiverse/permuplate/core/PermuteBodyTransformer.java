package io.quarkiverse.permuplate.core;

import java.util.Optional;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;

/**
 * Handles {@code @PermuteBody} on method and constructor declarations.
 *
 * <p>
 * Replaces the entire method or constructor body with a JEXL-evaluated template.
 * The {@code body} attribute must include surrounding braces, e.g. {@code "{ return ${i}; }"}.
 *
 * <p>
 * Applied AFTER {@link PermuteStatementsTransformer} so that any @PermuteStatements
 * insertions are overridden if both annotations are present (unusual but allowed).
 */
public class PermuteBodyTransformer {

    private static final String SIMPLE = "PermuteBody";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteBody";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<AnnotationExpr> annOpt = method.getAnnotations().stream()
                    .filter(PermuteBodyTransformer::isPermuteBody)
                    .findFirst();
            if (annOpt.isEmpty())
                return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr normal))
                return;

            String bodyTemplate = extractBody(normal);
            if (bodyTemplate == null)
                return;

            String evaluated = ctx.evaluate(bodyTemplate);
            BlockStmt newBody = StaticJavaParser.parseBlock(evaluated);
            method.setBody(newBody);
            method.getAnnotations().remove(ann);
        });

        classDecl.findAll(ConstructorDeclaration.class).forEach(constructor -> {
            Optional<AnnotationExpr> annOpt = constructor.getAnnotations().stream()
                    .filter(PermuteBodyTransformer::isPermuteBody)
                    .findFirst();
            if (annOpt.isEmpty())
                return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr normal))
                return;

            String bodyTemplate = extractBody(normal);
            if (bodyTemplate == null)
                return;

            String evaluated = ctx.evaluate(bodyTemplate);
            BlockStmt newBody = StaticJavaParser.parseBlock(evaluated);
            constructor.setBody(newBody);
            constructor.getAnnotations().remove(ann);
        });
    }

    private static String extractBody(NormalAnnotationExpr normal) {
        for (MemberValuePair pair : normal.getPairs()) {
            if ("body".equals(pair.getNameAsString())) {
                return pair.getValue().asStringLiteralExpr().asString();
            }
        }
        return null;
    }

    static boolean isPermuteBody(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(SIMPLE) || name.equals(FQ);
    }
}
