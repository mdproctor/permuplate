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
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.core.PermuteCaseTransformer;
import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.core.PermuteParamTransformer;
import io.quarkiverse.permuplate.core.PermuteStatementsTransformer;
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
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
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
        ClassOrInterfaceDeclaration outputParent = outputCu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> !c.isNestedType())
                .orElseThrow(() -> new IllegalStateException("Cannot find top-level class in parent CU"));

        // Remove the template nested type from the output (class, interface, or record)
        String templateClassName = templateClassDecl.getNameAsString();
        outputParent.getMembers().removeIf(member -> member instanceof TypeDeclaration<?> td &&
                td.getNameAsString().equals(templateClassName));

        // Re-add the template type if keepTemplate = true (strip all permuplate annotations)
        if (config.keepTemplate) {
            TypeDeclaration<?> templateCopy = templateClassDecl.clone();
            stripPermuteAnnotations(templateCopy);
            outputParent.addMember(templateCopy);
        }

        boolean isRecord = templateClassDecl instanceof RecordDeclaration;

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

        // Generate and append each permuted nested type
        for (Map<String, Object> vars : filteredCombinations) {
            EvaluationContext ctx = new EvaluationContext(vars);

            TypeDeclaration<?> generated = templateClassDecl.clone();

            // Rename the generated nested type
            String newClassName = ctx.evaluate(config.className);
            generated.setName(newClassName);
            // Rename constructors (only applies to class/record, not interface)
            generated.getConstructors().forEach(ctor -> ctor.setName(newClassName));

            // Apply transformations (null messager — Maven plugin has no Messager)
            PermuteTypeParamTransformer.transform(generated, ctx, null, null);

            if (!isRecord) {
                ClassOrInterfaceDeclaration coid = (ClassOrInterfaceDeclaration) generated;

                // Capture post-G1 type parameter names for extends expansion
                List<String> postG1TypeParams = new ArrayList<>();
                coid.getTypeParameters().forEach(tp -> postG1TypeParams.add(tp.getNameAsString()));

                // @PermuteMethod: generate overloads with (i,j) context — before other transforms
                applyPermuteMethod(coid, ctx, config, vars, allGeneratedNames);

                PermuteDeclrTransformer.transform(generated, ctx, null);
                PermuteParamTransformer.transform(generated, ctx, null);

                // Apply @PermuteReturn — explicit override; boundary omission
                Set<String> explicitReturnMethods = collectExplicitReturnMethodNames(coid);
                applyPermuteReturn(coid, ctx, allGeneratedNames);

                // Apply implicit return type + parameter type inference (Mechanism 1)
                applyImplicitInference(coid, ctx, allGeneratedNames, explicitReturnMethods);

                // @PermuteCase — expand switch statement cases per permutation
                PermuteCaseTransformer.transform(generated, ctx);

                // @PermuteValue — replace field initializers and method statement RHS
                PermuteValueTransformer.transform(generated, ctx);

                // @PermuteStatements — insert accumulated statements into method bodies
                PermuteStatementsTransformer.transform(generated, ctx);

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

                // @PermuteExtends — explicit override of extends/implements clause.
                boolean permuteExtendsApplied = hasPermuteExtendsAnnotation(coid);
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
            } else {
                // Record path: apply param + declr transforms (no extends, no methods/return/case)
                PermuteDeclrTransformer.transform(generated, ctx, null);
                PermuteParamTransformer.transform(generated, ctx, null);
                PermuteValueTransformer.transform(generated, ctx);
            }

            // Strip @Permute, @PermuteFilter, @PermuteFilters
            generated.getAnnotations().removeIf(a -> {
                String n = a.getNameAsString();
                return n.equals("Permute") || n.equals("io.quarkiverse.permuplate.Permute")
                        || n.equals("PermuteFilter") || n.equals("io.quarkiverse.permuplate.PermuteFilter")
                        || n.equals("PermuteFilters") || n.equals("io.quarkiverse.permuplate.PermuteFilters");
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

            // Evaluate from
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

            toRemove.add(method);

            // Collect declared class type parameter names for undeclared-var detection
            Set<String> declaredTypeParams = new java.util.LinkedHashSet<>();
            classDecl.getTypeParameters().forEach(tp -> declaredTypeParams.add(tp.getNameAsString()));

            for (int j = fromVal; j <= toVal; j++) {
                EvaluationContext innerCtx = ctx.withVariable(pmCfg.varName(), j);
                MethodDeclaration clone = method.clone();

                // Strip @PermuteMethod from clone
                clone.getAnnotations()
                        .removeIf(a -> a.getNameAsString().equals(PM_SIMPLE) || a.getNameAsString().equals(PM_FQ));

                // Expand method-level @PermuteTypeParam with the (i,k) inner context
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

                // If boundary omission removed the method, skip this j value
                if (tmpClass.getMethods().isEmpty()) {
                    continue;
                }

                // Retrieve the (possibly @PermuteReturn-updated) clone
                clone = tmpClass.getMethods().get(0);

                // Implicit j-based expansion: expand undeclared T+number vars and embedded
                // class-name numbers by (j-1), so that j=1 is a no-op and j>1 expands the tip.
                // This fires only on types NOT already handled by @PermuteReturn.
                String methodKey = clone.getNameAsString() + clone.getParameters().toString();
                if (!explicitMethods.contains(methodKey)) {
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

                // Apply name template if set
                if (pmCfg.hasName()) {
                    try {
                        clone.setName(innerCtx.evaluate(pmCfg.name()));
                    } catch (Exception ignored) {
                    }
                }

                toAdd.add(clone);
            }
        });

        toRemove.forEach(m -> classDecl.getMembers().removeIf(member -> member == m));
        toAdd.forEach(classDecl::addMember);
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

    private static java.util.List<String> collectInlinePermuteImports(
            ClassOrInterfaceDeclaration classDecl, EvaluationContext ctx) {
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
     * Strips all Permuplate annotations ({@code @Permute}, {@code @PermuteDeclr},
     * {@code @PermuteParam}) from a type declaration and all of its members,
     * including field-level and parameter-level annotations.
     */
    private static void stripPermuteAnnotations(TypeDeclaration<?> classDecl) {
        Set<String> PERMUPLATE_ANNOTATIONS = Set.of(
                "Permute", "io.quarkiverse.permuplate.Permute",
                "PermuteDeclr", "io.quarkiverse.permuplate.PermuteDeclr",
                "PermuteParam", "io.quarkiverse.permuplate.PermuteParam",
                "PermuteTypeParam", "io.quarkiverse.permuplate.PermuteTypeParam",
                "PermuteReturn", "io.quarkiverse.permuplate.PermuteReturn",
                "PermuteMethod", "io.quarkiverse.permuplate.PermuteMethod",
                "PermuteExtends", "io.quarkiverse.permuplate.PermuteExtends",
                "PermuteConst", "io.quarkiverse.permuplate.PermuteConst",
                "PermuteCase", "io.quarkiverse.permuplate.PermuteCase",
                "PermuteValue", "io.quarkiverse.permuplate.PermuteValue",
                "PermuteStatements", "io.quarkiverse.permuplate.PermuteStatements",
                "PermuteImport", "io.quarkiverse.permuplate.PermuteImport",
                "PermuteImports", "io.quarkiverse.permuplate.PermuteImports");

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

        // Strip TYPE_USE annotations from ObjectCreationExpr types (e.g. new @PermuteDeclr(...) Join3First<>())
        classDecl.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)
                .forEach(newExpr -> newExpr.getType().getAnnotations()
                        .removeIf(a -> PERMUPLATE_ANNOTATIONS.contains(a.getNameAsString())));
    }
}
