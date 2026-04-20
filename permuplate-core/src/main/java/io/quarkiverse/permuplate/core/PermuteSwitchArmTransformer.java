package io.quarkiverse.permuplate.core;

import java.util.Optional;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;

/**
 * Handles {@code @PermuteSwitchArm} on method declarations.
 *
 * <p>
 * For each generated class at arity {@code i}, inserts new {@link SwitchEntry}
 * arrow-switch arms into the method's switch statement or expression for each value
 * {@code k} in [{@code from}, {@code to}]. The seed arms and {@code default} arm are
 * preserved unchanged.
 *
 * <p>
 * Both {@code SwitchStmt} (standalone switch) and {@code SwitchExpr}
 * ({@code return switch (x) {...}}) are supported — the entries are extracted from
 * whichever node is found in the method body.
 *
 * <p>
 * Arms are parsed using the global JavaParser language level (JAVA_21) which
 * supports type patterns ({@code case Shape s ->}) and guard conditions
 * ({@code case Shape s when s.area() > 0 ->}).
 */
public class PermuteSwitchArmTransformer {

    private static final String ANNOTATION_SIMPLE = "PermuteSwitchArm";
    private static final String ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteSwitchArm";

    public static void transform(TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<AnnotationExpr> annOpt = method.getAnnotations().stream()
                    .filter(PermuteSwitchArmTransformer::isPermuteSwitchArm)
                    .findFirst();
            if (annOpt.isEmpty())
                return;

            AnnotationExpr ann = annOpt.get();
            if (!(ann instanceof NormalAnnotationExpr normal))
                return;

            String varName = null, from = null, to = null,
                    patternExpr = null, bodyExpr = null, whenExpr = "";
            for (MemberValuePair pair : normal.getPairs()) {
                // asStringLiteralExpr().asString() unescapes Java sequences (e.g. \" → ")
                // so the body can be reparsed as valid Java source by StaticJavaParser.
                String val = pair.getValue().asStringLiteralExpr().asString();
                switch (pair.getNameAsString()) {
                    case "varName" -> varName = val;
                    case "from" -> from = val;
                    case "to" -> to = val;
                    case "pattern" -> patternExpr = val;
                    case "body" -> bodyExpr = val;
                    case "when" -> whenExpr = val;
                }
            }
            if (varName == null || from == null || to == null
                    || patternExpr == null || bodyExpr == null)
                return;

            // Find the switch in the method body — handles both switch statement and switch expression
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

            // Remove annotation before potentially returning (empty range is valid)
            method.getAnnotations().remove(ann);

            if (fromVal > toVal)
                return; // empty range — no arms to insert

            int defaultIdx = findDefaultArmIndex(entries);

            for (int k = fromVal; k <= toVal; k++) {
                EvaluationContext innerCtx = ctx.withVariable(varName, k);
                String pattern = innerCtx.evaluate(patternExpr);
                String body = innerCtx.evaluate(bodyExpr);
                String guard = whenExpr.isEmpty() ? "" : innerCtx.evaluate(whenExpr);

                SwitchEntry arm = buildArm(pattern, guard, body);
                entries.add(defaultIdx, arm);
                defaultIdx++; // default shifts right after each insertion
            }
        });
    }

    /**
     * Finds the {@link NodeList} of {@link SwitchEntry} nodes in the method body,
     * handling both {@code SwitchStmt} (standalone) and {@code SwitchExpr}
     * ({@code return switch (x) {...}}).
     *
     * @return the entries list, or {@code null} if no switch is found
     */
    private static NodeList<SwitchEntry> findSwitchEntries(MethodDeclaration method) {
        // Try switch statement first
        Optional<SwitchStmt> stmtOpt = method.getBody()
                .flatMap(body -> body.findFirst(SwitchStmt.class));
        if (stmtOpt.isPresent())
            return stmtOpt.get().getEntries();

        // Fall back to switch expression (e.g. "return switch (x) { ... }")
        Optional<SwitchExpr> exprOpt = method.getBody()
                .flatMap(body -> body.findFirst(SwitchExpr.class));
        if (exprOpt.isPresent())
            return exprOpt.get().getEntries();

        return null;
    }

    private static int findDefaultArmIndex(NodeList<SwitchEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getLabels().isEmpty())
                return i; // default has no labels in both colon- and arrow-switch
        }
        return entries.size();
    }

    /**
     * Builds a single arrow-switch arm by parsing a synthetic switch statement
     * and extracting the first entry.
     *
     * <p>
     * Synthetic form:
     * {@code switch (__x__) { case <pattern> [when <guard>] -> { <body> } default -> { throw ...; } }}
     *
     * <p>
     * The parser language level is JAVA_21 (set globally), which supports type
     * patterns and guard conditions.
     */
    private static SwitchEntry buildArm(String pattern, String guard, String body) {
        String guardPart = guard.isEmpty() ? "" : " when " + guard;
        String normalized = body.trim();
        // Auto-append semicolon if body is not a block and doesn't already end with one.
        if (!normalized.startsWith("{") && !normalized.endsWith(";")) {
            normalized = normalized + ";";
        }
        String blockBody = normalized.startsWith("{") ? normalized : "{ " + normalized + " }";
        String synthetic = "switch (__x__) { case " + pattern + guardPart
                + " -> " + blockBody + " default -> { throw new UnsupportedOperationException(); } }";

        SwitchStmt temp = StaticJavaParser.parseStatement(synthetic).asSwitchStmt();
        return temp.getEntries().get(0).clone();
    }

    static boolean isPermuteSwitchArm(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(ANNOTATION_SIMPLE) || name.equals(ANNOTATION_FQ);
    }
}
