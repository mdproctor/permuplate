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
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;

/**
 * Handles {@code @PermuteCase} on method declarations.
 *
 * <p>
 * For each generated class at arity {@code i}, inserts new {@link SwitchEntry} nodes
 * into the method's switch statement for each value {@code k} in [{@code from}, {@code to}].
 * The seed case and {@code default} case are preserved. No {@code super()} calls are
 * generated — all cases are inlined directly, avoiding extra stack frames.
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
                // asStringLiteralExpr().asString() unescapes Java sequences (e.g. \" → ")
                // so the body can be reparsed as valid Java source by StaticJavaParser.
                String val = pair.getValue().asStringLiteralExpr().asString();
                switch (pair.getNameAsString()) {
                    case "varName" -> varName = val;
                    case "from" -> from = val;
                    case "to" -> to = val;
                    case "index" -> indexExpr = val;
                    case "body" -> bodyExpr = val;
                }
            }
            if (varName == null || from == null || to == null || indexExpr == null || bodyExpr == null)
                return;

            // Find the switch statement in the method body
            Optional<SwitchStmt> switchOpt = method.getBody()
                    .flatMap(body -> body.findFirst(SwitchStmt.class));
            if (switchOpt.isEmpty())
                return;

            SwitchStmt sw = switchOpt.get();

            // Evaluate loop bounds
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
                return; // empty range — no cases to insert

            // Find index of the default case (insert before it)
            int defaultIdx = findDefaultCaseIndex(sw);

            // Insert cases for k = fromVal..toVal
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

                SwitchEntry entry = buildSwitchEntry(caseLabel, caseBodyStr);
                sw.getEntries().add(defaultIdx, entry);
                defaultIdx++; // default shifts right after each insertion
            }
        });
    }

    private static int findDefaultCaseIndex(SwitchStmt sw) {
        NodeList<SwitchEntry> entries = sw.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getLabels().isEmpty())
                return i; // default has no labels
        }
        return entries.size();
    }

    private static SwitchEntry buildSwitchEntry(int label, String bodyStr) {
        // Wrap in block to parse multiple statements (e.g. "this.b = t; break;")
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
