package io.quarkiverse.permuplate.core;

import java.util.Optional;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;

/**
 * Handles {@code @PermuteCase} on method declarations.
 *
 * <p>
 * For each generated class at arity {@code i}, inserts new {@link SwitchEntry} nodes
 * into the method's switch statement or expression for each value {@code k} in
 * [{@code from}, {@code to}]. The seed cases and {@code default} case are preserved.
 *
 * <p>
 * Supports both colon-switch ({@code case N: body; break;}) and arrow-switch
 * ({@code case N -> body}). The form is detected from the existing entries in the switch:
 * if any entry uses arrow form ({@code EXPRESSION}, {@code BLOCK}, or
 * {@code THROWS_STATEMENT} type), new arms are generated in arrow form; otherwise colon
 * form is used (existing behaviour, unchanged).
 *
 * <p>
 * Both {@link SwitchStmt} (standalone switch) and {@link SwitchExpr}
 * ({@code return switch (x) \{...\}}) are supported.
 */
public class PermuteCaseTransformer {

    private static final String ANNOTATION_SIMPLE = "PermuteCase";
    private static final String ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteCase";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<AnnotationExpr> annOpt = method.getAnnotations().stream()
                    .filter(PermuteCaseTransformer::isPermuteCase)
                    .findFirst();
            if (annOpt.isEmpty())
                return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr normal))
                return;

            String varName = null, from = null, to = null, indexExpr = null, bodyExpr = null;
            for (MemberValuePair pair : normal.getPairs()) {
                String val = pair.getValue().asStringLiteralExpr().asString();
                switch (pair.getNameAsString()) {
                    case "varName" -> varName = val;
                    case "from" -> from = val;
                    case "to" -> to = val;
                    case "index" -> indexExpr = val;
                    case "body" -> bodyExpr = val;
                }
            }
            if (varName == null || from == null || to == null
                    || indexExpr == null || bodyExpr == null)
                return;

            NodeList<SwitchEntry> entries = findSwitchEntries(method);
            if (entries == null)
                return;

            int fromVal, toVal;
            try {
                fromVal = ctx.evaluateInt(from);
                toVal = ctx.evaluateInt(to);
            } catch (Exception ignored) {
                return;
            }

            // Remove annotation before potentially returning (empty range is still valid)
            method.getAnnotations().remove(ann);

            if (fromVal > toVal)
                return;

            boolean arrowForm = isArrowSwitch(entries);
            int defaultIdx = findDefaultCaseIndex(entries);

            for (int k = fromVal; k <= toVal; k++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, k);

                String caseLabelStr = innerCtx.evaluate(indexExpr);
                String caseBodyStr = innerCtx.evaluate(bodyExpr);

                int caseLabel;
                try {
                    caseLabel = Integer.parseInt(caseLabelStr.trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }

                SwitchEntry entry = arrowForm
                        ? buildArrowEntry(caseLabel, caseBodyStr)
                        : buildColonEntry(caseLabel, caseBodyStr);
                entries.add(defaultIdx, entry);
                defaultIdx++;
            }
        });
    }

    /**
     * Finds the entries of the first switch (statement or expression) in the method body.
     * Returns {@code null} if no switch is found.
     */
    private static NodeList<SwitchEntry> findSwitchEntries(MethodDeclaration method) {
        Optional<SwitchStmt> stmtOpt = method.getBody()
                .flatMap(body -> body.findFirst(SwitchStmt.class));
        if (stmtOpt.isPresent())
            return stmtOpt.get().getEntries();

        Optional<SwitchExpr> exprOpt = method.getBody()
                .flatMap(body -> body.findFirst(SwitchExpr.class));
        if (exprOpt.isPresent())
            return exprOpt.get().getEntries();

        return null;
    }

    /**
     * Returns {@code true} if the existing switch entries use arrow form
     * ({@code EXPRESSION}, {@code BLOCK}, or {@code THROWS_STATEMENT}).
     */
    private static boolean isArrowSwitch(NodeList<SwitchEntry> entries) {
        for (SwitchEntry e : entries) {
            SwitchEntry.Type t = e.getType();
            if (t == SwitchEntry.Type.EXPRESSION
                    || t == SwitchEntry.Type.BLOCK
                    || t == SwitchEntry.Type.THROWS_STATEMENT) {
                return true;
            }
        }
        return false;
    }

    private static int findDefaultCaseIndex(NodeList<SwitchEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getLabels().isEmpty())
                return i;
        }
        return entries.size();
    }

    /**
     * Builds an arrow-switch arm: {@code case <label> -> { <body> }}.
     * Auto-appends ';' to non-block bodies that lack it.
     * Parsed via synthetic switch with the global JAVA_21 language level.
     */
    private static SwitchEntry buildArrowEntry(int label, String bodyStr) {
        String normalized = bodyStr.trim();
        if (!normalized.startsWith("{") && !normalized.endsWith(";"))
            normalized = normalized + ";";
        String blockBody = normalized.startsWith("{") ? normalized : "{ " + normalized + " }";
        String synthetic = "switch (__x__) { case " + label + " -> " + blockBody
                + " default -> { throw new UnsupportedOperationException(); } }";
        SwitchStmt temp = StaticJavaParser.parseStatement(synthetic).asSwitchStmt();
        return temp.getEntries().get(0).clone();
    }

    /**
     * Builds a colon-switch entry: {@code case <label>: <statements>}.
     * This is the original behaviour, preserved for all colon-switch templates.
     */
    private static SwitchEntry buildColonEntry(int label, String bodyStr) {
        BlockStmt block = StaticJavaParser.parseBlock("{" + bodyStr + "}");
        SwitchEntry entry = new SwitchEntry();
        entry.setType(SwitchEntry.Type.STATEMENT_GROUP);
        entry.getLabels().add(new IntegerLiteralExpr(String.valueOf(label)));
        entry.getStatements().addAll(block.getStatements());
        return entry;
    }

    static boolean isPermuteCase(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(ANNOTATION_SIMPLE) || name.equals(ANNOTATION_FQ);
    }
}
