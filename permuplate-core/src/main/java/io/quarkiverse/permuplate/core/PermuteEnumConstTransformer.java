package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.List;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

/**
 * Handles {@code @PermuteEnumConst} on enum constant declarations.
 *
 * <p>
 * Replaces the annotated sentinel constant with zero or more generated constants
 * from the inner loop [{@code from}, {@code to}]. The sentinel is always removed;
 * an empty range produces no replacement constants.
 */
public class PermuteEnumConstTransformer {

    private static final String SIMPLE = "PermuteEnumConst";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteEnumConst";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        if (!(classDecl instanceof EnumDeclaration enumDecl))
            return;

        List<EnumConstantDeclaration> sentinels = new ArrayList<>();
        for (EnumConstantDeclaration ec : enumDecl.getEntries()) {
            if (ec.getAnnotations().stream().anyMatch(PermuteEnumConstTransformer::isPermuteEnumConst))
                sentinels.add(ec);
        }

        for (EnumConstantDeclaration sentinel : sentinels) {
            AnnotationExpr ann = sentinel.getAnnotations().stream()
                    .filter(PermuteEnumConstTransformer::isPermuteEnumConst)
                    .findFirst().orElseThrow();
            if (!(ann instanceof NormalAnnotationExpr normal)) {
                enumDecl.getEntries().remove(sentinel);
                continue;
            }

            String varName = null, from = null, to = null, nameTemplate = null, argsTemplate = "";
            for (MemberValuePair pair : normal.getPairs()) {
                String val = pair.getValue().asStringLiteralExpr().asString();
                switch (pair.getNameAsString()) {
                    case "varName" -> varName = val;
                    case "from" -> from = val;
                    case "to" -> to = val;
                    case "name" -> nameTemplate = val;
                    case "args" -> argsTemplate = val;
                }
            }
            if (varName == null || from == null || to == null || nameTemplate == null) {
                enumDecl.getEntries().remove(sentinel);
                continue;
            }

            int sentinelIdx = enumDecl.getEntries().indexOf(sentinel);

            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(from);
                toVal = ctx.evaluateInt(to);
            } catch (Exception ignored) {
                enumDecl.getEntries().remove(sentinel);
                continue;
            }

            enumDecl.getEntries().remove(sentinel);

            for (int k = fromVal; k <= toVal; k++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, k);
                String constName = innerCtx.evaluate(nameTemplate);
                EnumConstantDeclaration generated = new EnumConstantDeclaration(constName);

                String evaluatedArgs = argsTemplate.isEmpty() ? "" : innerCtx.evaluate(argsTemplate);
                if (!evaluatedArgs.isEmpty()) {
                    try {
                        NodeList<Expression> args = StaticJavaParser
                                .parseExpression("__DUMMY__(" + evaluatedArgs + ")")
                                .asMethodCallExpr()
                                .getArguments();
                        args.forEach(generated::addArgument);
                    } catch (Exception ignored) {
                    }
                }
                enumDecl.getEntries().add(sentinelIdx + (k - fromVal), generated);
            }
        }
    }

    static boolean isPermuteEnumConst(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(SIMPLE) || name.equals(FQ);
    }
}
