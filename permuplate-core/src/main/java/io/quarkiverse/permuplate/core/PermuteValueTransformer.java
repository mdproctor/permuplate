package io.quarkiverse.permuplate.core;

import java.util.Optional;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.Statement;

/**
 * Handles {@code @PermuteValue} on fields, local variables, and methods.
 *
 * <p>
 * On a field or local variable: replaces the initializer (like @PermuteConst).
 * <p>
 * On a method: replaces the RHS of the assignment statement at {@code index}
 * in the original template body (0-based, evaluated BEFORE @PermuteStatements insertions).
 */
public class PermuteValueTransformer {

    private static final String SIMPLE = "PermuteValue";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteValue";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        // --- Fields ---
        classDecl.getFields().forEach(field -> field.getAnnotations().stream()
                .filter(PermuteValueTransformer::isPermuteValue)
                .findFirst()
                .ifPresent(ann -> {
                    extractValue(ann).ifPresent(expr -> {
                        String evaluated = ctx.evaluate(expr);
                        field.getVariable(0).setInitializer(PermuteDeclrTransformer.toExpression(evaluated));
                    });
                    field.getAnnotations().remove(ann);
                }));

        // --- Local variables (not for-each) ---
        classDecl.walk(VariableDeclarationExpr.class, vde -> {
            if (vde.getParentNode().map(p -> p instanceof ForEachStmt).orElse(false))
                return;
            vde.getAnnotations().stream()
                    .filter(PermuteValueTransformer::isPermuteValue)
                    .findFirst()
                    .ifPresent(ann -> {
                        extractValue(ann).ifPresent(expr -> {
                            String evaluated = ctx.evaluate(expr);
                            vde.getVariables().get(0).setInitializer(PermuteDeclrTransformer.toExpression(evaluated));
                        });
                        vde.getAnnotations().remove(ann);
                    });
        });

        // --- Methods (statement RHS replacement by index) ---
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            method.getAnnotations().stream()
                    .filter(PermuteValueTransformer::isPermuteValue)
                    .findFirst()
                    .ifPresent(ann -> {
                        int idx = extractIndex(ann);
                        String valueExpr = extractValue(ann).orElse(null);
                        if (idx >= 0 && valueExpr != null) {
                            method.getBody().ifPresent(body -> {
                                if (idx < body.getStatements().size()) {
                                    Statement stmt = body.getStatements().get(idx);
                                    replaceAssignmentRhs(stmt, ctx.evaluate(valueExpr));
                                }
                            });
                        }
                        method.getAnnotations().remove(ann);
                    });
        });

        // --- Constructors (statement RHS replacement by index) ---
        classDecl.findAll(ConstructorDeclaration.class).forEach(constructor -> {
            constructor.getAnnotations().stream()
                    .filter(PermuteValueTransformer::isPermuteValue)
                    .findFirst()
                    .ifPresent(ann -> {
                        int idx = extractIndex(ann);
                        String valueExpr = extractValue(ann).orElse(null);
                        if (idx >= 0 && valueExpr != null) {
                            com.github.javaparser.ast.stmt.BlockStmt body = constructor.getBody();
                            if (idx < body.getStatements().size()) {
                                Statement stmt = body.getStatements().get(idx);
                                replaceAssignmentRhs(stmt, ctx.evaluate(valueExpr));
                            }
                        }
                        constructor.getAnnotations().remove(ann);
                    });
        });
    }

    private static void replaceAssignmentRhs(Statement stmt, String evaluated) {
        if (!(stmt instanceof ExpressionStmt es))
            return;
        if (!(es.getExpression() instanceof AssignExpr assign))
            return;
        try {
            assign.setValue(PermuteDeclrTransformer.toExpression(evaluated));
        } catch (Exception ignored) {
        }
    }

    static boolean isPermuteValue(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(SIMPLE) || name.equals(FQ);
    }

    private static Optional<String> extractValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr s)
            return Optional.of(PermuteDeclrTransformer.stripQuotes(s.getMemberValue().toString()));
        if (ann instanceof NormalAnnotationExpr n)
            return n.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value"))
                    .findFirst()
                    .map(p -> PermuteDeclrTransformer.stripQuotes(p.getValue().toString()));
        return Optional.empty();
    }

    private static int extractIndex(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr n)
            return n.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("index"))
                    .findFirst()
                    .map(p -> {
                        try {
                            return Integer.parseInt(p.getValue().toString());
                        } catch (Exception e) {
                            return -1;
                        }
                    })
                    .orElse(-1);
        return -1;
    }
}
