package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import io.quarkiverse.permuplate.ide.ValidationError;

/**
 * Handles {@code @PermuteDeclr} on field declarations, constructor parameters,
 * and for-each loop variables.
 *
 * <p>
 * For each annotated declaration:
 * <ol>
 * <li>Evaluates the new type and name using the provided {@link EvaluationContext}</li>
 * <li>Updates the declaration node</li>
 * <li>Renames all {@code NameExpr} references to the old name within the declaration's scope</li>
 * <li>Removes the {@code @PermuteDeclr} annotation</li>
 * </ol>
 *
 * <p>
 * Processing order: fields first (class-wide scope), then constructor parameters
 * (constructor-body scope), then for-each variables (loop-body scope). This ensures
 * that broader-scope renames are already applied when narrower scopes are walked.
 */
public class PermuteDeclrTransformer {

    private static final String ANNOTATION_SIMPLE = "PermuteDeclr";
    private static final String ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteDeclr";

    private static final String CONST_ANNOTATION_SIMPLE = "PermuteConst";
    private static final String CONST_ANNOTATION_FQ = "io.quarkiverse.permuplate.PermuteConst";

    public static void transform(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager) {
        // Fields first (broadest scope — entire class body)
        transformFields(classDecl, ctx, messager);
        // Field initializer substitution via @PermuteConst
        transformConstFields(classDecl, ctx);
        // Local variable initializer substitution via @PermuteConst (Task 3)
        transformConstLocals(classDecl, ctx);
        // Object creation expressions — update constructor class name via @PermuteDeclr TYPE_USE
        transformNewExpressions(classDecl, ctx);
        // Constructor parameters (scope = constructor body)
        transformConstructorParams(classDecl, ctx, messager);
        // For-each variables (narrowest scope — loop body only)
        transformForEachVars(classDecl, ctx, messager);
        // Method parameters — type always; name+body rename only when name is non-empty
        transformMethodParams(classDecl, ctx, messager);
    }

    // -------------------------------------------------------------------------
    // Field declarations
    // -------------------------------------------------------------------------

    private static void transformFields(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager) {
        // Collect annotated fields snapshot (avoid ConcurrentModification)
        List<FieldDeclaration> annotatedFields = new ArrayList<>();
        classDecl.getFields().forEach(f -> {
            if (hasPermuteDeclr(f.getAnnotations())) {
                annotatedFields.add(f);
            }
        });

        for (FieldDeclaration field : annotatedFields) {
            AnnotationExpr ann = getPermuteDeclr(field.getAnnotations());
            String[] params = extractTwoParams(ann, messager);
            if (params == null)
                continue;

            String newType = ctx.evaluate(params[0]);
            String newName = params[1].isEmpty() ? "" : ctx.evaluate(params[1]); // "" = keep original name

            // There should be exactly one declarator on an annotated field
            VariableDeclarator declarator = field.getVariable(0);
            String oldName = declarator.getNameAsString();

            // Update the declaration
            declarator.setType(StaticJavaParser.parseType(newType));

            // Remove the annotation
            field.getAnnotations().remove(ann);

            if (!newName.isEmpty()) {
                declarator.setName(newName);
                // Propagate: rename all usages of oldName in the entire class body
                renameAllUsages(classDecl, oldName, newName);
            }
        }
    }

    // -------------------------------------------------------------------------
    // @PermuteConst — field initializer substitution
    // -------------------------------------------------------------------------

    private static void transformConstFields(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx) {
        classDecl.getFields().forEach(field -> {
            field.getAnnotations().stream()
                    .filter(PermuteDeclrTransformer::isPermuteConst)
                    .findFirst()
                    .ifPresent(ann -> {
                        String expr = extractConstExpr(ann);
                        String evaluated = ctx.evaluate(expr);
                        Expression newInit = toExpression(evaluated);
                        field.getVariable(0).setInitializer(newInit);
                        field.getAnnotations().remove(ann);
                    });
        });
    }

