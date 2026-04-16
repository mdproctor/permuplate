package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;

/**
 * Adds Java annotations to generated types, methods, and fields based on
 * {@code @PermuteAnnotation} / {@code @PermuteAnnotations} on the template element.
 * Runs LAST in the transform pipeline.
 */
public class PermuteAnnotationTransformer {

    private static final String SIMPLE = "PermuteAnnotation";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteAnnotation";
    private static final String SIMPLE_CTR = "PermuteAnnotations";
    private static final String FQ_CTR = "io.quarkiverse.permuplate.PermuteAnnotations";

    public static void transform(TypeDeclaration<?> classDecl,
            EvaluationContext ctx,
            Messager messager,
            javax.lang.model.element.Element element) {
        processElement(classDecl.getAnnotations(), classDecl::addAnnotation, ctx, messager, element);
        classDecl.getMethods().forEach(m -> processElement(m.getAnnotations(), m::addAnnotation, ctx, messager, element));
        classDecl.getFields().forEach(f -> processElement(f.getAnnotations(), f::addAnnotation, ctx, messager, element));
    }

    private static void processElement(NodeList<AnnotationExpr> annotations,
            Consumer<AnnotationExpr> adder,
            EvaluationContext ctx,
            Messager messager,
            javax.lang.model.element.Element element) {
        List<AnnotationExpr> toRemove = new ArrayList<>();
        List<AnnotationExpr> toAdd = new ArrayList<>();

        for (AnnotationExpr ann : annotations) {
            String name = ann.getNameAsString();
            if (FQ.equals(name) || SIMPLE.equals(name)) {
                toRemove.add(ann);
                applyOne(ann, ctx, messager, element, toAdd);
            } else if (FQ_CTR.equals(name) || SIMPLE_CTR.equals(name)) {
                toRemove.add(ann);
                if (ann instanceof NormalAnnotationExpr normal) {
                    for (MemberValuePair pair : normal.getPairs()) {
                        if ("value".equals(pair.getNameAsString())
                                && pair.getValue() instanceof ArrayInitializerExpr arr) {
                            for (Expression inner : arr.getValues()) {
                                applyOne(inner, ctx, messager, element, toAdd);
                            }
                        }
                    }
                }
            }
        }

        toRemove.forEach(annotations::remove);
        toAdd.forEach(adder);
    }

    private static void applyOne(Expression annExpr,
            EvaluationContext ctx,
            Messager messager,
            javax.lang.model.element.Element element,
            List<AnnotationExpr> toAdd) {
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
                            "@PermuteAnnotation when expression error (annotation skipped): "
                                    + when + " — " + e.getMessage(),
                            element);
                }
                return;
            }
        }

        String evaluated = ctx.evaluate(value);
        try {
            toAdd.add(StaticJavaParser.parseAnnotation(evaluated));
        } catch (Exception e) {
            if (messager != null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteAnnotation value is not a valid annotation: \"" + evaluated + "\"",
                        element);
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
