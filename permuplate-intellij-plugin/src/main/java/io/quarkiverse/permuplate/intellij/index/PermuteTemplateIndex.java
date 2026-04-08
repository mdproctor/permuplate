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
            "io.quarkiverse.permuplate.Permute";
    private static final String PERMUTE_SIMPLE = "Permute";
    private static final Set<String> MEMBER_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.PermuteDeclr",
            "io.quarkiverse.permuplate.PermuteParam",
            "io.quarkiverse.permuplate.PermuteTypeParam",
            "io.quarkiverse.permuplate.PermuteMethod"
    );
    private static final Set<String> MEMBER_ANNOTATION_SIMPLE_NAMES = Set.of(
            "PermuteDeclr", "PermuteParam", "PermuteTypeParam", "PermuteMethod"
    );

    @Override public @NotNull ID<String, PermuteTemplateData> getName() { return NAME; }

    @Override
    public @NotNull DataIndexer<String, PermuteTemplateData, FileContent> getIndexer() {
        return inputData -> {
            Map<String, PermuteTemplateData> result = new HashMap<>();
            PsiFile psiFile = inputData.getPsiFile();
            if (!(psiFile instanceof PsiJavaFile javaFile)) return result;

            for (PsiClass cls : javaFile.getClasses()) {
                if (cls.isAnnotationType()) continue; // skip annotation declarations (e.g. Permute.java itself)
                PsiAnnotation permute = findAnnotation(cls, PERMUTE_FQN, PERMUTE_SIMPLE);
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

    /**
     * Find an annotation on the given element by FQN, with fallback to simple name.
     * The fallback handles projects where the annotation JAR is not yet on the
     * compile classpath — PSI cannot resolve the import to a FQN in that case,
     * so {@code getQualifiedName()} returns only the simple name.
     */
    private static @org.jetbrains.annotations.Nullable PsiAnnotation findAnnotation(
            PsiModifierListOwner owner, String fqn, String simpleName) {
        PsiAnnotation direct = owner.getAnnotation(fqn);
        if (direct != null) return direct;
        // Fallback: scan annotations by name string to handle unresolved imports
        for (PsiAnnotation ann : owner.getAnnotations()) {
            String name = ann.getQualifiedName();
            if (fqn.equals(name) || simpleName.equals(name)) return ann;
        }
        return null;
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
                String annName = ann.getQualifiedName();
                if (!MEMBER_ANNOTATION_FQNS.contains(annName)
                        && !MEMBER_ANNOTATION_SIMPLE_NAMES.contains(annName)) continue;
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

    @Override public int getVersion() { return 2; }

    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return (VirtualFile file) -> "java".equals(file.getExtension());
    }

    @Override public boolean dependsOnFileContent() { return true; }
}
