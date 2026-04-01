package io.quarkiverse.permuplate.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteVar;
import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.core.PermuteParamTransformer;

/**
 * Annotation processor for {@link Permute}.
 *
 * <p>
 * {@code @Permute} is supported in two positions:
 *
 * <ul>
 * <li><b>On a class or interface</b> — for each combination of permutation
 * variables, clones the type declaration, applies all transformations, and writes
 * a new top-level source file. With {@code extraVars}, generates one file per
 * cross-product combination.</li>
 * <li><b>On a method</b> — for each combination, clones and transforms the method,
 * then collects all variants into a single new class containing N overloads.</li>
 * </ul>
 *
 * <p>
 * Transformation order for each permutation:
 * <ol>
 * <li>Type/method rename</li>
 * <li>{@code @PermuteDeclr} on fields — rename declaration + propagate to whole class</li>
 * <li>{@code @PermuteDeclr} on constructor parameters — rename + propagate in constructor body</li>
 * <li>{@code @PermuteDeclr} on for-each / local variables — rename + propagate in enclosing scope</li>
 * <li>{@code @PermuteParam} — replace parameter list + expand anchor at call sites</li>
 * <li>Strip all permgen imports and leftover annotations</li>
 * </ol>
 */
@SupportedAnnotationTypes("io.quarkiverse.permuplate.Permute")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class PermuteProcessor extends AbstractProcessor {

    private Trees trees;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        try {
            trees = Trees.instance(processingEnv);
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE, "Trees.instance() failed: " + e.getMessage());
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver())
            return false;

        TypeElement permuteAnnotationType = processingEnv.getElementUtils()
                .getTypeElement("io.quarkiverse.permuplate.Permute");
        if (permuteAnnotationType == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "PermuteProcessor: could not find Permute annotation type");
            return false;
        }

        Set<? extends Element> annotated = roundEnv.getElementsAnnotatedWith(permuteAnnotationType);
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "PermuteProcessor: found " + annotated.size() + " @Permute-annotated elements");

        for (Element element : annotated) {
            if (element instanceof TypeElement) {
                processTypePermutation((TypeElement) element);
            } else if (element instanceof ExecutableElement) {
                processMethodPermutation((ExecutableElement) element);
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Type permutation — generates one file per variable combination
    // -------------------------------------------------------------------------

    private void processTypePermutation(TypeElement typeElement) {
        Permute permute = typeElement.getAnnotation(Permute.class);
        AnnotationMirror permuteMirror = findAnnotationMirror(typeElement, "io.quarkiverse.permuplate.Permute");

        if (permute.from() > permute.to()) {
            error(String.format(
                    "@Permute has invalid range: from=%d is greater than to=%d — no classes will be generated",
                    permute.from(), permute.to()),
                    typeElement, permuteMirror, findAnnotationValue(permuteMirror, "from"));
            return;
        }

        // Check for inline — APT cannot generate inline (requires Maven plugin)
        if (permute.inline()) {
            boolean isNested = typeElement.getEnclosingElement() instanceof TypeElement;
            if (!isNested) {
                error("@Permute inline = true is only valid on nested static classes — " +
                        "there is no parent class to inline into",
                        typeElement, permuteMirror, findAnnotationValue(permuteMirror, "inline"));
            } else {
                error("@Permute inline = true requires permuplate-maven-plugin — " +
                        "the annotation processor cannot modify existing source files. " +
                        "See README §'APT vs Maven Plugin' for migration instructions.",
                        typeElement, permuteMirror, findAnnotationValue(permuteMirror, "inline"));
            }
            return;
        }

        // Warn if keepTemplate is set but inline is false
        if (permute.keepTemplate()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "@Permute keepTemplate = true has no effect when inline = false",
                    typeElement, permuteMirror, findAnnotationValue(permuteMirror, "keepTemplate"));
        }

        if (!validateStrings(permute, typeElement, permuteMirror))
            return;

        if (!validateExtraVars(permute, typeElement, permuteMirror))
            return;

        // className prefix rule: the LEADING literal (before the first ${...}) must be
        // a prefix of the template class name. Using only the leading literal (rather
        // than all literal segments combined) correctly handles multi-variable classNames
        // like "Combo${i}x${k}" whose leading literal is "Combo", not "Combox".
        String leadingLiteral = permute.className().contains("${")
                ? permute.className().substring(0, permute.className().indexOf("${"))
                : permute.className();
        String templateSimpleName = typeElement.getSimpleName().toString();
        if (!leadingLiteral.isEmpty() && !templateSimpleName.startsWith(leadingLiteral)) {
            error(String.format(
                    "@Permute className leading literal \"%s\" is not a prefix of the template" +
                            " class name \"%s\" — the className expression must share the same" +
                            " base name as the template",
                    leadingLiteral, templateSimpleName),
                    typeElement, permuteMirror, findAnnotationValue(permuteMirror, "className"));
            return;
        }

        CompilationUnit templateCu = parseSource(typeElement);
        if (templateCu == null)
            return;

        Optional<ClassOrInterfaceDeclaration> foundForValidation = templateCu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals(templateSimpleName));
        if (foundForValidation.isPresent()) {
            ClassOrInterfaceDeclaration templateClassDecl = foundForValidation.get();
            boolean declrValid = PermuteDeclrTransformer.validatePrefixes(
                    templateClassDecl, processingEnv.getMessager(), typeElement);
            boolean paramValid = PermuteParamTransformer.validatePrefixes(
                    templateClassDecl, processingEnv.getMessager(), typeElement);
            if (!declrValid || !paramValid)
                return;
        }

        for (Map<String, Object> vars : buildAllCombinations(permute)) {
            generatePermutation(templateCu, typeElement, permute, new EvaluationContext(vars));
        }
    }

    private void generatePermutation(CompilationUnit templateCu, TypeElement typeElement,
            Permute permute, EvaluationContext ctx) {
        String templateClassName = typeElement.getSimpleName().toString();
        AnnotationMirror permuteMirror = findAnnotationMirror(typeElement, "io.quarkiverse.permuplate.Permute");

        // Find the class by name anywhere in the compilation unit (handles nested classes)
        Optional<ClassOrInterfaceDeclaration> found = templateCu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals(templateClassName));
        if (!found.isPresent()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot find class " + templateClassName + " in source", typeElement);
            return;
        }

        // Clone just the class declaration — handles both top-level and nested types.
        ClassOrInterfaceDeclaration classDecl = found.get().clone();

        // If the template was a nested type, strip modifiers that don't apply to top-level
        classDecl.setStatic(false);
        if (!classDecl.isPublic()) {
            classDecl.setModifier(Modifier.Keyword.PUBLIC, true);
        }

        // 1. Rename the class
        String newClassName;
        try {
            newClassName = ctx.evaluate(permute.className());
        } catch (Exception e) {
            error("Failed to evaluate @Permute className \"" + permute.className() + "\": " + e.getMessage(),
                    typeElement, permuteMirror, findAnnotationValue(permuteMirror, "className"));
            return;
        }
        if (newClassName == null || newClassName.isBlank()) {
            error("@Permute className \"" + permute.className() + "\" evaluated to an empty name",
                    typeElement, permuteMirror, findAnnotationValue(permuteMirror, "className"));
            return;
        }
        classDecl.setName(newClassName);
        // Constructor names must match the class name — rename them all to match.
        // JavaParser does not propagate class renames to constructors automatically.
        classDecl.getConstructors().forEach(ctor -> ctor.setName(newClassName));

        // 2, 3 & 4. @PermuteDeclr — fields, constructor params, then for-each vars
        PermuteDeclrTransformer.transform(classDecl, ctx, processingEnv.getMessager());

        // 5. @PermuteParam — expand parameter list + anchor expansion at call sites
        PermuteParamTransformer.transform(classDecl, ctx, processingEnv.getMessager());

        // 6. Remove @Permute from the class
        classDecl.getAnnotations().removeIf(a -> {
            String name = a.getNameAsString();
            return name.equals("Permute") || name.equals("io.quarkiverse.permuplate.Permute");
        });

        // Build a fresh CompilationUnit, copying package and non-permuplate imports.
        CompilationUnit generatedCu = new CompilationUnit();
        templateCu.getPackageDeclaration().ifPresent(p -> generatedCu.setPackageDeclaration(p.clone()));
        templateCu.getImports().forEach(imp -> {
            if (!imp.getNameAsString().startsWith("io.quarkiverse.permuplate")) {
                generatedCu.addImport(imp.clone());
            }
        });
        generatedCu.addType(classDecl);

        writeGeneratedClass(typeElement, newClassName, generatedCu.toString());
    }

    // -------------------------------------------------------------------------
    // Method permutation — generates ONE file containing N method overloads
    // -------------------------------------------------------------------------

    private void processMethodPermutation(ExecutableElement methodElement) {
        TypeElement enclosingClass = (TypeElement) methodElement.getEnclosingElement();
        Permute permute = methodElement.getAnnotation(Permute.class);
        AnnotationMirror permuteMirror = findAnnotationMirror(methodElement, "io.quarkiverse.permuplate.Permute");

        if (permute.from() > permute.to()) {
            error(String.format(
                    "@Permute has invalid range: from=%d is greater than to=%d — no methods will be generated",
                    permute.from(), permute.to()),
                    methodElement, permuteMirror, findAnnotationValue(permuteMirror, "from"));
            return;
        }

        if (!validateStrings(permute, methodElement, permuteMirror))
            return;

        if (!validateExtraVars(permute, methodElement, permuteMirror))
            return;

        CompilationUnit templateCu = parseSource(enclosingClass);
        if (templateCu == null)
            return;

        // Locate the enclosing class in the AST
        String enclosingName = enclosingClass.getSimpleName().toString();
        Optional<ClassOrInterfaceDeclaration> foundClass = templateCu.findFirst(
                ClassOrInterfaceDeclaration.class, c -> c.getNameAsString().equals(enclosingName));
        if (!foundClass.isPresent()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot find class " + enclosingName + " in source", methodElement);
            return;
        }

        // Locate the annotated method by name and parameter count
        String methodName = methodElement.getSimpleName().toString();
        int methodParamCount = methodElement.getParameters().size();
        Optional<MethodDeclaration> foundMethod = foundClass.get().getMethods().stream()
                .filter(m -> m.getNameAsString().equals(methodName)
                        && m.getParameters().size() == methodParamCount)
                .findFirst();
        if (!foundMethod.isPresent()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot find method " + methodName + " in class " + enclosingName, methodElement);
            return;
        }

        // Evaluate the output class name using the first combination's context.
        List<Map<String, Object>> allCombinations = buildAllCombinations(permute);
        EvaluationContext firstCtx = new EvaluationContext(allCombinations.get(0));
        String outputClassName;
        try {
            outputClassName = firstCtx.evaluate(permute.className());
        } catch (Exception e) {
            error("Failed to evaluate @Permute className \"" + permute.className() + "\": " + e.getMessage(),
                    methodElement, permuteMirror, findAnnotationValue(permuteMirror, "className"));
            return;
        }
        if (outputClassName == null || outputClassName.isBlank()) {
            error("@Permute className evaluated to an empty name",
                    methodElement, permuteMirror, findAnnotationValue(permuteMirror, "className"));
            return;
        }

        // Generate one method variant per combination.
        List<MethodDeclaration> methods = new ArrayList<>();
        for (Map<String, Object> vars : allCombinations) {
            EvaluationContext ctx = new EvaluationContext(vars);
            MethodDeclaration clone = foundMethod.get().clone();

            // Strip @Permute from the clone — it belongs on the template, not the output
            clone.getAnnotations().removeIf(a -> {
                String name = a.getNameAsString();
                return name.equals("Permute") || name.equals("io.quarkiverse.permuplate.Permute");
            });

            // Wrap in a temporary class so the existing transformers can operate normally
            ClassOrInterfaceDeclaration wrapper = new ClassOrInterfaceDeclaration(
                    new NodeList<>(), false, "_W");
            wrapper.addMember(clone);
            PermuteDeclrTransformer.transform(wrapper, ctx, processingEnv.getMessager());
            PermuteParamTransformer.transform(wrapper, ctx, processingEnv.getMessager());
            methods.add(wrapper.getMethods().get(0));
        }

        // Assemble the output class
        ClassOrInterfaceDeclaration generatedClass = foundClass.get().clone();
        generatedClass.setName(outputClassName);
        generatedClass.setStatic(false);
        if (!generatedClass.isPublic()) {
            generatedClass.setModifier(Modifier.Keyword.PUBLIC, true);
        }
        generatedClass.getAnnotations().removeIf(a -> {
            String name = a.getNameAsString();
            return name.equals("Permute") || name.equals("io.quarkiverse.permuplate.Permute");
        });
        generatedClass.setMembers(new NodeList<>());
        for (MethodDeclaration m : methods) {
            generatedClass.addMember(m);
        }

        CompilationUnit generatedCu = new CompilationUnit();
        templateCu.getPackageDeclaration().ifPresent(p -> generatedCu.setPackageDeclaration(p.clone()));
        templateCu.getImports().forEach(imp -> {
            if (!imp.getNameAsString().startsWith("io.quarkiverse.permuplate")) {
                generatedCu.addImport(imp.clone());
            }
        });
        generatedCu.addType(generatedClass);

        writeGeneratedClass(enclosingClass, outputClassName, generatedCu.toString());
    }

    // -------------------------------------------------------------------------
    // Variable combination builder
    // -------------------------------------------------------------------------

    /**
     * Builds the full cross-product of variable bindings across the primary variable
     * and all {@link Permute#extraVars()}, merged with {@link Permute#strings()} constants.
     *
     * <p>
     * The primary variable is the outermost loop; {@code extraVars} are inner loops in
     * declaration order. For primary i∈[2,3] and k∈[2,3], the result is:
     * [{i=2,k=2}, {i=2,k=3}, {i=3,k=2}, {i=3,k=3}].
     */
    private static List<Map<String, Object>> buildAllCombinations(Permute permute) {
        // Start with the primary variable's range
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = permute.from(); i <= permute.to(); i++) {
            Map<String, Object> vars = new HashMap<>();
            vars.put(permute.varName(), i);
            result.add(vars);
        }

        // Expand by each extraVar (cross-product)
        for (PermuteVar extra : permute.extraVars()) {
            List<Map<String, Object>> expanded = new ArrayList<>();
            for (Map<String, Object> base : result) {
                for (int v = extra.from(); v <= extra.to(); v++) {
                    Map<String, Object> copy = new HashMap<>(base);
                    copy.put(extra.varName(), v);
                    expanded.add(copy);
                }
            }
            result = expanded;
        }

        // Merge string constants into every combination
        for (Map<String, Object> vars : result) {
            for (String entry : permute.strings()) {
                int sep = entry.indexOf('=');
                vars.put(entry.substring(0, sep).trim(), entry.substring(sep + 1));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that every entry in {@code @Permute strings} has the required
     * {@code "key=value"} format with a non-empty key that does not conflict with
     * {@code varName} or any {@code extraVars} variable name.
     */
    private boolean validateStrings(Permute permute, Element element, AnnotationMirror mirror) {
        for (String entry : permute.strings()) {
            int sep = entry.indexOf('=');
            if (sep < 0) {
                error(String.format(
                        "@Permute strings entry \"%s\" is malformed — each entry must be in \"key=value\" format",
                        entry),
                        element, mirror, findAnnotationValue(mirror, "strings"));
                return false;
            }
            String key = entry.substring(0, sep).trim();
            if (key.isEmpty()) {
                error(String.format(
                        "@Permute strings entry \"%s\" has an empty key — key must be non-empty",
                        entry),
                        element, mirror, findAnnotationValue(mirror, "strings"));
                return false;
            }
            if (key.equals(permute.varName())) {
                error(String.format(
                        "@Permute strings key \"%s\" conflicts with varName \"%s\" — use a different name",
                        key, permute.varName()),
                        element, mirror, findAnnotationValue(mirror, "strings"));
                return false;
            }
            for (PermuteVar extra : permute.extraVars()) {
                if (key.equals(extra.varName())) {
                    error(String.format(
                            "@Permute strings key \"%s\" conflicts with extraVars varName \"%s\" — use a different name",
                            key, extra.varName()),
                            element, mirror, findAnnotationValue(mirror, "strings"));
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Validates each {@link PermuteVar} in {@code extraVars}: range must be valid,
     * and variable names must not conflict with {@code varName}, each other, or any
     * {@code strings} key.
     */
    private boolean validateExtraVars(Permute permute, Element element, AnnotationMirror mirror) {
        Set<String> seen = new HashSet<>();
        seen.add(permute.varName());
        for (String entry : permute.strings()) {
            int sep = entry.indexOf('=');
            if (sep > 0)
                seen.add(entry.substring(0, sep).trim());
        }

        for (PermuteVar extra : permute.extraVars()) {
            if (extra.from() > extra.to()) {
                error(String.format(
                        "@PermuteVar \"%s\" has invalid range: from=%d is greater than to=%d",
                        extra.varName(), extra.from(), extra.to()),
                        element, mirror, findAnnotationValue(mirror, "extraVars"));
                return false;
            }
            if (seen.contains(extra.varName())) {
                error(String.format(
                        "@PermuteVar varName \"%s\" conflicts with an existing variable name" +
                                " — each variable name must be unique across varName, extraVars, and strings",
                        extra.varName()),
                        element, mirror, findAnnotationValue(mirror, "extraVars"));
                return false;
            }
            seen.add(extra.varName());
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Source parsing
    // -------------------------------------------------------------------------

    /**
     * Reads the source of the annotated element via the compiler Trees API.
     * Uses {@code getCharContent} so this works for both file-based and in-memory
     * sources (the latter is used by compile-testing in unit tests).
     */
    private CompilationUnit parseSource(TypeElement typeElement) {
        try {
            TreePath path = trees.getPath(typeElement);
            String source = path.getCompilationUnit().getSourceFile()
                    .getCharContent(true).toString();
            return StaticJavaParser.parse(source);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to parse source for @Permute class: " + e.getMessage(),
                    typeElement);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Error-reporting helpers
    // -------------------------------------------------------------------------

    private static AnnotationMirror findAnnotationMirror(Element element, String fqn) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement())
                    .getQualifiedName().contentEquals(fqn)) {
                return mirror;
            }
        }
        return null;
    }

    private static AnnotationValue findAnnotationValue(AnnotationMirror mirror, String attribute) {
        if (mirror == null)
            return null;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : mirror.getElementValues()
                .entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(attribute)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void error(String message, Element element, AnnotationMirror mirror, AnnotationValue value) {
        if (mirror != null && value != null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element, mirror, value);
        } else if (mirror != null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element, mirror);
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
        }
    }

    private void writeGeneratedClass(TypeElement typeElement, String newClassName, String source) {
        // Determine package from the enclosing element.
        // For nested classes, getEnclosingElement() is the outer class, not a package —
        // walk up until we reach the package.
        Element enclosing = typeElement.getEnclosingElement();
        while (!(enclosing instanceof PackageElement)) {
            enclosing = enclosing.getEnclosingElement();
        }
        String packageName = ((PackageElement) enclosing).getQualifiedName().toString();
        String qualifiedName = packageName.isEmpty() ? newClassName : packageName + "." + newClassName;

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, typeElement);
            try (Writer w = file.openWriter()) {
                w.write(source);
            }
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE, "Generated " + qualifiedName, typeElement);
        } catch (IOException e) {
            String raw = e.getMessage() != null ? e.getMessage() : "";
            boolean isDuplicate = raw.contains("recreate") || raw.contains("already");
            String msg = isDuplicate
                    ? "Duplicate generated class " + qualifiedName
                            + " — @Permute className must include the permutation variable (e.g. \"Foo${i}\")"
                    : "Failed to write " + qualifiedName + ": " + raw;
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, typeElement);
        }
    }
}
