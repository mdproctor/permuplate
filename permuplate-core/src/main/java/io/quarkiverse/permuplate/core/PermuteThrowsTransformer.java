package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ReferenceType;

/**
 * Adds exception types to method {@code throws} clauses based on
 * {@code @PermuteThrows} / {@code @PermuteThrowsList} on the template method.
 * Add-only. Runs after PermuteAnnotationTransformer.
 */
public class PermuteThrowsTransformer {

    private static final String SIMPLE = "PermuteThrows";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteThrows";
    private static final String SIMPLE_CTR = "PermuteThrowsList";
    private static final String FQ_CTR = "io.quarkiverse.permuplate.PermuteThrowsList";

    public static void transform(TypeDeclaration<?> classDecl,
            EvaluationContext ctx,
            Messager messager,
            javax.lang.model.element.Element element) {
        classDecl.getMethods().forEach(method -> processMethod(method, ctx, messager, element));
    }

    private static void processMethod(MethodDeclaration method,
            EvaluationContext ctx,
            Messager messager,
            javax.lang.model.element.Element element) {
        List<AnnotationExpr> toRemove = new ArrayList<>();

        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            if (FQ.equals(name) || SIMPLE.equals(name)) {
                toRemove.add(ann);
                applyOne(ann, method, ctx, messager, element);
            } else if (FQ_CTR.equals(name) || SIMPLE_CTR.equals(name)) {
                toRemove.add(ann);
                if (ann instanceof NormalAnnotationExpr normal) {
                    for (MemberValuePair pair : normal.getPairs()) {
                        if ("value".equals(pair.getNameAsString())
                                && pair.getValue() instanceof ArrayInitializerExpr arr) {
                            arr.getValues().forEach(inner -> applyOne(inner, method, ctx, messager, element));
                        }
                    }
                }
            }
        }

        toRemove.forEach(method.getAnnotations()::remove);
    }

    private static void applyOne(Expression annExpr,
            MethodDeclaration method,
            EvaluationContext ctx,
            Messager messager,
            javax.lang.model.element.Element element) {
        String when = extractAttr(annExpr, "when");
        String value = extractAttr(annExpr, "value");
        if (value == null)
            return;

        if (when != null && !when.isEmpty()) {
            try {
                if (!ctx.evaluateBoolean(when))
                    return;
            } catch (Exception e) {
                if (messager != null) {
                    messager.printMessage(Diagnostic.Kind.WARNING,
                            "@PermuteThrows when expression error (entry skipped): "
                                    + when + " — " + e.getMessage(),
                            element);
                }
                return;
            }
        }

        String evaluated = ctx.evaluate(value);
        try {
            ReferenceType type = StaticJavaParser.parseClassOrInterfaceType(evaluated);
            method.addThrownException(type);
        } catch (Exception e) {
            if (messager != null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteThrows value does not parse as a type: \"" + evaluated + "\"",
                        element);
            } else {
                throw new IllegalStateException(
                        "@PermuteThrows value does not parse as a type: \"" + evaluated + "\"", e);
            }
        }
    }

    private static String extractAttr(Expression annExpr, String attrName) {
        if (annExpr instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (attrName.equals(pair.getNameAsString())
                        && pair.getValue() instanceof StringLiteralExpr lit) {
                    return lit.asString();
                }
            }
        } else if (annExpr instanceof SingleMemberAnnotationExpr single
                && "value".equals(attrName)
                && single.getMemberValue() instanceof StringLiteralExpr lit) {
            return lit.asString();
        }
        return null;
    }
}
