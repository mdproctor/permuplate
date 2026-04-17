package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;

/**
 * Handles {@code @PermuteStatements} on method and constructor declarations.
 *
 * <p>
 * Inserts statements at position="first" (before existing) or "last" (after).
 * With varName/from/to, loops like @PermuteCase. Without, inserts once.
 *
 * <p>
 * Applied AFTER {@link PermuteValueTransformer} so @PermuteValue indices
 * refer to the original template body.
 */
public class PermuteStatementsTransformer {

    private static final String SIMPLE = "PermuteStatements";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteStatements";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<AnnotationExpr> annOpt = method.getAnnotations().stream()
                    .filter(PermuteStatementsTransformer::isPermuteStatements)
                    .findFirst();
            if (annOpt.isEmpty())
                return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr normal))
                return;

            List<Statement> toInsert = buildInsertList(normal, ctx);
            String position = extractAttr(normal, "position");
            if (position == null || toInsert == null)
                return;

            method.getBody().ifPresent(methodBody -> insertStatements(methodBody, toInsert, position));
            method.getAnnotations().remove(ann);
        });

        classDecl.findAll(ConstructorDeclaration.class).forEach(constructor -> {
            Optional<AnnotationExpr> annOpt = constructor.getAnnotations().stream()
                    .filter(PermuteStatementsTransformer::isPermuteStatements)
                    .findFirst();
            if (annOpt.isEmpty())
                return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr normal))
                return;

            List<Statement> toInsert = buildInsertList(normal, ctx);
            String position = extractAttr(normal, "position");
            if (position == null || toInsert == null)
                return;

            insertStatements(constructor.getBody(), toInsert, position);
            constructor.getAnnotations().remove(ann);
        });
    }

    /**
     * Builds the list of statements to insert, evaluating the inner loop if configured.
     * Returns {@code null} if required attributes are missing or the range evaluation fails.
     */
    private static List<Statement> buildInsertList(NormalAnnotationExpr normal, EvaluationContext ctx) {
        String varName = null, from = null, to = null, body = null;
        for (MemberValuePair pair : normal.getPairs()) {
            String val = PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            switch (pair.getNameAsString()) {
                case "varName" -> varName = val;
                case "from" -> from = val;
                case "to" -> to = val;
                case "body" -> body = val;
            }
        }
        if (body == null)
            return null;

        final String finalBody = body;
        final String finalVarName = varName;
        final String finalFrom = from;
        final String finalTo = to;

        boolean hasLoop = finalVarName != null && !finalVarName.isEmpty()
                && finalFrom != null && !finalFrom.isEmpty()
                && finalTo != null && !finalTo.isEmpty();

        List<Statement> toInsert = new ArrayList<>();
        if (hasLoop) {
            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(finalFrom);
                toVal = ctx.evaluateInt(finalTo);
            } catch (Exception ignored) {
                return null;
            }
            for (int k = fromVal; k <= toVal; k++) {
                EvaluationContext innerCtx = ctx.withVariable(finalVarName, k);
                String evaluated = innerCtx.evaluate(finalBody);
                parseStatements(evaluated).forEach(toInsert::add);
            }
        } else {
            String evaluated = ctx.evaluate(finalBody);
            parseStatements(evaluated).forEach(toInsert::add);
        }
        return toInsert;
    }

    private static void insertStatements(BlockStmt block, List<Statement> toInsert, String position) {
        if ("first".equals(position)) {
            for (int i = toInsert.size() - 1; i >= 0; i--) {
                block.getStatements().add(0, toInsert.get(i).clone());
            }
        } else { // "last"
            toInsert.forEach(s -> block.getStatements().add(s.clone()));
        }
    }

    private static String extractAttr(NormalAnnotationExpr normal, String attrName) {
        for (MemberValuePair pair : normal.getPairs()) {
            if (pair.getNameAsString().equals(attrName)) {
                return PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            }
        }
        return null;
    }

    private static List<Statement> parseStatements(String bodyStr) {
        // First try parseBlock (handles ordinary statements and multi-statement bodies).
        // Falls back to parseStatement for constructs that parseBlock doesn't support,
        // such as explicit constructor invocations (super(...), this(...)).
        try {
            BlockStmt block = StaticJavaParser.parseBlock("{" + bodyStr + "}");
            return new ArrayList<>(block.getStatements());
        } catch (Exception e) {
            // ignored — try single-statement parse
        }
        try {
            Statement stmt = StaticJavaParser.parseStatement(bodyStr);
            List<Statement> result = new ArrayList<>();
            result.add(stmt);
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    static boolean isPermuteStatements(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(SIMPLE) || name.equals(FQ);
    }
}
