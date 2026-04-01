package io.quarkiverse.permuplate.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.core.PermuteParamTransformer;
import io.quarkiverse.permuplate.core.PermuteVarConfig;

/**
 * Generates permuted classes from {@code @Permute}-annotated templates.
 *
 * <p>
 * Supports all functionality of the APT annotation processor plus
 * <em>inline generation</em>: when a nested template class carries
 * {@code @Permute(inline = true)}, the permuted classes are written as nested
 * siblings inside the parent class rather than as separate top-level files.
 *
 * <p>
 * Inline templates must be placed in {@code src/main/permuplate/} (the plugin's
 * {@code templateDirectory}) so they are not compiled directly by javac. Regular
 * (non-inline) templates stay in {@code src/main/java/} as usual.
 *
 * <p>
 * <strong>Do not use this plugin together with the APT annotation processor
 * ({@code permuplate-processor} in {@code annotationProcessorPaths}).</strong>
 * The two would process the same {@code @Permute} annotations independently,
 * producing duplicate generated classes and compile errors. When switching from
 * APT to this plugin, remove the processor from {@code annotationProcessorPaths}
 * and disable APT with {@code <compilerArg>-proc:none</compilerArg>}.
 *
 * <p>
 * Minimal {@code pom.xml} configuration:
 *
 * <pre>{@code
 * <plugin>
 *     <groupId>io.quarkiverse.permuplate</groupId>
 *     <artifactId>quarkus-permuplate-maven-plugin</artifactId>
 *     <executions>
 *         <execution>
 *             <goals><goal>generate</goal></goals>
 *         </execution>
 *     </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class PermuteMojo extends AbstractMojo {

    /**
     * Directory containing regular (non-inline) {@code @Permute} templates.
     * These are processed and generate separate top-level files. Templates here
     * must have {@code inline = false} (the default).
     */
    @Parameter(defaultValue = "${project.build.sourceDirectory}")
    private File sourceDirectory;

    /**
     * Directory containing inline {@code @Permute} templates — those with
     * {@code inline = true}. These files are NOT compiled directly by javac.
     * The plugin reads them and writes augmented parent classes to
     * {@code outputDirectory}. Mark this directory as a source root in your
     * IDE for navigation and refactoring support (IntelliJ: right-click →
     * <em>Mark Directory As → Sources Root</em>).
     */
    @Parameter(defaultValue = "src/main/permuplate")
    private File templateDirectory;

    /**
     * Directory where all generated source files are written. Added as a
     * compile source root automatically by the plugin.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/permuplate")
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            outputDirectory.mkdirs();
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());

            // --- Non-inline: scan sourceDirectory ---
            getLog().info("Permuplate: scanning " + sourceDirectory + " for @Permute templates");
            SourceScanner.ScanResult mainScan = SourceScanner.scan(sourceDirectory);
            for (SourceScanner.AnnotatedType entry : mainScan.types()) {
                processType(entry);
            }
            for (SourceScanner.AnnotatedMethod entry : mainScan.methods()) {
                processMethod(entry);
            }

            // --- Inline: scan templateDirectory ---
            if (templateDirectory.exists()) {
                getLog().info("Permuplate: scanning " + templateDirectory + " for inline templates");
                SourceScanner.ScanResult templateScan = SourceScanner.scan(templateDirectory);
                for (SourceScanner.AnnotatedType entry : templateScan.types()) {
                    processType(entry);
                }
            }
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Permuplate generation failed: " + e.getMessage(), e);
        }
    }

    private void processType(SourceScanner.AnnotatedType entry) throws Exception {
        PermuteConfig config;
        try {
            config = AnnotationReader.readPermute(entry.permuteAnn());
        } catch (AnnotationReader.MojoAnnotationException e) {
            throw new MojoExecutionException(entry.sourceFile() + ": " + e.getMessage(), e);
        }

        // Validate inline only on nested classes
        boolean isNested = entry.classDecl().isNestedType();
        if (config.inline && !isNested) {
            throw new MojoExecutionException(entry.sourceFile() +
                    ": @Permute inline=true is only valid on nested static classes");
        }

        validateConfig(config, entry.sourceFile().toString());

        if (config.inline) {
            generateInline(entry, config);
        } else {
            generateTopLevel(entry, config);
        }
    }

    private void validateConfig(PermuteConfig config, String location) throws MojoExecutionException {
        if (config.from > config.to) {
            throw new MojoExecutionException(location + ": @Permute has invalid range: from=" +
                    config.from + " is greater than to=" + config.to);
        }
        for (String entry : config.strings) {
            int sep = entry.indexOf('=');
            if (sep < 0)
                throw new MojoExecutionException(location +
                        ": @Permute strings entry \"" + entry + "\" is malformed — must be \"key=value\"");
            String key = entry.substring(0, sep).trim();
            if (key.isEmpty())
                throw new MojoExecutionException(location +
                        ": @Permute strings entry \"" + entry + "\" has an empty key");
            if (key.equals(config.varName))
                throw new MojoExecutionException(location +
                        ": @Permute strings key \"" + key + "\" conflicts with varName");
        }
        Set<String> seen = new HashSet<>();
        seen.add(config.varName);
        for (PermuteVarConfig extra : config.extraVars) {
            if (extra.from > extra.to)
                throw new MojoExecutionException(location +
                        ": @PermuteVar \"" + extra.varName + "\" has invalid range");
            if (seen.contains(extra.varName))
                throw new MojoExecutionException(location +
                        ": @PermuteVar varName \"" + extra.varName + "\" conflicts with an existing variable");
            seen.add(extra.varName);
        }
    }

    private void generateTopLevel(SourceScanner.AnnotatedType entry, PermuteConfig config)
            throws Exception {
        String templateClassName = entry.classDecl().getNameAsString();
        List<Map<String, Object>> allCombinations = buildAllCombinations(config);

        // Leading literal prefix check
        String leadingLiteral = config.className.contains("${")
                ? config.className.substring(0, config.className.indexOf("${"))
                : config.className;
        if (!leadingLiteral.isEmpty() && !templateClassName.startsWith(leadingLiteral)) {
            throw new MojoExecutionException(entry.sourceFile() +
                    ": @Permute className leading literal \"" + leadingLiteral +
                    "\" is not a prefix of the template class name \"" + templateClassName + "\"");
        }

        for (Map<String, Object> vars : allCombinations) {
            EvaluationContext ctx = new EvaluationContext(vars);
            ClassOrInterfaceDeclaration classDecl = entry.classDecl().clone();
            classDecl.setStatic(false);
            if (!classDecl.isPublic())
                classDecl.setModifier(Modifier.Keyword.PUBLIC, true);

            String newClassName = ctx.evaluate(config.className);
            classDecl.setName(newClassName);
            classDecl.getConstructors().forEach(ctor -> ctor.setName(newClassName));

            PermuteDeclrTransformer.transform(classDecl, ctx, null);
            PermuteParamTransformer.transform(classDecl, ctx, null);

            classDecl.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute");
            });

            CompilationUnit generatedCu = new CompilationUnit();
            entry.cu().getPackageDeclaration().ifPresent(p -> generatedCu.setPackageDeclaration(p.clone()));
            entry.cu().getImports().forEach(imp -> {
                if (!imp.getNameAsString().startsWith("io.quarkiverse.permuplate"))
                    generatedCu.addImport(imp.clone());
            });
            generatedCu.addType(classDecl);

            String packageName = entry.cu().getPackageDeclaration()
                    .map(p -> p.getNameAsString()).orElse("");
            String qualifiedName = packageName.isEmpty() ? newClassName
                    : packageName + "." + newClassName;
            writeGeneratedFile(qualifiedName, generatedCu.toString());
        }
    }

    private void generateInline(SourceScanner.AnnotatedType entry, PermuteConfig config)
            throws Exception {
        List<Map<String, Object>> allCombinations = buildAllCombinations(config);
        CompilationUnit outputCu = InlineGenerator.generate(
                entry.cu(), entry.classDecl(), config, allCombinations);

        // Write the augmented parent using the TOP-LEVEL class name
        String parentClassName = entry.cu().findFirst(ClassOrInterfaceDeclaration.class,
                c -> !c.isNestedType())
                .orElseThrow(() -> new MojoExecutionException(
                        "Cannot find top-level class in " + entry.sourceFile()))
                .getNameAsString();

        String packageName = entry.cu().getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        String qualifiedName = packageName.isEmpty() ? parentClassName
                : packageName + "." + parentClassName;
        writeGeneratedFile(qualifiedName, outputCu.toString());
        getLog().info("Permuplate: generated inline classes in " + qualifiedName);
    }

    private void processMethod(SourceScanner.AnnotatedMethod entry) throws Exception {
        PermuteConfig config;
        try {
            config = AnnotationReader.readPermute(entry.permuteAnn());
        } catch (AnnotationReader.MojoAnnotationException e) {
            throw new MojoExecutionException(entry.sourceFile() + ": " + e.getMessage(), e);
        }
        validateConfig(config, entry.sourceFile().toString());

        List<Map<String, Object>> allCombinations = buildAllCombinations(config);
        EvaluationContext firstCtx = new EvaluationContext(allCombinations.get(0));
        String outputClassName = firstCtx.evaluate(config.className);

        ClassOrInterfaceDeclaration enclosingClass = entry.method()
                .findAncestor(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new MojoExecutionException(
                        entry.sourceFile() + ": cannot find enclosing class for method @Permute"));

        List<MethodDeclaration> methods = new ArrayList<>();
        for (Map<String, Object> vars : allCombinations) {
            EvaluationContext ctx = new EvaluationContext(vars);
            MethodDeclaration clone = entry.method().clone();
            clone.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute");
            });
            ClassOrInterfaceDeclaration wrapper = new ClassOrInterfaceDeclaration(
                    new NodeList<>(), false, "_W");
            wrapper.addMember(clone);
            PermuteDeclrTransformer.transform(wrapper, ctx, null);
            PermuteParamTransformer.transform(wrapper, ctx, null);
            methods.add(wrapper.getMethods().get(0));
        }

        ClassOrInterfaceDeclaration generatedClass = enclosingClass.clone();
        generatedClass.setName(outputClassName);
        generatedClass.setStatic(false);
        if (!generatedClass.isPublic())
            generatedClass.setModifier(Modifier.Keyword.PUBLIC, true);
        generatedClass.getAnnotations().removeIf(a -> {
            String n = a.getNameAsString();
            return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute");
        });
        generatedClass.setMembers(new NodeList<>());
        methods.forEach(m -> generatedClass.addMember(m));

        CompilationUnit generatedCu = new CompilationUnit();
        entry.cu().getPackageDeclaration().ifPresent(p -> generatedCu.setPackageDeclaration(p.clone()));
        entry.cu().getImports().forEach(imp -> {
            if (!imp.getNameAsString().startsWith("io.quarkiverse.permuplate"))
                generatedCu.addImport(imp.clone());
        });
        generatedCu.addType(generatedClass);

        String packageName = entry.cu().getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        String qualifiedName = packageName.isEmpty() ? outputClassName
                : packageName + "." + outputClassName;
        writeGeneratedFile(qualifiedName, generatedCu.toString());
    }

    private void writeGeneratedFile(String qualifiedName, String source) throws IOException {
        String path = qualifiedName.replace('.', '/') + ".java";
        Path outputPath = outputDirectory.toPath().resolve(path);
        outputPath.getParent().toFile().mkdirs();
        Files.writeString(outputPath, source);
        getLog().info("Permuplate: generated " + qualifiedName);
    }

    private static List<Map<String, Object>> buildAllCombinations(PermuteConfig config) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = config.from; i <= config.to; i++) {
            Map<String, Object> vars = new HashMap<>();
            vars.put(config.varName, i);
            result.add(vars);
        }
        for (PermuteVarConfig extra : config.extraVars) {
            List<Map<String, Object>> expanded = new ArrayList<>();
            for (Map<String, Object> base : result) {
                for (int v = extra.from; v <= extra.to; v++) {
                    Map<String, Object> copy = new HashMap<>(base);
                    copy.put(extra.varName, v);
                    expanded.add(copy);
                }
            }
            result = expanded;
        }
        for (Map<String, Object> vars : result) {
            for (String entry : config.strings) {
                int sep = entry.indexOf('=');
                vars.put(entry.substring(0, sep).trim(), entry.substring(sep + 1));
            }
        }
        return result;
    }
}
