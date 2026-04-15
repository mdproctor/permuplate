package io.quarkiverse.permuplate.intellij.index;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.PsiJavaCodeReferenceElement;
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

    @Override public @NotNull ID<String, String> getName() { return NAME; }

    @Override
    public @NotNull DataIndexer<String, String, FileContent> getIndexer() {
        return inputData -> {
            Map<String, String> result = new HashMap<>();

            // Cheap text pre-filter — avoids PSI for files that can't possibly be templates
            CharSequence text = inputData.getContentAsText();
            if (!containsPermute(text)) return result;

            PsiFile psiFile = inputData.getPsiFile();
            if (!(psiFile instanceof PsiJavaFile javaFile)) return result;

            for (PsiClass cls : javaFile.getClasses()) {
                if (cls.isAnnotationType()) continue;
                PsiAnnotation permute = findPermuteAnnotation(cls);
                if (permute == null) continue;

                String templateName = cls.getName();
                if (templateName == null) continue;

                String varName   = getStringAttr(permute, "varName");
                String className = getStringAttr(permute, "className");
                if (varName == null || className == null) continue;

                String placeholder = "${" + varName + "}";
                String[] values = getStringArrayAttr(permute, "values");
                if (values.length > 0) {
                    for (String v : values) {
                        result.put(className.replace(placeholder, v), templateName);
                    }
                } else {
                    int from = getIntAttr(permute, "from", 1);
                    int to   = getIntAttr(permute, "to",   1);
                    for (int v = from; v <= to; v++) {
                        result.put(className.replace(placeholder, String.valueOf(v)), templateName);
                    }
                }
            }
            return result;
        };
    }

    /** Cheap substring check — avoids PSI for 99% of project files. */
    private static boolean containsPermute(CharSequence text) {
        String s = text.toString();
        return s.contains("@Permute") || s.contains("@io.quarkiverse.permuplate.Permute");
    }

    /**
     * Find @Permute by simple name via source text — avoids FQN resolution
     * which would trigger recursive index reads from within the indexer.
     */
    @org.jetbrains.annotations.Nullable
    private static PsiAnnotation findPermuteAnnotation(PsiClass cls) {
        for (PsiAnnotation ann : cls.getAnnotations()) {
            PsiJavaCodeReferenceElement ref = ann.getNameReferenceElement();
            if (ref != null && "Permute".equals(ref.getReferenceName())) return ann;
        }
        return null;
    }

    private static String getStringAttr(PsiAnnotation ann, String attr) {
        PsiAnnotationMemberValue v = ann.findAttributeValue(attr);
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
        return null;
    }

    /**
     * Reads a String[] annotation attribute value from PSI.
     * Handles array form {@code values={"Byte","Short"}} and single-element form {@code values="Byte"}.
     * Returns an empty array if the attribute is absent or not a string literal.
     */
    private static String[] getStringArrayAttr(PsiAnnotation ann, String attr) {
        PsiAnnotationMemberValue v = ann.findAttributeValue(attr);
        if (v == null) return new String[0];
        if (v instanceof PsiArrayInitializerMemberValue arr) {
            java.util.List<String> vals = new java.util.ArrayList<>();
            for (PsiAnnotationMemberValue init : arr.getInitializers()) {
                if (init instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
                    vals.add(s);
                }
            }
            return vals.toArray(new String[0]);
        }
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
            return new String[]{ s };
        }
        return new String[0];
    }

    /** Handles both legacy int literals and current String JEXL literals (plain integers only).
     *  @Permute.from and @Permute.to changed from int to String in issue #16. */
    private static int getIntAttr(PsiAnnotation ann, String attr, int defaultVal) {
        PsiAnnotationMemberValue v = ann.findAttributeValue(attr);
        if (v instanceof PsiLiteralExpression lit) {
            Object value = lit.getValue();
            if (value instanceof Integer i) return i;
            if (value instanceof String s) {
                try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
            }
        }
        return defaultVal;
    }

    @Override public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override public @NotNull DataExternalizer<String> getValueExternalizer() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override public int getVersion() { return 5; } // bumped: string-set values[] support

    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return (VirtualFile file) -> "java".equals(file.getExtension());
    }

    @Override public boolean dependsOnFileContent() { return true; }
}
