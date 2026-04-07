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
 * Forward index: template class simple name → PermuteTemplateData.
 * Key:   "Join2"
 * Value: PermuteTemplateData{varName="i", from=3, to=10, generatedNames=["Join3"…], …}
 */
public class PermuteTemplateIndex extends FileBasedIndexExtension<String, PermuteTemplateData> {

    public static final ID<String, PermuteTemplateData> NAME =
            ID.create("permuplate.template.forward");

    private static final String PERMUTE_FQN =
            "io.quarkiverse.permuplate.annotations.Permute";
    private static final Set<String> MEMBER_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.annotations.PermuteDeclr",
            "io.quarkiverse.permuplate.annotations.PermuteParam",
            "io.quarkiverse.permuplate.annotations.PermuteTypeParam",
            "io.quarkiverse.permuplate.annotations.PermuteMethod"
    );

    @Override public @NotNull ID<String, PermuteTemplateData> getName() { return NAME; }

    @Override
    public @NotNull DataIndexer<String, PermuteTemplateData, FileContent> getIndexer() {
        return inputData -> {
            Map<String, PermuteTemplateData> result = new HashMap<>();
            PsiFile psiFile = inputData.getPsiFile();
            if (!(psiFile instanceof PsiJavaFile javaFile)) return result;

            for (PsiClass cls : javaFile.getClasses()) {
                PsiAnnotation permute = cls.getAnnotation(PERMUTE_FQN);
                if (permute == null) continue;

                String templateName = cls.getName();
                if (templateName == null) continue;

                String varName    = getStringAttr(permute, "varName");
                int    from       = getIntAttr(permute, "from", 1);
                int    to         = getIntAttr(permute, "to", 1);
                String className  = getStringAttr(permute, "className");
                if (varName == null || className == null) continue;

                List<String> generatedNames = computeGeneratedNames(varName, from, to, className);
                List<String> memberStrings  = collectMemberAnnotationStrings(cls);

                result.put(templateName, new PermuteTemplateData(
                        varName, from, to, className, generatedNames,
                        inputData.getFile().getPath(), memberStrings));
            }
            return result;
        };
    }

    // --- helpers ---

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

    /**
     * Simple variable substitution for the common case: "Join${i}" with i from 3 to 10.
     * Handles only single-variable integer substitution — sufficient for index purposes.
     */
    private static List<String> computeGeneratedNames(String varName, int from, int to, String template) {
        List<String> names = new ArrayList<>(to - from + 1);
        String placeholder = "${" + varName + "}";
        for (int v = from; v <= to; v++) {
            names.add(template.replace(placeholder, String.valueOf(v)));
        }
        return names;
    }

    /** Collect all string attribute values from @PermuteDeclr / @PermuteParam /
     *  @PermuteTypeParam / @PermuteMethod on class members. */
    private static List<String> collectMemberAnnotationStrings(PsiClass cls) {
        List<String> strings = new ArrayList<>();
        for (PsiMember member : getAllMembers(cls)) {
            for (PsiAnnotation ann : member.getAnnotations()) {
                if (!MEMBER_ANNOTATION_FQNS.contains(ann.getQualifiedName())) continue;
                for (PsiNameValuePair pair : ann.getParameterList().getAttributes()) {
                    if (pair.getValue() instanceof PsiLiteralExpression lit
                            && lit.getValue() instanceof String s
                            && !s.isEmpty()) {
                        strings.add(s);
                    }
                }
            }
        }
        return strings;
    }

    private static List<PsiMember> getAllMembers(PsiClass cls) {
        List<PsiMember> members = new ArrayList<>();
        members.addAll(Arrays.asList(cls.getFields()));
        members.addAll(Arrays.asList(cls.getMethods()));
        members.addAll(Arrays.asList(cls.getInnerClasses()));
        return members;
    }

    @Override public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override public @NotNull DataExternalizer<PermuteTemplateData> getValueExternalizer() {
        return PermuteTemplateDataExternalizer.INSTANCE;
    }

    @Override public int getVersion() { return 1; }

    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return (VirtualFile file) -> "java".equals(file.getExtension());
    }

    @Override public boolean dependsOnFileContent() { return true; }
}
