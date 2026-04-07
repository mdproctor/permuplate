package io.quarkiverse.permuplate.intellij.navigation;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import io.quarkiverse.permuplate.intellij.index.PermuteFileDetector;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Extends Find Usages to include all permutation siblings.
 * When find-usages runs on a method/field in a template class,
 * results include the same-named elements in all generated family members.
 */
public class PermuteFamilyFindUsagesHandlerFactory extends FindUsagesHandlerFactory {

    @Override
    public boolean canFindUsages(@NotNull PsiElement element) {
        if (!(element instanceof PsiMember member)) return false;
        PsiClass cls = member.getContainingClass();
        if (cls == null) return false;
        return PermuteFileDetector.isTemplate(cls);
    }

    @Override
    public @Nullable FindUsagesHandler createFindUsagesHandler(
            @NotNull PsiElement element, boolean forHighlightUsages) {
        if (!(element instanceof PsiMember member)) return null;
        PsiClass cls = member.getContainingClass();
        if (cls == null) return null;

        String templateName = cls.getName();
        if (templateName == null) return null;

        PermuteTemplateData data = PermuteFileDetector.templateDataFor(
                templateName, element.getProject());
        if (data == null) return null;

        List<PsiElement> additionalElements = new ArrayList<>();
        if (!(element instanceof PsiNamedElement named)) return null;
        String memberName = named.getName();
        if (memberName == null) return null;

        GlobalSearchScope scope = GlobalSearchScope.projectScope(element.getProject());
        for (String generatedName : data.generatedNames) {
            PsiClass[] classes = PsiShortNamesCache.getInstance(element.getProject())
                    .getClassesByName(generatedName, scope);
            for (PsiClass generatedClass : classes) {
                PsiElement sibling = findMatchingMember(generatedClass, memberName, element);
                if (sibling != null) additionalElements.add(sibling);
            }
        }

        if (additionalElements.isEmpty()) return null;

        return new FindUsagesHandler(element) {
            @Override
            public PsiElement[] getSecondaryElements() {
                return additionalElements.toArray(PsiElement.EMPTY_ARRAY);
            }
        };
    }

    @Nullable
    private static PsiElement findMatchingMember(PsiClass cls, String memberName, PsiElement original) {
        String baseName = stripTrailingDigits(memberName);
        if (original instanceof PsiMethod) {
            for (PsiMethod m : cls.getMethods()) {
                if (m.getName().equals(memberName) || m.getName().startsWith(baseName)) return m;
            }
        } else if (original instanceof PsiField) {
            for (PsiField f : cls.getFields()) {
                if (memberName.equals(f.getName())
                        || (f.getName() != null && f.getName().startsWith(baseName))) return f;
            }
        }
        return null;
    }

    private static String stripTrailingDigits(String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return name.substring(0, i);
    }
}
