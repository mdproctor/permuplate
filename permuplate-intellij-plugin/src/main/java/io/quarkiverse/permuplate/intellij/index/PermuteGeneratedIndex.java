package io.quarkiverse.permuplate.intellij.index;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Reverse index: generated class simple name → template class simple name.
 * Key:   "Join4"
 * Value: "Join2"
 *
 * Emitted by scanning the same @Permute annotations as PermuteTemplateIndex
 * and expanding generatedNames. One file scan, two indexes.
 */
public class PermuteGeneratedIndex extends FileBasedIndexExtension<String, String> {

    public static final ID<String, String> NAME =
            ID.create("permuplate.template.reverse");

    private static final String PERMUTE_FQN =
            "io.quarkiverse.permuplate.annotations.Permute";

    @Override public @NotNull ID<String, String> getName() { return NAME; }

    @Override
    public @NotNull DataIndexer<String, String, FileContent> getIndexer() {
        return inputData -> {
            Map<String, String> result = new HashMap<>();
            PsiFile psiFile = inputData.getPsiFile();
            if (!(psiFile instanceof PsiJavaFile javaFile)) return result;

            for (PsiClass cls : javaFile.getClasses()) {
                PsiAnnotation permute = cls.getAnnotation(PERMUTE_FQN);
                if (permute == null) continue;

                String templateName = cls.getName();
                if (templateName == null) continue;

                String varName   = getStringAttr(permute, "varName");
                int    from      = getIntAttr(permute, "from", 1);
                int    to        = getIntAttr(permute, "to", 1);
                String className = getStringAttr(permute, "className");
                if (varName == null || className == null) continue;

                String placeholder = "${" + varName + "}";
                for (int v = from; v <= to; v++) {
                    String generatedName = className.replace(placeholder, String.valueOf(v));
                    result.put(generatedName, templateName);
                }
            }
            return result;
        };
    }

    private static String getStringAttr(PsiAnnotation ann, String attr) {
        PsiAnnotationMemberValue v = ann.findAttributeValue(attr);
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
        return null;
    }

    private static int getIntAttr(PsiAnnotation ann, String attr, int defaultVal) {
        PsiAnnotationMemberValue v = ann.findAttributeValue(attr);
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof Integer i) return i;
        return defaultVal;
    }

    @Override public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override public @NotNull DataExternalizer<String> getValueExternalizer() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override public int getVersion() { return 1; }

    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return (VirtualFile file) -> "java".equals(file.getExtension());
    }

    @Override public boolean dependsOnFileContent() { return true; }
}
