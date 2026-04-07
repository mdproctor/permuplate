package io.quarkiverse.permuplate.intellij.inspection;

import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Warns on @PermuteMethod when boundary evaluation will silently omit the method
 * from the first generated class(es).
 *
 * Detects the common leaf-node pattern: to="${i-N}" where N > 0.
 * When outer loop variable i equals outerFrom (first generated class), from > to → method omitted.
 *
 * Severity: WARNING (intentional boundary omission is valid; add when="true" to override).
 */
public class BoundaryOmissionInspection extends LocalInspectionTool {

    @Override
    public @NotNull String getShortName() {
        return "PermuteBoundaryOmission";
    }

    // Matches to="${i-N}" or to="${varName-N}" patterns
    private static final Pattern SUBTRACTION_PATTERN =
            Pattern.compile("\\$\\{\\s*\\w+\\s*-\\s*(\\d+)\\s*\\}");

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                String fqn = annotation.getQualifiedName();
                if (fqn == null) return;
                boolean isPermuteMethod = "PermuteMethod".equals(fqn) || fqn.endsWith(".PermuteMethod");
                if (!isPermuteMethod) return;

                PsiAnnotationMemberValue toValue = annotation.findAttributeValue("to");
                if (!(toValue instanceof PsiLiteralExpression lit)) return;
                if (!(lit.getValue() instanceof String toStr)) return;

                Matcher m = SUBTRACTION_PATTERN.matcher(toStr);
                if (!m.find()) return;

                int subtracted = Integer.parseInt(m.group(1));

                // Find the outer @Permute annotation to get the loop's from value
                PsiElement owner = annotation.getParent();
                if (owner instanceof PsiModifierList) owner = owner.getParent();
                if (!(owner instanceof PsiMethod method)) return;
                PsiClass cls = method.getContainingClass();
                if (cls == null) return;

                int outerFrom = 1;
                for (PsiAnnotation a : cls.getAnnotations()) {
                    String afqn = a.getQualifiedName();
                    if (afqn == null) continue;
                    if (!"Permute".equals(afqn) && !afqn.endsWith(".Permute")) continue;
                    PsiAnnotationMemberValue fromVal = a.findAttributeValue("from");
                    if (fromVal instanceof PsiLiteralExpression fl
                            && fl.getValue() instanceof Integer fi) {
                        outerFrom = fi;
                    }
                    break;
                }

                // Boundary omission when outer i = outerFrom and to = outerFrom - subtracted < from (1)
                if (subtracted >= outerFrom) {
                    holder.registerProblem(annotation,
                            "Permuplate: this method will be omitted from the first " + subtracted
                            + " generated class(es) due to empty inner range (from > to). "
                            + "Add when=\"true\" to override.",
                            ProblemHighlightType.WARNING);
                }
            }
        };
    }
}
