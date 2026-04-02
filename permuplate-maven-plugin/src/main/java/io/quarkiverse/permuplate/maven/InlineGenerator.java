package io.quarkiverse.permuplate.maven;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.core.PermuteParamTransformer;
import io.quarkiverse.permuplate.core.PermuteTypeParamTransformer;

/**
 * Generates an augmented parent {@link CompilationUnit} containing all permuted
 * nested classes produced from a template nested class annotated with
 * {@code @Permute(inline = true)}.
 *
 * <p>
 * The augmented parent preserves all original content (fields, methods, other
 * nested classes) and either removes or retains the template class depending on
 * {@link PermuteConfig#keepTemplate}.
 */
public class InlineGenerator {

    private InlineGenerator() {
    }

    /**
     * Generates the augmented parent class.
     *
     * @param parentCu the full compilation unit of the parent class
     * @param templateClassDecl the nested template class with {@code @Permute}
     * @param config the parsed {@code @Permute} configuration
     * @param allCombinations variable maps from {@code buildAllCombinations}
     * @return a new {@link CompilationUnit} containing the augmented parent
     */
    public static CompilationUnit generate(CompilationUnit parentCu,
            ClassOrInterfaceDeclaration templateClassDecl,
            PermuteConfig config,
            List<Map<String, Object>> allCombinations) {

        // Clone the entire parent CU as the starting point for the output
        CompilationUnit outputCu = parentCu.clone();
        ClassOrInterfaceDeclaration outputParent = outputCu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> !c.isNestedType())
                .orElseThrow(() -> new IllegalStateException("Cannot find top-level class in parent CU"));

        // Remove the template nested class from the output
        String templateClassName = templateClassDecl.getNameAsString();
        outputParent.getMembers().removeIf(member -> member instanceof ClassOrInterfaceDeclaration &&
                ((ClassOrInterfaceDeclaration) member).getNameAsString().equals(templateClassName));

        // Re-add the template class if keepTemplate = true (strip all permuplate annotations)
        if (config.keepTemplate) {
            ClassOrInterfaceDeclaration templateCopy = templateClassDecl.clone();
            stripPermuteAnnotations(templateCopy);
            outputParent.addMember(templateCopy);
        }

        // Build complete generated class set for boundary omission
        Set<String> allGeneratedNames = scanAllGeneratedClassNames(parentCu, config);

