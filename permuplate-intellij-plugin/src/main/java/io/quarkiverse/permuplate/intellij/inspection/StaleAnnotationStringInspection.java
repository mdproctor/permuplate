package io.quarkiverse.permuplate.intellij.inspection;

import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Detects annotation strings that no longer match their target after a rename.
 * Uses AnnotationStringAlgorithm.matches() — returns false when static literals
 * don't appear as substrings in the target name.
 *
 * Example: className="Bar${i}" on class Join2 → "Bar" not in "Join2" → stale.
 */
public class StaleAnnotationStringInspection extends LocalInspectionTool {

    @Override
    public @NotNull String getShortName() {
        return "PermuteStaleAnnotationString";
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
                    if (template.hasNoLiteral()) continue; // no literals to check

                    if (!AnnotationStringAlgorithm.matches(template, targetName)) {
                        holder.registerProblem(lit,
                                "Permuplate: annotation string '" + s + "' does not match '"
                                + targetName + "' — may be stale after a rename",
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
