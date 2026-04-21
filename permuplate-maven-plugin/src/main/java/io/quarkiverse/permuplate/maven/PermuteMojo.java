package io.quarkiverse.permuplate.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.core.PermuteParamTransformer;
import io.quarkiverse.permuplate.core.PermuteTypeParamTransformer;
import io.quarkiverse.permuplate.core.PermuteVarConfig;
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;

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
    @Parameter(defaultValue = "${project.basedir}/src/main/permuplate")
    private File templateDirectory;

    /**
     * Directory where all generated source files are written. Added as a
     * compile source root automatically by the plugin.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/permuplate")
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** External properties resolved from {@code -Dpermuplate.*} system properties this run. */
    private java.util.Map<String, Object> externalProperties = java.util.Collections.emptyMap();

    @Override
    public void execute() throws MojoExecutionException {
        try {
            outputDirectory.mkdirs();
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());

            // Build external properties from system properties (-Dpermuplate.*).
            // APT options (-Apermuplate.*) are NOT available in the Maven plugin —
            // only system properties work here. Both APT and Maven plugin support -D.
            externalProperties = PermuteConfig.buildExternalProperties(null, false);
            if (!externalProperties.isEmpty()) {
                getLog().info("Permuplate: resolved external properties from -Dpermuplate.*: "
                        + externalProperties.keySet());
            }
            InlineGenerator.setExternalProperties(externalProperties);

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

                // Collect ALL parsed CUs from the template directory — needed for @PermuteMixin
                // lookup. Mixin classes are not @Permute-annotated so they don't appear in
                // templateScan.types(); parseAll() covers the entire directory.
                List<CompilationUnit> allTemplateCus = SourceScanner.parseAll(templateDirectory);

                // Group inline templates by source file. Multiple inline templates in the same
                // parent must be chained: output CU of each call becomes input of the next.
                // Declaration order is preserved by SourceScanner.findAll() (depth-first).
                java.util.Map<java.nio.file.Path, java.util.List<SourceScanner.AnnotatedType>> inlineByFile = new java.util.LinkedHashMap<>();

                for (SourceScanner.AnnotatedType entry : templateScan.types()) {
                    PermuteConfig config;
                    try {
                        config = AnnotationReader.readPermute(entry.permuteAnn());
                    } catch (AnnotationReader.MojoAnnotationException e) {
                        throw new MojoExecutionException(entry.sourceFile() + ": " + e.getMessage(), e);
                    }
                    validateConfig(config, entry.sourceFile().toString());
                    if (config.inline) {
                        inlineByFile.computeIfAbsent(entry.sourceFile(),
                                k -> new java.util.ArrayList<>()).add(entry);
                    } else {
                        generateTopLevel(entry, config);
                    }
                }

                for (java.util.Map.Entry<java.nio.file.Path, java.util.List<SourceScanner.AnnotatedType>> fileGroup : inlineByFile
                        .entrySet()) {
                    List<SourceScanner.AnnotatedType> sorted = sortBySourceDependency(fileGroup.getValue());
                    generateInlineGroup(fileGroup.getKey(), sorted, allTemplateCus);
                }

                // Non-template @PermuteMixin processing — classes with @PermuteMixin but no @Permute
                processNonTemplateMixins(allTemplateCus);
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
        boolean isNested = entry.typeDecl().isNestedType();
        if (config.inline && !isNested) {
            throw new MojoExecutionException(entry.sourceFile() +
                    ": @Permute inline=true is only valid on nested static classes");
        }
        if (config.inline) {
            throw new MojoExecutionException(entry.sourceFile() +
                    ": @Permute inline=true templates must be placed in the templateDirectory" +
                    " (default: src/main/permuplate/), not sourceDirectory." +
                    " Move this file to src/main/permuplate/ and remove it from src/main/java/.");
        }

        validateConfig(config, entry.sourceFile().toString());

        generateTopLevel(entry, config);
    }

    private void validateConfig(PermuteConfig config, String location) throws MojoExecutionException {
        // Evaluate from/to using the external properties + strings base context
        java.util.Map<String, Object> baseVars = PermuteConfig.buildBaseVars(config, externalProperties);
        io.quarkiverse.permuplate.core.EvaluationContext valCtx = new io.quarkiverse.permuplate.core.EvaluationContext(
                baseVars);
        // String-set mode: values is non-empty — skip from/to range validation.
        // buildAllCombinations already handles the values path correctly.
        if (config.values.length == 0) {
            int fromVal, toVal;
            try {
                fromVal = valCtx.evaluateInt(config.from);
                toVal = valCtx.evaluateInt(config.to);
            } catch (Exception e) {
                throw new MojoExecutionException(location +
                        ": @Permute from/to expression failed to evaluate: " + e.getMessage(), e);
            }
            if (fromVal > toVal) {
                throw new MojoExecutionException(location + ": @Permute has invalid range: from=" +
                        fromVal + " is greater than to=" + toVal);
            }
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
            // from/to are now String expressions; only validate if plain integers
            try {
                int ef = Integer.parseInt(extra.from.trim());
                int et = Integer.parseInt(extra.to.trim());
                if (ef > et)
                    throw new MojoExecutionException(location +
                            ": @PermuteVar \"" + extra.varName + "\" has invalid range");
            } catch (NumberFormatException ignored) {
                // expression-based — validated at eval time
            }
            if (seen.contains(extra.varName))
                throw new MojoExecutionException(location +
                        ": @PermuteVar varName \"" + extra.varName + "\" conflicts with an existing variable");
            seen.add(extra.varName);
        }
    }

    private void generateTopLevel(SourceScanner.AnnotatedType entry, PermuteConfig config)
            throws Exception {
        String templateClassName = entry.typeDecl().getNameAsString();
        List<Map<String, Object>> allCombinations = PermuteConfig.buildAllCombinations(config);

        // Prefix check using full substring matching — consistent with APT processor.
        // Skip when className has no static literals (all-variables case e.g. "${i}"):
        // matches() returns false for those, but the appropriate error is a missing
        // literal (caught as a JEXL evaluation error), not a match failure.
        AnnotationStringTemplate classNameTemplate = AnnotationStringAlgorithm.parse(config.className);
        if (!classNameTemplate.hasNoLiteral()
                && !AnnotationStringAlgorithm.matches(classNameTemplate, templateClassName)) {
            throw new MojoExecutionException(entry.sourceFile() +
                    ": @Permute className \"" + config.className +
                    "\" has a literal that does not appear as a substring of the template" +
                    " class name \"" + templateClassName +
                    "\" — the className expression must reference the template class name");
        }

        if (entry.typeDecl() instanceof com.github.javaparser.ast.body.RecordDeclaration) {
            throw new MojoExecutionException(entry.sourceFile() +
                    ": record templates require inline=true");
        }

        for (Map<String, Object> vars : allCombinations) {
            EvaluationContext ctx = new EvaluationContext(vars);
            TypeDeclaration<?> typeDecl = entry.typeDecl().clone();
            if (typeDecl instanceof ClassOrInterfaceDeclaration coid)
                coid.setStatic(false);
            if (!typeDecl.isPublic())
                typeDecl.setModifier(Modifier.Keyword.PUBLIC, true);

            String newClassName = ctx.evaluate(config.className);
            typeDecl.setName(newClassName);
            typeDecl.getConstructors().forEach(ctor -> ctor.setName(newClassName));

            PermuteTypeParamTransformer.transform(typeDecl, ctx, null, null);
            PermuteDeclrTransformer.transform(typeDecl, ctx, null);
            PermuteParamTransformer.transform(typeDecl, ctx, null);

            // @PermuteAnnotation — add Java annotations to generated elements
            io.quarkiverse.permuplate.core.PermuteAnnotationTransformer.transform(
                    typeDecl, ctx, null, null);

            // Strip all Permuplate annotations (including @Permute, @PermuteAnnotation, etc.)
            InlineGenerator.stripPermuteAnnotations(typeDecl);

            CompilationUnit generatedCu = new CompilationUnit();
            entry.cu().getPackageDeclaration().ifPresent(p -> generatedCu.setPackageDeclaration(p.clone()));
            entry.cu().getImports().forEach(imp -> {
                if (!imp.getNameAsString().startsWith("io.quarkiverse.permuplate"))
                    generatedCu.addImport(imp.clone());
            });
            generatedCu.addType(typeDecl);

            String packageName = entry.cu().getPackageDeclaration()
                    .map(p -> p.getNameAsString()).orElse("");
            String qualifiedName = packageName.isEmpty() ? newClassName
                    : packageName + "." + newClassName;
            writeGeneratedFile(qualifiedName, generatedCu.toString());
        }

        // keepTemplate=true: write the template class itself (annotations stripped) as its own file.
        // This is required when the template class is referenced directly by other code
        // (e.g. NotScope is used by JoinBuilder alongside the generated ExistsScope).
        if (config.keepTemplate) {
            TypeDeclaration<?> templateDecl = entry.typeDecl().clone();
            if (templateDecl instanceof ClassOrInterfaceDeclaration coid)
                coid.setStatic(false);
            if (!templateDecl.isPublic())
                templateDecl.setModifier(Modifier.Keyword.PUBLIC, true);
            InlineGenerator.stripPermuteAnnotations(templateDecl);

            CompilationUnit templateCu = new CompilationUnit();
            entry.cu().getPackageDeclaration().ifPresent(p -> templateCu.setPackageDeclaration(p.clone()));
            entry.cu().getImports().forEach(imp -> {
                if (!imp.getNameAsString().startsWith("io.quarkiverse.permuplate"))
                    templateCu.addImport(imp.clone());
            });
            templateCu.addType(templateDecl);

            String packageName = entry.cu().getPackageDeclaration()
                    .map(p -> p.getNameAsString()).orElse("");
            String qualifiedTemplateName = packageName.isEmpty() ? templateClassName
                    : packageName + "." + templateClassName;
            writeGeneratedFile(qualifiedTemplateName, templateCu.toString());
        }
    }

    private void generateInline(SourceScanner.AnnotatedType entry, PermuteConfig config)
            throws Exception {
        config = InlineGenerator.mergeContainerMacros(config, entry.typeDecl());
        List<Map<String, Object>> allCombinations = PermuteConfig.buildAllCombinations(config);
        CompilationUnit outputCu = InlineGenerator.generate(
                entry.cu(), entry.typeDecl(), config, allCombinations);

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

    /**
     * Processes all inline templates from a single parent file in declaration order,
     * chaining InlineGenerator calls so the output CU of each becomes the input of
     * the next. Writes the final combined output once.
     *
     * <p>
     * This is required when a parent file has multiple @Permute(inline=true) templates.
     * Without chaining, each call would write independently, overwriting the previous output.
     *
     * @param allTemplateCus all parsed CompilationUnits from the template directory,
     *        used for {@code @PermuteMixin} mixin class lookup
     */
    private void generateInlineGroup(java.nio.file.Path sourceFile,
            java.util.List<SourceScanner.AnnotatedType> entries,
            List<CompilationUnit> allTemplateCus) throws Exception {
        if (entries.isEmpty())
            return;

        com.github.javaparser.ast.CompilationUnit currentCu = entries.get(0).cu();

        for (SourceScanner.AnnotatedType entry : entries) {
            String templateName = entry.typeDecl().getNameAsString();

            // Find the template in the CURRENT CU — may be output of a previous call.
            // Supports both class/interface and record templates.
            final String tName = templateName;
            final com.github.javaparser.ast.CompilationUnit searchCu = currentCu;
            TypeDeclaration<?> currentTemplate = searchCu.findFirst(ClassOrInterfaceDeclaration.class,
                    c -> c.getNameAsString().equals(tName))
                    .<TypeDeclaration<?>> map(c -> c)
                    .or(() -> searchCu.findFirst(RecordDeclaration.class,
                            r -> r.getNameAsString().equals(tName)))
                    .or(() -> searchCu.findFirst(EnumDeclaration.class,
                            e -> e.getNameAsString().equals(tName)))
                    .orElseThrow(() -> new MojoExecutionException(sourceFile +
                            ": cannot find template '" + tName + "' in current CU"));

            // Re-read @Permute config from the template in the current CU.
            com.github.javaparser.ast.expr.AnnotationExpr permuteAnn = currentTemplate.getAnnotations().stream()
                    .filter(a -> a.getNameAsString().equals("Permute")
                            || a.getNameAsString().equals("io.quarkiverse.permuplate.Permute"))
                    .findFirst()
                    .orElseThrow(() -> new MojoExecutionException(sourceFile +
                            ": @Permute annotation missing on '" + tName + "'"));

            PermuteConfig config;
            try {
                config = AnnotationReader.readPermute(permuteAnn);
            } catch (AnnotationReader.MojoAnnotationException e) {
                throw new MojoExecutionException(sourceFile + ": " + e.getMessage(), e);
            }
            // Use the original entry's typeDecl for container macro collection — the original
            // source tree retains @PermuteMacros on the outer class, whereas currentTemplate
            // may come from a previously-generated (stripped) CU when processing the second
            // template in a chained group.
            config = InlineGenerator.mergeContainerMacros(config, entry.typeDecl());
            java.util.List<java.util.Map<String, Object>> allCombinations = PermuteConfig.buildAllCombinations(config);
            // Inject mixin methods before generate() so they participate in the full pipeline.
            InlineGenerator.injectMixinMethods(currentTemplate, allTemplateCus);
            currentCu = InlineGenerator.generate(currentCu, currentTemplate, config, allCombinations);
        }

        // Write the final combined output once.
        ClassOrInterfaceDeclaration topLevel = currentCu.findFirst(
                ClassOrInterfaceDeclaration.class, c -> !c.isNestedType())
                .orElseThrow(() -> new MojoExecutionException(
                        sourceFile + ": cannot find top-level class in output"));
        String parentClassName = topLevel.getNameAsString();
        String packageName = currentCu.getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        String qualifiedName = packageName.isEmpty() ? parentClassName
                : packageName + "." + parentClassName;
        writeGeneratedFile(qualifiedName, currentCu.toString());
        getLog().info("Permuplate: generated inline group in " + qualifiedName);
    }

    /**
     * Sorts templates so @PermuteSource-dependent templates process after their sources.
     * Source templates (no @PermuteSource) come first; derived templates come second.
     * Single-level only — preserves original relative order within each group.
     */
    private List<SourceScanner.AnnotatedType> sortBySourceDependency(
            List<SourceScanner.AnnotatedType> templates) {
        AnnotationReader reader = new AnnotationReader();
        List<SourceScanner.AnnotatedType> sources = new java.util.ArrayList<>();
        List<SourceScanner.AnnotatedType> derived = new java.util.ArrayList<>();
        for (SourceScanner.AnnotatedType t : templates) {
            if (reader.readPermuteSourceNames(t.typeDecl()).isEmpty()) {
                sources.add(t);
            } else {
                derived.add(t);
            }
        }
        List<SourceScanner.AnnotatedType> sorted = new java.util.ArrayList<>(sources);
        sorted.addAll(derived);
        return sorted;
    }

    private void processMethod(SourceScanner.AnnotatedMethod entry) throws Exception {
        PermuteConfig config;
        try {
            config = AnnotationReader.readPermute(entry.permuteAnn());
        } catch (AnnotationReader.MojoAnnotationException e) {
            throw new MojoExecutionException(entry.sourceFile() + ": " + e.getMessage(), e);
        }
        validateConfig(config, entry.sourceFile().toString());

        List<Map<String, Object>> allCombinations = PermuteConfig.buildAllCombinations(config);
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

    /**
     * Processes non-template classes in the template directory that have {@code @PermuteMixin}
     * but no {@code @Permute}. Injects mixin-generated methods and writes the augmented class
     * to the generated sources directory — eliminating the need for a dummy
     * {@code @Permute(varName="i", from="1", to="1")} annotation.
     *
     * <p>
     * <b>Scope:</b> Only top-level types are examined ({@code cu.getTypes()});
     * nested classes with {@code @PermuteMixin} and no {@code @Permute} are silently ignored.
     * Only classes from the template directory ({@code src/main/permuplate/}) are considered;
     * classes in {@code src/main/java/} are not processed here.
     *
     * <p>
     * Only concrete classes are processed — interfaces are excluded because
     * {@code ClassOrInterfaceDeclaration} in JavaParser covers both.
     */
    private void processNonTemplateMixins(List<CompilationUnit> allTemplateCus) throws Exception {
        for (CompilationUnit cu : allTemplateCus) {
            for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
                // Only process ClassOrInterfaceDeclaration (not records, enums, interfaces)
                if (!(typeDecl instanceof ClassOrInterfaceDeclaration coid) || coid.isInterface())
                    continue;
                // Must have @PermuteMixin
                boolean hasMixin = typeDecl.getAnnotations().stream()
                        .anyMatch(a -> {
                            String n = a.getNameAsString();
                            return n.equals("PermuteMixin") || n.equals("io.quarkiverse.permuplate.PermuteMixin");
                        });
                if (!hasMixin)
                    continue;
                // Must NOT have @Permute (those are handled by the regular inline pipeline)
                boolean hasPermute = typeDecl.getAnnotations().stream()
                        .anyMatch(a -> {
                            String n = a.getNameAsString();
                            return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute");
                        });
                if (hasPermute)
                    continue;

                String className = typeDecl.getNameAsString();
                getLog().info("Permuplate: processing non-template @PermuteMixin on " + className);

                // Clone the CU and work on the clone to avoid mutating the shared allTemplateCus
                CompilationUnit workCu = cu.clone();
                TypeDeclaration<?> workTd = workCu.findFirst(ClassOrInterfaceDeclaration.class,
                        c -> c.getNameAsString().equals(className))
                        .orElseThrow(() -> new MojoExecutionException(
                                "Cannot find class '" + className + "' in cloned CU"));

                // Inject mixin methods before generate()
                InlineGenerator.injectMixinMethods(workTd, allTemplateCus);

                // Synthesize a single-iteration config — no actual permutation loop, just mixin expansion.
                PermuteConfig syntheticConfig = new PermuteConfig(
                        "i", "1", "1", className,
                        new String[0], new PermuteVarConfig[0],
                        true, false);

                List<Map<String, Object>> combos = PermuteConfig.buildAllCombinations(syntheticConfig);
                CompilationUnit outputCu = InlineGenerator.generate(workCu, workTd, syntheticConfig, combos);

                String packageName = cu.getPackageDeclaration()
                        .map(p -> p.getNameAsString()).orElse("");
                String qualifiedName = packageName.isEmpty() ? className : packageName + "." + className;
                writeGeneratedFile(qualifiedName, outputCu.toString());
            }
        }
    }

    private void writeGeneratedFile(String qualifiedName, String source) throws IOException {
        String path = qualifiedName.replace('.', '/') + ".java";
        Path outputPath = outputDirectory.toPath().resolve(path);
        outputPath.getParent().toFile().mkdirs();
        Files.writeString(outputPath, source);
        getLog().info("Permuplate: generated " + qualifiedName);
    }
}