        // Generate and append each permuted nested class
        for (Map<String, Object> vars : allCombinations) {
            EvaluationContext ctx = new EvaluationContext(vars);

            ClassOrInterfaceDeclaration generated = templateClassDecl.clone();

            // Rename the generated nested class
            String newClassName = ctx.evaluate(config.className);
            generated.setName(newClassName);
            generated.getConstructors().forEach(ctor -> ctor.setName(newClassName));

            // Apply transformations (null messager — Maven plugin has no Messager)
            PermuteTypeParamTransformer.transform(generated, ctx, null, null);
            PermuteDeclrTransformer.transform(generated, ctx, null);
            PermuteParamTransformer.transform(generated, ctx, null);

            // Apply @PermuteReturn — explicit override; boundary omission
            // Track names of processed methods to prevent implicit inference from overwriting them
            Set<String> explicitReturnMethods = collectExplicitReturnMethodNames(generated);
            applyPermuteReturn(generated, ctx, allGeneratedNames);

            // Apply implicit return type + parameter type inference (Mechanism 1)
            // Skip methods that had explicit @PermuteReturn (they were just processed above)
            applyImplicitInference(generated, ctx, allGeneratedNames, explicitReturnMethods);

            // Strip @Permute
            generated.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute");
            });

            outputParent.addMember(generated);
        }

        // Strip permuplate imports from the output
        outputCu.getImports().removeIf(imp -> imp.getNameAsString().startsWith("io.quarkiverse.permuplate"));

        return outputCu;
    }

    /**
     * Scans the parent CU for all @Permute annotations and collects every class name
     * that will be generated. Used for boundary omission in @PermuteReturn processing.
     */
    private static Set<String> scanAllGeneratedClassNames(CompilationUnit parentCu,
            PermuteConfig thisConfig) {
        Set<String> names = new HashSet<>();
        addNamesFromConfig(thisConfig, names);

        parentCu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> classDecl.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("Permute")
                        || a.getNameAsString().equals("io.quarkiverse.permuplate.Permute"))
                .forEach(ann -> {
                    try {
                        addNamesFromConfig(AnnotationReader.readPermute(ann), names);
                    } catch (Exception ignored) {
                    }
                }));
        return names;
    }

    private static void addNamesFromConfig(PermuteConfig config, Set<String> names) {
        for (Map<String, Object> vars : PermuteConfig.buildAllCombinations(config)) {
            try {
                names.add(new EvaluationContext(vars).evaluate(config.className));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Processes @PermuteReturn annotations on methods in the generated class:
     * replaces the sentinel return type with the computed type, and removes
     * methods whose boundary check fails.
     */
    private static void applyPermuteReturn(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Set<String> allGeneratedNames) {

        List<MethodDeclaration> toRemove = new ArrayList<>();

        classDecl.getMethods().forEach(method -> {
            // Find @PermuteReturn on this method
            java.util.Optional<AnnotationExpr> annOpt = method.getAnnotations().stream()
                    .filter(a -> a.getNameAsString().equals("PermuteReturn")
                            || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteReturn"))
                    .findFirst();

            if (annOpt.isEmpty())
                return;

            AnnotationReader.PermuteReturnConfig cfg = AnnotationReader.readPermuteReturn(annOpt.get());
            if (cfg == null)
                return;

            // Evaluate className
            String evaluatedClass;
            try {
                evaluatedClass = ctx.evaluate(cfg.className());
            } catch (Exception ignored) {
                return;
            }

            // Boundary omission
            boolean shouldGenerate;
            if (cfg.when().isEmpty()) {
                shouldGenerate = allGeneratedNames.contains(evaluatedClass);
            } else {
                try {
                    shouldGenerate = Boolean.parseBoolean(ctx.evaluate("${" + cfg.when() + "}"));
                } catch (Exception ignored) {
                    shouldGenerate = allGeneratedNames.contains(evaluatedClass);
                }
            }

            if (!shouldGenerate) {
                toRemove.add(method);
                return;
            }

            // Build return type string and replace
            String returnTypeStr = buildReturnTypeStr(evaluatedClass, cfg, ctx);
            try {
                method.setType(StaticJavaParser.parseType(returnTypeStr));
            } catch (Exception ignored) {
            }

            // Remove @PermuteReturn annotation
            method.getAnnotations().removeIf(a -> a == annOpt.get());
        });

        toRemove.forEach(m -> classDecl.getMembers().removeIf(member -> member == m));
    }

    private static String buildReturnTypeStr(String className,
            AnnotationReader.PermuteReturnConfig cfg,
            EvaluationContext ctx) {
        if (cfg.hasTypeArgsExpr()) {
            try {
                String evaluated = ctx.evaluate("${" + cfg.typeArgs() + "}");
                return evaluated.isEmpty() ? className : className + "<" + evaluated + ">";
            } catch (Exception ignored) {
                return className;
            }
        }
        if (!cfg.hasTypeArgLoop())
            return className;

        String from = (cfg.typeArgFrom() == null || cfg.typeArgFrom().isEmpty()) ? "1" : cfg.typeArgFrom();
        try {
            int fromVal = ctx.evaluateInt(from);
            int toVal = ctx.evaluateInt(cfg.typeArgTo());
            StringBuilder sb = new StringBuilder(className).append("<");
            for (int j = fromVal; j <= toVal; j++) {
                if (j > fromVal)
                    sb.append(", ");
                sb.append(ctx.withVariable(cfg.typeArgVarName(), j).evaluate(cfg.typeArgName()));
            }
            return sb.append(">").toString();
        } catch (Exception ignored) {
            return className;
        }
    }

    /** Collects method keys that have explicit @PermuteReturn — used to exclude from implicit inference. */
    private static Set<String> collectExplicitReturnMethodNames(ClassOrInterfaceDeclaration classDecl) {
        Set<String> names = new HashSet<>();
        classDecl.getMethods().forEach(method -> method.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("PermuteReturn")
                        || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteReturn"))
                .findFirst()
                .ifPresent(a -> names.add(method.getNameAsString() + method.getParameters().toString())));
        return names;
    }

    /**
     * Applies implicit return type inference to methods without @PermuteReturn.
     *
     * <p>
     * Fires when BOTH conditions hold:
     * 1. The return type base class is in allGeneratedNames
     * 2. The return type's type args contain undeclared T+number vars (the growing tip)
     *
     * <p>
     * The growing tip is expanded: if the template has tip starting at T(n), the generated
     * class at permutation i gets tips T(n)..T(i+1), with fixed args in their original positions.
     */
    private static void applyImplicitInference(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Set<String> allGeneratedNames,
            Set<String> explicitReturnMethods) {

        // Collect declared class type parameter names (e.g. "T1", "R")
        Set<String> declaredTypeParams = new java.util.LinkedHashSet<>();
        classDecl.getTypeParameters().forEach(tp -> declaredTypeParams.add(tp.getNameAsString()));

        List<MethodDeclaration> toRemove = new ArrayList<>();

        classDecl.getMethods().forEach(method -> {
            // Skip methods that had explicit @PermuteReturn (already processed by applyPermuteReturn)
            String methodKey = method.getNameAsString() + method.getParameters().toString();
            if (explicitReturnMethods.contains(methodKey))
                return;

            String returnTypeStr = method.getTypeAsString();
            if (returnTypeStr.equals("void") || returnTypeStr.equals("Object"))
                return;

            // Parse base class and type args
            ReturnTypeInfo info = parseReturnTypeInfo(returnTypeStr);
            if (info == null)
                return;

            // Condition 1: return type base class must be in generated set
            if (!allGeneratedNames.contains(info.baseClass()))
                return;

            // Condition 2: type args must contain undeclared T+number vars (growing tip)
            // Separate args into fixed (declared or non-T+number) and growing tip (undeclared T+number)
            List<String> fixedArgs = new ArrayList<>();
            List<String> growingTip = new ArrayList<>();

            for (String arg : info.typeArgs()) {
                if (!declaredTypeParams.contains(arg) && isTNumberVar(arg)) {
                    growingTip.add(arg);
                } else {
                    fixedArgs.add(arg);
                }
            }

            if (growingTip.isEmpty())
                return;

            // Find the first tip number (e.g. T2 → 2)
            int firstTipNum = Integer.parseInt(growingTip.get(0).substring(1));

            // Compute currentSuffix from the generated class name (e.g. "Step3" → 3)
            int currentSuffix = classNameSuffix(classDecl.getNameAsString());
            if (currentSuffix < 0)
                return;

            int newSuffix = currentSuffix + 1;
            String baseClassName = stripNumericSuffix(info.baseClass());
            String newBaseClass = baseClassName + newSuffix;

            // Boundary omission: if new class not in generated set, omit the method
            if (!allGeneratedNames.contains(newBaseClass)) {
                toRemove.add(method);
                return;
            }

            // Build new type args: reconstruct in original order, expanding the growing tip
            // The growing tip expands from T(firstTipNum) to T(newSuffix)
            // Fixed args stay in their original positions (before tip, between tip, after tip)
            List<String> newTypeArgs = buildExpandedTypeArgs(info.typeArgs(), declaredTypeParams,
                    firstTipNum, newSuffix);

            String newReturnType = newBaseClass + "<" + String.join(", ", newTypeArgs) + ">";
            try {
                method.setType(StaticJavaParser.parseType(newReturnType));
            } catch (Exception ignored) {
                return;
            }

            // Parameter type inference: the old tip sentinel var (T(firstTipNum)) maps to
            // T(newSuffix) at this permutation. When firstTipNum == newSuffix no replacement needed.
            if (firstTipNum != newSuffix) {
                String oldTipVar = "T" + firstTipNum;
                String newTipVar = "T" + newSuffix;
                method.getParameters().forEach(param -> {
                    String paramType = param.getTypeAsString();
                    if (paramType.contains(oldTipVar) && !declaredTypeParams.contains(oldTipVar)) {
                        String newParamType = replaceTypeVar(paramType, oldTipVar, newTipVar);
                        try {
                            param.setType(StaticJavaParser.parseType(newParamType));
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        });

        toRemove.forEach(m -> classDecl.getMembers().removeIf(member -> member == m));
    }

    /**
     * Rebuilds the type argument list, expanding the growing tip from T(firstTipNum)
     * to T(newSuffix) while preserving fixed args in their original relative positions.
     *
     * <p>
     * Example: args=[T1, T2, R], declaredTypeParams={T1,R}, firstTipNum=2, newSuffix=4
     * → [T1, T2, T3, T4, R]
     */
    private static List<String> buildExpandedTypeArgs(List<String> originalArgs,
            Set<String> declaredTypeParams,
            int firstTipNum, int newSuffix) {
        List<String> result = new ArrayList<>();
        boolean tipInserted = false;

        for (String arg : originalArgs) {
            boolean isTip = !declaredTypeParams.contains(arg) && isTNumberVar(arg);
            if (isTip && !tipInserted) {
                // Insert expanded tip: T(firstTipNum)..T(newSuffix)
                for (int t = firstTipNum; t <= newSuffix; t++) {
                    result.add("T" + t);
                }
                tipInserted = true;
                // Skip additional tip vars from the original (they are subsumed)
            } else if (isTip) {
                // Additional tip vars from original — skip (already expanded above)
            } else {
                // Fixed arg — pass through
                result.add(arg);
            }
        }
        return result;
    }

    // ---- Helper types and utilities ----

    private record ReturnTypeInfo(String baseClass, List<String> typeArgs) {
    }

    /**
     * Parses "Step2<T1, T2>" into ReturnTypeInfo("Step2", ["T1","T2"]).
     * Returns null if the string cannot be parsed as a class name.
     */
    private static ReturnTypeInfo parseReturnTypeInfo(String returnType) {
        int lt = returnType.indexOf('<');
        if (lt < 0)
            return new ReturnTypeInfo(returnType.trim(), List.of());
        String base = returnType.substring(0, lt).trim();
        String argsStr = returnType.substring(lt + 1, returnType.lastIndexOf('>')).trim();
        if (argsStr.isEmpty())
            return new ReturnTypeInfo(base, List.of());
        // Split on comma at depth 0 (handles nested generics like Source<T2>)
        List<String> args = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (c == '<')
                depth++;
            else if (c == '>')
                depth--;
            else if (c == ',' && depth == 0) {
                args.add(argsStr.substring(start, i).trim());
                start = i + 1;
            }
        }
        args.add(argsStr.substring(start).trim());
        return new ReturnTypeInfo(base, args);
    }

    /** Returns true if s matches T+number pattern (e.g. "T1", "T23"). */
    private static boolean isTNumberVar(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != 'T')
            return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i)))
                return false;
        }
        return true;
    }

    /** Extracts numeric suffix from class name (e.g. "Step3" → 3). Returns -1 if none. */
    private static int classNameSuffix(String name) {
        int i = name.length() - 1;
        if (i < 0 || !Character.isDigit(name.charAt(i)))
            return -1;
        while (i > 0 && Character.isDigit(name.charAt(i - 1)))
            i--;
        try {
            return Integer.parseInt(name.substring(i));
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** Strips numeric suffix from class name (e.g. "Step3" → "Step"). */
    private static String stripNumericSuffix(String name) {
        int i = name.length() - 1;
        if (i < 0 || !Character.isDigit(name.charAt(i)))
            return name;
        while (i > 0 && Character.isDigit(name.charAt(i - 1)))
            i--;
        return name.substring(0, i);
    }

    /**
     * Replaces word-boundary occurrences of oldVar with newVar in a type string.
     * Handles cases like "Source<T2>" → "Source<T3>" without replacing "T20" when oldVar="T2".
     */
    private static String replaceTypeVar(String type, String oldVar, String newVar) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < type.length()) {
            int idx = type.indexOf(oldVar, i);
            if (idx < 0) {
                sb.append(type.substring(i));
                break;
            }
            // Check word boundaries
            boolean beforeOk = idx == 0 || !Character.isJavaIdentifierPart(type.charAt(idx - 1));
            int end = idx + oldVar.length();
            boolean afterOk = end >= type.length() || !Character.isJavaIdentifierPart(type.charAt(end));
            if (beforeOk && afterOk) {
                sb.append(type, i, idx).append(newVar);
                i = end;
            } else {
                sb.append(type.charAt(idx));
                i = idx + 1;
            }
        }
        return sb.toString();
    }

    /**
     * Strips all Permuplate annotations ({@code @Permute}, {@code @PermuteDeclr},
     * {@code @PermuteParam}) from a class declaration and all of its members,
     * including field-level and parameter-level annotations.
     */
    private static void stripPermuteAnnotations(ClassOrInterfaceDeclaration classDecl) {
        Set<String> PERMUPLATE_ANNOTATIONS = Set.of(
                "Permute", "io.quarkiverse.permuplate.Permute",
                "PermuteDeclr", "io.quarkiverse.permuplate.PermuteDeclr",
                "PermuteParam", "io.quarkiverse.permuplate.PermuteParam",
                "PermuteTypeParam", "io.quarkiverse.permuplate.PermuteTypeParam",
                "PermuteReturn", "io.quarkiverse.permuplate.PermuteReturn");

        // Strip from the class itself
        classDecl.getAnnotations().removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString()));

        // Strip from fields
        classDecl.findAll(FieldDeclaration.class)
                .forEach(field -> field.getAnnotations().removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));

        // Strip from method annotations and method parameters
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            method.getAnnotations().removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString()));
            method.getParameters()
                    .forEach(param -> param.getAnnotations()
                            .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));
        });
    }
}
