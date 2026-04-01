package io.quarkiverse.permuplate.maven;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.core.PermuteParamTransformer;

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

        // Generate and append each permuted nested class
        for (Map<String, Object> vars : allCombinations) {
            EvaluationContext ctx = new EvaluationContext(vars);

            ClassOrInterfaceDeclaration generated = templateClassDecl.clone();

            // Rename the generated nested class
            String newClassName = ctx.evaluate(config.className);
            generated.setName(newClassName);
            generated.getConstructors().forEach(ctor -> ctor.setName(newClassName));

            // Apply transformations (null messager — Maven plugin has no Messager)
            PermuteDeclrTransformer.transform(generated, ctx, null);
            PermuteParamTransformer.transform(generated, ctx, null);

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
     * Strips all Permuplate annotations ({@code @Permute}, {@code @PermuteDeclr},
     * {@code @PermuteParam}) from a class declaration and all of its members,
     * including field-level and parameter-level annotations.
     */
    private static void stripPermuteAnnotations(ClassOrInterfaceDeclaration classDecl) {
        Set<String> PERMUPLATE_ANNOTATIONS = Set.of(
                "Permute", "io.quarkiverse.permuplate.Permute",
                "PermuteDeclr", "io.quarkiverse.permuplate.PermuteDeclr",
                "PermuteParam", "io.quarkiverse.permuplate.PermuteParam");

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
