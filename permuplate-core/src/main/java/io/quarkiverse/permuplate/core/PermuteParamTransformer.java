package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

/**
 * Handles {@code @PermuteParam} on method parameters.
 *
 * <p>
 * A method may carry multiple {@code @PermuteParam} sentinels, each expanding
 * independently. Sentinels are processed left-to-right in declaration order.
 * For each sentinel:
 * <ol>
 * <li>Records the sentinel parameter's original name as the call-site <em>anchor</em></li>
 * <li>Evaluates the inner loop range ({@code from}/{@code to}) using the outer context</li>
 * <li>Generates the expanded parameter sequence via the inner variable</li>
 * <li>Replaces the sentinel in the method parameter list with the generated sequence,
 * preserving parameters before and after the sentinel in their original positions</li>
 * <li>Walks all method call expressions in the method body: any argument list containing
 * the anchor name has it replaced by the full generated argument sequence</li>
 * </ol>
 *
 * <p>
 * Processing is iterative: after each sentinel is expanded (removing its
 * {@code @PermuteParam} annotation from the parameter list), the next sentinel is
 * located by re-scanning the current parameter list. This means each expansion's
 * call-site rewriting is visible to subsequent expansions — anchors at shared call
 * sites are expanded correctly in sequence.
 */
public class PermuteParamTransformer {

    private static final String ANNOTATION_SIMPLE = "PermuteParam";
    private static final String ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteParam";

