package io.quarkiverse.permuplate.core;

import java.util.stream.Collectors;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;

/**
 * Handles {@code @PermuteSelf} on method declarations.
 *
 * <p>
 * Sets the method return type to the current generated class with all its type parameters.
 * Runs after {@link PermuteTypeParamTransformer} so that type parameters are already in
 * their final expanded form.
 *
 * <p>
 * The return type is constructed from {@code classDecl.getNameAsString()} and
 * {@code classDecl.getTypeParameters()} — both already reflect the generated class at
 * transformation time.
 */
public class PermuteSelfTransformer {

    private static final String SIMPLE = "PermuteSelf";
    private static final String FQ = "io.quarkiverse.permuplate.PermuteSelf";

    public static void transform(TypeDeclaration<?> classDecl) {
        String className = classDecl.getNameAsString();

        NodeList<TypeParameter> typeParamList = getTypeParameters(classDecl);
        String typeParams = typeParamList.stream()
                .map(tp -> tp.getNameAsString())
                .collect(Collectors.joining(", "));

        String returnTypeSrc = typeParams.isEmpty()
                ? className
                : className + "<" + typeParams + ">";

        Type returnType = StaticJavaParser.parseType(returnTypeSrc);

        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            boolean hasAnnotation = method.getAnnotations().stream()
                    .anyMatch(PermuteSelfTransformer::isPermuteSelf);
            if (!hasAnnotation)
                return;

            method.getAnnotations().removeIf(PermuteSelfTransformer::isPermuteSelf);
            method.setType(returnType.clone());
        });
    }

    static boolean isPermuteSelf(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        return name.equals(SIMPLE) || name.equals(FQ);
    }

    /**
     * Returns the type parameters of the given declaration.
     * Both {@code ClassOrInterfaceDeclaration} and {@code RecordDeclaration} implement
     * {@code NodeWithTypeParameters}; {@code EnumDeclaration} does not — returns empty list.
     */
    @SuppressWarnings("unchecked")
    private static NodeList<TypeParameter> getTypeParameters(TypeDeclaration<?> classDecl) {
        if (classDecl instanceof NodeWithTypeParameters<?> nwtp) {
            return ((NodeWithTypeParameters<TypeDeclaration<?>>) nwtp).getTypeParameters();
        }
        return new NodeList<>();
    }
}
