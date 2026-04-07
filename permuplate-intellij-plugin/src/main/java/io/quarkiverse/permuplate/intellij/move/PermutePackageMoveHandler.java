package io.quarkiverse.permuplate.intellij.move;

import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler;
import com.intellij.usageView.UsageInfo;
import io.quarkiverse.permuplate.intellij.index.PermuteFileDetector;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * When a template Java file is moved to a new package, finds import references
 * to the generated family names so IntelliJ can update them in the same transaction.
 *
 * Generated files in target/ regenerate in the correct new package on next build.
 * This handler addresses the import-reference gap: callers that import generated
 * class names need their imports updated when the family moves.
 */
public class PermutePackageMoveHandler extends MoveFileHandler {

    @Override
    public boolean canProcessElement(@NotNull PsiFile element) {
        if (!(element instanceof PsiJavaFile jf) || jf.getClasses().length == 0) return false;
        return PermuteFileDetector.isTemplate(jf.getClasses()[0]);
    }

    @Override
    public void prepareMovedFile(PsiFile file, PsiDirectory moveDestination,
                                  Map<PsiElement, PsiElement> oldToNewMap) {}

    @Override
    public @Nullable List<UsageInfo> findUsages(@NotNull PsiFile psiFile,
                                                 @NotNull PsiDirectory newParent,
                                                 boolean searchInComments,
                                                 boolean searchInNonJavaFiles) {
        if (!(psiFile instanceof PsiJavaFile jf) || jf.getClasses().length == 0) return null;
        String templateName = jf.getClasses()[0].getName();
        if (templateName == null) return null;

        PermuteTemplateData data = PermuteFileDetector.templateDataFor(
                templateName, psiFile.getProject());
        if (data == null || data.generatedNames.isEmpty()) return null;

        List<UsageInfo> usages = new ArrayList<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(psiFile.getProject());

        for (String generatedName : data.generatedNames) {
            PsiClass[] classes = PsiShortNamesCache.getInstance(psiFile.getProject())
                    .getClassesByName(generatedName, scope);
            for (PsiClass cls : classes) {
                PsiReference[] refs = ReferencesSearch.search(cls, scope)
                        .toArray(PsiReference.EMPTY_ARRAY);
                for (PsiReference ref : refs) {
                    usages.add(new UsageInfo(ref));
                }
            }
        }

        return usages.isEmpty() ? null : usages;
    }

    @Override
    public void retargetUsages(@NotNull List<UsageInfo> usageInfos,
                                @NotNull Map<PsiElement, PsiElement> oldToNewMap) {}

    @Override
    public void updateMovedFile(@NotNull PsiFile file)
            throws com.intellij.util.IncorrectOperationException {}
}