    private static void transformConstLocals(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx) {
        // Walk all method and constructor bodies looking for annotated local variable declarations.
        // VariableDeclarationExpr covers local vars in method bodies; skip for-each variables
        // (those are handled by transformForEachVars via ForEachStmt.getVariable()).
        classDecl.walk(VariableDeclarationExpr.class, varDeclExpr -> {
            // Skip for-each variables — their parent is a ForEachStmt
            if (varDeclExpr.getParentNode().map(p -> p instanceof ForEachStmt).orElse(false))
                return;

            varDeclExpr.getAnnotations().stream()
                    .filter(PermuteDeclrTransformer::isPermuteConst)
                    .findFirst()
                    .ifPresent(ann -> {
                        String expr = extractConstExpr(ann);
                        String evaluated = ctx.evaluate(expr);
                        Expression newInit = toExpression(evaluated);
                        varDeclExpr.getVariables().get(0).setInitializer(newInit);
                        varDeclExpr.getAnnotations().remove(ann);
                    });
        });
    }

    // -------------------------------------------------------------------------
    // @PermuteDeclr on ObjectCreationExpr — TYPE_USE target
    // -------------------------------------------------------------------------

    /**
     * Walks all {@code new X<>()} expressions in method and constructor bodies.
     * When the instantiated type carries {@code @PermuteDeclr(type="...")}, evaluates
     * the type expression and replaces the constructor class name. The annotation is
     * removed from the new type node. Any diamond {@code <>} or type arguments on the
     * {@code ObjectCreationExpr} itself are preserved unchanged.
     *
     * <p>
     * This enables templates like:
     *
     * <pre>{@code
     * return new @PermuteDeclr(type = "Join${i+1}First") Join3First<>(end(), rule);
     * // Generated for i=3: new Join4First<>(end(), rule)
     * }</pre>
     */
    private static void transformNewExpressions(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx) {
        classDecl.walk(com.github.javaparser.ast.expr.ObjectCreationExpr.class, newExpr -> {
            com.github.javaparser.ast.type.ClassOrInterfaceType type = newExpr.getType();
            if (!hasPermuteDeclr(type.getAnnotations()))
                return;

            AnnotationExpr ann = getPermuteDeclr(type.getAnnotations());
            String[] params = extractTwoParams(ann, null);
            if (params == null)
                return;

            String newTypeName = ctx.evaluate(params[0]);
            // Parse as a proper ClassOrInterfaceType — handles generic types correctly
            com.github.javaparser.ast.type.ClassOrInterfaceType newType = StaticJavaParser.parseType(newTypeName)
                    .asClassOrInterfaceType();
            // Replace the type; @PermuteDeclr is removed since we replaced the whole node
            newExpr.setType(newType);
        });
    }

    private static boolean isPermuteConst(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(CONST_ANNOTATION_SIMPLE) || name.equals(CONST_ANNOTATION_FQ);
    }

