package io.quarkiverse.permuplate.processor;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

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

    public static void transform(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Messager messager) {
        // Fields first (broadest scope — entire class body)
        transformFields(classDecl, ctx, messager);
        // Constructor parameters (scope = constructor body)
        transformConstructorParams(classDecl, ctx, messager);
        // For-each variables (narrowest scope — loop body only)
        transformForEachVars(classDecl, ctx, messager);
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
            String newName = ctx.evaluate(params[1]);

            // There should be exactly one declarator on an annotated field
            VariableDeclarator declarator = field.getVariable(0);
            String oldName = declarator.getNameAsString();

            // Update the declaration
            declarator.setType(new ClassOrInterfaceType(null, newType));
            declarator.setName(newName);

            // Remove the annotation
            field.getAnnotations().remove(ann);

            // Propagate: rename all usages of oldName in the entire class body
            renameAllUsages(classDecl, oldName, newName);
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
                String newName = ctx.evaluate(params[1]);
                String oldName = param.getNameAsString();

                param.setType(new ClassOrInterfaceType(null, newType));
                param.setName(newName);
                param.getAnnotations().remove(ann);

                // Propagate rename within the constructor body only.
                // ConstructorDeclaration.getBody() returns BlockStmt directly (always present).
                renameAllUsages(constructor.getBody(), oldName, newName);
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
            String newName = ctx.evaluate(params[1]);
            String oldName = varDeclExpr.getVariables().get(0).getNameAsString();

            // Update declaration — type lives on the VariableDeclarator in JavaParser
            varDeclExpr.getVariables().get(0).setType(new ClassOrInterfaceType(null, newType));
            varDeclExpr.getVariables().get(0).setName(newName);

            // Remove annotation
            varDeclExpr.getAnnotations().remove(ann);

            // Propagate: rename usages only within the loop body
            renameAllUsages(forEachStmt.getBody(), oldName, newName);
        });
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
     * Extracts the two string parameters from a {@code @PermuteDeclr(type, name)} annotation.
     * Supports both {@code @PermuteDeclr("t", "n")} (pair style) and
     * {@code @PermuteDeclr(type="t", name="n")} (named style).
     */
    static String[] extractTwoParams(AnnotationExpr ann, Messager messager) {
        if (ann instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) ann;
            String type = null, name = null;
            for (MemberValuePair pair : normal.getPairs()) {
                String val = stripQuotes(pair.getValue().toString());
                if (pair.getNameAsString().equals("type"))
                    type = val;
                else if (pair.getNameAsString().equals("name"))
                    name = val;
            }
            if (type != null && name != null)
                return new String[] { type, name };
        }
        if (messager != null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@PermuteDeclr must use named parameters: @PermuteDeclr(type=\"...\", name=\"...\")");
        }
        return null;
    }

    static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * Returns the static (non-{@code ${...}}) portion of a template string.
     * For example, {@code "Callable${i}"} → {@code "Callable"}, {@code "${i}"} → {@code ""}.
     */
    static String staticPrefix(String template) {
        return template.replaceAll("\\$\\{[^}]*\\}", "");
    }

    // -------------------------------------------------------------------------
    // Pre-generation validation
    // -------------------------------------------------------------------------

    /**
     * Validates that the static part of each {@code @PermuteDeclr} {@code type} and
     * {@code name} attribute is a prefix of the actual declaration's type and name.
     * This catches mismatches like {@code type = "Bar${i}"} on a field of type
     * {@code Callable2} before any permutation is generated.
     *
     * @return {@code true} if all prefix constraints are satisfied
     */
    public static boolean validatePrefixes(ClassOrInterfaceDeclaration classDecl, Messager messager,
            Element element) {
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
            if (!checkPrefix("@PermuteDeclr type", params[0], "field type",
                    declarator.getType().asString(), messager, element))
                valid[0] = false;
            if (!checkPrefix("@PermuteDeclr name", params[1], "field name",
                    declarator.getNameAsString(), messager, element))
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
                if (!checkPrefix("@PermuteDeclr type", params[0], "constructor parameter type",
                        param.getType().asString(), messager, element))
                    valid[0] = false;
                if (!checkPrefix("@PermuteDeclr name", params[1], "constructor parameter name",
                        param.getNameAsString(), messager, element))
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
            if (!checkPrefix("@PermuteDeclr type", params[0], "for-each variable type",
                    v.getType().asString(), messager, element))
                valid[0] = false;
            if (!checkPrefix("@PermuteDeclr name", params[1], "for-each variable name",
                    v.getNameAsString(), messager, element))
                valid[0] = false;
        });

        return valid[0];
    }

    private static boolean checkPrefix(String annotationAttr, String template,
            String targetDesc, String actual, Messager messager, Element element) {
        String prefix = staticPrefix(template);
        if (prefix.isEmpty() || actual.startsWith(prefix))
            return true;
        messager.printMessage(Diagnostic.Kind.ERROR,
                String.format("%s literal part \"%s\" is not a prefix of the %s \"%s\"",
                        annotationAttr, prefix, targetDesc, actual),
                element);
        return false;
    }
}
