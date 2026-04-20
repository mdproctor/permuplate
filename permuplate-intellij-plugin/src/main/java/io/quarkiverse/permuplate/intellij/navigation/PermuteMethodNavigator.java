package io.quarkiverse.permuplate.intellij.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.indexing.FileBasedIndex;
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import io.quarkiverse.permuplate.intellij.index.PermuteElementResolver;
import io.quarkiverse.permuplate.intellij.index.PermuteFileDetector;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateData;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateIndex;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Two navigation features:
 * 1. Ctrl+click on element in a generated file → navigate to template
 * 2. Ctrl+click on a Permuplate annotation string literal → navigate to referenced template class
 */
public class PermuteMethodNavigator implements GotoDeclarationHandler {

    private static final Set<String> ALL_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.Permute",
            "io.quarkiverse.permuplate.PermuteDeclr",
            "io.quarkiverse.permuplate.PermuteParam",
            "io.quarkiverse.permuplate.PermuteTypeParam",
            "io.quarkiverse.permuplate.PermuteMethod",
            "io.quarkiverse.permuplate.PermuteVar",
            "io.quarkiverse.permuplate.PermuteReturn",
            "io.quarkiverse.permuplate.PermuteExtends",
            "io.quarkiverse.permuplate.PermuteSwitchArm"
    );

    @Override
    public @Nullable PsiElement[] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null) return null;

        // Feature 1: annotation string literal → referenced template class
        PsiElement[] stringTargets = resolveAnnotationStringLiteral(sourceElement);
        if (stringTargets != null) return stringTargets;

        // Feature 2: element in generated file → template class/method
        PsiElement[] generatedTargets = resolveGeneratedElementToTemplate(sourceElement);
        if (generatedTargets != null) return generatedTargets;

        return null; // fall through to IntelliJ default
    }

    /**
     * Resolves an annotation string literal to the referenced template class.
     * E.g., clicking on "Callable" in type="Callable${i}" navigates to Callable2.
     */
    @Nullable
    private PsiElement[] resolveAnnotationStringLiteral(PsiElement element) {
        if (!(element instanceof PsiJavaToken token)) return null;
        if (!(token.getParent() instanceof PsiLiteralExpression lit)) return null;
        if (!(lit.getValue() instanceof String s)) return null;
        if (!(lit.getParent() instanceof PsiNameValuePair)) return null;

        PsiElement parent = lit.getParent().getParent();
        if (!(parent instanceof PsiAnnotationParameterList)) return null;

        PsiAnnotation ann = (PsiAnnotation) parent.getParent();
        String fqn = ann.getQualifiedName();
        if (fqn == null) return null;

        // Check if this is a Permuplate annotation
        boolean isPermuplate = ALL_ANNOTATION_FQNS.contains(fqn)
                || ALL_ANNOTATION_FQNS.stream().anyMatch(f -> f.endsWith("." + fqn));
        if (!isPermuplate) return null;

        // Parse the annotation string template and extract the leading literal
        AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(s);
        List<String> staticLiterals = template.staticLiterals();
        if (staticLiterals.isEmpty()) return null;

        String familyLiteral = staticLiterals.get(0);
        if (familyLiteral.isEmpty()) return null;

        PsiClass resolved = findTemplateClassByLiteral(familyLiteral, element);
        return resolved != null ? new PsiElement[]{resolved} : null;
    }

    /**
     * Resolves an element in a generated file to its corresponding template class/method.
     * For example, if Join3.java is in target/generated-sources/,
     * Ctrl+click on Join3 navigates to Join2 (the template).
     */
    @Nullable
    private PsiElement[] resolveGeneratedElementToTemplate(PsiElement element) {
        PsiFile containingFile = element.getContainingFile();
        if (containingFile == null) return null;

        VirtualFile vFile = containingFile.getVirtualFile();
        if (vFile == null || !PermuteFileDetector.isGeneratedFile(vFile)) return null;

        if (!(containingFile instanceof PsiJavaFile jf)) return null;
        if (jf.getClasses().length == 0) return null;

        String generatedClassName = jf.getClasses()[0].getName();
        if (generatedClassName == null) return null;

        // Find the template class name that generated this class
        String templateName = PermuteFileDetector.templateNameFor(
                generatedClassName, element.getProject());
        if (templateName == null) return null;

        // Look up the template's file path
        PermuteTemplateData data = PermuteFileDetector.templateDataFor(
                templateName, element.getProject());
        if (data == null) return null;

        // Load the template file and find the class
        VirtualFile templateVFile = LocalFileSystem.getInstance()
                .findFileByPath(data.templateFilePath);
        if (templateVFile == null) return null;

        PsiFile templateFile = PsiManager.getInstance(element.getProject()).findFile(templateVFile);
        if (!(templateFile instanceof PsiJavaFile templateJf)) return null;
        if (templateJf.getClasses().length == 0) return null;

        PsiClass templateClass = templateJf.getClasses()[0];

        // If the user clicked on a method or field, try to find the corresponding one in the template
        PsiElement namedAncestor = findNamedAncestor(element);
        if (namedAncestor instanceof PsiMethod method && method.getName() != null) {
            String baseName = stripTrailingDigits(method.getName());
            for (PsiMethod m : templateClass.getMethods()) {
                if (m.getName() != null && m.getName().startsWith(baseName)) {
                    return new PsiElement[]{m};
                }
            }
        } else if (namedAncestor instanceof PsiField field && field.getName() != null) {
            String baseName = stripTrailingDigits(field.getName());
            for (PsiField f : templateClass.getFields()) {
                if (f.getName() != null && f.getName().startsWith(baseName)) {
                    return new PsiElement[]{f};
                }
            }
        }

        // Default: navigate to the template class itself
        return new PsiElement[]{templateClass};
    }

    /**
     * Finds a template class by its leading literal (e.g., "Callable" for "Callable2").
     * Searches the PermuteTemplateIndex for keys starting with the literal.
     */
    @Nullable
    private PsiClass findTemplateClassByLiteral(String literal, PsiElement context) {
        FileBasedIndex index = FileBasedIndex.getInstance();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(context.getProject());
        List<PsiClass> candidates = new ArrayList<>();

        // Process all keys in the forward index (template class names)
        index.processAllKeys(PermuteTemplateIndex.NAME, templateName -> {
            // Check if the template name starts with the literal (e.g., "Callable2" starts with "Callable")
            if (templateName.startsWith(literal)) {
                // Find the actual PsiClass by name
                PsiClass[] classes = PsiShortNamesCache.getInstance(context.getProject())
                        .getClassesByName(templateName, scope);
                for (PsiClass cls : classes) {
                    // Verify it's actually a template (has @Permute annotation)
                    if (PermuteFileDetector.isTemplate(cls)) {
                        candidates.add(cls);
                    }
                }
            }
            return true;
        }, context.getProject());

        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Finds the nearest enclosing PsiMethod or PsiField for the given element.
     */
    @Nullable
    private static PsiElement findNamedAncestor(PsiElement element) {
        PsiElement current = element;
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof PsiMethod || current instanceof PsiField) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Strips all trailing digits from a string.
     * E.g., "join2" → "join", "c3" → "c".
     */
    private static String stripTrailingDigits(String name) {
        return PermuteElementResolver.stripTrailingDigits(name);
    }
}