    private static String extractConstExpr(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return stripQuotes(single.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value")) {
                    return stripQuotes(pair.getValue().toString());
                }
            }
        }
        throw new IllegalStateException("@PermuteConst missing value");
    }

    /**
     * Converts an evaluated JEXL string to a JavaParser Expression.
     * Integers become IntegerLiteralExpr; everything else becomes StringLiteralExpr.
     */
    static Expression toExpression(String evaluated) {
        try {
            Integer.parseInt(evaluated);
            return new IntegerLiteralExpr(evaluated);
        } catch (NumberFormatException e) {
            return new StringLiteralExpr(evaluated);
        }
    }

    // -------------------------------------------------------------------------
    // Constructor parameters
    // -------------------------------------------------------------------------

    private static void transformConstructorParams(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager) {
        classDecl.getConstructors().forEach(constructor -> {
            // Snapshot to avoid ConcurrentModification while iterating
            List<Parameter> annotatedParams = new ArrayList<>();
            constructor.getParameters().forEach(p -> {
                if (hasPermuteDeclr(p.getAnnotations()))
                    annotatedParams.add(p);
            });

            for (Parameter param : annotatedParams) {
                AnnotationExpr ann = getPermuteDeclr(param.getAnnotations());
                String[] params = extractTwoParams(ann, messager);
                if (params == null)
                    continue;

                String newType = ctx.evaluate(params[0]);
                String newName = params[1].isEmpty() ? "" : ctx.evaluate(params[1]); // "" = keep original name
                String oldName = param.getNameAsString();

                param.setType(StaticJavaParser.parseType(newType));
                param.getAnnotations().remove(ann);

                if (!newName.isEmpty()) {
                    param.setName(newName);
                    // Propagate rename within the constructor body only.
                    // ConstructorDeclaration.getBody() returns BlockStmt directly (always present).
                    renameAllUsages(constructor.getBody(), oldName, newName);
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // For-each loop variables
    // -------------------------------------------------------------------------

    private static void transformForEachVars(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager) {
        // Walk the class to find all ForEachStmt nodes whose variable is annotated.
        // ForEachStmt.getVariable() returns VariableDeclarationExpr (not Parameter).
        classDecl.walk(ForEachStmt.class, forEachStmt -> {
            com.github.javaparser.ast.expr.VariableDeclarationExpr varDeclExpr = forEachStmt.getVariable();
            if (!hasPermuteDeclr(varDeclExpr.getAnnotations()))
                return;

            AnnotationExpr ann = getPermuteDeclr(varDeclExpr.getAnnotations());
            String[] params = extractTwoParams(ann, messager);
            if (params == null)
                return;

            String newType = ctx.evaluate(params[0]);
            String newName = params[1].isEmpty() ? "" : ctx.evaluate(params[1]); // "" = keep original name
            String oldName = varDeclExpr.getVariables().get(0).getNameAsString();

            // Update declaration — type lives on the VariableDeclarator in JavaParser
            varDeclExpr.getVariables().get(0).setType(StaticJavaParser.parseType(newType));

            // Remove annotation
            varDeclExpr.getAnnotations().remove(ann);

            if (!newName.isEmpty()) {
                varDeclExpr.getVariables().get(0).setName(newName);
                // Propagate: rename usages only within the loop body
                renameAllUsages(forEachStmt.getBody(), oldName, newName);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Method parameters (G2a)
    // -------------------------------------------------------------------------

    private static void transformMethodParams(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager) {
        classDecl.getMethods().forEach(method -> {
            // Snapshot annotated params to avoid ConcurrentModification
            List<Parameter> annotated = new ArrayList<>();
            method.getParameters().forEach(p -> {
                if (hasPermuteDeclr(p.getAnnotations()))
                    annotated.add(p);
            });

            for (Parameter param : annotated) {
                AnnotationExpr ann = getPermuteDeclr(param.getAnnotations());
                String[] params = extractTwoParams(ann, messager);
                if (params == null)
                    continue;

                String newType = ctx.evaluate(params[0]);
                String newName = params[1].isEmpty() ? "" : ctx.evaluate(params[1]); // "" = keep original name

                param.setType(StaticJavaParser.parseType(newType));
                param.getAnnotations().remove(ann);

                if (!newName.isEmpty()) {
                    // Name also changes — propagate rename within the method body only
                    String oldName = param.getNameAsString();
                    param.setName(newName);
                    method.getBody().ifPresent(body -> renameAllUsages(body, oldName, newName));
                }
            }
        });
    }

    /**
     * Processes {@code @PermuteDeclr} on the parameters of a single method using the
     * provided context. Called by InlineGenerator when generating @PermuteMethod overloads
     * with the inner (i,j) context — before the method is added to the generated class,
     * so downstream transforms see no remaining @PermuteDeclr annotations.
     */
    public static void processMethodParamDeclr(MethodDeclaration method,
            EvaluationContext ctx) {
        List<Parameter> annotated = new ArrayList<>();
        method.getParameters().forEach(p -> {
            if (hasPermuteDeclr(p.getAnnotations()))
                annotated.add(p);
        });
        for (Parameter param : annotated) {
            AnnotationExpr ann = getPermuteDeclr(param.getAnnotations());
            String[] params = extractTwoParams(ann, null);
            if (params == null)
                continue;
            String newType = ctx.evaluate(params[0]);
            String newName = params[1];
            param.setType(StaticJavaParser.parseType(newType));
            param.getAnnotations().remove(ann);
            if (!newName.isEmpty()) {
                String oldName = param.getNameAsString();
                param.setName(ctx.evaluate(newName));
                method.getBody().ifPresent(body -> renameAllUsages(body, oldName, ctx.evaluate(newName)));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared utilities
    // -------------------------------------------------------------------------

    /**
     * Renames every {@link NameExpr} matching {@code oldName} within {@code scope}.
     * Uses a ModifierVisitor so the walk is safe for in-place mutation.
     */
    static void renameAllUsages(Node scope, String oldName, String newName) {
        scope.accept(new ModifierVisitor<Void>() {
            @Override
            public Visitable visit(NameExpr n, Void arg) {
                if (n.getNameAsString().equals(oldName)) {
                    return new NameExpr(newName);
                }
                return super.visit(n, arg);
            }
        }, null);
    }

    static boolean hasPermuteDeclr(NodeList<AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(PermuteDeclrTransformer::isPermuteDeclr);
    }

    static AnnotationExpr getPermuteDeclr(NodeList<AnnotationExpr> annotations) {
        return annotations.stream()
                .filter(PermuteDeclrTransformer::isPermuteDeclr)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("@PermuteDeclr not found"));
    }

    private static boolean isPermuteDeclr(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(ANNOTATION_SIMPLE) || name.equals(ANNOTATION_FQ);
    }

    /**
     * Extracts the {@code type} and optional {@code name} parameters from a
     * {@code @PermuteDeclr(type=..., name=...)} annotation.
     * {@code name} is optional — defaults to {@code ""} (keep original name).
     * Supports both named-parameter style: {@code @PermuteDeclr(type="t", name="n")}
     * and type-only style: {@code @PermuteDeclr(type="t")}.
     */
    static String[] extractTwoParams(AnnotationExpr ann, Messager messager) {
        if (ann instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;
            String type = null, name = ""; // name is optional, default ""
            for (MemberValuePair pair : normal.getPairs()) {
                String val = stripQuotes(pair.getValue().toString());
                if (pair.getNameAsString().equals("type"))
                    type = val;
                else if (pair.getNameAsString().equals("name"))
                    name = val;
            }
            if (type != null)
                return new String[] { type, name };
        }
        if (messager != null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@PermuteDeclr must specify type: @PermuteDeclr(type=\"...\") " +
                            "or @PermuteDeclr(type=\"...\", name=\"...\")");
        }
        return null;
    }

    public static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // -------------------------------------------------------------------------
    // Pre-generation validation
    // -------------------------------------------------------------------------

    /**
     * Validates that the static literals of each {@code @PermuteDeclr} {@code type} and
     * {@code name} attribute appear as substrings of the actual declaration's type and
     * name (R2), that no variable is orphaned (R3), and that at least one literal anchor
     * exists (R4). This catches mismatches like {@code type = "Bar${i}"} on a field of
     * type {@code Callable2} before any permutation is generated.
     *
     * @param stringConstants the string constants from {@code @Permute strings}, used to
     *        expand any string-constant variables before validation
     * @return {@code true} if all constraints are satisfied
     */
    public static boolean validatePrefixes(ClassOrInterfaceDeclaration classDecl, Messager messager,
            Element element, Map<String, String> stringConstants) {
        boolean[] valid = { true };

        // Fields
        for (FieldDeclaration field : classDecl.getFields()) {
            if (!hasPermuteDeclr(field.getAnnotations()))
                continue;
            AnnotationExpr ann = getPermuteDeclr(field.getAnnotations());
            String[] params = extractTwoParams(ann, messager);
            if (params == null) {
                valid[0] = false;
                continue;
            }
            VariableDeclarator declarator = field.getVariable(0);
            if (!checkAnnotationString("@PermuteDeclr type", params[0], "field type",
                    declarator.getType().asString(), messager, element, stringConstants))
                valid[0] = false;
            if (!checkAnnotationString("@PermuteDeclr name", params[1], "field name",
                    declarator.getNameAsString(), messager, element, stringConstants))
                valid[0] = false;
        }

        // Constructor parameters
        classDecl.getConstructors().forEach(constructor -> {
            constructor.getParameters().forEach(param -> {
                if (!hasPermuteDeclr(param.getAnnotations()))
                    return;
                AnnotationExpr ann = getPermuteDeclr(param.getAnnotations());
                String[] params = extractTwoParams(ann, messager);
                if (params == null) {
                    valid[0] = false;
                    return;
                }
                if (!checkAnnotationString("@PermuteDeclr type", params[0], "constructor parameter type",
                        param.getType().asString(), messager, element, stringConstants))
                    valid[0] = false;
                if (!checkAnnotationString("@PermuteDeclr name", params[1], "constructor parameter name",
                        param.getNameAsString(), messager, element, stringConstants))
                    valid[0] = false;
            });
        });

        // For-each variables
        classDecl.walk(ForEachStmt.class, forEachStmt -> {
            com.github.javaparser.ast.expr.VariableDeclarationExpr varDeclExpr = forEachStmt.getVariable();
            if (!hasPermuteDeclr(varDeclExpr.getAnnotations()))
                return;
            AnnotationExpr ann = getPermuteDeclr(varDeclExpr.getAnnotations());
            String[] params = extractTwoParams(ann, messager);
            if (params == null) {
                valid[0] = false;
                return;
            }
            VariableDeclarator v = varDeclExpr.getVariables().get(0);
            if (!checkAnnotationString("@PermuteDeclr type", params[0], "for-each variable type",
                    v.getType().asString(), messager, element, stringConstants))
                valid[0] = false;
            if (!checkAnnotationString("@PermuteDeclr name", params[1], "for-each variable name",
                    v.getNameAsString(), messager, element, stringConstants))
                valid[0] = false;
        });

        // Note: Method parameters are NOT validated here because they fundamentally differ
        // from fields/for-each vars — the annotation string describes what the type BECOMES
        // in the generated code (where the template is a generic placeholder like Object),
        // not what it currently is. Validation happens in transformMethodParams where we have
        // the EvaluationContext to properly evaluate the annotation strings.

        return valid[0];
    }

    /**
     * Validates an annotation string template against the actual target name using
     * the full algorithm (R2 substring matching, R3 orphan variable, R4 no anchor).
     * Reports all errors via the messager. Returns false if any error was found.
     *
     * <p>
     * The {@code strings} constants from {@code @Permute} are expanded into the
     * template before validation so that string-constant-composed literals are
     * correctly recognised.
     */
    public static boolean checkAnnotationString(String annotationAttr, String template,
            String targetDesc, String actual, Messager messager, Element element,
            Map<String, String> stringConstants) {
        AnnotationStringTemplate t = AnnotationStringAlgorithm.expandStringConstants(
                AnnotationStringAlgorithm.parse(template), stringConstants);

        List<ValidationError> errors = AnnotationStringAlgorithm.validate(t, actual);
        if (errors.isEmpty())
            return true;

        for (ValidationError err : errors) {
            String msg = buildMessage(annotationAttr, template, targetDesc, actual, err);
            if (messager != null)
                messager.printMessage(Diagnostic.Kind.ERROR, msg, element);
        }
        return false;
    }

    private static String buildMessage(String annotationAttr, String template,
            String targetDesc, String actual, ValidationError err) {
        return switch (err.kind()) {
            case UNMATCHED_LITERAL -> String.format(
                    "%s literal does not match any substring of %s \"%s\" — %s",
                    annotationAttr, targetDesc, actual, err.suggestion());
            case ORPHAN_VARIABLE -> String.format(
                    "%s: variable ${%s} has no corresponding text in \"%s\" — %s",
                    annotationAttr, err.varName(), actual, err.suggestion());
            case NO_ANCHOR -> String.format(
                    "%s string \"%s\" has no static literal to match against \"%s\" — %s",
                    annotationAttr, template, actual, err.suggestion());
            case NO_VARIABLES -> String.format(
                    "%s \"%s\" contains no variables — it will generate the same %s" +
                            " for every permutation",
                    annotationAttr, template, targetDesc);
        };
    }
}
