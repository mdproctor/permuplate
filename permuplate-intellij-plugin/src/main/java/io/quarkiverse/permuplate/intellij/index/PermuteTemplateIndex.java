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
 * Value: PermuteTemplateData{varName="i", from="3", to="10", generatedNames=["Join3"…], …}
 */
public class PermuteTemplateIndex extends FileBasedIndexExtension<String, PermuteTemplateData> {

    public static final ID<String, PermuteTemplateData> NAME =
            ID.create("permuplate.template.forward");

    private static final Set<String> MEMBER_ANNOTATION_SIMPLE_NAMES = Set.of(
            "PermuteDeclr", "PermuteParam", "PermuteTypeParam", "PermuteMethod"
    );

    @Override public @NotNull ID<String, PermuteTemplateData> getName() { return NAME; }

    @Override
    public @NotNull DataIndexer<String, PermuteTemplateData, FileContent> getIndexer() {
        return inputData -> {
            Map<String, PermuteTemplateData> result = new HashMap<>();

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

    /** Cheap substring check — avoids PSI for 99% of project files. */
    private static boolean containsPermute(CharSequence text) {
        String s = text.toString();
        return s.contains("@Permute") || s.contains("@io.quarkiverse.permuplate.Permute");
    }

    /**
     * Find @Permute by simple name via source text reference element.
     * Avoids getQualifiedName() / getAnnotation(fqn) which trigger FQN resolution
     * and can cause recursive index reads from within the indexer.
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
     * Reads an integer-valued annotation attribute. Handles both:
     * - Legacy int literals: {@code from = 3}
     * - Current String JEXL literals (plain integers only): {@code from = "3"}
     *
     * The {@code @Permute.from} and {@code @Permute.to} attributes changed from
     * {@code int} to {@code String} in issue #16 (expression-based ranges). This
     * method handles both forms so existing templates and future templates work.
     * JEXL expressions containing variables (e.g. {@code "${max}"}) are not
     * supported by the index — the default is returned for those cases.
     */
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
     *  @PermuteTypeParam / @PermuteMethod on class members.
     *  Uses simple name matching to avoid FQN resolution in the indexer. */
    private static List<String> collectMemberAnnotationStrings(PsiClass cls) {
        List<String> strings = new ArrayList<>();
        for (PsiMember member : getAllMembers(cls)) {
            for (PsiAnnotation ann : member.getAnnotations()) {
                PsiJavaCodeReferenceElement ref = ann.getNameReferenceElement();
                if (ref == null || !MEMBER_ANNOTATION_SIMPLE_NAMES.contains(ref.getReferenceName())) continue;
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

    @Override public int getVersion() { return 4; } // bumped: from/to now parsed as String (issue #16)

    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return (VirtualFile file) -> "java".equals(file.getExtension());
    }

    @Override public boolean dependsOnFileContent() { return true; }
}
