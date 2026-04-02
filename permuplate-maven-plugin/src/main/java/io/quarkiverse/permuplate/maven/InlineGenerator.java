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
            applyPermuteReturn(generated, ctx, allGeneratedNames);

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

        // Strip from method parameters
        classDecl.findAll(MethodDeclaration.class).forEach(method -> method.getParameters()
                .forEach(param -> param.getAnnotations().removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString()))));
    }
}
