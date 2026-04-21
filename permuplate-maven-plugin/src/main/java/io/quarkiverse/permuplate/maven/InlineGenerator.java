package io.quarkiverse.permuplate.maven;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.TypeParameter;

import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.core.PermuteBodyTransformer;
import io.quarkiverse.permuplate.core.PermuteCaseTransformer;
import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.core.PermuteEnumConstTransformer;
import io.quarkiverse.permuplate.core.PermuteParamTransformer;
import io.quarkiverse.permuplate.core.PermuteSelfTransformer;
import io.quarkiverse.permuplate.core.PermuteStatementsTransformer;
import io.quarkiverse.permuplate.core.PermuteSwitchArmTransformer;
import io.quarkiverse.permuplate.core.PermuteTypeParamTransformer;
import io.quarkiverse.permuplate.core.PermuteValueTransformer;

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

    static {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private static final String PM_SIMPLE = "PermuteMethod";
    private static final String PM_FQ = "io.quarkiverse.permuplate.PermuteMethod";

    /**
     * External properties injected into JEXL contexts for {@code from}/{@code to} evaluation.
     * Set by {@link io.quarkiverse.permuplate.maven.PermuteMojo} at the start of each build.
     * In the Maven plugin, only system properties ({@code -Dpermuplate.*}) are available —
     * APT options ({@code -Apermuplate.*}) are not accessible outside the javac pipeline.
     */
    private static java.util.Map<String, Object> externalProperties = java.util.Collections.emptyMap();

    /** Sets the external properties for this generation run (called by PermuteMojo). */
    public static void setExternalProperties(java.util.Map<String, Object> props) {
        externalProperties = props != null ? props : java.util.Collections.emptyMap();
    }

    public InlineGenerator() {
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
            TypeDeclaration<?> templateClassDecl,
            PermuteConfig config,
            List<Map<String, Object>> allCombinations) {

        // Clone the entire parent CU as the starting point for the output
        CompilationUnit outputCu = parentCu.clone();

        // Strip @PermuteMacros / @PermuteBodyFragment(s) from all types in the output CU —
        // these are source-only annotations that must not appear in the generated (compiled) output.
        // Also remove the corresponding imports so javac does not try to resolve them.
        outputCu.findAll(TypeDeclaration.class).forEach(td -> {
            @SuppressWarnings("unchecked")
            TypeDeclaration<?> t = (TypeDeclaration<?>) td;
            t.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("PermuteMacros") || n.equals("io.quarkiverse.permuplate.PermuteMacros")
                        || n.equals("PermuteBodyFragment") || n.equals("io.quarkiverse.permuplate.PermuteBodyFragment")
                        || n.equals("PermuteBodyFragments")
                        || n.equals("io.quarkiverse.permuplate.PermuteBodyFragments");
            });
        });
        outputCu.getImports().removeIf(imp -> {
            String name = imp.getNameAsString();
            return name.equals("io.quarkiverse.permuplate.PermuteMacros")
                    || name.equals("io.quarkiverse.permuplate.PermuteBodyFragment")
                    || name.equals("io.quarkiverse.permuplate.PermuteBodyFragments");
        });

        // Top-level templates (inline=true on a non-nested class): the template IS the
        // top-level type. Generated classes are added directly to the CU, not as members
        // of an enclosing outer class. Nested templates follow the original path.
        boolean isTopLevel = !templateClassDecl.isNestedType();
        ClassOrInterfaceDeclaration outputParent = isTopLevel ? null
                : outputCu.findFirst(ClassOrInterfaceDeclaration.class, c -> !c.isNestedType())
                        .orElseThrow(() -> new IllegalStateException("Cannot find top-level class in parent CU"));

        // Remove the template from the output (class, interface, or record)
        String templateClassName = templateClassDecl.getNameAsString();
        if (isTopLevel) {
            outputCu.getTypes().removeIf(t -> t.getNameAsString().equals(templateClassName));
        } else {
            outputParent.getMembers().removeIf(member -> member instanceof TypeDeclaration<?> td &&
                    td.getNameAsString().equals(templateClassName));
        }

        // Re-add the template type if keepTemplate = true (strip all permuplate annotations)
        if (config.keepTemplate) {
            TypeDeclaration<?> templateCopy = templateClassDecl.clone();
            stripPermuteAnnotations(templateCopy);
            if (isTopLevel) {
                outputCu.addType(templateCopy);
            } else {
                outputParent.addMember(templateCopy);
            }
        }

        boolean isRecord = templateClassDecl instanceof RecordDeclaration;

        // Pre-compute whether the template has @PermuteExtends or @PermuteExtendsChain — used by super-call inference
        boolean templateHasExtendsAnnotation = templateClassDecl instanceof ClassOrInterfaceDeclaration tcoid
                && (hasPermuteExtendsAnnotation(tcoid) || hasPermuteExtendsChainAnnotation(tcoid));

        // Build complete generated class set for boundary omission
        Set<String> allGeneratedNames = scanAllGeneratedClassNames(parentCu, config);

        // Apply @PermuteFilter — drop combinations where any filter returns false
        List<String> filterExprs = readFilterExpressions(templateClassDecl);
        final List<Map<String, Object>> filteredCombinations;
        if (filterExprs.isEmpty()) {
            filteredCombinations = allCombinations;
        } else {
            filteredCombinations = allCombinations.stream().filter(vars -> {
                EvaluationContext filterCtx = new EvaluationContext(vars);
                for (String expr : filterExprs) {
                    try {
                        if (!filterCtx.evaluateBoolean(expr))
                            return false;
                    } catch (Exception e) {
                        System.err.println("[Permuplate] @PermuteFilter expression error (combination kept): "
                                + expr + " — " + e.getMessage());
                    }
                }
                return true;
            }).collect(java.util.stream.Collectors.toList());
        }

        // Collect all generated class names in order — used for sealed permits expansion
        List<String> generatedNames = new ArrayList<>();

        // Generate and append each permuted nested type
        for (Map<String, Object> vars : filteredCombinations) {
            EvaluationContext ctx = new EvaluationContext(vars);

            TypeDeclaration<?> generated = templateClassDecl.clone();

            // Rename the generated nested type
            String newClassName = ctx.evaluate(config.className);
            generatedNames.add(newClassName);
            generated.setName(newClassName);
            // Rename constructors (only applies to class/record, not interface)
            generated.getConstructors().forEach(ctor -> ctor.setName(newClassName));

            // Phase 1: collect alpha letters before @PermuteTypeParam consumes method annotations.
            // Only applies to COID — enum/record paths skip @PermuteReturn processing anyway.
            java.util.IdentityHashMap<MethodDeclaration, String> alphaLetters = (generated instanceof ClassOrInterfaceDeclaration coidPre)
                    ? collectAlphaLetters(coidPre, ctx)
                    : new java.util.IdentityHashMap<>();

            // Apply @PermuteTypeParam transformations first — must run before @PermuteSource
            // type param injection so that implicit expansion (triggered by @PermuteParam type=T${j}
            // matching a class type param) does not fire on source-injected params.
            PermuteTypeParamTransformer.transform(generated, ctx, null, null);

            // @PermuteSelf — set return type to current generated class (after type param expansion)
            PermuteSelfTransformer.transform(generated);

            // Infer type parameters from @PermuteSource if present — after @PermuteTypeParam so
            // that implicit expansion does not confuse source-injected params with sentinel params.
            applySourceTypeParams(generated, templateClassDecl, parentCu, ctx);

            // Update type references from template sentinel (e.g. Callable2<Object>)
            // to generated source (e.g. Callable3<A, B, C>) throughout the class body.
            applySourceTypeRefUpdate(generated, templateClassDecl, parentCu, ctx);

            // Builder synthesis: empty body + record source → complete fluent builder
            // (runs after applySourceTypeParams so type param names are already set)
            applyBuilderSynthesis(generated, templateClassDecl, parentCu, ctx);

            if (generated instanceof ClassOrInterfaceDeclaration coid) {
                // Capture post-G1 type parameter names for extends expansion
                List<String> postG1TypeParams = new ArrayList<>();
                coid.getTypeParameters().forEach(tp -> postG1TypeParams.add(tp.getNameAsString()));

                // Phase 2: build alpha inference map now that class type params are expanded.
                java.util.IdentityHashMap<MethodDeclaration, String> alphaInference = buildAlphaInference(coid, alphaLetters);

                // @PermuteMethod: generate overloads with (i,j) context — before other transforms
                applyPermuteMethod(coid, ctx, config, vars, allGeneratedNames);

                PermuteDeclrTransformer.transform(generated, ctx, null);
                PermuteParamTransformer.transform(generated, ctx, null);

                // Apply @PermuteReturn — explicit override; boundary omission
                Set<String> explicitReturnMethods = collectExplicitReturnMethodNames(coid);
                applyPermuteReturn(coid, ctx, allGeneratedNames, alphaInference);

                // Apply @PermuteDefaultReturn — class-level default return for remaining Object-returning methods
                applyInlineDefaultReturn(coid, ctx, allGeneratedNames);

                // Apply implicit return type + parameter type inference (Mechanism 1)
                applyImplicitInference(coid, ctx, allGeneratedNames, explicitReturnMethods);

                // @PermuteCase — expand switch statement cases per permutation
                PermuteCaseTransformer.transform(generated, ctx);

                // @PermuteSwitchArm — generate Java 21+ arrow-switch pattern arms
                PermuteSwitchArmTransformer.transform(generated, ctx);

                // @PermuteValue — replace field initializers and method statement RHS
                PermuteValueTransformer.transform(generated, ctx);

                // @PermuteStatements — insert accumulated statements into method bodies
                PermuteStatementsTransformer.transform(generated, ctx);

                // Constructor super-call inference (after @PermuteStatements so explicit wins)
                inferSuperCalls(coid, templateHasExtendsAnnotation);

                // @PermuteBodyFragment — substitute named fragments into @PermuteBody strings
                java.util.Map<String, String> bodyFragments = collectBodyFragments(templateClassDecl, ctx);
                applyBodyFragments(coid, bodyFragments);

                // @PermuteBody — replace entire method or constructor body per permutation
                PermuteBodyTransformer.transform(generated, ctx);

                // Infer return type from last 'return new X<>()' in body when X is in generated set
                inferReturnFromBody(coid, allGeneratedNames);

                // @PermuteAnnotation — add Java annotations to generated elements (runs last)
                io.quarkiverse.permuplate.core.PermuteAnnotationTransformer.transform(
                        generated, ctx, null, null);

                // @PermuteThrows — add exception types to method throws clauses
                io.quarkiverse.permuplate.core.PermuteThrowsTransformer.transform(
                        generated, ctx, null, null);

                // @PermuteImport — add evaluated imports to the parent CU
                for (String importStr : collectInlinePermuteImports(coid, ctx)) {
                    boolean alreadyPresent = outputCu.getImports().stream()
                            .anyMatch(imp -> imp.getNameAsString().equals(importStr));
                    if (!alreadyPresent) {
                        outputCu.addImport(importStr);
                    }
                }
                // Strip @PermuteImport annotations from the generated inner class
                generated.getAnnotations().removeIf(a -> {
                    String n = a.getNameAsString();
                    return n.equals("PermuteImport") || n.equals("io.quarkiverse.permuplate.PermuteImport")
                            || n.equals("PermuteImports") || n.equals("io.quarkiverse.permuplate.PermuteImports");
                });

                // @PermuteExtendsChain — shorthand (only fires when explicit @PermuteExtends absent)
                boolean hasExplicitPermuteExtends = hasPermuteExtendsAnnotation(coid);
                boolean hasChain = !hasExplicitPermuteExtends && hasPermuteExtendsChainAnnotation(coid);
                if (hasChain) {
                    applyPermuteExtendsChain(coid, config, ctx);
                }
                // @PermuteExtends — explicit override of extends/implements clause.
                boolean permuteExtendsApplied = hasExplicitPermuteExtends || hasChain;
                applyPermuteExtendsAnnotation(coid, ctx);

                // Extends/implements clause expansion (same-N formula) — only when no explicit override
                if (!permuteExtendsApplied) {
                    int templateEmbeddedNum = firstEmbeddedNumber(templateClassName);
                    int currentEmbeddedNum = firstEmbeddedNumber(newClassName);
                    if (templateEmbeddedNum >= 0 && currentEmbeddedNum >= 0) {
                        applyExtendsExpansion(coid, templateClassName, templateEmbeddedNum, currentEmbeddedNum,
                                postG1TypeParams);
                    }
                }
            } else if (generated instanceof EnumDeclaration) {
                // Enum path: apply value + declr transforms; expand @PermuteEnumConst sentinels.
                // @PermuteMethod, @PermuteReturn, and extends expansion are COID-only — skipped.
                PermuteDeclrTransformer.transform(generated, ctx, null);
                PermuteParamTransformer.transform(generated, ctx, null);

                // @PermuteCase — expand switch statement cases per permutation (enums can have methods)
                PermuteCaseTransformer.transform(generated, ctx);

                // @PermuteSwitchArm — generate Java 21+ arrow-switch pattern arms
                PermuteSwitchArmTransformer.transform(generated, ctx);

                PermuteValueTransformer.transform(generated, ctx);

                // @PermuteStatements — insert accumulated statements into method bodies
                PermuteStatementsTransformer.transform(generated, ctx);

                // @PermuteBodyFragment — substitute ${name} refs in @PermuteBody body strings (enum path)
                java.util.Map<String, String> enumBodyFragments = collectBodyFragments(templateClassDecl, ctx);
                applyBodyFragments(generated, enumBodyFragments);

                // @PermuteBody — replace entire method or constructor body per permutation
                PermuteBodyTransformer.transform(generated, ctx);

                // @PermuteEnumConst — expand sentinel enum constants
                PermuteEnumConstTransformer.transform(generated, ctx);

                // @PermuteAnnotation — add Java annotations to generated elements (runs last)
                io.quarkiverse.permuplate.core.PermuteAnnotationTransformer.transform(
                        generated, ctx, null, null);

                // @PermuteThrows — add exception types to method throws clauses (enums can have methods)
                io.quarkiverse.permuplate.core.PermuteThrowsTransformer.transform(
                        generated, ctx, null, null);

                // @PermuteImport — add evaluated imports to the parent CU
                for (String importStr : collectInlinePermuteImports(generated, ctx)) {
                    boolean alreadyPresent = outputCu.getImports().stream()
                            .anyMatch(imp -> imp.getNameAsString().equals(importStr));
                    if (!alreadyPresent) {
                        outputCu.addImport(importStr);
                    }
                }
                generated.getAnnotations().removeIf(a -> {
                    String n = a.getNameAsString();
                    return n.equals("PermuteImport") || n.equals("io.quarkiverse.permuplate.PermuteImport")
                            || n.equals("PermuteImports") || n.equals("io.quarkiverse.permuplate.PermuteImports");
                });
            } else {
                // Record path: apply param + declr transforms (no extends, no methods/return/case)
                PermuteDeclrTransformer.transform(generated, ctx, null);
                PermuteParamTransformer.transform(generated, ctx, null);
                // PermuteStatementsTransformer is not applied to records — records cannot have
                // arbitrary constructor bodies via template syntax; @PermuteStatements support
                // for compact record constructors is deferred.
                // PermuteBodyTransformer is also not applied to records — record compact constructors
                // cannot have arbitrary bodies replaced via @PermuteBody; deferred with @PermuteStatements.
                // PermuteCaseTransformer is also omitted — switch cases in record methods are not
                // a supported pattern.
                PermuteValueTransformer.transform(generated, ctx);

                // @PermuteAnnotation — add Java annotations to generated elements (runs last)
                io.quarkiverse.permuplate.core.PermuteAnnotationTransformer.transform(
                        generated, ctx, null, null);

                // @PermuteThrows — add exception types to method throws clauses
                io.quarkiverse.permuplate.core.PermuteThrowsTransformer.transform(
                        generated, ctx, null, null);
            }

            // Self-return inference: methods returning 'this' with Object sentinel and
            // no explicit @PermuteReturn are automatically given the generated class return type.
            applySelfReturnInference(generated);

            // Synthesise @PermuteDelegate method bodies
            applyPermuteDelegate(generated, parentCu);

            // Strip all Permuplate annotations from the generated type and its members
            stripPermuteAnnotations(generated);

            if (isTopLevel) {
                outputCu.addType(generated);
            } else {
                outputParent.addMember(generated);
            }
        }

        // Strip permuplate imports from the output
        outputCu.getImports().removeIf(imp -> imp.getNameAsString().startsWith("io.quarkiverse.permuplate"));

        // Expand any sealed permits clause that references the template class name
        expandSealedPermits(outputCu, templateClassName, generatedNames, config.keepTemplate);

        return outputCu;
    }

    /**
     * Replaces any {@code permits TemplateName} entry in sealed classes/interfaces within
     * {@code cu} with one entry per generated class name. Called after all classes are generated.
     * <p>
     * When {@code keepTemplate} is {@code true}, the template class is retained in the output as a
     * real member, so it must remain listed in the {@code permits} clause. In that case the template
     * name is appended to the end of the expanded list after the generated names, preserving a valid
     * sealed-type declaration.
     */
    private static void expandSealedPermits(
            CompilationUnit cu, String templateName, List<String> generatedNames, boolean keepTemplate) {
        if (generatedNames.isEmpty())
            return;

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            NodeList<com.github.javaparser.ast.type.ClassOrInterfaceType> permitted = decl.getPermittedTypes();
            if (permitted.isEmpty())
                return;

            int placeholderIdx = -1;
            for (int i = 0; i < permitted.size(); i++) {
                if (templateName.equals(permitted.get(i).getNameAsString())) {
                    placeholderIdx = i;
                    break;
                }
            }
            if (placeholderIdx < 0)
                return;

            permitted.remove(placeholderIdx);
            for (int j = generatedNames.size() - 1; j >= 0; j--) {
                permitted.add(placeholderIdx,
                        new com.github.javaparser.ast.type.ClassOrInterfaceType(generatedNames.get(j)));
            }
            // If the template class is kept in the output it must still appear in the permits clause.
            if (keepTemplate) {
                permitted.add(new com.github.javaparser.ast.type.ClassOrInterfaceType(templateName));
            }
        });
    }

    /**
     * Scans the parent CU for all @Permute annotations and collects every class name
     * that will be generated. Used for boundary omission in @PermuteReturn processing.
     */
    private static Set<String> scanAllGeneratedClassNames(CompilationUnit parentCu,
            PermuteConfig thisConfig) {
        Set<String> names = new HashSet<>();
        addNamesFromConfig(thisConfig, names);

        parentCu.findAll(TypeDeclaration.class).forEach(td -> {
            @SuppressWarnings("unchecked")
            TypeDeclaration<?> typeDecl = (TypeDeclaration<?>) td;
            typeDecl.getAnnotations().stream()
                    .filter(a -> a.getNameAsString().equals("Permute")
                            || a.getNameAsString().equals("io.quarkiverse.permuplate.Permute"))
                    .forEach(ann -> {
                        try {
                            addNamesFromConfig(AnnotationReader.readPermute(ann), names);
                        } catch (Exception ignored) {
                        }
                    });
        });
        return names;
    }

    private static void addNamesFromConfig(PermuteConfig config, Set<String> names) {
        for (Map<String, Object> vars : PermuteConfig.buildAllCombinations(config, externalProperties)) {
            try {
                names.add(new EvaluationContext(vars).evaluate(config.className));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Processes @PermuteMethod: for each inner j value, clones the sentinel method,
     * processes @PermuteReturn and @PermuteDeclr with the (i,j) context, applies
     * implicit inference, and adds the overload to the class. The sentinel is removed.
     *
     * <p>
     * Runs BEFORE PermuteDeclrTransformer so that @PermuteDeclr on each clone's
     * parameters is consumed with the inner context — the downstream transform sees
     * no remaining @PermuteDeclr annotations on these methods.
     */
    private static void applyPermuteMethod(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            PermuteConfig config,
            Map<String, Object> vars,
            Set<String> allGeneratedNames) {

        List<MethodDeclaration> toRemove = new ArrayList<>();
        List<MethodDeclaration> toAdd = new ArrayList<>();

        classDecl.getMethods().forEach(method -> {
            java.util.Optional<AnnotationExpr> pmAnnOpt = method.getAnnotations().stream()
                    .filter(a -> a.getNameAsString().equals(PM_SIMPLE)
                            || a.getNameAsString().equals(PM_FQ))
                    .findFirst();
            if (pmAnnOpt.isEmpty())
                return;

            AnnotationReader.PermuteMethodConfig pmCfg = AnnotationReader.readPermuteMethod(pmAnnOpt.get());
            if (pmCfg == null)
                return;

            toRemove.add(method);

            // Read @PermuteFilter expressions from the method (if any)
            List<String> methodFilterExprs = readFilterExpressions(method);

            // Collect declared class type parameter names for undeclared-var detection
            Set<String> declaredTypeParams = new java.util.LinkedHashSet<>();
            classDecl.getTypeParameters().forEach(tp -> declaredTypeParams.add(tp.getNameAsString()));

            if (pmCfg.hasValues()) {
                // String-set axis: one clone per string value.
                // fromVal/toVal are not evaluated here — the outer @Permute may be in
                // string-set mode, making vars.get(config.varName) a String, not a Number.
                for (String value : pmCfg.values()) {
                    EvaluationContext innerCtx = ctx.withVariable(pmCfg.varName(), value);
                    innerCtx = applyMethodMacros(pmCfg, innerCtx);

                    // Apply method-level @PermuteFilter
                    if (!evaluateMethodFilters(methodFilterExprs, innerCtx)) {
                        continue;
                    }

                    applyPermuteMethodClone(method, clone -> toAdd.add(clone),
                            innerCtx, classDecl, declaredTypeParams, pmCfg, allGeneratedNames,
                            /* j for implicit expansion */ -1);
                }
            } else {
                // Integer range axis: evaluate from/to then iterate.
                // Safe to cast vars.get(config.varName) to Number here — outer @Permute
                // is in integer range mode (otherwise pmCfg.hasValues() would be true
                // and we'd have taken the branch above).
                int fromVal;
                try {
                    fromVal = ctx.evaluateInt(pmCfg.from().isEmpty() ? "1" : pmCfg.from());
                } catch (Exception ignored) {
                    fromVal = 1;
                }

                // Evaluate to: infer as @Permute.to - currentI when not explicit
                int toVal;
                if (!pmCfg.hasExplicitTo()) {
                    int currentI = ((Number) vars.get(config.varName)).intValue();
                    int outerTo = ctx.evaluateInt(config.to);
                    toVal = outerTo - currentI;
                } else {
                    try {
                        toVal = ctx.evaluateInt(pmCfg.to());
                    } catch (Exception ignored) {
                        return;
                    }
                }

                for (int j = fromVal; j <= toVal; j++) {
                    EvaluationContext innerCtx = ctx.withVariable(pmCfg.varName(), j);
                    innerCtx = applyMethodMacros(pmCfg, innerCtx);

                    // Apply method-level @PermuteFilter
                    if (!evaluateMethodFilters(methodFilterExprs, innerCtx)) {
                        continue;
                    }

                    applyPermuteMethodClone(method, clone -> toAdd.add(clone),
                            innerCtx, classDecl, declaredTypeParams, pmCfg, allGeneratedNames, j);
                }
            }
        });

        toRemove.forEach(m -> classDecl.getMembers().removeIf(member -> member == m));
        toAdd.forEach(classDecl::addMember);
    }

    /**
     * Applies {@code @PermuteMethod macros=} to an evaluation context.
     * Each macro is evaluated in declaration order; later macros may reference earlier ones.
     * Macro format: {@code "name=jexlExpression"}.
     */
    private static EvaluationContext applyMethodMacros(AnnotationReader.PermuteMethodConfig pmCfg,
            EvaluationContext innerCtx) {
        if (!pmCfg.hasMacros())
            return innerCtx;
        for (String macro : pmCfg.macros()) {
            int eq = macro.indexOf('=');
            if (eq < 0)
                continue;
            String name = macro.substring(0, eq).trim();
            String expr = macro.substring(eq + 1).trim();
            try {
                String value = innerCtx.evaluate("${" + expr + "}");
                innerCtx = innerCtx.withVariable(name, value);
            } catch (Exception ignored) {
                // Malformed macro — skip silently; evaluation errors surface later
            }
        }
        return innerCtx;
    }

    /**
     * Processes a single {@code @PermuteMethod} clone for one inner-loop value.
     *
     * <p>
     * Shared by both the integer-range path and the string-set path of
     * {@link #applyPermuteMethod}. The only difference between the two paths is how
     * {@code innerCtx} is created — integer: {@code ctx.withVariable(name, j)};
     * string: {@code ctx.withVariable(name, value)}.
     *
     * <p>
     * When {@code j} is negative (string-set path), j-based implicit type expansion is
     * skipped — there is no numeric inner variable to drive it.
     *
     * @param method the sentinel method to clone
     * @param addClone consumer that appends the finished clone to the output list
     * @param innerCtx evaluation context with the inner variable bound
     * @param classDecl the class being generated (source of type parameters)
     * @param declaredTypeParams type parameter names declared on the class
     * @param pmCfg the parsed {@code @PermuteMethod} config
     * @param allGeneratedNames names of all classes to be generated in this run
     * @param j inner integer value (for implicit expansion); {@code -1} for string-set
     */
    private static void applyPermuteMethodClone(MethodDeclaration method,
            Consumer<MethodDeclaration> addClone,
            EvaluationContext innerCtx,
            ClassOrInterfaceDeclaration classDecl,
            Set<String> declaredTypeParams,
            AnnotationReader.PermuteMethodConfig pmCfg,
            Set<String> allGeneratedNames,
            int j) {

        MethodDeclaration clone = method.clone();

        // Strip @PermuteMethod from clone
        clone.getAnnotations()
                .removeIf(a -> a.getNameAsString().equals(PM_SIMPLE) || a.getNameAsString().equals(PM_FQ));

        // Expand method-level @PermuteTypeParam with the inner context
        io.quarkiverse.permuplate.core.PermuteTypeParamTransformer
                .transformMethod(clone, innerCtx, null, null);

        // Handle explicit @PermuteReturn first; fall through to implicit expansion otherwise.
        // Use a temporary wrapper class so applyPermuteReturn can operate on it.
        ClassOrInterfaceDeclaration tmpClass = new ClassOrInterfaceDeclaration();
        tmpClass.setName("_Tmp");
        classDecl.getTypeParameters().forEach(tp -> tmpClass.addTypeParameter(tp.clone()));
        tmpClass.addMember(clone);

        Set<String> explicitMethods = collectExplicitReturnMethodNames(tmpClass);
        applyPermuteReturn(tmpClass, innerCtx, allGeneratedNames);

        // If boundary omission removed the method, skip this value
        if (tmpClass.getMethods().isEmpty()) {
            return;
        }

        // Retrieve the (possibly @PermuteReturn-updated) clone
        clone = tmpClass.getMethods().get(0);

        // Implicit j-based expansion: only when j >= 1 (integer path).
        // String-set path passes j=-1 to skip this entirely.
        String methodKey = clone.getNameAsString() + clone.getParameters().toString();
        if (j >= 1 && !explicitMethods.contains(methodKey)) {
            expandMethodTypesForJ(clone, declaredTypeParams, j);
        }

        // Process @PermuteDeclr on parameters with innerCtx
        io.quarkiverse.permuplate.core.PermuteDeclrTransformer
                .processMethodParamDeclr(clone, innerCtx);

        // Process @PermuteParam with innerCtx
        ClassOrInterfaceDeclaration tmpParam = new ClassOrInterfaceDeclaration();
        tmpParam.setName("_TmpParam");
        tmpParam.addMember(clone);
        PermuteParamTransformer.transform(tmpParam, innerCtx, null);
        if (!tmpParam.getMethods().isEmpty()) {
            clone = tmpParam.getMethods().get(0);
        }

        // Process @PermuteDeclr TYPE_USE in the method body with innerCtx.
        // Must run AFTER @PermuteParam so we operate on the final clone node.
        io.quarkiverse.permuplate.core.PermuteDeclrTransformer
                .transformNewExpressions(clone, innerCtx);

        // Apply @PermuteBody with innerCtx so body templates can reference
        // the @PermuteMethod inner variable (e.g. ${n} or ${T}).
        {
            ClassOrInterfaceDeclaration tmpBody = new ClassOrInterfaceDeclaration();
            tmpBody.addMember(clone);
            PermuteBodyTransformer.transform(tmpBody, innerCtx);
            if (!tmpBody.getMethods().isEmpty())
                clone = tmpBody.getMethods().get(0);
        }

        // Apply name template if set
        if (pmCfg.hasName()) {
            try {
                clone.setName(innerCtx.evaluate(pmCfg.name()));
            } catch (Exception ignored) {
            }
        }

        addClone.accept(clone);
    }

    /**
     * Applies j-based implicit type expansion to a method's return type and parameter types.
     *
     * <p>
     * For each type string (return and each param), finds undeclared T+number vars
     * (the "growing tip"), expands them by adding T(firstTip+1)..T(firstTip+j-1), and
     * increments the first embedded integer in the base class name by (j-1). When j=1
     * this is a no-op (offset=0, tip unchanged).
     *
     * <p>
     * Used by {@code applyPermuteMethod} to expand the template method's types for each
     * inner j value without relying on {@code allGeneratedNames} or class name suffix heuristics.
     */
    private static void expandMethodTypesForJ(MethodDeclaration method,
            Set<String> declaredTypeParams, int j) {
        if (j <= 1)
            return; // j=1 is always a no-op

        int offset = j - 1;

        // Expand return type
        String rt = method.getTypeAsString();
        String newRt = expandTypeStringForJ(rt, declaredTypeParams, offset);
        if (!newRt.equals(rt)) {
            try {
                method.setType(StaticJavaParser.parseType(newRt));
            } catch (Exception ignored) {
            }
        }

        // Expand each parameter type
        method.getParameters().forEach(param -> {
            String pt = param.getTypeAsString();
            String newPt = expandTypeStringForJ(pt, declaredTypeParams, offset);
            if (!newPt.equals(pt)) {
                try {
                    param.setType(StaticJavaParser.parseType(newPt));
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * Expands a single type string for the j-based inner loop.
     *
     * <p>
     * For a type like {@code "Join2First<T1, T2>"} where T2 is undeclared:
     * <ul>
     * <li>Finds the growing tip (undeclared T+number vars), e.g. T2</li>
     * <li>Expands tip to T2, T3, ..., T(firstTipNum + offset)</li>
     * <li>Increments the first embedded integer in the base class name by offset</li>
     * </ul>
     *
     * <p>
     * If no undeclared T+number vars are present, the type string is returned unchanged.
     */
    private static String expandTypeStringForJ(String typeStr,
            Set<String> declaredTypeParams, int offset) {
        ReturnTypeInfo info = parseReturnTypeInfo(typeStr);
        if (info == null)
            return typeStr;

        // Find undeclared T+number vars (growing tip)
        List<String> growingTip = new ArrayList<>();
        for (String arg : info.typeArgs()) {
            if (!declaredTypeParams.contains(arg) && isTNumberVar(arg)) {
                growingTip.add(arg);
            }
        }
        if (growingTip.isEmpty())
            return typeStr;

        int firstTipNum = Integer.parseInt(growingTip.get(0).substring(1));
        int newLastTipNum = firstTipNum + offset;

        // Rebuild type args: preserve fixed args in position, expand tip
        List<String> newTypeArgs = buildExpandedTypeArgs(info.typeArgs(), declaredTypeParams,
                firstTipNum, newLastTipNum);

        // Increment the first embedded integer in the base class name by offset
        String newBase = incrementFirstEmbeddedNumber(info.baseClass(), offset);

        if (newTypeArgs.isEmpty())
            return newBase;
        return newBase + "<" + String.join(", ", newTypeArgs) + ">";
    }

    /**
     * Finds the first contiguous run of digits in a class name and increments it by offset.
     * E.g. {@code incrementFirstEmbeddedNumber("Join2First", 1)} → {@code "Join3First"}.
     * If no digits found, returns the name unchanged.
     */
    private static String incrementFirstEmbeddedNumber(String name, int offset) {
        int start = -1;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                start = i;
                break;
            }
        }
        if (start < 0)
            return name;
        int end = start;
        while (end < name.length() && Character.isDigit(name.charAt(end)))
            end++;
        int num = Integer.parseInt(name.substring(start, end));
        return name.substring(0, start) + (num + offset) + name.substring(end);
    }

    /** Returns {@code true} if the class has a {@code @PermuteExtends} annotation. */
    private static boolean hasPermuteExtendsAnnotation(ClassOrInterfaceDeclaration classDecl) {
        return classDecl.getAnnotations().stream()
                .anyMatch(a -> {
                    String n = a.getNameAsString();
                    return n.equals("PermuteExtends") || n.equals("io.quarkiverse.permuplate.PermuteExtends");
                });
    }

    /** Returns {@code true} if the class has a {@code @PermuteExtendsChain} annotation. */
    private static boolean hasPermuteExtendsChainAnnotation(ClassOrInterfaceDeclaration classDecl) {
        return classDecl.getAnnotations().stream()
                .anyMatch(a -> {
                    String n = a.getNameAsString();
                    return n.equals("PermuteExtendsChain")
                            || n.equals("io.quarkiverse.permuplate.PermuteExtendsChain");
                });
    }

    /**
     * Applies {@code @PermuteExtendsChain}: sets extends clause to
     * {@code ${familyBase}${i-1}} with alpha type args {@code typeArgList(1, i-1, 'alpha')}.
     * Family base is inferred from the config {@code className} pattern (prefix before the
     * first {@code ${}).
     * Strips the {@code @PermuteExtendsChain} annotation from the generated class.
     */
    private static void applyPermuteExtendsChain(
            ClassOrInterfaceDeclaration classDecl,
            PermuteConfig config,
            EvaluationContext ctx) {

        // Extract family base from className pattern (everything before first "${")
        String classNamePattern = config.className;
        int dollarIdx = classNamePattern.indexOf("${");
        if (dollarIdx <= 0)
            return; // cannot infer family base
        String familyBase = classNamePattern.substring(0, dollarIdx);

        // Evaluate parent class name
        String evaluatedClass;
        try {
            evaluatedClass = ctx.evaluate(familyBase + "${i-1}");
        } catch (Exception ignored) {
            classDecl.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("PermuteExtendsChain")
                        || n.equals("io.quarkiverse.permuplate.PermuteExtendsChain");
            });
            return;
        }

        // Evaluate alpha type arg list (empty string when i-1 == 0)
        String typeArgStr;
        try {
            typeArgStr = ctx.evaluate("${typeArgList(1, i-1, 'alpha')}");
        } catch (Exception ignored) {
            typeArgStr = "";
        }

        String newTypeStr = (typeArgStr == null || typeArgStr.isEmpty())
                ? evaluatedClass
                : evaluatedClass + "<" + typeArgStr + ">";

        try {
            com.github.javaparser.ast.type.ClassOrInterfaceType newType = (com.github.javaparser.ast.type.ClassOrInterfaceType) StaticJavaParser
                    .parseType(newTypeStr);
            classDecl.getExtendedTypes().clear();
            classDecl.addExtendedType(newType);
        } catch (Exception ignored) {
        }

        // Strip the @PermuteExtendsChain annotation
        classDecl.getAnnotations().removeIf(a -> {
            String n = a.getNameAsString();
            return n.equals("PermuteExtendsChain")
                    || n.equals("io.quarkiverse.permuplate.PermuteExtendsChain");
        });
    }

    /**
     * Infers and inserts {@code super(p1, ..., p_{N-1})} as the first constructor
     * statement when all conditions hold:
     * <ol>
     * <li>The template has {@code @PermuteExtends} (extends-previous pattern)</li>
     * <li>The constructor has &ge;2 parameters</li>
     * <li>The constructor does NOT already have a {@code super()} call as its first statement</li>
     * <li>The constructor does NOT have {@code @PermuteStatements} (explicit annotation wins)</li>
     * </ol>
     * Only the parameters before the last one are passed to super — the last param is the
     * new field added by this level of the hierarchy.
     */
    private static void inferSuperCalls(ClassOrInterfaceDeclaration classDecl,
            boolean templateHasExtendsAnnotation) {
        if (!templateHasExtendsAnnotation)
            return;

        classDecl.getConstructors().forEach(ctor -> {
            // Skip if @PermuteStatements is present — explicit always wins
            boolean hasPermuteStatements = ctor.getAnnotations().stream()
                    .anyMatch(a -> {
                        String n = a.getNameAsString();
                        return n.equals("PermuteStatements")
                                || n.equals("io.quarkiverse.permuplate.PermuteStatements");
                    });
            if (hasPermuteStatements)
                return;

            // Skip single-param constructors — nothing to delegate
            if (ctor.getParameters().size() < 2)
                return;

            // Skip if already has super() as first statement
            if (!ctor.getBody().getStatements().isEmpty()) {
                com.github.javaparser.ast.stmt.Statement first = ctor.getBody().getStatements().get(0);
                if (first instanceof com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt e
                        && !e.isThis()) {
                    return; // already has super(...)
                }
            }

            // Build super(p1, p2, ..., p_{N-1}) — all params except the last
            com.github.javaparser.ast.NodeList<com.github.javaparser.ast.expr.Expression> args = new com.github.javaparser.ast.NodeList<>();
            java.util.List<com.github.javaparser.ast.body.Parameter> params = ctor.getParameters();
            for (int idx = 0; idx < params.size() - 1; idx++) {
                args.add(new com.github.javaparser.ast.expr.NameExpr(
                        params.get(idx).getNameAsString()));
            }
            com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt superCall = new com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt(
                    false, null, args);
            ctor.getBody().getStatements().addFirst(superCall);
        });
    }

    /**
     * Applies explicit {@code @PermuteExtends} annotation to override the extends or
     * implements clause of the generated class.
     *
     * <p>
     * Evaluates {@code className} using the current permutation context, then builds
     * the type argument list either via a loop ({@code typeArgVarName}/{@code typeArgFrom}/
     * {@code typeArgTo}/{@code typeArgName}) or a full expression ({@code typeArgs}).
     * Sets the extends clause (index 0) or the specified implements clause
     * ({@code interfaceIndex} ≥ 0 selects which implements entry).
     *
     * <p>
     * Strips the {@code @PermuteExtends} annotation after processing. The annotation
     * was previously only stripped without being acted on — this method provides the
     * missing implementation.
     */
    private static void applyPermuteExtendsAnnotation(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx) {

        java.util.Optional<com.github.javaparser.ast.expr.AnnotationExpr> annOpt = classDecl.getAnnotations()
                .stream()
                .filter(a -> {
                    String n = a.getNameAsString();
                    return n.equals("PermuteExtends")
                            || n.equals("io.quarkiverse.permuplate.PermuteExtends");
                })
                .findFirst();

        if (annOpt.isEmpty())
            return;

        com.github.javaparser.ast.expr.AnnotationExpr ann = annOpt.get();
        AnnotationReader.PermuteExtendsConfig cfg = AnnotationReader.readPermuteExtends(ann);
        if (cfg == null) {
            classDecl.getAnnotations().remove(ann);
            return;
        }

        // Evaluate the base class name
        String evaluatedClass;
        try {
            evaluatedClass = ctx.evaluate(cfg.className());
        } catch (Exception ignored) {
            classDecl.getAnnotations().remove(ann);
            return;
        }

        // Build type argument list
        String typeArgStr = "";
        if (cfg.hasTypeArgsExpr()) {
            try {
                typeArgStr = ctx.evaluate("${" + cfg.typeArgs() + "}");
            } catch (Exception ignored) {
            }
        } else if (cfg.hasTypeArgLoop()) {
            try {
                int fromVal = ctx.evaluateInt(cfg.typeArgFrom());
                int toVal = ctx.evaluateInt(cfg.typeArgTo());
                StringBuilder sb = new StringBuilder();
                for (int k = fromVal; k <= toVal; k++) {
                    if (k > fromVal)
                        sb.append(", ");
                    EvaluationContext innerCtx = ctx.withVariable(cfg.typeArgVarName(), k);
                    sb.append(innerCtx.evaluate(cfg.typeArgName()));
                }
                typeArgStr = sb.toString();
            } catch (Exception ignored) {
            }
        }

        // Build the full type expression string
        String newTypeStr = typeArgStr.isEmpty()
                ? evaluatedClass
                : evaluatedClass + "<" + typeArgStr + ">";

        try {
            com.github.javaparser.ast.type.ClassOrInterfaceType newType = StaticJavaParser
                    .parseClassOrInterfaceType(newTypeStr);

            int idx = cfg.interfaceIndex();
            if (idx == 0) {
                // interfaceIndex 0: target the extends clause
                if (!classDecl.getExtendedTypes().isEmpty()) {
                    classDecl.getExtendedTypes().set(0, newType);
                } else {
                    classDecl.addExtendedType(newType);
                }
            } else {
                // interfaceIndex 1+: target the (idx-1)th implements entry (0-indexed)
                int implIdx = idx - 1;
                com.github.javaparser.ast.NodeList<com.github.javaparser.ast.type.ClassOrInterfaceType> implemented = classDecl
                        .getImplementedTypes();
                if (implIdx < implemented.size()) {
                    implemented.set(implIdx, newType);
                } else {
                    implemented.add(newType);
                }
            }
        } catch (Exception ignored) {
        }

        // Remove @PermuteExtends from the generated class
        classDecl.getAnnotations().remove(ann);
    }

    /**
     * Expands the extends/implements clause of a generated class to match the current arity.
     *
     * <p>
     * Fires only when both the template class name and the generated class name contain
     * an embedded number (the arity discriminator). Only expands extends base classes that
     * share the same name prefix and the template's embedded number.
     *
     * <p>
     * Two detection branches:
     * <ol>
     * <li>All-T+number type args → hardcodes T1..T(newNum) (existing T+number behaviour)</li>
     * <li>Extends type args are a prefix of postG1TypeParams → uses full postG1TypeParams
     * list (alpha naming support; requires {@code @PermuteTypeParam} to have fired)</li>
     * </ol>
     *
     * <p>
     * Formula: {@code newNum = currentEmbeddedNum} — produces same-N extends
     * (Join2First extends Join2Second, not forward-reference Join3Second).
     */
    private static void applyExtendsExpansion(ClassOrInterfaceDeclaration classDecl,
            String templateName,
            int templateEmbeddedNum,
            int currentEmbeddedNum,
            List<String> postG1TypeParams) {

        String templateNamePrefix = prefixBeforeFirstDigit(templateName);
        int newNum = currentEmbeddedNum; // same-N: Join2First → extends Join2Second

        com.github.javaparser.ast.NodeList<com.github.javaparser.ast.type.ClassOrInterfaceType> extended = classDecl
                .getExtendedTypes();
        for (int idx = 0; idx < extended.size(); idx++) {
            com.github.javaparser.ast.type.ClassOrInterfaceType ext = extended.get(idx);
            String baseName = ext.getNameAsString();

            // Guard: only expand if the base class shares the same prefix-before-digit
            // as the template class. This prevents incorrectly expanding third-party
            // classes (e.g. External1Lib) that happen to share the template's embedded digit.
            if (!prefixBeforeFirstDigit(baseName).equals(templateNamePrefix))
                continue;

            // Only expand if extends base class has same first embedded number as the template
            int extNum = firstEmbeddedNumber(baseName);
            if (extNum < 0 || extNum != templateEmbeddedNum)
                continue;

            java.util.Optional<com.github.javaparser.ast.NodeList<com.github.javaparser.ast.type.Type>> typeArgsOpt = ext
                    .getTypeArguments();
            if (typeArgsOpt.isEmpty() || typeArgsOpt.get().isEmpty())
                continue;

            List<String> extArgNames = typeArgsOpt.get().stream()
                    .map(com.github.javaparser.ast.type.Type::asString)
                    .collect(java.util.stream.Collectors.toList());

            // Determine new type args via one of two detection branches.
            List<String> newTypeArgNames;
            boolean allTNumber = extArgNames.stream().allMatch(InlineGenerator::isTNumberVar);
            if (allTNumber) {
                // T+number case: build T1..T(newNum) from scratch
                newTypeArgNames = new ArrayList<>();
                for (int t = 1; t <= newNum; t++)
                    newTypeArgNames.add("T" + t);
            } else {
                // Alpha case: extends type args must be a prefix of postG1TypeParams.
                // This fires when @PermuteTypeParam has already expanded the class type
                // params (postG1TypeParams is longer than the template-level extends args).
                boolean isPrefix = extArgNames.size() <= postG1TypeParams.size()
                        && java.util.stream.IntStream.range(0, extArgNames.size())
                                .allMatch(k -> extArgNames.get(k).equals(postG1TypeParams.get(k)));
                if (!isPrefix)
                    continue;
                newTypeArgNames = postG1TypeParams;
            }

            // Build new base class name: replace embedded number with newNum
            String newBaseName = replaceFirstEmbeddedNumber(baseName, newNum);
            String newExtStr = newBaseName + "<" + String.join(", ", newTypeArgNames) + ">";
            try {
                extended.set(idx, StaticJavaParser.parseClassOrInterfaceType(newExtStr));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Returns the substring of {@code name} up to (but not including) its first digit.
     * E.g. {@code "Join1Second"} → {@code "Join"}, {@code "BaseStep"} → {@code "BaseStep"}.
     */
    private static String prefixBeforeFirstDigit(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i)))
                return name.substring(0, i);
        }
        return name;
    }

    /** Returns the first contiguous run of digits in a name as an integer, or -1 if none. */
    private static int firstEmbeddedNumber(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                int end = i;
                while (end < name.length() && Character.isDigit(name.charAt(end)))
                    end++;
                try {
                    return Integer.parseInt(name.substring(i, end));
                } catch (Exception ignored) {
                }
            }
        }
        return -1;
    }

    /** Replaces the first contiguous run of digits in {@code name} with {@code newNum}. */
    private static String replaceFirstEmbeddedNumber(String name, int newNum) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                int end = i;
                while (end < name.length() && Character.isDigit(name.charAt(end)))
                    end++;
                return name.substring(0, i) + newNum + name.substring(end);
            }
        }
        return name; // no digits found — return unchanged
    }

    /**
     * Alpha growing-tip inference — Phase 1 (pre-PermuteTypeParamTransformer):
     * Collects the new alpha letter for each method that has @PermuteReturn (no typeArgs)
     * AND a single-value @PermuteTypeParam with alpha naming.
     *
     * <p>
     * Must be called BEFORE PermuteTypeParamTransformer consumes the method-level
     *
     * @PermuteTypeParam annotation. Uses identity keys (method node references) so that
     *                   parameter type renames by downstream transformers do not invalidate the lookup.
     */
    private static java.util.IdentityHashMap<MethodDeclaration, String> collectAlphaLetters(
            ClassOrInterfaceDeclaration classDecl, EvaluationContext ctx) {
        java.util.IdentityHashMap<MethodDeclaration, String> result = new java.util.IdentityHashMap<>();

        classDecl.getMethods().forEach(method -> {
            // Only fire when @PermuteReturn is present with no typeArgs
            java.util.Optional<AnnotationExpr> retAnnOpt = method.getAnnotations().stream()
                    .filter(a -> a.getNameAsString().equals("PermuteReturn")
                            || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteReturn"))
                    .findFirst();
            if (retAnnOpt.isEmpty())
                return;
            AnnotationReader.PermuteReturnConfig cfg = AnnotationReader.readPermuteReturn(retAnnOpt.get());
            if (cfg == null || cfg.hasTypeArgsExpr() || cfg.hasTypeArgLoop())
                return; // explicit typeArgs set — inference must not override

            // Find a single-value @PermuteTypeParam with alpha naming on this method
            for (AnnotationExpr ann : method.getAnnotations()) {
                String n = ann.getNameAsString();
                if (!n.equals("PermuteTypeParam") && !n.equals("io.quarkiverse.permuplate.PermuteTypeParam"))
                    continue;
                if (!(ann instanceof NormalAnnotationExpr normal))
                    continue;
                String varName = null, fromStr = null, toStr = null, nameTemplate = null;
                for (com.github.javaparser.ast.expr.MemberValuePair p : normal.getPairs()) {
                    switch (p.getNameAsString()) {
                        case "varName" -> varName = p.getValue().asStringLiteralExpr().asString();
                        case "from" -> fromStr = p.getValue().asStringLiteralExpr().asString();
                        case "to" -> toStr = p.getValue().asStringLiteralExpr().asString();
                        case "name" -> nameTemplate = p.getValue().asStringLiteralExpr().asString();
                    }
                }
                if (varName == null || fromStr == null || toStr == null || nameTemplate == null)
                    continue;
                if (!nameTemplate.contains("alpha"))
                    continue;
                try {
                    int from = ctx.evaluateInt(fromStr);
                    int to = ctx.evaluateInt(toStr);
                    if (from != to)
                        continue; // must be single-value expansion
                    EvaluationContext innerCtx = ctx.withVariable(varName, from);
                    String newLetter = innerCtx.evaluate(nameTemplate);
                    result.put(method, newLetter);
                } catch (Exception ignored) {
                }
                break; // only consider the first matching @PermuteTypeParam per method
            }
        });
        return result;
    }

    /**
     * Alpha growing-tip inference — Phase 2 (post-PermuteTypeParamTransformer):
     * Combines the pre-collected alpha letters with the post-expansion class type params
     * to produce the full inferred typeArgs string per method (identity-keyed).
     */
    private static java.util.IdentityHashMap<MethodDeclaration, String> buildAlphaInference(
            ClassOrInterfaceDeclaration classDecl,
            java.util.IdentityHashMap<MethodDeclaration, String> alphaLetters) {
        if (alphaLetters.isEmpty())
            return new java.util.IdentityHashMap<>();

        java.util.List<String> classTypeParams = new java.util.ArrayList<>();
        classDecl.getTypeParameters().forEach(tp -> classTypeParams.add(tp.getNameAsString()));
        String currentParams = String.join(", ", classTypeParams);

        java.util.IdentityHashMap<MethodDeclaration, String> result = new java.util.IdentityHashMap<>();
        alphaLetters.forEach((method, newLetter) -> {
            String inferred = currentParams.isEmpty() ? newLetter : currentParams + ", " + newLetter;
            result.put(method, inferred);
        });
        return result;
    }

    /**
     * Processes @PermuteReturn annotations on methods in the generated class:
     * replaces the sentinel return type with the computed type, and removes
     * methods whose boundary check fails.
     */
    private static void applyPermuteReturn(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Set<String> allGeneratedNames) {
        applyPermuteReturn(classDecl, ctx, allGeneratedNames, new java.util.IdentityHashMap<>());
    }

    private static void applyPermuteReturn(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Set<String> allGeneratedNames,
            java.util.IdentityHashMap<MethodDeclaration, String> alphaInference) {

        record CfgAnn(AnnotationReader.PermuteReturnConfig cfg, AnnotationExpr ann) {
        }

        List<MethodDeclaration> toRemove = new ArrayList<>();

        classDecl.getMethods().forEach(method -> {
            // Collect all @PermuteReturn configs from direct or @PermuteReturns container annotations
            List<CfgAnn> allCfgs = new ArrayList<>();
            for (AnnotationExpr ann : List.copyOf(method.getAnnotations())) {
                String n = ann.getNameAsString();
                if (n.equals("PermuteReturns") || n.equals("io.quarkiverse.permuplate.PermuteReturns")) {
                    // Unwrap container
                    if (ann instanceof NormalAnnotationExpr normal) {
                        for (com.github.javaparser.ast.expr.MemberValuePair p : normal.getPairs()) {
                            if (p.getNameAsString().equals("value")
                                    && p.getValue() instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr) {
                                for (com.github.javaparser.ast.expr.Expression v : arr.getValues()) {
                                    if (v instanceof AnnotationExpr inner) {
                                        AnnotationReader.PermuteReturnConfig c = AnnotationReader.readPermuteReturn(inner);
                                        if (c != null)
                                            allCfgs.add(new CfgAnn(c, ann));
                                    }
                                }
                            }
                        }
                    }
                } else if (n.equals("PermuteReturn") || n.equals("io.quarkiverse.permuplate.PermuteReturn")) {
                    AnnotationReader.PermuteReturnConfig c = AnnotationReader.readPermuteReturn(ann);
                    if (c != null)
                        allCfgs.add(new CfgAnn(c, ann));
                }
            }

            if (allCfgs.isEmpty())
                return;

            // Pick the first config whose when= evaluates to true (or has no when=)
            CfgAnn selected = null;
            for (CfgAnn ca : allCfgs) {
                if (ca.cfg().when().isEmpty()) {
                    selected = ca;
                    break;
                }
                try {
                    boolean matches = Boolean.parseBoolean(ctx.evaluate("${" + ca.cfg().when() + "}"));
                    if (matches) {
                        selected = ca;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }

            // Remove all @PermuteReturn/@PermuteReturns annotations regardless of outcome
            method.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("PermuteReturn") || n.equals("io.quarkiverse.permuplate.PermuteReturn")
                        || n.equals("PermuteReturns") || n.equals("io.quarkiverse.permuplate.PermuteReturns");
            });

            if (selected == null) {
                // No condition matched — omit method
                toRemove.add(method);
                return;
            }

            AnnotationReader.PermuteReturnConfig cfg = selected.cfg();

            // typeParam= path: set return type to the named type parameter, always emit
            if (cfg.hasTypeParam()) {
                String evaluatedTypeParam = cfg.typeParam();
                try {
                    evaluatedTypeParam = ctx.evaluate(cfg.typeParam());
                } catch (Exception ignored) {
                }
                try {
                    method.setType(StaticJavaParser.parseType(evaluatedTypeParam));
                } catch (Exception ignored) {
                }
                return;
            }

            // Evaluate className
            String evaluatedClass;
            try {
                evaluatedClass = ctx.evaluate(cfg.className());
            } catch (Exception ignored) {
                return;
            }

            // Boundary omission (only applies when when= was empty and className path is used)
            boolean shouldGenerate;
            if (cfg.alwaysEmit()) {
                shouldGenerate = true;
            } else if (cfg.when().isEmpty()) {
                shouldGenerate = allGeneratedNames.contains(evaluatedClass);
            } else {
                // when= was already evaluated above (selected != null means it was true)
                shouldGenerate = true;
            }

            if (!shouldGenerate) {
                toRemove.add(method);
                return;
            }

            // replaceLastTypeArgWith= — replaces the last type param in the return type
            if (cfg.hasReplaceLastTypeArgWith() && !cfg.hasTypeArgsExpr() && !cfg.hasTypeArgLoop()) {
                java.util.List<String> params = new java.util.ArrayList<>();
                classDecl.getTypeParameters().forEach(tp -> params.add(tp.getNameAsString()));
                if (!params.isEmpty()) {
                    params.set(params.size() - 1, cfg.replaceLastTypeArgWith());
                } else {
                    params.add(cfg.replaceLastTypeArgWith());
                }
                String typeSrc = evaluatedClass + "<" + String.join(", ", params) + ">";
                try {
                    method.setType(StaticJavaParser.parseType(typeSrc));
                } catch (Exception ignored) {
                }
                // Constructor-coherence inference: rename new SeedClass<>() to match resolved return type.
                renameConstructorsToMatchReturn(method, evaluatedClass);
                return;
            }

            // Alpha inference: when typeArgs is absent, use pre-collected inference map (identity lookup)
            AnnotationReader.PermuteReturnConfig effectiveCfg = cfg;
            if (!cfg.hasTypeArgsExpr() && !cfg.hasTypeArgLoop()) {
                String inferred = alphaInference.get(method);
                if (inferred != null) {
                    // Wrap inferred value as a JEXL string literal so buildReturnTypeStr evaluates it
                    effectiveCfg = cfg.withTypeArgs("'" + inferred + "'");
                }
            }

            // Build return type string and replace
            String returnTypeStr = buildReturnTypeStr(evaluatedClass, effectiveCfg, ctx);
            try {
                method.setType(StaticJavaParser.parseType(returnTypeStr));
            } catch (Exception ignored) {
            }
            // Constructor-coherence inference: rename new SeedClass<>() to match resolved return type.
            renameConstructorsToMatchReturn(method, evaluatedClass);
        });

        toRemove.forEach(m -> classDecl.getMembers().removeIf(member -> member == m));
    }

    /**
     * Applies @PermuteDefaultReturn: for every Object-returning method without an explicit
     * @PermuteReturn, replaces the return type with the evaluated class-level default.
     * Must run AFTER applyPermuteReturn so explicit @PermuteReturn annotations are already gone.
     */
    private static void applyInlineDefaultReturn(ClassOrInterfaceDeclaration classDecl,
            EvaluationContext ctx,
            Set<String> allGeneratedNames) {

        java.util.Optional<AnnotationExpr> annOpt = classDecl.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("PermuteDefaultReturn")
                        || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteDefaultReturn"))
                .findFirst();

        if (annOpt.isEmpty())
            return;

        AnnotationExpr ann = annOpt.get();
        if (!(ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normalAnn))
            return;

        String classNameTemplate = null;
        String typeArgsExpr = "";
        boolean alwaysEmit = true;

        for (com.github.javaparser.ast.expr.MemberValuePair pair : normalAnn.getPairs()) {
            switch (pair.getNameAsString()) {
                case "className" -> classNameTemplate = pair.getValue().asStringLiteralExpr().asString();
                case "typeArgs" -> typeArgsExpr = pair.getValue().asStringLiteralExpr().asString();
                case "alwaysEmit" -> {
                    if (pair.getValue() instanceof com.github.javaparser.ast.expr.BooleanLiteralExpr b)
                        alwaysEmit = b.getValue();
                }
            }
        }

        if (classNameTemplate == null || classNameTemplate.isEmpty())
            return;

        // "self" is a reserved literal — return current generated class + all type parameters.
        // typeArgs is ignored when className="self".
        if ("self".equals(classNameTemplate)) {
            String selfName = classDecl.getNameAsString();
            List<String> typeParamNames = new ArrayList<>();
            classDecl.getTypeParameters().forEach(tp -> typeParamNames.add(tp.getNameAsString()));
            final String selfTypeSrc = typeParamNames.isEmpty()
                    ? selfName
                    : selfName + "<" + String.join(", ", typeParamNames) + ">";

            classDecl.getMethods().stream()
                    .filter(m -> m.getType().asString().equals("Object"))
                    .filter(m -> m.getAnnotations().stream().noneMatch(a -> {
                        String n = a.getNameAsString();
                        return n.equals("PermuteReturn") || n.equals("io.quarkiverse.permuplate.PermuteReturn")
                                || n.equals("PermuteReturns") || n.equals("io.quarkiverse.permuplate.PermuteReturns");
                    }))
                    .forEach(m -> {
                        try {
                            m.setType(StaticJavaParser.parseType(selfTypeSrc));
                        } catch (Exception ignored) {
                        }
                    });

            classDecl.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("PermuteDefaultReturn") || n.equals("io.quarkiverse.permuplate.PermuteDefaultReturn");
            });
            return;
        }

        String evaluatedClass;
        try {
            evaluatedClass = ctx.evaluate(classNameTemplate);
        } catch (Exception ignored) {
            return;
        }

        boolean shouldGenerate = alwaysEmit || allGeneratedNames.contains(evaluatedClass);

        String typeArgs = "";
        if (!typeArgsExpr.isEmpty()) {
            try {
                typeArgs = ctx.evaluate("${" + typeArgsExpr + "}");
            } catch (Exception ignored) {
            }
        }

        // typeArgs is a JEXL expression that evaluates to the full type argument string including "<>",
        // e.g. "'<END, A>'" evaluates to "<END, A>". Append directly as suffix — no extra wrapping.
        final String typeSrc = typeArgs.isEmpty() ? evaluatedClass : evaluatedClass + typeArgs;

        List<MethodDeclaration> toRemove = new ArrayList<>();

        classDecl.getMethods().stream()
                .filter(m -> m.getType().asString().equals("Object"))
                .filter(m -> m.getAnnotations().stream().noneMatch(a -> {
                    String n = a.getNameAsString();
                    return n.equals("PermuteReturn") || n.equals("io.quarkiverse.permuplate.PermuteReturn");
                }))
                .forEach(m -> {
                    if (!shouldGenerate) {
                        toRemove.add(m);
                    } else {
                        try {
                            m.setType(StaticJavaParser.parseType(typeSrc));
                        } catch (Exception ignored) {
                        }
                    }
                });

        toRemove.forEach(m -> classDecl.getMembers().removeIf(member -> member == m));

        // Strip @PermuteDefaultReturn from the generated class
        classDecl.getAnnotations().removeIf(a -> {
            String n = a.getNameAsString();
            return n.equals("PermuteDefaultReturn") || n.equals("io.quarkiverse.permuplate.PermuteDefaultReturn");
        });
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

    /** Collects method keys that have explicit @PermuteReturn or @PermuteReturns — used to exclude from implicit inference. */
    private static Set<String> collectExplicitReturnMethodNames(ClassOrInterfaceDeclaration classDecl) {
        Set<String> names = new HashSet<>();
        classDecl.getMethods().forEach(method -> method.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("PermuteReturn")
                        || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteReturn")
                        || a.getNameAsString().equals("PermuteReturns")
                        || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteReturns"))
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

    private static java.util.List<String> collectInlinePermuteImports(
            TypeDeclaration<?> classDecl, EvaluationContext ctx) {
        java.util.List<String> result = new java.util.ArrayList<>();
        classDecl.getAnnotations().forEach(ann -> {
            String name = ann.getNameAsString();
            if (name.equals("PermuteImport") || name.equals("io.quarkiverse.permuplate.PermuteImport")) {
                extractImportValueInline(ann).ifPresent(v -> {
                    try {
                        result.add(ctx.evaluate(v));
                    } catch (Exception ignored) {
                    }
                });
            } else if (name.equals("PermuteImports") || name.equals("io.quarkiverse.permuplate.PermuteImports")) {
                if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
                    normal.getPairs().stream().filter(p -> p.getNameAsString().equals("value"))
                            .findFirst().ifPresent(p -> {
                                com.github.javaparser.ast.expr.Expression val = p.getValue();
                                java.util.List<com.github.javaparser.ast.expr.Expression> elems = val instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr
                                        ? arr.getValues()
                                        : java.util.List.of(val);
                                elems.forEach(e -> {
                                    if (e instanceof AnnotationExpr ae) {
                                        extractImportValueInline(ae).ifPresent(v -> {
                                            try {
                                                result.add(ctx.evaluate(v));
                                            } catch (Exception ignored) {
                                            }
                                        });
                                    }
                                });
                            });
                }
            }
        });
        return result;
    }

    private static java.util.Optional<String> extractImportValueInline(AnnotationExpr ann) {
        if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr s) {
            return java.util.Optional.of(PermuteDeclrTransformer.stripQuotes(s.getMemberValue().toString()));
        }
        if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr n) {
            return n.getPairs().stream().filter(p -> p.getNameAsString().equals("value")).findFirst()
                    .map(p -> PermuteDeclrTransformer.stripQuotes(p.getValue().toString()));
        }
        return java.util.Optional.empty();
    }

    /**
     * Reads all {@code @PermuteFilter} expression strings from the template type's annotations.
     * Handles both the single {@code @PermuteFilter} and the {@code @PermuteFilters} container.
     */
    private static List<String> readFilterExpressions(
            TypeDeclaration<?> templateClass) {
        List<String> result = new ArrayList<>();
        for (com.github.javaparser.ast.expr.AnnotationExpr ann : templateClass.getAnnotations()) {
            String name = ann.getNameAsString();
            if ("PermuteFilter".equals(name) || "io.quarkiverse.permuplate.PermuteFilter".equals(name)) {
                extractFilterValue(ann).ifPresent(result::add);
            } else if ("PermuteFilters".equals(name) || "io.quarkiverse.permuplate.PermuteFilters".equals(name)) {
                if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
                    normal.getPairs().stream()
                            .filter(p -> "value".equals(p.getNameAsString()))
                            .findFirst()
                            .ifPresent(p -> {
                                com.github.javaparser.ast.expr.Expression val = p.getValue();
                                List<com.github.javaparser.ast.expr.Expression> elems = val instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr
                                        ? arr.getValues()
                                        : List.of(val);
                                elems.forEach(e -> extractFilterValue(e).ifPresent(result::add));
                            });
                }
            }
        }
        return result;
    }

    /**
     * Reads all {@code @PermuteFilter} expression strings from a method's annotations.
     * Handles both the single {@code @PermuteFilter} and the {@code @PermuteFilters} container.
     * Returns an empty list if no filter annotations are present.
     */
    private static List<String> readFilterExpressions(MethodDeclaration method) {
        List<String> result = new ArrayList<>();
        for (com.github.javaparser.ast.expr.AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            if ("PermuteFilter".equals(name) || "io.quarkiverse.permuplate.PermuteFilter".equals(name)) {
                extractFilterValue(ann).ifPresent(result::add);
            } else if ("PermuteFilters".equals(name) || "io.quarkiverse.permuplate.PermuteFilters".equals(name)) {
                if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
                    normal.getPairs().stream()
                            .filter(p -> "value".equals(p.getNameAsString()))
                            .findFirst()
                            .ifPresent(p -> {
                                com.github.javaparser.ast.expr.Expression val = p.getValue();
                                List<com.github.javaparser.ast.expr.Expression> elems = val instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr
                                        ? arr.getValues()
                                        : List.of(val);
                                elems.forEach(e -> extractFilterValue(e).ifPresent(result::add));
                            });
                }
            }
        }
        return result;
    }

    /**
     * Evaluates method-level @PermuteFilter expressions. Returns true if all filters
     * pass (or if no filters are present), false if any filter fails.
     * Filters are ANDed — all must pass for the method clone to be generated.
     */
    private static boolean evaluateMethodFilters(List<String> filterExprs, EvaluationContext ctx) {
        for (String expr : filterExprs) {
            try {
                if (!ctx.evaluateBoolean(expr)) {
                    return false;
                }
            } catch (Exception e) {
                System.err.println("[Permuplate] @PermuteFilter expression error on method (clone kept): "
                        + expr + " — " + e.getMessage());
            }
        }
        return true;
    }

    private static java.util.Optional<String> extractFilterValue(
            com.github.javaparser.ast.Node node) {
        if (node instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr single) {
            String raw = single.getMemberValue().toString();
            if (raw.startsWith("\"") && raw.endsWith("\"")) {
                return java.util.Optional.of(raw.substring(1, raw.length() - 1));
            }
        } else if (node instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()))
                    .findFirst()
                    .map(p -> {
                        String raw = p.getValue().toString();
                        return (raw.startsWith("\"") && raw.endsWith("\""))
                                ? raw.substring(1, raw.length() - 1)
                                : null;
                    })
                    .filter(s -> s != null);
        }
        return java.util.Optional.empty();
    }

    /**
     * Returns @PermuteSource value patterns from a template class's AST annotations.
     * E.g. @PermuteSource("Callable${i}") → ["Callable${i}"]
     */
    private static List<String> readSourceNamePatterns(TypeDeclaration<?> templateClass) {
        List<String> result = new ArrayList<>();
        for (AnnotationExpr ann : templateClass.getAnnotations()) {
            String name = ann.getNameAsString();
            if ("PermuteSource".equals(name) || name.endsWith(".PermuteSource")) {
                extractSourceValue(ann).ifPresent(result::add);
            } else if ("PermuteSources".equals(name) || name.endsWith(".PermuteSources")) {
                if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
                    normal.getPairs().stream()
                            .filter(p -> "value".equals(p.getNameAsString()))
                            .findFirst()
                            .map(p -> p.getValue())
                            .filter(v -> v instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr)
                            .map(v -> (com.github.javaparser.ast.expr.ArrayInitializerExpr) v)
                            .ifPresent(arr -> arr.getValues().forEach(expr -> extractSourceValue(expr).ifPresent(result::add)));
                }
            }
        }
        return result;
    }

    private static java.util.Optional<String> extractSourceValue(Object node) {
        if (node instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr s
                && s.getMemberValue() instanceof com.github.javaparser.ast.expr.StringLiteralExpr lit) {
            return java.util.Optional.of(lit.asString());
        }
        if (node instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr n) {
            return n.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()))
                    .map(p -> p.getValue())
                    .filter(v -> v instanceof com.github.javaparser.ast.expr.StringLiteralExpr)
                    .map(v -> ((com.github.javaparser.ast.expr.StringLiteralExpr) v).asString())
                    .findFirst();
        }
        return java.util.Optional.empty();
    }

    /**
     * If the template has @PermuteSource("X${i}"), evaluates the source name
     * for the current context (e.g. "Callable3"), finds that class in parentCu
     * (already generated by Template A in the chain), and copies its type
     * parameters to the derived generated class.
     *
     * This is what makes "type params are inferred from the source" work.
     * No @PermuteTypeParam needed on the derived template.
     */
    private static void applySourceTypeParams(TypeDeclaration<?> generated,
            TypeDeclaration<?> templateClass,
            CompilationUnit parentCu,
            EvaluationContext ctx) {
        List<String> patterns = readSourceNamePatterns(templateClass);
        if (patterns.isEmpty())
            return;
        // Use first @PermuteSource for type inference
        String sourceName = ctx.evaluate(patterns.get(0));

        // Find the source class in parentCu (already generated by Template A)
        java.util.Optional<TypeDeclaration<?>> sourceType = parentCu.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals(sourceName))
                .<TypeDeclaration<?>> map(c -> c)
                .or(() -> parentCu.findFirst(RecordDeclaration.class,
                        r -> r.getNameAsString().equals(sourceName)));

        if (sourceType.isEmpty())
            return; // source not yet generated, skip

        // Copy type parameters from source to generated class using NodeWithTypeParameters
        if (sourceType.get() instanceof com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters<?> srcNwtp
                && generated instanceof com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters<?> genNwtp) {
            @SuppressWarnings("unchecked")
            com.github.javaparser.ast.NodeList<com.github.javaparser.ast.type.TypeParameter> srcTps = ((com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters<TypeDeclaration<?>>) srcNwtp)
                    .getTypeParameters();
            if (!srcTps.isEmpty()) {
                @SuppressWarnings("unchecked")
                com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters<TypeDeclaration<?>> genTyped = (com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters<TypeDeclaration<?>>) genNwtp;
                com.github.javaparser.ast.NodeList<com.github.javaparser.ast.type.TypeParameter> copied = new com.github.javaparser.ast.NodeList<>();
                srcTps.forEach(tp -> copied.add(tp.clone()));
                genTyped.setTypeParameters(copied);
            }
        }
    }

    /**
     * Rewrites all type references of the form {@code TemplateSourceN<...>} to
     * {@code CurrentSourceN<A, B, ...>} throughout the generated class body.
     *
     * <p>
     * Example: template says {@code implements Callable2<Object>}; for i=3 the
     * source is {@code Callable3<A,B,C>}, so the implements clause becomes
     * {@code implements Callable3<A, B, C>}.
     *
     * <p>
     * The template source name (e.g. {@code Callable2}) is read from the first
     * {@code @PermuteSource} pattern evaluated with the <em>template</em>'s sentinel
     * value (the embedded number in the template class name). The current source name
     * is the same pattern evaluated with the current context.
     *
     * <p>
     * Rewrites:
     * <ul>
     * <li>implements / extends type arguments on the class declaration</li>
     * <li>field declared types</li>
     * <li>method parameter types</li>
     * <li>constructor parameter types</li>
     * </ul>
     */
    private static void applySourceTypeRefUpdate(TypeDeclaration<?> generated,
            TypeDeclaration<?> templateClass,
            CompilationUnit parentCu,
            EvaluationContext ctx) {
        List<String> patterns = readSourceNamePatterns(templateClass);
        if (patterns.isEmpty())
            return;

        // Determine the template's embedded sentinel number from templateClass name
        String templateClassName = templateClass.getNameAsString();
        int templateEmbedded = firstEmbeddedNumber(templateClassName);

        // Current generated source name (e.g. "Callable3" for i=3)
        String currentSourceName = ctx.evaluate(patterns.get(0));

        // Template sentinel source name (e.g. "Callable2"): evaluate pattern with
        // the template's embedded number substituted for the primary var.
        // We derive this by replacing the number in templateClassName with the
        // pattern to find what the source pattern resolves to at template time.
        // Simpler: just look for any ClassOrInterfaceType whose name ends with a
        // number that, when the number is replaced by the template's sentinel,
        // matches the current source name prefix.
        // Even simpler approach: for each @PermuteSource pattern, build all source
        // names the pattern can produce and find the one that appears in the class body.

        // Find the source type in parentCu to get its type parameter names
        Optional<TypeDeclaration<?>> sourceTypeOpt = parentCu.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals(currentSourceName))
                .<TypeDeclaration<?>> map(c -> c)
                .or(() -> parentCu.findFirst(RecordDeclaration.class,
                        r -> r.getNameAsString().equals(currentSourceName)));
        if (sourceTypeOpt.isEmpty())
            return;

        // Build the new type arg string from the current source's type parameters
        String newTypeArgStr;
        TypeDeclaration<?> sourceType = sourceTypeOpt.get();
        if (sourceType instanceof NodeWithTypeParameters<?> nwtp) {
            @SuppressWarnings("unchecked")
            NodeList<TypeParameter> tps = ((NodeWithTypeParameters<TypeDeclaration<?>>) nwtp).getTypeParameters();
            if (tps.isEmpty())
                return; // no type params to substitute
            newTypeArgStr = tps.stream().map(TypeParameter::getNameAsString).collect(Collectors.joining(", "));
        } else {
            return;
        }

        // Determine the template sentinel source name (what the template literally contains,
        // e.g. "Callable2" or "SyncCallable2"). Strategy: find the source name prefix
        // (everything before the number in currentSourceName) and add templateEmbedded.
        String currentPrefix = prefixBeforeFirstDigit(currentSourceName);
        String templateSourceName = templateEmbedded >= 0
                ? currentPrefix + templateEmbedded
                : currentSourceName;

        // New type string to replace with (e.g. "Callable3<A, B, C>")
        String newTypeStr = currentSourceName + "<" + newTypeArgStr + ">";

        // Rewrite all ClassOrInterfaceType nodes whose simple name equals templateSourceName
        // (or currentSourceName when they already match — handles cases where the template
        // uses a name that doesn't have an embedded number).
        generated.walk(com.github.javaparser.ast.type.ClassOrInterfaceType.class, typeRef -> {
            String refName = typeRef.getNameAsString();
            if (refName.equals(templateSourceName) || refName.equals(currentSourceName)) {
                // Only rewrite if type args are present (don't touch raw type references)
                if (typeRef.getTypeArguments().isPresent()) {
                    try {
                        com.github.javaparser.ast.type.ClassOrInterfaceType replacement = StaticJavaParser
                                .parseClassOrInterfaceType(newTypeStr);
                        typeRef.setName(replacement.getNameAsString());
                        typeRef.setTypeArguments(replacement.getTypeArguments().orElse(null));
                    } catch (Exception ignored) {
                    }
                }
            }
        });

        // Also rewrite the implements/extends clause on the class declaration itself
        if (generated instanceof ClassOrInterfaceDeclaration coid) {
            rewriteTypeList(coid.getImplementedTypes(), templateSourceName, currentSourceName, newTypeStr);
            rewriteTypeList(coid.getExtendedTypes(), templateSourceName, currentSourceName, newTypeStr);
        }
    }

    /**
     * Rewrites any entry in {@code types} whose simple name is {@code templateSourceName}
     * or {@code currentSourceName} and that carries type arguments, replacing it with
     * the fully-specified {@code newTypeStr}.
     */
    private static void rewriteTypeList(
            NodeList<com.github.javaparser.ast.type.ClassOrInterfaceType> types,
            String templateSourceName,
            String currentSourceName,
            String newTypeStr) {
        for (int idx = 0; idx < types.size(); idx++) {
            com.github.javaparser.ast.type.ClassOrInterfaceType t = types.get(idx);
            String name = t.getNameAsString();
            if ((name.equals(templateSourceName) || name.equals(currentSourceName))
                    && t.getTypeArguments().isPresent()) {
                try {
                    types.set(idx, StaticJavaParser.parseClassOrInterfaceType(newTypeStr));
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * If the template has @PermuteSource referencing a RecordDeclaration AND the
     * generated class body is empty (no declared members), synthesises a complete
     * fluent builder:
     * - Private fields per record component
     * - Fluent setter per component (returns this, builder type as return type)
     * - build() method returning new RecordType<>(fields...)
     *
     * Type parameters are already set by applySourceTypeParams() before this runs.
     * Triggers only on empty body — if the user wrote any members, synthesis is skipped.
     */
    private static void applyBuilderSynthesis(TypeDeclaration<?> generated,
            TypeDeclaration<?> templateClass,
            CompilationUnit parentCu,
            EvaluationContext ctx) {
        // Only trigger when template body is empty
        if (!generated.getMembers().isEmpty())
            return;

        List<String> patterns = readSourceNamePatterns(templateClass);
        if (patterns.isEmpty())
            return;
        String sourceName = ctx.evaluate(patterns.get(0));

        // Source must be a record
        Optional<RecordDeclaration> recordOpt = parentCu.findFirst(RecordDeclaration.class,
                r -> r.getNameAsString().equals(sourceName));
        if (recordOpt.isEmpty())
            return;

        RecordDeclaration record = recordOpt.get();
        String builderName = generated.getNameAsString();

        // Build type param string (e.g. "<A, B, C>") from already-set type params
        String typeParamStr = "";
        if (generated instanceof NodeWithTypeParameters<?> nwtp) {
            @SuppressWarnings("unchecked")
            NodeList<TypeParameter> tps = ((NodeWithTypeParameters<TypeDeclaration<?>>) nwtp).getTypeParameters();
            if (!tps.isEmpty()) {
                typeParamStr = "<" + tps.stream()
                        .map(TypeParameter::getNameAsString)
                        .collect(Collectors.joining(", ")) + ">";
            }
        }
        String builderType = builderName + typeParamStr;
        String recordType = sourceName + typeParamStr;

        // Collect record components
        List<Parameter> components = record.getParameters();

        // Generate: private fields
        for (Parameter comp : components) {
            String fieldSrc = "private " + comp.getType() + " " + comp.getNameAsString() + ";";
            generated.addMember(StaticJavaParser.parseBodyDeclaration(fieldSrc));
        }

        // Generate: fluent setters
        for (Parameter comp : components) {
            String compName = comp.getNameAsString();
            String compType = comp.getType().asString();
            String setterSrc = "public " + builderType + " " + compName
                    + "(" + compType + " " + compName + ") { this." + compName
                    + " = " + compName + "; return this; }";
            generated.addMember(StaticJavaParser.parseBodyDeclaration(setterSrc));
        }

        // Generate: build() method (use diamond <> for the constructor call)
        String args = components.stream()
                .map(Parameter::getNameAsString)
                .collect(Collectors.joining(", "));
        String buildSrc = "public " + recordType + " build() { return new "
                + sourceName + "<>(" + args + "); }";
        generated.addMember(StaticJavaParser.parseBodyDeclaration(buildSrc));
    }

    /**
     * Synthesises delegating method bodies for all fields annotated with @PermuteDelegate.
     * For each such field:
     * - Strips @PermuteDelegate annotation from output
     * - Reads the field's declared type name to find the source class in parentCu
     * - For each method in that source class NOT already declared in the generated class,
     * generates a delegating method body (with optional modifier from @PermuteDelegate)
     * - User-declared methods take precedence — they are never overwritten
     */
    private static void applyPermuteDelegate(TypeDeclaration<?> generated,
            CompilationUnit parentCu) {
        for (FieldDeclaration field : new ArrayList<>(generated.getFields())) {
            AnnotationExpr delegateAnn = null;
            for (AnnotationExpr ann : field.getAnnotations()) {
                String n = ann.getNameAsString();
                if ("PermuteDelegate".equals(n) || n.endsWith(".PermuteDelegate")) {
                    delegateAnn = ann;
                    break;
                }
            }
            if (delegateAnn == null)
                continue;

            // Remove @PermuteDelegate from output
            field.getAnnotations().remove(delegateAnn);

            // Read modifier= attribute (empty = no modifier)
            String modifier = "";
            if (delegateAnn instanceof NormalAnnotationExpr normal) {
                modifier = normal.getPairs().stream()
                        .filter(p -> "modifier".equals(p.getNameAsString()))
                        .map(p -> p.getValue().toString().replace("\"", ""))
                        .findFirst().orElse("");
            }

            // Get field's type simple name (strip generic type args)
            String fieldTypeName = field.getCommonType().asString()
                    .replaceAll("<.*>", "").trim();

            // Get the field variable name (e.g. "delegate")
            String fieldName = field.getVariables().get(0).getNameAsString();

            // Find that type in parentCu
            Optional<TypeDeclaration<?>> sourceTypeOpt2 = parentCu.findFirst(ClassOrInterfaceDeclaration.class,
                    c -> c.getNameAsString().equals(fieldTypeName))
                    .<TypeDeclaration<?>> map(c -> c);
            if (sourceTypeOpt2.isEmpty())
                continue;

            // Interface methods are implicitly public — the synthesised delegate must be public.
            ClassOrInterfaceDeclaration sourceInterface = (ClassOrInterfaceDeclaration) sourceTypeOpt2.get();
            boolean sourceIsInterface = sourceInterface.isInterface();

            // Collect method names already declared in the generated class (user takes precedence)
            Set<String> declaredMethods = generated.getMethods().stream()
                    .map(MethodDeclaration::getNameAsString)
                    .collect(Collectors.toSet());

            // Synthesise delegating methods for each undeclared source method
            final String mod = modifier;
            final String fname = fieldName;
            sourceTypeOpt2.get().getMethods().forEach(srcMethod -> {
                if (declaredMethods.contains(srcMethod.getNameAsString()))
                    return;

                // Join param names for the call site (just names, not types)
                String paramNames = srcMethod.getParameters().stream()
                        .map(p -> p.getNameAsString())
                        .collect(Collectors.joining(", "));
                // Join param declarations for the method signature (type + name)
                String paramDecls = srcMethod.getParameters().stream()
                        .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                        .collect(Collectors.joining(", "));
                boolean isVoid = srcMethod.getType().asString().equals("void");
                String callExpr = fname + "." + srcMethod.getNameAsString() + "(" + paramNames + ")";
                String body = isVoid ? "{ " + callExpr + "; }" : "{ return " + callExpr + "; }";

                String throws_ = srcMethod.getThrownExceptions().isEmpty() ? ""
                        : " throws " + srcMethod.getThrownExceptions().stream()
                                .map(Object::toString).collect(Collectors.joining(", "));

                // Interface methods are implicitly public — emit "public" so the implementing
                // class does not weaken the access privilege.
                String accessMod = sourceIsInterface ? "public " : "";

                String methodSrc = accessMod + (mod.isEmpty() ? "" : mod + " ")
                        + srcMethod.getTypeAsString() + " " + srcMethod.getNameAsString()
                        + "(" + paramDecls + ")" + throws_ + " " + body;

                try {
                    MethodDeclaration synth = StaticJavaParser.parseMethodDeclaration(methodSrc);
                    synth.addAnnotation("Override");
                    generated.addMember(synth);
                } catch (Exception e) {
                    System.err.println("[Permuplate] @PermuteDelegate: failed to synthesise "
                            + srcMethod.getNameAsString() + ": " + e.getMessage());
                }
            });
        }
    }

    /**
     * Returns true if every return statement in the method returns {@code this}
     * or {@code cast(this)} — making this a fluent self-return method eligible
     * for automatic return-type inference.
     */
    private static boolean isSelfReturning(MethodDeclaration method) {
        if (!method.getType().asString().equals("Object"))
            return false;
        java.util.Optional<com.github.javaparser.ast.stmt.BlockStmt> body = method.getBody();
        if (body.isEmpty())
            return false;
        java.util.List<com.github.javaparser.ast.stmt.ReturnStmt> returns = body.get()
                .findAll(com.github.javaparser.ast.stmt.ReturnStmt.class);
        if (returns.isEmpty())
            return false;
        return returns.stream().allMatch(ret -> {
            if (ret.getExpression().isEmpty())
                return false;
            com.github.javaparser.ast.expr.Expression expr = ret.getExpression().get();
            // Direct: return this;
            if (expr instanceof com.github.javaparser.ast.expr.ThisExpr)
                return true;
            // Wrapped: return cast(this);
            if (expr instanceof com.github.javaparser.ast.expr.MethodCallExpr call) {
                return call.getNameAsString().equals("cast")
                        && call.getArguments().size() == 1
                        && call.getArgument(0) instanceof com.github.javaparser.ast.expr.ThisExpr;
            }
            return false;
        });
    }

    /**
     * Post-pass: methods with {@code Object} sentinel return, no explicit
     * {@code @PermuteReturn}, and a body that always returns {@code this} get their
     * return type set to the current generated class automatically. No annotation needed.
     *
     * <p>
     * Runs after {@code PermuteSelfTransformer} so that methods with explicit
     * {@code @PermuteSelf} are already processed (their annotation consumed).
     * This inference post-pass catches the remaining unannotated self-return methods.
     */
    private static void applySelfReturnInference(TypeDeclaration<?> classDecl) {
        String className = classDecl.getNameAsString();
        String typeParams = "";
        if (classDecl instanceof NodeWithTypeParameters<?> nwtp) {
            @SuppressWarnings("unchecked")
            NodeList<TypeParameter> tps = ((NodeWithTypeParameters<TypeDeclaration<?>>) nwtp).getTypeParameters();
            typeParams = tps.stream()
                    .map(TypeParameter::getNameAsString)
                    .collect(Collectors.joining(", "));
        }
        String returnTypeSrc = typeParams.isEmpty()
                ? className
                : className + "<" + typeParams + ">";
        com.github.javaparser.ast.type.Type returnType = StaticJavaParser.parseType(returnTypeSrc);

        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            // Explicit @PermuteReturn takes precedence — skip
            boolean hasExplicitReturn = method.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("PermuteReturn")
                            || a.getNameAsString().equals("io.quarkiverse.permuplate.PermuteReturn"));
            if (hasExplicitReturn)
                return;
            if (!isSelfReturning(method))
                return;
            method.setType(returnType.clone());
        });
    }

    /**
     * Post-pass inference: when a method has {@code Object} return, no explicit
     * {@code @PermuteReturn} remaining, and its last statement is
     * {@code return new X<>()} or {@code return cast(new X<>())} where X is in
     * {@code allGeneratedNames} — infer the return type as X.
     *
     * <p>
     * Runs AFTER {@link PermuteBodyTransformer} so the final body is in place, and
     * after {@link #applyInlineDefaultReturn} so explicit defaults are already applied.
     * Explicit {@code @PermuteReturn} annotations are already consumed and removed
     * by this point.
     */
    private static void inferReturnFromBody(ClassOrInterfaceDeclaration classDecl,
            Set<String> allGeneratedNames) {
        classDecl.getMethods().forEach(method -> {
            if (!method.getType().asString().equals("Object"))
                return;
            boolean hasReturn = method.getAnnotations().stream().anyMatch(a -> {
                String n = a.getNameAsString();
                return n.equals("PermuteReturn") || n.equals("io.quarkiverse.permuplate.PermuteReturn")
                        || n.equals("PermuteReturns") || n.equals("io.quarkiverse.permuplate.PermuteReturns");
            });
            if (hasReturn)
                return;
            method.getBody().ifPresent(body -> {
                if (body.getStatements().isEmpty())
                    return;
                com.github.javaparser.ast.stmt.Statement last = body.getStatements().get(body.getStatements().size() - 1);
                if (!(last instanceof com.github.javaparser.ast.stmt.ReturnStmt rs))
                    return;
                if (rs.getExpression().isEmpty())
                    return;
                String candidateClass = extractNewClassName(rs.getExpression().get());
                if (candidateClass == null)
                    return;
                if (!allGeneratedNames.contains(candidateClass))
                    return;
                try {
                    method.setType(StaticJavaParser.parseType(candidateClass));
                } catch (Exception ignored) {
                }
            });
        });
    }

    /**
     * Extracts the simple class name from {@code new X<>()} or {@code cast(new X<>())}
     * expressions. Returns {@code null} for anything else.
     */
    private static String extractNewClassName(com.github.javaparser.ast.expr.Expression expr) {
        if (expr instanceof com.github.javaparser.ast.expr.MethodCallExpr mc
                && mc.getNameAsString().equals("cast")
                && mc.getArguments().size() == 1) {
            expr = mc.getArguments().get(0);
        }
        if (expr instanceof com.github.javaparser.ast.expr.ObjectCreationExpr oce) {
            return oce.getType().getNameAsString();
        }
        return null;
    }

    /**
     * Coherence inference: after @PermuteReturn resolves a method's return type to
     * {@code resolvedClass}, finds any ObjectCreationExpr in the method body whose type
     * simple name belongs to the same generated-class family (same name prefix after
     * stripping all digit sequences) and renames it to match.
     *
     * <p>
     * The digit-presence check ({@code typeName.matches(".*\\d.*")}) is intentionally loose;
     * the family equality check is the real safety filter — both the constructor type and the
     * resolved return type must strip to the same letter-only string before a rename fires.
     *
     * <p>
     * Note: {@code allGeneratedNames} is not used as a guard here because cross-file
     * generated families (e.g., {@code RuleExtendsPoint2..7} from {@code RuleExtendsPoint.java})
     * are absent from the per-CU set when processing a different file (e.g., JoinBuilder.java).
     * Family matching is sufficient and cross-file safe.
     *
     * <p>
     * Skipped when the method has @PermuteBody (body is a string template, not real Java).
     */
    private static void renameConstructorsToMatchReturn(
            MethodDeclaration method, String resolvedClass) {
        // Skip string-template methods
        boolean hasPermuteBody = method.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.equals("PermuteBody") || n.equals("io.quarkiverse.permuplate.PermuteBody")
                    || n.equals("PermuteBodies") || n.equals("io.quarkiverse.permuplate.PermuteBodies");
        });
        if (hasPermuteBody)
            return;
        if (method.getBody().isEmpty())
            return;

        String resolvedSimple = resolvedClass.contains(".")
                ? resolvedClass.substring(resolvedClass.lastIndexOf('.') + 1)
                : resolvedClass;
        // Strip ALL digit sequences to get the structural family — handles embedded digits
        // like Join2First → JoinFirst, RuleExtendsPoint3 → RuleExtendsPoint.
        String resolvedFamily = resolvedSimple.replaceAll("\\d+", "");

        method.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).forEach(oce -> {
            com.github.javaparser.ast.type.ClassOrInterfaceType type = oce.getType();
            String typeName = type.getNameAsString(); // simple name only
            // Only consider types that contain at least one digit (arity-numbered classes)
            // and share the same structural family as the resolved return type.
            // Cross-file families (e.g. RuleExtendsPoint) are not in allGeneratedNames for
            // this CU, so we use family matching rather than the set membership check.
            if (!typeName.matches(".*\\d.*"))
                return;
            String typeFamily = typeName.replaceAll("\\d+", "");
            if (!typeFamily.equals(resolvedFamily))
                return;
            if (!typeName.equals(resolvedSimple)) {
                type.setName(resolvedSimple);
            }
        });
    }

    /**
     * Injects methods from classes listed in {@code @PermuteMixin} into the template class.
     * Must be called before {@link #generate} so injected methods are processed by the pipeline.
     *
     * @param templateDecl the template class to inject into
     * @param allSourceCus all parsed CompilationUnits in the template source root
     */
    public static void injectMixinMethods(TypeDeclaration<?> templateDecl,
            List<CompilationUnit> allSourceCus) {

        templateDecl.getAnnotations().stream()
                .filter(a -> {
                    String n = a.getNameAsString();
                    return n.equals("PermuteMixin")
                            || n.equals("io.quarkiverse.permuplate.PermuteMixin");
                })
                .findFirst()
                .ifPresent(ann -> {
                    List<String> mixinNames = new ArrayList<>();
                    if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
                        normal.getPairs().stream()
                                .filter(p -> p.getNameAsString().equals("value"))
                                .findFirst()
                                .ifPresent(p -> extractMixinClassNames(p.getValue(), mixinNames));
                    } else if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr sm) {
                        extractMixinClassNames(sm.getMemberValue(), mixinNames);
                    }

                    for (String mixinSimpleName : mixinNames) {
                        allSourceCus.stream()
                                .flatMap(cu -> cu.findAll(TypeDeclaration.class).stream())
                                .filter(td -> ((TypeDeclaration<?>) td).getNameAsString().equals(mixinSimpleName))
                                .findFirst()
                                .ifPresent(mixinDecl -> {
                                    if (templateDecl instanceof ClassOrInterfaceDeclaration coid) {
                                        // Only inject methods that carry at least one Permuplate
                                        // annotation — plain override stubs (e.g. ruleName()) are
                                        // excluded so they don't clash with the template's own methods.
                                        ((TypeDeclaration<?>) mixinDecl).getMethods().stream()
                                                .filter(m -> ((MethodDeclaration) m).getAnnotations().stream()
                                                        .anyMatch(a -> a.getNameAsString().startsWith("Permute")
                                                                || a.getNameAsString()
                                                                        .startsWith("io.quarkiverse.permuplate.Permute")))
                                                .forEach(m -> coid.addMember(((MethodDeclaration) m).clone()));
                                    }
                                });
                    }
                });
    }

    private static void extractMixinClassNames(com.github.javaparser.ast.expr.Expression expr,
            List<String> out) {
        if (expr instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr) {
            arr.getValues().forEach(v -> {
                if (v instanceof com.github.javaparser.ast.expr.ClassExpr ce)
                    out.add(ce.getTypeAsString());
            });
        } else if (expr instanceof com.github.javaparser.ast.expr.ClassExpr ce) {
            out.add(ce.getTypeAsString());
        }
    }

    /**
     * Strips all Permuplate annotations ({@code @Permute}, {@code @PermuteDeclr},
     * {@code @PermuteParam}) from a type declaration and all of its members,
     * including field-level and parameter-level annotations.
     */
    static void stripPermuteAnnotations(TypeDeclaration<?> classDecl) {
        Set<String> PERMUPLATE_ANNOTATIONS = Set.of(
                "Permute", "io.quarkiverse.permuplate.Permute",
                "PermuteDeclr", "io.quarkiverse.permuplate.PermuteDeclr",
                "PermuteParam", "io.quarkiverse.permuplate.PermuteParam",
                "PermuteTypeParam", "io.quarkiverse.permuplate.PermuteTypeParam",
                "PermuteReturn", "io.quarkiverse.permuplate.PermuteReturn",
                "PermuteReturns", "io.quarkiverse.permuplate.PermuteReturns",
                "PermuteMethod", "io.quarkiverse.permuplate.PermuteMethod",
                "PermuteExtends", "io.quarkiverse.permuplate.PermuteExtends",
                "PermuteExtendsChain", "io.quarkiverse.permuplate.PermuteExtendsChain",
                "PermuteConst", "io.quarkiverse.permuplate.PermuteConst",
                "PermuteCase", "io.quarkiverse.permuplate.PermuteCase",
                "PermuteValue", "io.quarkiverse.permuplate.PermuteValue",
                "PermuteStatements", "io.quarkiverse.permuplate.PermuteStatements",
                "PermuteBody", "io.quarkiverse.permuplate.PermuteBody",
                "PermuteBodies", "io.quarkiverse.permuplate.PermuteBodies",
                "PermuteFilter", "io.quarkiverse.permuplate.PermuteFilter",
                "PermuteFilters", "io.quarkiverse.permuplate.PermuteFilters",
                "PermuteEnumConst", "io.quarkiverse.permuplate.PermuteEnumConst",
                "PermuteSwitchArm", "io.quarkiverse.permuplate.PermuteSwitchArm",
                "PermuteImport", "io.quarkiverse.permuplate.PermuteImport",
                "PermuteImports", "io.quarkiverse.permuplate.PermuteImports",
                "PermuteSelf", "io.quarkiverse.permuplate.PermuteSelf",
                "PermuteDefaultReturn", "io.quarkiverse.permuplate.PermuteDefaultReturn",
                "PermuteAnnotation", "io.quarkiverse.permuplate.PermuteAnnotation",
                "PermuteAnnotations", "io.quarkiverse.permuplate.PermuteAnnotations",
                "PermuteThrows", "io.quarkiverse.permuplate.PermuteThrows",
                "PermuteSource", "io.quarkiverse.permuplate.PermuteSource",
                "PermuteSources", "io.quarkiverse.permuplate.PermuteSources",
                "PermuteMixin", "io.quarkiverse.permuplate.PermuteMixin",
                "PermuteBodyFragment", "io.quarkiverse.permuplate.PermuteBodyFragment",
                "PermuteBodyFragments", "io.quarkiverse.permuplate.PermuteBodyFragments");

        // Strip from the class itself
        classDecl.getAnnotations().removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString()));

        // Strip from fields
        classDecl.findAll(FieldDeclaration.class)
                .forEach(field -> field.getAnnotations().removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));

        // Strip from type parameters (e.g. @PermuteTypeParam on sentinel C)
        // TypeDeclaration<?> does not expose getTypeParameters() — branch on concrete type.
        if (classDecl instanceof ClassOrInterfaceDeclaration coid) {
            coid.getTypeParameters().forEach(tp -> tp.getAnnotations()
                    .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));
        } else if (classDecl instanceof RecordDeclaration rd) {
            rd.getTypeParameters().forEach(tp -> tp.getAnnotations()
                    .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));
            // Also strip from record components (analogous to constructor parameters)
            rd.getParameters().forEach(param -> param.getAnnotations()
                    .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));
        } else if (classDecl instanceof EnumDeclaration ed) {
            // Strip annotations from enum constant declarations (e.g. @PermuteEnumConst sentinels)
            ed.getEntries().forEach(ec -> ec.getAnnotations()
                    .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));
        }

        // Strip from method annotations, method type parameters, and method parameters
        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
            method.getAnnotations().removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString()));
            method.getTypeParameters()
                    .forEach(tp -> tp.getAnnotations()
                            .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));
            method.getParameters()
                    .forEach(param -> {
                        param.getAnnotations()
                                .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString()));
                        // Strip TYPE_USE annotations on the parameter's type (e.g. @PermuteDeclr on new expr types)
                        if (param.getType() instanceof com.github.javaparser.ast.type.ClassOrInterfaceType) {
                            ((com.github.javaparser.ast.type.ClassOrInterfaceType) param.getType())
                                    .getAnnotations()
                                    .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString()));
                        }
                    });
        });

        // Strip from constructor annotations and constructor parameters
        classDecl.findAll(ConstructorDeclaration.class).forEach(ctor -> {
            ctor.getAnnotations().removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString()));
            ctor.getParameters().forEach(
                    param -> param.getAnnotations().removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));
        });

        // Strip from local variable annotations inside method and constructor bodies
        classDecl.findAll(com.github.javaparser.ast.expr.VariableDeclarationExpr.class)
                .forEach(vde -> vde.getAnnotations()
                        .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));

        // Strip TYPE_USE annotations from ObjectCreationExpr types (e.g. new @PermuteDeclr(...) Join3First<>())
        classDecl.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)
                .forEach(newExpr -> newExpr.getType().getAnnotations()
                        .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));
    }

    /**
     * Merges container macros from {@code @PermuteMacros} on enclosing types into
     * {@code config.macros}, returning an updated config. Container macros are prepended
     * so that the template's own {@code macros=} entries (appended last) take precedence
     * when names collide — {@code buildAllCombinations} evaluates in order and the last
     * write wins for duplicate names.
     *
     * <p>
     * Enclosing types are walked from innermost to outermost; the collected layers are
     * reversed so outermost appears first in the merged list (declaration order).
     *
     * @param config the parsed {@code @Permute} config for the template
     * @param templateDecl the template class declaration (used to find enclosing types)
     * @return a new {@link PermuteConfig} with merged macros, or {@code config} unchanged
     *         if no {@code @PermuteMacros} annotations are found on enclosing types
     */
    public static PermuteConfig mergeContainerMacros(PermuteConfig config,
            TypeDeclaration<?> templateDecl) {
        List<String> containerMacros = collectContainerMacros(templateDecl);
        if (containerMacros.isEmpty())
            return config;
        // Container macros come first; template's own macros= are appended so they win
        // on name collision (buildAllCombinations evaluates in declaration order, last write wins).
        List<String> combined = new java.util.ArrayList<>(containerMacros);
        if (config.macros != null)
            combined.addAll(java.util.Arrays.asList(config.macros));
        return config.withMacros(combined.toArray(String[]::new));
    }

    /**
     * Collects macro strings from {@code @PermuteMacros} annotations on all enclosing
     * types of the given template declaration. Walks from innermost to outermost enclosing
     * type, then reverses the collected layers so the result is in outermost-first order.
     */
    private static List<String> collectContainerMacros(TypeDeclaration<?> templateDecl) {
        List<String[]> layers = new java.util.ArrayList<>();
        com.github.javaparser.ast.Node current = templateDecl.getParentNode().orElse(null);
        while (current instanceof TypeDeclaration<?> enclosing) {
            enclosing.getAnnotations().stream()
                    .filter(a -> {
                        String n = a.getNameAsString();
                        return n.equals("PermuteMacros")
                                || n.equals("io.quarkiverse.permuplate.PermuteMacros");
                    })
                    .findFirst()
                    .ifPresent(ann -> {
                        if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr sm) {
                            layers.add(readStringArrayExpr(sm.getMemberValue()));
                        } else if (ann instanceof NormalAnnotationExpr normal) {
                            normal.getPairs().stream()
                                    .filter(p -> p.getNameAsString().equals("value"))
                                    .findFirst()
                                    .ifPresent(p -> layers.add(readStringArrayExpr(p.getValue())));
                        }
                    });
            current = enclosing.getParentNode().orElse(null);
        }
        // Reverse so outermost is first, innermost is last
        java.util.Collections.reverse(layers);
        List<String> result = new java.util.ArrayList<>();
        for (String[] arr : layers)
            result.addAll(java.util.Arrays.asList(arr));
        return result;
    }

    /**
     * Reads a string array from a JavaParser annotation value expression.
     * Handles both {@code {"a", "b"}} (ArrayInitializerExpr) and {@code "a"} (single string).
     */
    private static String[] readStringArrayExpr(com.github.javaparser.ast.expr.Expression expr) {
        if (expr instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr) {
            return arr.getValues().stream()
                    .map(v -> io.quarkiverse.permuplate.core.PermuteDeclrTransformer.stripQuotes(v.toString()))
                    .toArray(String[]::new);
        }
        return new String[] { io.quarkiverse.permuplate.core.PermuteDeclrTransformer.stripQuotes(expr.toString()) };
    }

    /**
     * Collects {@code @PermuteBodyFragment} declarations from the template class and all enclosing
     * types (outermost first, so innermost overrides outermost on name collision), evaluates each
     * fragment {@code value} with the current permutation context, and returns a name→evaluated-code map.
     */
    private static java.util.Map<String, String> collectBodyFragments(
            TypeDeclaration<?> templateDecl, EvaluationContext ctx) {
        java.util.Deque<List<AnnotationExpr>> layers = new java.util.ArrayDeque<>();
        com.github.javaparser.ast.Node current = templateDecl.getParentNode().orElse(null);
        while (current instanceof TypeDeclaration<?> enc) {
            layers.addFirst(enc.getAnnotations());
            current = enc.getParentNode().orElse(null);
        }
        layers.addLast(templateDecl.getAnnotations());

        java.util.Map<String, String> fragments = new java.util.LinkedHashMap<>();
        for (List<AnnotationExpr> anns : layers) {
            for (AnnotationExpr ann : anns) {
                String n = ann.getNameAsString();
                if (n.equals("PermuteBodyFragment")
                        || n.equals("io.quarkiverse.permuplate.PermuteBodyFragment")) {
                    readBodyFragment(ann, ctx, fragments);
                } else if (n.equals("PermuteBodyFragments")
                        || n.equals("io.quarkiverse.permuplate.PermuteBodyFragments")) {
                    if (ann instanceof NormalAnnotationExpr normal) {
                        normal.getPairs().stream()
                                .filter(p -> p.getNameAsString().equals("value"))
                                .findFirst()
                                .ifPresent(p -> {
                                    if (p.getValue() instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr) {
                                        arr.getValues().forEach(v -> {
                                            if (v instanceof AnnotationExpr inner)
                                                readBodyFragment(inner, ctx, fragments);
                                        });
                                    } else if (p.getValue() instanceof AnnotationExpr inner) {
                                        readBodyFragment(inner, ctx, fragments);
                                    }
                                });
                    }
                }
            }
        }
        return fragments;
    }

    private static void readBodyFragment(AnnotationExpr ann, EvaluationContext ctx,
            java.util.Map<String, String> out) {
        if (!(ann instanceof NormalAnnotationExpr normal))
            return;
        String name = null, value = null;
        for (com.github.javaparser.ast.expr.MemberValuePair p : normal.getPairs()) {
            String raw = PermuteDeclrTransformer.stripQuotes(p.getValue().toString());
            if (p.getNameAsString().equals("name"))
                name = raw;
            else if (p.getNameAsString().equals("value"))
                value = raw;
        }
        if (name == null || value == null)
            return;
        try {
            out.put(name, ctx.evaluate(value));
        } catch (Exception e) {
            throw new RuntimeException(
                    "@PermuteBodyFragment(name=\"" + name + "\"): JEXL evaluation failed for value: " + value + " — "
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * Substitutes {@code ${name}} fragment references in all {@code @PermuteBody} body attribute
     * strings on the given type declaration. Must be called BEFORE {@link PermuteBodyTransformer} runs.
     */
    private static void applyBodyFragments(TypeDeclaration<?> classDecl,
            java.util.Map<String, String> fragments) {
        if (fragments.isEmpty())
            return;
        classDecl.findAll(AnnotationExpr.class).stream()
                .filter(a -> {
                    String n = a.getNameAsString();
                    return n.equals("PermuteBody") || n.equals("io.quarkiverse.permuplate.PermuteBody");
                })
                .forEach(ann -> {
                    if (!(ann instanceof NormalAnnotationExpr normal))
                        return;
                    normal.getPairs().stream()
                            .filter(p -> p.getNameAsString().equals("body"))
                            .forEach(p -> {
                                String body = PermuteDeclrTransformer.stripQuotes(p.getValue().toString());
                                String expanded = body;
                                for (java.util.Map.Entry<String, String> entry : fragments.entrySet()) {
                                    expanded = expanded.replace("${" + entry.getKey() + "}", entry.getValue());
                                }
                                if (!expanded.equals(body)) {
                                    p.setValue(new com.github.javaparser.ast.expr.StringLiteralExpr(expanded));
                                }
                            });
                });
    }
}
