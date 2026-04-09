package io.quarkiverse.permuplate.intellij.index;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

public final class PermuteElementResolver {

    private PermuteElementResolver() {}

    /**
     * Strip trailing digits. "c3" → "c", "join2" → "join", "Join10" → "Join".
     */
    public static String stripTrailingDigits(@NotNull String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return name.substring(0, i);
    }

    /**
     * Find the template PsiClass for a given generated class name.
     * Fast path: FileBasedIndex reverse lookup via PermuteFileDetector.
     * Fallback: PSI scan (used when index is not yet populated, e.g. in tests).
     */
    @Nullable
    public static PsiClass findTemplateClass(@NotNull String generatedName,
                                              @NotNull Project project) {
        // Fast path
        String templateName = PermuteFileDetector.templateNameFor(generatedName, project);
        if (templateName != null) {
            PermuteTemplateData data = PermuteFileDetector.templateDataFor(templateName, project);
            if (data != null) {
                VirtualFile vFile = LocalFileSystem.getInstance()
                        .findFileByPath(data.templateFilePath);
                if (vFile != null) {
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
                    if (psiFile instanceof PsiJavaFile javaFile) {
                        for (PsiClass cls : javaFile.getClasses()) {
                            if (templateName.equals(cls.getName())) return cls;
                        }
                    }
                }
            }
        }

        // Fallback: PSI scan
        PsiManager psiManager = PsiManager.getInstance(project);
        AtomicReference<PsiClass> found = new AtomicReference<>();

        ProjectFileIndex.getInstance(project).iterateContent(vFile -> {
            if (!vFile.getName().endsWith(".java")) return true;
            PsiFile psiFile = psiManager.findFile(vFile);
            if (!(psiFile instanceof PsiJavaFile javaFile)) return true;

            for (PsiClass cls : javaFile.getClasses()) {
                if (cls.isAnnotationType()) continue;
                for (PsiAnnotation ann : cls.getAnnotations()) {
                    PsiJavaCodeReferenceElement ref = ann.getNameReferenceElement();
                    if (ref == null || !"Permute".equals(ref.getReferenceName())) continue;

                    PsiAnnotationMemberValue varVal = ann.findAttributeValue("varName");
                    PsiAnnotationMemberValue classVal = ann.findAttributeValue("className");
                    PsiAnnotationMemberValue fromVal = ann.findAttributeValue("from");
                    PsiAnnotationMemberValue toVal = ann.findAttributeValue("to");

                    String varName = varVal instanceof PsiLiteralExpression vlit
                            && vlit.getValue() instanceof String vs ? vs : null;
                    String className = classVal instanceof PsiLiteralExpression clit
                            && clit.getValue() instanceof String cs ? cs : null;
                    int from = fromVal instanceof PsiLiteralExpression flit
                            && flit.getValue() instanceof Integer fi ? fi : 1;
                    int to = toVal instanceof PsiLiteralExpression tlit
                            && tlit.getValue() instanceof Integer ti ? ti : 1;

                    if (varName == null || className == null) continue;
                    String placeholder = "${" + varName + "}";
                    for (int v = from; v <= to; v++) {
                        if (generatedName.equals(className.replace(placeholder, String.valueOf(v)))) {
                            found.set(cls);
                            return false;
                        }
                    }
                }
            }
            return true;
        });

        return found.get();
    }
}
