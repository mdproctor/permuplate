package io.quarkiverse.permuplate.intellij.navigation;

import com.intellij.find.findUsages.FindUsagesHandlerBase;
import com.intellij.find.findUsages.FindUsagesManager;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
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
 * Right-click action: "Find Usages in Permutation Family".
 * Shows a combined find-usages result for the selected member AND all its
 * counterparts across the permutation family (template + all generated siblings).
 *
 * Registered in EditorPopupMenu after the standard "Find Usages" entry.
 * Replaces the old PermuteFamilyFindUsagesHandlerFactory which hooked silently
 * into every find-usages call on template members.
 */
public class PermuteFamilyFindUsagesAction extends AnAction {

    public PermuteFamilyFindUsagesAction() {
        super("Find Usages in Permutation Family");
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiMember member = getTargetMember(e);
        e.getPresentation().setEnabledAndVisible(member != null && isInFamily(member));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        PsiMember member = getTargetMember(e);
        if (member == null) return;

        List<PsiElement> siblings = collectFamilySiblings(member);

        PsiElement[] primary = {member};
        PsiElement[] secondary = siblings.toArray(PsiElement.EMPTY_ARRAY);

        FindUsagesHandlerBase handler = new FindUsagesHandlerBase(member) {
            @Override
            public PsiElement[] getPrimaryElements() { return primary; }
            @Override
            public PsiElement[] getSecondaryElements() { return secondary; }
        };

        FindUsagesOptions options = FindUsagesHandlerBase.createFindUsagesOptions(project, null);
        options.isUsages = true;

        new FindUsagesManager(project).findUsages(primary, secondary, handler, options, false);
    }

    // --- helpers (package-private for testing) ---

    @Nullable
    static PsiMember getTargetMember(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (editor != null && file != null) {
            int offset = editor.getCaretModel().getOffset();
            PsiElement leaf = file.findElementAt(offset);
            PsiElement el = leaf;
            while (el != null && !(el instanceof PsiMember)) el = el.getParent();
            if (el instanceof PsiMember m) return m;
        }
        // Fallback: project-view selection
        PsiElement el = e.getData(CommonDataKeys.PSI_ELEMENT);
        return el instanceof PsiMember m ? m : null;
    }

    static boolean isInFamily(@NotNull PsiMember member) {
        PsiClass cls = member.getContainingClass();
        if (cls == null) return false;
        return PermuteFileDetector.isTemplate(cls) || PermuteFileDetector.isGenerated(cls);
    }

    /**
     * Collects counterpart members from all other classes in the permutation family.
     * The containing class (primary) is excluded; all other template + generated siblings
     * are included if a matching member can be found by name.
     */
    @NotNull
    static List<PsiElement> collectFamilySiblings(@NotNull PsiMember member) {
        PsiClass containingClass = member.getContainingClass();
        if (containingClass == null) return List.of();
        String containingName = containingClass.getName();
        if (containingName == null) return List.of();

        String memberName = member instanceof PsiNamedElement named ? named.getName() : null;
        if (memberName == null) return List.of();

        Project project = member.getProject();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        // Resolve template name and generated names for the full family
        String templateName;
        List<String> generatedNames;

        if (PermuteFileDetector.isTemplate(containingClass)) {
            PermuteTemplateData data = PermuteFileDetector.templateDataFor(containingName, project);
            if (data == null) return List.of();
            templateName = containingName;
            generatedNames = data.generatedNames;
        } else {
            // In a generated class — look up its template via the reverse index
            templateName = PermuteFileDetector.templateNameFor(containingName, project);
            if (templateName == null) return List.of();
            PermuteTemplateData data = PermuteFileDetector.templateDataFor(templateName, project);
            if (data == null) return List.of();
            generatedNames = data.generatedNames;
        }

        // All family class names except the one we're already in
        List<String> familyNames = new ArrayList<>();
        if (!containingName.equals(templateName)) familyNames.add(templateName);
        for (String gen : generatedNames) {
            if (!gen.equals(containingName)) familyNames.add(gen);
        }

        List<PsiElement> siblings = new ArrayList<>();
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        for (String familyName : familyNames) {
            for (PsiClass familyClass : cache.getClassesByName(familyName, scope)) {
                PsiElement sibling = findMatchingMember(familyClass, memberName, member);
                if (sibling != null) siblings.add(sibling);
            }
        }

        return siblings;
    }

    @Nullable
    private static PsiElement findMatchingMember(@NotNull PsiClass cls,
                                                  @NotNull String memberName,
                                                  @NotNull PsiMember original) {
        String baseName = stripTrailingDigits(memberName);
        if (original instanceof PsiMethod) {
            for (PsiMethod m : cls.getMethods()) {
                if (memberName.equals(m.getName()) || m.getName().startsWith(baseName)) return m;
            }
        } else if (original instanceof PsiField) {
            for (PsiField f : cls.getFields()) {
                if (memberName.equals(f.getName())
                        || (f.getName() != null && f.getName().startsWith(baseName))) return f;
            }
        }
        return null;
    }

    private static String stripTrailingDigits(@NotNull String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return name.substring(0, i);
    }
}