    public static void transform(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager) {
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            // Process sentinels in order. Each transformMethod call removes one sentinel
            // (replacing it with expanded params that carry no @PermuteParam), so
            // re-scanning naturally finds the next remaining sentinel.
            Parameter sentinel;
            while ((sentinel = findNextSentinel(method)) != null) {
                transformMethod(method, sentinel, ctx, messager);
            }
        });
    }

    private static Parameter findNextSentinel(MethodDeclaration method) {
        return method.getParameters().stream()
                .filter(p -> hasPermuteParam(p.getAnnotations()))
                .findFirst()
                .orElse(null);
    }

    private static void transformMethod(MethodDeclaration method,
            Parameter sentinel,
            EvaluationContext ctx,
            Messager messager) {
        AnnotationExpr ann = getPermuteParam(sentinel.getAnnotations());
        PermuteParamValues values = extractValues(ann, messager);
        if (values == null)
            return;

        String anchorName = sentinel.getNameAsString();

        // Evaluate inner range using outer context
        int fromVal = ctx.evaluateInt(values.from);
        int toVal = ctx.evaluateInt(values.to);

        // Build expanded parameter list
        NodeList<Parameter> newParams = new NodeList<>();
        List<String> generatedArgNames = new ArrayList<>();
        for (int j = fromVal; j <= toVal; j++) {
            EvaluationContext innerCtx = ctx.withVariable(values.varName, j);
            String paramType = innerCtx.evaluate(values.type);
            String paramName = innerCtx.evaluate(values.name);
            newParams.add(new Parameter(new ClassOrInterfaceType(null, paramType), paramName));
            generatedArgNames.add(paramName);
        }

        // Find the sentinel's position so params before and after it are preserved.
        // e.g. method(String ctx, @PermuteParam ... Object o1, List results)
        // becomes method(String ctx, Object o1, Object o2, List results) for i=3.
        NodeList<Parameter> origParams = method.getParameters();
        int sentinelIdx = origParams.indexOf(sentinel);

        NodeList<Parameter> allParams = new NodeList<>();
        for (int k = 0; k < sentinelIdx; k++) {
            allParams.add(origParams.get(k).clone());
        }
        allParams.addAll(newParams);
        for (int k = sentinelIdx + 1; k < origParams.size(); k++) {
            allParams.add(origParams.get(k).clone());
        }
        method.setParameters(allParams);

        // Expand anchor at all call sites within the method body
        expandAnchorAtCallSites(method, anchorName, generatedArgNames);
    }

    /**
     * Walks all method call expressions in the method body. When an argument list
     * contains a {@link NameExpr} whose name matches {@code anchorName}, that single
     * argument is replaced by the full generated argument sequence.
     *
     * <p>
     * Other arguments in the call (e.g. the for-each variable, already renamed by
     * {@link PermuteDeclrTransformer}) are preserved and follow the expanded sequence.
     */
    private static void expandAnchorAtCallSites(MethodDeclaration method,
            String anchorName,
            List<String> generatedArgNames) {
        method.getBody().ifPresent(body -> body.accept(new ModifierVisitor<Void>() {
            @Override
            public Visitable visit(MethodCallExpr call, Void arg) {
                // Let the visitor descend first (handles nested calls)
                super.visit(call, arg);

                NodeList<Expression> args = call.getArguments();
                int anchorIdx = indexOfAnchor(args, anchorName);
                if (anchorIdx < 0)
                    return call;

                // Rebuild the argument list: everything before anchor + expanded sequence + everything after
                NodeList<Expression> newArgs = new NodeList<>();
                for (int i = 0; i < anchorIdx; i++) {
                    newArgs.add(args.get(i).clone());
                }
                for (String name : generatedArgNames) {
                    newArgs.add(new NameExpr(name));
                }
                for (int i = anchorIdx + 1; i < args.size(); i++) {
                    newArgs.add(args.get(i).clone());
                }
                call.setArguments(newArgs);
                return call;
            }
        }, null));
    }

    private static int indexOfAnchor(NodeList<Expression> args, String anchorName) {
        for (int i = 0; i < args.size(); i++) {
            Expression e = args.get(i);
            if (e instanceof NameExpr && ((NameExpr) e).getNameAsString().equals(anchorName)) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // Annotation parsing
    // -------------------------------------------------------------------------

    private static boolean hasPermuteParam(NodeList<AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(PermuteParamTransformer::isPermuteParam);
    }

    private static AnnotationExpr getPermuteParam(NodeList<AnnotationExpr> annotations) {
        return annotations.stream()
                .filter(PermuteParamTransformer::isPermuteParam)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("@PermuteParam not found"));
    }

    private static boolean isPermuteParam(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(ANNOTATION_SIMPLE) || name.equals(ANNOTATION_FQ);
    }

    private static PermuteParamValues extractValues(AnnotationExpr ann, Messager messager) {
        if (!(ann instanceof NormalAnnotationExpr)) {
            if (messager != null)
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteParam must use named parameters: varName, from, to, type, name");
            return null;
        }
        NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;
        String varName = null, from = null, to = null, type = null, name = null;
        for (MemberValuePair pair : normal.getPairs()) {
            String val = PermuteDeclrTransformer.stripQuotes(pair.getValue().toString());
            switch (pair.getNameAsString()) {
                case "varName":
                    varName = val;
                    break;
                case "from":
                    from = val;
                    break;
                case "to":
                    to = val;
                    break;
                case "type":
                    type = val;
                    break;
                case "name":
                    name = val;
                    break;
            }
        }
        if (varName == null || from == null || to == null || type == null || name == null) {
            if (messager != null)
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@PermuteParam is missing one or more required parameters (varName, from, to, type, name)");
            return null;
        }
        return new PermuteParamValues(varName, from, to, type, name);
    }

    // -------------------------------------------------------------------------
    // Pre-generation validation
    // -------------------------------------------------------------------------

    /**
     * Validates that the static literals of each {@code @PermuteParam} {@code name}
     * attribute appear as substrings of the actual sentinel parameter name (R2), that
     * no variable is orphaned (R3), and that at least one literal anchor exists (R4).
     *
     * <p>
     * The {@code type} attribute is intentionally not checked here: it describes the
     * generated parameter type, not the sentinel's placeholder type, so a mismatch is
     * not necessarily a mistake.
     *
     * @param stringConstants the string constants from {@code @Permute strings}, used to
     *        expand any string-constant variables before validation
     * @return {@code true} if all constraints are satisfied
     */
    public static boolean validatePrefixes(ClassOrInterfaceDeclaration classDecl, Messager messager,
            Element element, Map<String, String> stringConstants) {
        boolean[] valid = { true };
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            // Validate all sentinels in the method, not just the first
            method.getParameters().stream()
                    .filter(p -> hasPermuteParam(p.getAnnotations()))
                    .forEach(sentinel -> {
                        AnnotationExpr ann = getPermuteParam(sentinel.getAnnotations());
                        PermuteParamValues values = extractValues(ann, messager);
                        if (values == null) {
                            valid[0] = false;
                            return;
                        }
                        String actualName = sentinel.getNameAsString();
                        if (!PermuteDeclrTransformer.checkAnnotationString(
                                "@PermuteParam name", values.name, "sentinel parameter name",
                                actualName, messager, element, stringConstants)) {
                            valid[0] = false;
                        }
                    });
        });
        return valid[0];
    }

    private static class PermuteParamValues {
        final String varName, from, to, type, name;

        PermuteParamValues(String varName, String from, String to, String type, String name) {
            this.varName = varName;
            this.from = from;
            this.to = to;
            this.type = type;
            this.name = name;
        }
    }
}
