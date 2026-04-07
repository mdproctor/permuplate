package io.quarkiverse.permuplate.intellij.inspection;

import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import io.quarkiverse.permuplate.ide.ValidationError;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Validates Permuplate annotation string attributes using AnnotationStringAlgorithm.validate().
 * Surfaces R2 (unmatched literal), R3 (orphan variable), R4 (no anchor) errors inline.
 *
 * Validates "name" and "className" attributes only (not "type" — may reference external family).
 * Target: for "className" → containing class name; for "name" → field/param/method identifier.
 */
public class AnnotationStringInspection extends LocalInspectionTool {

    @Override
    public @NotNull String getShortName() {
        return "PermuteAnnotationString";
    }

    private static final Set<String> ALL_ANNOTATION_SIMPLE_NAMES = Set.of(
            "Permute", "PermuteDeclr", "PermuteParam", "PermuteTypeParam", "PermuteMethod"
    );

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                String fqn = annotation.getQualifiedName();
                if (fqn == null) return;
                // Match by simple name suffix — works even when annotations are unresolved
                boolean isPermuplate = ALL_ANNOTATION_SIMPLE_NAMES.stream()
                        .anyMatch(n -> fqn.equals(n) || fqn.endsWith("." + n));
                if (!isPermuplate) return;

                for (PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
                    if (!(pair.getValue() instanceof PsiLiteralExpression lit)) continue;
                    if (!(lit.getValue() instanceof String s) || s.isEmpty()) continue;

                    String attrName = pair.getAttributeName();
                    if (!"name".equals(attrName) && !"className".equals(attrName)) continue;

                    String targetName = resolveTargetName(annotation, attrName);
                    if (targetName == null) continue;

                    AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(s);
                    List<ValidationError> errors = AnnotationStringAlgorithm.validate(template, targetName);

                    for (ValidationError error : errors) {
                        holder.registerProblem(lit,
                                "Permuplate: " + error.suggestion(),
                                ProblemHighlightType.WARNING);
                    }
                }
            }
        };
    }

    @Nullable
    private static String resolveTargetName(@NotNull PsiAnnotation annotation, @NotNull String attrName) {
        PsiElement owner = annotation.getParent();
        if (owner instanceof PsiModifierList) owner = owner.getParent();

        if ("className".equals(attrName) && owner instanceof PsiClass cls) return cls.getName();
        if ("name".equals(attrName)) {
            if (owner instanceof PsiField f) return f.getName();
            if (owner instanceof PsiParameter p) return p.getName();
            if (owner instanceof PsiMethod m) return m.getName();
        }
        return null;
    }
}
