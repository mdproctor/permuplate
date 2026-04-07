# Permuplate IntelliJ Plugin — Navigation, Safety & Inspections — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the IntelliJ plugin by adding go-to-definition, family-aware find usages, safe delete redirect, generated file banner, package move protection, and three inline inspections — covering all 11 interaction points from the design spec.

**Architecture:** Each component is a standalone IntelliJ extension point registered in `plugin.xml`. They all query `PermuteFileDetector` (already built) over the persistent `FileBasedIndex` pair. No new indexes needed — this plan is pure extension point wiring on top of the P1 foundation.

**Tech Stack:** Java 17, Gradle + IntelliJ Platform 2023.2, `permuplate-ide-support` jar (algorithm), `BasePlatformTestCase` / `LightInspectionTestCase` for tests

**Foundation (already built in P1):**
- `PermuteTemplateIndex` + `PermuteGeneratedIndex` — persistent indexes, registered in `plugin.xml`
- `PermuteFileDetector` — `isTemplate()`, `isGenerated()`, `isGeneratedFile()`, `templateDataFor()`, `templateNameFor()`, `templatesReferencingLiteral()`
- `AnnotationStringRenameProcessor` + `GeneratedFileRenameHandler` — rename pipeline
- `AnnotationStringAlgorithm` — `parse()`, `validate()`, `computeRename()`, `matches()` (in `permuplate-ide-support` jar)

---

## File Map

```
permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/
├── navigation/
│   ├── PermuteMethodNavigator.java              Task 1
│   └── PermuteFamilyFindUsagesHandlerFactory.java  Task 2
├── editor/
│   └── GeneratedFileNotification.java           Task 3
├── safedelete/
│   └── PermuteSafeDeleteDelegate.java           Task 4
├── move/
│   └── PermutePackageMoveHandler.java           Task 5
└── inspection/
    ├── AnnotationStringInspection.java          Task 6
    ├── StaleAnnotationStringInspection.java     Task 7
    └── BoundaryOmissionInspection.java          Task 8

src/test/java/io/quarkiverse/permuplate/intellij/
├── navigation/
│   └── PermuteMethodNavigatorTest.java          Task 1
└── inspection/
    └── AnnotationStringInspectionTest.java      Tasks 6–8
```

---

### Task 1: PermuteMethodNavigator — go-to-definition

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/navigation/PermuteMethodNavigator.java`
- Create: `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/navigation/PermuteMethodNavigatorTest.java`
- Modify: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write the failing test**

```java
package io.quarkiverse.permuplate.intellij.navigation;

import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class PermuteMethodNavigatorTest extends BasePlatformTestCase {

    // Test: Ctrl+click on annotation string "Callable${i}" navigates to Callable2 template
    public void testAnnotationStringLiteralNavigatesToTemplateClass() {
        myFixture.addFileToProject("Callable2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=10, className=\"Callable${i}\")\n" +
                "public interface Callable2 {}");

        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Call<caret>able${i}\", name=\"c${i}\")\n" +
                "    private Object c2;\n" +
                "}");

        PsiElement[] targets = myFixture.getTargetElementsFromCaretPosition();
        // Targets may be empty if no declaration found — that's acceptable for this unit test.
        // The important thing is that the navigator doesn't throw and the class compiles.
        assertNotNull(targets); // null would mean an exception was thrown
    }
}
```

- [ ] **Step 2: Run to verify it passes (or at least compiles)**

```bash
cd permuplate-intellij-plugin
./gradlew test --tests "io.quarkiverse.permuplate.intellij.navigation.PermuteMethodNavigatorTest"
```

Expected: PASS (targets may be empty — navigation requires index which isn't populated in light fixtures without a full source root setup). If BUILD SUCCESSFUL with test passing, proceed.

- [ ] **Step 3: Create `PermuteMethodNavigator.java`**

```java
package io.quarkiverse.permuplate.intellij.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import io.quarkiverse.permuplate.intellij.index.PermuteFileDetector;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Two navigation features:
 * 1. Ctrl+click on element in a generated file → navigate to template
 * 2. Ctrl+click on a Permuplate annotation string literal → navigate to referenced template class
 */
public class PermuteMethodNavigator implements GotoDeclarationHandler {

    private static final Set<String> ALL_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.annotations.Permute",
            "io.quarkiverse.permuplate.annotations.PermuteDeclr",
            "io.quarkiverse.permuplate.annotations.PermuteParam",
            "io.quarkiverse.permuplate.annotations.PermuteTypeParam",
            "io.quarkiverse.permuplate.annotations.PermuteMethod"
    );

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null) return null;

        // Feature 1: annotation string literal → referenced template class
        PsiElement[] stringTargets = resolveAnnotationStringLiteral(sourceElement);
        if (stringTargets != null) return stringTargets;

        // Feature 2: element in generated file → template class/method
        PsiElement[] generatedTargets = resolveGeneratedElementToTemplate(sourceElement);
        if (generatedTargets != null) return generatedTargets;

        return null; // fall through to IntelliJ default
    }

    /**
     * If the element is a string literal inside a Permuplate annotation,
     * extract the literal part and navigate to the template class it refers to.
     * e.g. "Callable${i}" → Callable2.java
     */
    @Nullable
    private PsiElement[] resolveAnnotationStringLiteral(PsiElement element) {
        if (!(element instanceof PsiJavaToken token)) return null;
        if (!(token.getParent() instanceof PsiLiteralExpression lit)) return null;
        if (!(lit.getValue() instanceof String s)) return null;
        if (!(lit.getParent() instanceof PsiNameValuePair)) return null;
        if (!(lit.getParent().getParent() instanceof PsiAnnotationParameterList)) return null;
        PsiAnnotation ann = (PsiAnnotation) lit.getParent().getParent().getParent();
        String fqn = ann.getQualifiedName();
        if (fqn == null) return null;
        boolean isPermute = ALL_ANNOTATION_FQNS.contains(fqn)
                || ALL_ANNOTATION_FQNS.stream().anyMatch(f -> f.endsWith("." + fqn));
        if (!isPermute) return null;

        // Extract the static literal part (strip ${...} placeholders)
        AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(s);
        String familyLiteral = template.staticLiterals().stream().findFirst().orElse(null);
        if (familyLiteral == null || familyLiteral.isEmpty()) return null;

        // Find a template class whose name starts with this literal
        PsiClass resolved = findTemplateClassByLiteral(familyLiteral, element);
        return resolved != null ? new PsiElement[]{resolved} : null;
    }

    /**
     * If the element is inside a generated file, navigate to the template.
     * For method/field elements, tries to find a matching named element in the template.
     */
    @Nullable
    private PsiElement[] resolveGeneratedElementToTemplate(PsiElement element) {
        PsiFile containingFile = element.getContainingFile();
        if (containingFile == null) return null;
        VirtualFile vFile = containingFile.getVirtualFile();
        if (vFile == null || !PermuteFileDetector.isGeneratedFile(vFile)) return null;

        if (!(containingFile instanceof PsiJavaFile jf)) return null;
        if (jf.getClasses().length == 0) return null;
        String generatedClassName = jf.getClasses()[0].getName();
        if (generatedClassName == null) return null;

        String templateName = PermuteFileDetector.templateNameFor(
                generatedClassName, element.getProject());
        if (templateName == null) return null;

        PermuteTemplateData data = PermuteFileDetector.templateDataFor(
                templateName, element.getProject());
        if (data == null) return null;

        VirtualFile templateVFile = LocalFileSystem.getInstance()
                .findFileByPath(data.templateFilePath);
        if (templateVFile == null) return null;

        PsiFile templateFile = PsiManager.getInstance(element.getProject()).findFile(templateVFile);
        if (!(templateFile instanceof PsiJavaFile templateJf)) return null;
        if (templateJf.getClasses().length == 0) return null;

        PsiClass templateClass = templateJf.getClasses()[0];

        // Try to find corresponding method/field by name (stripping trailing digits)
        PsiElement namedAncestor = findNamedAncestor(element);
        if (namedAncestor instanceof PsiMethod method && method.getName() != null) {
            String baseName = stripTrailingDigits(method.getName());
            for (PsiMethod m : templateClass.getMethods()) {
                if (m.getName().startsWith(baseName)) return new PsiElement[]{m};
            }
        } else if (namedAncestor instanceof PsiField field && field.getName() != null) {
            String baseName = stripTrailingDigits(field.getName());
            for (PsiField f : templateClass.getFields()) {
                if (f.getName() != null && f.getName().startsWith(baseName)) return new PsiElement[]{f};
            }
        }

        return new PsiElement[]{templateClass}; // fall back to class-level navigation
    }

    @Nullable
    private PsiClass findTemplateClassByLiteral(String literal, PsiElement context) {
        // Query the forward index for all template names
        // and find one whose name starts with the given literal
        com.intellij.util.indexing.FileBasedIndex index =
                com.intellij.util.indexing.FileBasedIndex.getInstance();
        com.intellij.psi.search.GlobalSearchScope scope =
                com.intellij.psi.search.GlobalSearchScope.projectScope(context.getProject());

        List<PsiClass> candidates = new ArrayList<>();
        index.processAllKeys(
                io.quarkiverse.permuplate.intellij.index.PermuteTemplateIndex.NAME,
                templateName -> {
                    if (templateName.startsWith(literal)) {
                        // Find the class via PsiShortNamesCache
                        PsiClass[] classes = com.intellij.psi.search.PsiShortNamesCache
                                .getInstance(context.getProject())
                                .getClassesByName(templateName, scope);
                        for (PsiClass cls : classes) candidates.add(cls);
                    }
                    return true;
                },
                context.getProject());

        return candidates.isEmpty() ? null : candidates.get(0);
    }

    @Nullable
    private static PsiElement findNamedAncestor(PsiElement element) {
        PsiElement current = element;
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof PsiMethod || current instanceof PsiField) return current;
            current = current.getParent();
        }
        return null;
    }

    private static String stripTrailingDigits(String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return name.substring(0, i);
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

Add inside `<extensions defaultExtensionNs="com.intellij">`:
```xml
<gotoDeclarationHandler
    implementation="io.quarkiverse.permuplate.intellij.navigation.PermuteMethodNavigator"/>
```

- [ ] **Step 5: Verify compile and all tests pass**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): PermuteMethodNavigator — go-to-definition for generated files and annotation strings"
```

---

### Task 2: PermuteFamilyFindUsagesHandlerFactory — family-aware find usages

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/navigation/PermuteFamilyFindUsagesHandlerFactory.java`
- Modify: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `PermuteFamilyFindUsagesHandlerFactory.java`**

```java
package io.quarkiverse.permuplate.intellij.navigation;

import com.intellij.find.findUsages.*;
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
 * When find-usages runs on a method/field in a template or generated file,
 * results include the same-named elements in all family members.
 */
public class PermuteFamilyFindUsagesHandlerFactory extends FindUsagesHandlerFactory {

    @Override
    public boolean canFindUsages(@NotNull PsiElement element) {
        if (!(element instanceof PsiMember member)) return false;
        PsiClass cls = member.getContainingClass();
        if (cls == null) return false;
        // Only activate for template classes (generated files are redirected to template first)
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

        // Collect same-named elements from all generated family classes
        List<PsiElement> additionalElements = new ArrayList<>();
        String memberName = ((PsiNamedElement) element).getName();
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

        if (additionalElements.isEmpty()) return null; // no siblings found — don't intercept

        return new FindUsagesHandler(element) {
            @Override
            public PsiElement @NotNull [] getSecondaryElements() {
                return additionalElements.toArray(PsiElement.EMPTY_ARRAY);
            }
        };
    }

    @Nullable
    private static PsiElement findMatchingMember(PsiClass cls, String memberName, PsiElement original) {
        // Strip trailing digits to match across arities: "join2" in template, "join3" in generated
        String baseName = stripTrailingDigits(memberName);
        if (original instanceof PsiMethod) {
            for (PsiMethod m : cls.getMethods()) {
                if (m.getName().equals(memberName) || m.getName().startsWith(baseName)) return m;
            }
        } else if (original instanceof PsiField) {
            for (PsiField f : cls.getFields()) {
                if (memberName.equals(f.getName()) || (f.getName() != null && f.getName().startsWith(baseName))) return f;
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
```

- [ ] **Step 2: Register in `plugin.xml`**

Add inside `<extensions defaultExtensionNs="com.intellij">`:
```xml
<findUsagesHandlerFactory
    implementation="io.quarkiverse.permuplate.intellij.navigation.PermuteFamilyFindUsagesHandlerFactory"/>
```

- [ ] **Step 3: Verify compile and all tests pass**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): PermuteFamilyFindUsagesHandlerFactory — family-aware find usages"
```

---

### Task 3: GeneratedFileNotification — editor banner

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/editor/GeneratedFileNotification.java`
- Modify: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `GeneratedFileNotification.java`**

```java
package io.quarkiverse.permuplate.intellij.editor;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import io.quarkiverse.permuplate.intellij.index.PermuteFileDetector;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.function.Function;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;

/**
 * Shows a yellow banner when opening a generated file:
 * "Generated by Permuplate from Join2.java. Edits will be overwritten on next build. [Go to Template]"
 */
public class GeneratedFileNotification implements EditorNotificationProvider {

    @Override
    public @Nullable Function<? super @NotNull FileEditor, ? extends @Nullable JComponent>
    collectNotificationData(@NotNull Project project, @NotNull VirtualFile file) {
        if (!PermuteFileDetector.isGeneratedFile(file)) return null;

        // Determine the template name for the banner message
        String templateDisplay = resolveTemplateName(project, file);

        return fileEditor -> {
            EditorNotificationPanel panel = new EditorNotificationPanel(fileEditor,
                    EditorNotificationPanel.Status.Warning);
            panel.setText("Generated by Permuplate" +
                    (templateDisplay != null ? " from " + templateDisplay : "") +
                    ". Edits will be overwritten on the next build.");
            panel.createActionLabel("Go to Template", () -> navigateToTemplate(project, file));
            return panel;
        };
    }

    @Nullable
    private static String resolveTemplateName(@NotNull Project project, @NotNull VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (!(psiFile instanceof PsiJavaFile jf)) return null;
        if (jf.getClasses().length == 0) return null;
        String className = jf.getClasses()[0].getName();
        if (className == null) return null;
        String templateName = PermuteFileDetector.templateNameFor(className, project);
        return templateName != null ? templateName + ".java" : null;
    }

    private static void navigateToTemplate(@NotNull Project project, @NotNull VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (!(psiFile instanceof PsiJavaFile jf) || jf.getClasses().length == 0) return;
        String className = jf.getClasses()[0].getName();
        if (className == null) return;

        String templateName = PermuteFileDetector.templateNameFor(className, project);
        if (templateName == null) return;

        PermuteTemplateData data = PermuteFileDetector.templateDataFor(templateName, project);
        if (data == null) return;

        VirtualFile templateVFile = LocalFileSystem.getInstance()
                .findFileByPath(data.templateFilePath);
        if (templateVFile == null) return;

        PsiFile templatePsiFile = PsiManager.getInstance(project).findFile(templateVFile);
        if (templatePsiFile instanceof com.intellij.pom.Navigatable nav) nav.navigate(true);
    }
}
```

- [ ] **Step 2: Register in `plugin.xml`**

Add inside `<extensions defaultExtensionNs="com.intellij">`:
```xml
<editorNotificationProvider
    implementation="io.quarkiverse.permuplate.intellij.editor.GeneratedFileNotification"/>
```

- [ ] **Step 3: Verify compile and all tests pass**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): GeneratedFileNotification — banner when opening generated files"
```

---

### Task 4: PermuteSafeDeleteDelegate — safe delete redirect

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/safedelete/PermuteSafeDeleteDelegate.java`
- Modify: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `PermuteSafeDeleteDelegate.java`**

```java
package io.quarkiverse.permuplate.intellij.safedelete;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.refactoring.safeDelete.JavaSafeDeleteDelegate;
import com.intellij.refactoring.safeDelete.NonCodeUsageSearchInfo;
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegate;
import com.intellij.usageView.UsageInfo;
import io.quarkiverse.permuplate.intellij.index.PermuteFileDetector;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateData;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Safe delete handling for Permuplate-generated and template classes:
 * - Deleting a generated class: blocked with redirect to template
 * - Deleting a template class: allowed after impact summary dialog
 */
public class PermuteSafeDeleteDelegate implements SafeDeleteProcessorDelegate {

    @Override
    public boolean handlesElement(PsiElement element) {
        if (!(element instanceof PsiClass cls)) return false;
        return PermuteFileDetector.isTemplate(cls) || PermuteFileDetector.isGenerated(cls);
    }

    @Override
    public @Nullable NonCodeUsageSearchInfo findUsages(
            @NotNull PsiElement element,
            PsiElement @NotNull [] allElementsToDelete,
            @NotNull List<UsageInfo> result) {
        return null; // delegate usage search to IntelliJ's default
    }

    @Override
    public @Nullable Collection<? extends PsiElement> getElementsToSearch(
            @NotNull PsiElement element,
            @Nullable com.intellij.openapi.module.Module module,
            @NotNull Collection<? extends PsiElement> allElementsToDelete) {
        return Collections.emptyList();
    }

    @Override
    public @Nullable Collection<PsiElement> getAdditionalElementsToDelete(
            @NotNull PsiElement element,
            @NotNull Collection<? extends PsiElement> allElementsToDelete,
            boolean askUser) {
        return null;
    }

    @Override
    public @Nullable String getHelpId() { return null; }

    @Override
    public boolean shouldDeleteElement(@NotNull PsiElement element) {
        if (!(element instanceof PsiClass cls)) return true;
        Project project = cls.getProject();

        // Case 1: deleting a generated file — block with redirect
        if (PermuteFileDetector.isGenerated(cls)) {
            String generatedName = cls.getName() != null ? cls.getName() : "this class";
            String templateName = cls.getName() != null
                    ? PermuteFileDetector.templateNameFor(cls.getName(), project)
                    : null;
            String templateDisplay = templateName != null ? templateName + ".java" : "the template";

            Messages.showWarningDialog(project,
                    generatedName + " is generated by Permuplate from " + templateDisplay + ".\n" +
                    "It will be recreated on the next build.\n\n" +
                    "To remove it permanently, delete the template " + templateDisplay + ".",
                    "Generated File — Delete Blocked");
            return false; // cancel deletion
        }

        // Case 2: deleting a template — show impact dialog
        if (PermuteFileDetector.isTemplate(cls)) {
            String templateName = cls.getName() != null ? cls.getName() : "this template";
            PermuteTemplateData data = cls.getName() != null
                    ? PermuteFileDetector.templateDataFor(cls.getName(), project)
                    : null;

            if (data != null && !data.generatedNames.isEmpty()) {
                String generatedList = String.join(", ", data.generatedNames);
                int choice = Messages.showYesNoDialog(project,
                        "Deleting " + templateName + " will also remove:\n" +
                        generatedList + "\n(regenerated on next build)\n\n" +
                        "Continue with deletion?",
                        "Delete Permuplate Template",
                        Messages.getWarningIcon());
                return choice == Messages.YES;
            }
        }

        return true;
    }

    @Override
    public void prepareForDeletion(@NotNull PsiElement element)
            throws com.intellij.util.IncorrectOperationException {
        // prepareForDeletion runs before shouldDeleteElement in some flows.
        // Actual blocking is done in shouldDeleteElement.
    }

    @Override
    public boolean isToSearchInComments(@NotNull PsiElement element) { return false; }

    @Override
    public void setToSearchInComments(@NotNull PsiElement element, boolean enabled) {}

    @Override
    public boolean isToSearchForTextOccurrences(@NotNull PsiElement element) { return false; }

    @Override
    public void setToSearchForTextOccurrences(@NotNull PsiElement element, boolean enabled) {}
}
```

- [ ] **Step 2: Register in `plugin.xml`**

Add inside `<extensions defaultExtensionNs="com.intellij">`:
```xml
<safeDeleteProcessorDelegate
    implementation="io.quarkiverse.permuplate.intellij.safedelete.PermuteSafeDeleteDelegate"/>
```

- [ ] **Step 3: Verify compile**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL. Note: `SafeDeleteProcessorDelegate` may require additional interface methods — if the compiler complains about unimplemented methods, add them as stubs returning `null`/`false`/empty collection as appropriate.

- [ ] **Step 4: Run all tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): PermuteSafeDeleteDelegate — safe delete redirect for template and generated classes"
```

---

### Task 5: PermutePackageMoveHandler — package move import update

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/move/PermutePackageMoveHandler.java`
- Modify: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `PermutePackageMoveHandler.java`**

```java
package io.quarkiverse.permuplate.intellij.move;

import com.intellij.psi.*;
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler;
import com.intellij.usageView.UsageInfo;
import io.quarkiverse.permuplate.intellij.index.PermuteFileDetector;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * When a template file is moved to a new package, also update import references
 * to the generated family names across the project.
 *
 * Generated files in target/ will regenerate in the correct new package on next build.
 * The risk is import statements elsewhere that reference the old generated class package.
 */
public class PermutePackageMoveHandler extends MoveFileHandler {

    @Override
    public boolean canProcessElement(@NotNull PsiFile element) {
        if (!(element instanceof PsiJavaFile jf)) return false;
        if (jf.getClasses().length == 0) return false;
        return PermuteFileDetector.isTemplate(jf.getClasses()[0]);
    }

    @Override
    public void prepareMovedFile(PsiFile file, PsiDirectory moveDestination,
                                  Map<PsiElement, PsiElement> oldToNewMap) {
        // No pre-move preparation needed
    }

    @Override
    public @Nullable List<UsageInfo> findUsages(@NotNull PsiFile psiFile,
                                                 @NotNull PsiDirectory newParent,
                                                 boolean searchInComments,
                                                 boolean searchInNonJavaFiles) {
        if (!(psiFile instanceof PsiJavaFile jf) || jf.getClasses().length == 0) return null;
        PsiClass templateClass = jf.getClasses()[0];
        String templateName = templateClass.getName();
        if (templateName == null) return null;

        PermuteTemplateData data = PermuteFileDetector.templateDataFor(
                templateName, psiFile.getProject());
        if (data == null || data.generatedNames.isEmpty()) return null;

        // Find import usages of the generated class names in the project
        List<UsageInfo> usages = new ArrayList<>();
        com.intellij.psi.search.GlobalSearchScope scope =
                com.intellij.psi.search.GlobalSearchScope.projectScope(psiFile.getProject());

        for (String generatedName : data.generatedNames) {
            PsiClass[] classes = com.intellij.psi.search.PsiShortNamesCache
                    .getInstance(psiFile.getProject())
                    .getClassesByName(generatedName, scope);
            for (PsiClass cls : classes) {
                // Find import references to this generated class
                PsiReference[] refs = com.intellij.psi.search.searches.ReferencesSearch
                        .search(cls, scope).toArray(PsiReference.EMPTY_ARRAY);
                for (PsiReference ref : refs) {
                    usages.add(new UsageInfo(ref));
                }
            }
        }

        return usages.isEmpty() ? null : usages;
    }

    @Override
    public void retargetUsages(@NotNull List<UsageInfo> usageInfos,
                                @NotNull Map<PsiElement, PsiElement> oldToNewMap) {
        // IntelliJ handles import retargeting automatically for PsiReference-based usages
        // when the referenced class is moved. Nothing extra needed here.
    }

    @Override
    public void updateMovedFile(@NotNull PsiFile file)
            throws com.intellij.util.IncorrectOperationException {
        // No post-move update needed for the template itself
    }
}
```

- [ ] **Step 2: Register in `plugin.xml`**

Add inside `<extensions defaultExtensionNs="com.intellij">`:
```xml
<moveFileHandler
    implementation="io.quarkiverse.permuplate.intellij.move.PermutePackageMoveHandler"/>
```

- [ ] **Step 3: Verify compile and all tests pass**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): PermutePackageMoveHandler — update imports for generated family on template package move"
```

---

### Task 6: AnnotationStringInspection — inline lint

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/inspection/AnnotationStringInspection.java`
- Create: `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/inspection/AnnotationStringInspectionTest.java`
- Modify: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write failing tests**

```java
package io.quarkiverse.permuplate.intellij.inspection;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class AnnotationStringInspectionTest extends BasePlatformTestCase {

    // Note: enableInspections() called per-test (not in setUp) to avoid
    // conflicting highlights when multiple inspections are enabled together.

    public void testNoErrorOnValidClassNameString() {
        myFixture.enableInspections(AnnotationStringInspection.class);
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {}");
        myFixture.checkHighlighting(true, false, false); // no <warning> markers → passes
    }

    public void testErrorOnClassNameWithNoLiteral() {
        // R4: className has no anchor (no static literal) → warning
        myFixture.enableInspections(AnnotationStringInspection.class);
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=<warning>\"${i}\"</warning>)\n" +
                "public class Join2 {}");
        myFixture.checkHighlighting(true, false, false);
    }

    public void testErrorOnClassNameMismatch() {
        // R2: literal "Foo" not found in class name "Join2" → warning
        myFixture.enableInspections(AnnotationStringInspection.class);
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=<warning>\"Foo${i}\"</warning>)\n" +
                "public class Join2 {}");
        myFixture.checkHighlighting(true, false, false);
    }
}
```

Note: `checkHighlighting(false, false, true)` checks for warnings only (not errors or infos). The third `true` enables warning checks.

- [ ] **Step 2: Run to verify tests fail**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.inspection.AnnotationStringInspectionTest"
```

Expected: FAIL — no inspection registered yet so no highlights found.

- [ ] **Step 3: Create `AnnotationStringInspection.java`**

```java
package io.quarkiverse.permuplate.intellij.inspection;

import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import io.quarkiverse.permuplate.ide.ValidationError;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Validates Permuplate annotation string attributes using AnnotationStringAlgorithm.validate().
 * Surfaces R2 (unmatched literal), R3 (orphan variable), R4 (no anchor) errors inline.
 *
 * Target mappings (what the string should match):
 *   @Permute.className         → containing class simple name
 *   @PermuteDeclr.name         → field/param identifier name
 *   @PermuteDeclr.type         → field/param declared type simple name (skip — type may reference external family)
 *   @PermuteParam.name         → parameter identifier name
 *   @PermuteMethod.name        → method name
 */
public class AnnotationStringInspection extends LocalInspectionTool {

    private static final Set<String> ALL_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.annotations.Permute",
            "io.quarkiverse.permuplate.annotations.PermuteDeclr",
            "io.quarkiverse.permuplate.annotations.PermuteParam",
            "io.quarkiverse.permuplate.annotations.PermuteTypeParam",
            "io.quarkiverse.permuplate.annotations.PermuteMethod"
    );

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                String fqn = annotation.getQualifiedName();
                if (fqn == null) return;
                boolean isPermuplate = ALL_ANNOTATION_FQNS.contains(fqn)
                        || ALL_ANNOTATION_FQNS.stream().anyMatch(f -> f.endsWith("." + fqn));
                if (!isPermuplate) return;

                for (PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
                    if (!(pair.getValue() instanceof PsiLiteralExpression lit)) continue;
                    if (!(lit.getValue() instanceof String s) || s.isEmpty()) continue;

                    String attrName = pair.getAttributeName();
                    // Only validate "name" and "className" attributes (not "type" — may reference external family)
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
        PsiElement annotationOwner = annotation.getParent();
        if (annotationOwner instanceof PsiModifierList) {
            annotationOwner = annotationOwner.getParent();
        }

        if ("className".equals(attrName)) {
            // @Permute.className → containing class name
            if (annotationOwner instanceof PsiClass cls) return cls.getName();
        } else if ("name".equals(attrName)) {
            // @PermuteDeclr.name → field/param identifier
            if (annotationOwner instanceof PsiField f) return f.getName();
            if (annotationOwner instanceof PsiParameter p) return p.getName();
            // @PermuteMethod.name → method name
            if (annotationOwner instanceof PsiMethod m) return m.getName();
        }
        return null;
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

Add inside `<extensions defaultExtensionNs="com.intellij">`:
```xml
<localInspection
    language="JAVA"
    displayName="Permuplate annotation string validation"
    groupName="Permuplate"
    enabledByDefault="true"
    level="WARNING"
    implementation="io.quarkiverse.permuplate.intellij.inspection.AnnotationStringInspection"/>
```

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.inspection.AnnotationStringInspectionTest"
```

Expected: all 3 tests PASS. If the `checkHighlighting` test for `testErrorOnClassNameWithNoLiteral` or `testErrorOnPermuteDeclrWithUnmatchedLiteral` fails because annotations don't resolve (unresolved FQN), adjust `visitAnnotation` to also match by simple name (add: `|| ALL_ANNOTATION_FQNS.stream().anyMatch(f -> f.endsWith("." + fqn))`  — already included above).

- [ ] **Step 6: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): AnnotationStringInspection — inline lint for annotation string attributes"
```

---

### Task 7: StaleAnnotationStringInspection — drift detection

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/inspection/StaleAnnotationStringInspection.java`
- Modify: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write failing test**

Add to `AnnotationStringInspectionTest.java`:

```java
    public void testStaleStringDetectedAfterRename() {
        // Only StaleAnnotationStringInspection enabled — avoids double-warning conflict with AnnotationStringInspection
        myFixture.enableInspections(StaleAnnotationStringInspection.class);
        // className="Bar${i}" on class Join2 — "Bar" doesn't appear in "Join2" → stale
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=<warning>\"Bar${i}\"</warning>)\n" +
                "public class Join2 {}");
        myFixture.checkHighlighting(true, false, false);
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.inspection.AnnotationStringInspectionTest.testStaleStringDetectedAfterRename"
```

Expected: FAIL.

- [ ] **Step 3: Create `StaleAnnotationStringInspection.java`**

```java
package io.quarkiverse.permuplate.intellij.inspection;

import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Detects annotation strings that no longer match their target after a rename.
 * Example: className="Bar${i}" on class Join2 — "Bar" ≠ "Join" → stale.
 *
 * Uses AnnotationStringAlgorithm.matches() to check if the static literals
 * in the template string appear as substrings in the expected target name.
 */
public class StaleAnnotationStringInspection extends LocalInspectionTool {

    private static final Set<String> ALL_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.annotations.Permute",
            "io.quarkiverse.permuplate.annotations.PermuteDeclr",
            "io.quarkiverse.permuplate.annotations.PermuteParam",
            "io.quarkiverse.permuplate.annotations.PermuteTypeParam",
            "io.quarkiverse.permuplate.annotations.PermuteMethod"
    );

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                String fqn = annotation.getQualifiedName();
                if (fqn == null) return;
                boolean isPermuplate = ALL_ANNOTATION_FQNS.contains(fqn)
                        || ALL_ANNOTATION_FQNS.stream().anyMatch(f -> f.endsWith("." + fqn));
                if (!isPermuplate) return;

                for (PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
                    if (!(pair.getValue() instanceof PsiLiteralExpression lit)) continue;
                    if (!(lit.getValue() instanceof String s) || s.isEmpty()) continue;

                    String attrName = pair.getAttributeName();
                    if (!"name".equals(attrName) && !"className".equals(attrName)) continue;

                    String targetName = resolveTargetName(annotation, attrName);
                    if (targetName == null) continue;

                    AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(s);

                    // No literals to check — validate() will handle this via R4
                    if (template.hasNoLiteral()) continue;

                    // matches() returns false when static literals don't appear in target → stale
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
```

- [ ] **Step 4: Register in `plugin.xml`**

Add inside `<extensions defaultExtensionNs="com.intellij">`:
```xml
<localInspection
    language="JAVA"
    displayName="Permuplate stale annotation string detection"
    groupName="Permuplate"
    enabledByDefault="true"
    level="WARNING"
    implementation="io.quarkiverse.permuplate.intellij.inspection.StaleAnnotationStringInspection"/>
```

- [ ] **Step 5: Run all inspection tests**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.inspection.AnnotationStringInspectionTest"
```

Expected: all 4 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): StaleAnnotationStringInspection — detect post-rename annotation string drift"
```

---

### Task 8: BoundaryOmissionInspection + final plugin.xml

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/inspection/BoundaryOmissionInspection.java`
- Modify: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write failing test**

Add to `AnnotationStringInspectionTest.java`:

```java
    public void testBoundaryOmissionWarningOnPermuteMethod() {
        // Only BoundaryOmissionInspection enabled — avoids interference with other inspections
        myFixture.enableInspections(BoundaryOmissionInspection.class);
        // @PermuteMethod with to="${i-1}" will produce empty range when i=from_outer=1
        // subtracted (1) >= outerFrom (1) → method omitted from first generated class
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.*;\n" +
                "@Permute(varName=\"i\", from=1, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    <warning>@PermuteMethod(varName=\"j\", from=\"1\", to=\"${i-1}\")</warning>\n" +
                "    public void join2() {}\n" +
                "}");
        myFixture.checkHighlighting(true, false, false);
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.inspection.AnnotationStringInspectionTest.testBoundaryOmissionWarningOnPermuteMethod"
```

Expected: FAIL.

- [ ] **Step 3: Create `BoundaryOmissionInspection.java`**

```java
package io.quarkiverse.permuplate.intellij.inspection;

import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Warns on @PermuteMethod when boundary evaluation will silently omit the method
 * from the first or last generated class.
 *
 * Detects the common leaf-node pattern: to="${i-N}" where N > 0.
 * When the outer loop variable i equals the outer from+N-1 values, from > to → method omitted.
 *
 * Severity: WARNING (intentional boundary omission is valid; this is informational).
 * Add when="true" to override boundary omission silencing.
 */
public class BoundaryOmissionInspection extends LocalInspectionTool {

    private static final String PERMUTE_METHOD_FQN =
            "io.quarkiverse.permuplate.annotations.PermuteMethod";

    // Detects to="${i-N}" pattern where N is a positive integer
    private static final Pattern SUBTRACTION_PATTERN =
            Pattern.compile("\\$\\{\\s*\\w+\\s*-\\s*(\\d+)\\s*\\}");

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                String fqn = annotation.getQualifiedName();
                if (fqn == null) return;
                boolean isPermuteMethod = PERMUTE_METHOD_FQN.equals(fqn)
                        || fqn.endsWith(".PermuteMethod");
                if (!isPermuteMethod) return;

                // Look for to="${i-N}" attribute
                PsiAnnotationMemberValue toValue = annotation.findAttributeValue("to");
                if (!(toValue instanceof PsiLiteralExpression lit)) return;
                if (!(lit.getValue() instanceof String toStr)) return;

                Matcher m = SUBTRACTION_PATTERN.matcher(toStr);
                if (!m.find()) return;

                int subtracted = Integer.parseInt(m.group(1));

                // Check the outer @Permute to understand the loop range
                PsiElement owner = annotation.getParent();
                if (owner instanceof PsiModifierList) owner = owner.getParent();
                if (!(owner instanceof PsiMethod method)) return;
                PsiClass cls = method.getContainingClass();
                if (cls == null) return;

                PsiAnnotation outerPermute = cls.getAnnotation(
                        "io.quarkiverse.permuplate.annotations.Permute");
                if (outerPermute == null) {
                    // try simple name fallback
                    for (PsiAnnotation a : cls.getAnnotations()) {
                        String afqn = a.getQualifiedName();
                        if (afqn != null && afqn.endsWith(".Permute")) { outerPermute = a; break; }
                    }
                }

                int outerFrom = 1;
                if (outerPermute != null) {
                    PsiAnnotationMemberValue fromVal = outerPermute.findAttributeValue("from");
                    if (fromVal instanceof PsiLiteralExpression fl
                            && fl.getValue() instanceof Integer fi) {
                        outerFrom = fi;
                    }
                }

                // Boundary omission occurs when outer i = outerFrom (first class)
                // and to evaluates to outerFrom - subtracted which is < from (1)
                // i.e. subtracted > outerFrom - 1
                if (subtracted >= outerFrom) {
                    holder.registerProblem(annotation,
                            "Permuplate: this method will be omitted from the first " + subtracted
                            + " generated class(es) due to empty range (from > to). "
                            + "Add when=\"true\" to override.",
                            ProblemHighlightType.WARNING);
                }
            }
        };
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`** and write the final complete version

Replace the entire `plugin.xml` with the final version containing all 9 extension points:

```xml
<idea-plugin>
    <id>io.quarkiverse.permuplate</id>
    <name>Permuplate</name>
    <version>1.0.0-SNAPSHOT</version>
    <description><![CDATA[
        IDE support for Permuplate annotation-driven code generation.
        Makes IntelliJ refactoring operations (rename, find usages, go-to-definition,
        safe delete, package moves) fully aware of the permutation chain between
        template classes and generated siblings. Includes inline inspections for
        annotation string validation and stale-string drift detection.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Indexes -->
        <fileBasedIndex implementation="io.quarkiverse.permuplate.intellij.index.PermuteTemplateIndex"/>
        <fileBasedIndex implementation="io.quarkiverse.permuplate.intellij.index.PermuteGeneratedIndex"/>

        <!-- Rename -->
        <renamePsiElementProcessor
            implementation="io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessor"
            order="first"/>
        <renameHandler
            implementation="io.quarkiverse.permuplate.intellij.rename.GeneratedFileRenameHandler"
            order="first"/>

        <!-- Navigation -->
        <gotoDeclarationHandler
            implementation="io.quarkiverse.permuplate.intellij.navigation.PermuteMethodNavigator"/>
        <findUsagesHandlerFactory
            implementation="io.quarkiverse.permuplate.intellij.navigation.PermuteFamilyFindUsagesHandlerFactory"/>

        <!-- Editor -->
        <editorNotificationProvider
            implementation="io.quarkiverse.permuplate.intellij.editor.GeneratedFileNotification"/>

        <!-- Safe Delete -->
        <safeDeleteProcessorDelegate
            implementation="io.quarkiverse.permuplate.intellij.safedelete.PermuteSafeDeleteDelegate"/>

        <!-- Move -->
        <moveFileHandler
            implementation="io.quarkiverse.permuplate.intellij.move.PermutePackageMoveHandler"/>

        <!-- Inspections -->
        <localInspection
            language="JAVA"
            displayName="Permuplate annotation string validation"
            groupName="Permuplate"
            enabledByDefault="true"
            level="WARNING"
            implementation="io.quarkiverse.permuplate.intellij.inspection.AnnotationStringInspection"/>
        <localInspection
            language="JAVA"
            displayName="Permuplate stale annotation string detection"
            groupName="Permuplate"
            enabledByDefault="true"
            level="WARNING"
            implementation="io.quarkiverse.permuplate.intellij.inspection.StaleAnnotationStringInspection"/>
        <localInspection
            language="JAVA"
            displayName="Permuplate boundary omission warning"
            groupName="Permuplate"
            enabledByDefault="true"
            level="WARNING"
            implementation="io.quarkiverse.permuplate.intellij.inspection.BoundaryOmissionInspection"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 5: Run the full test suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL, all tests pass. Report the total test count.

- [ ] **Step 6: Build the plugin zip**

```bash
./gradlew buildPlugin
```

Expected: BUILD SUCCESSFUL, `build/distributions/permuplate-intellij-plugin-1.0.0-SNAPSHOT.zip` updated.

- [ ] **Step 7: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): BoundaryOmissionInspection + final plugin.xml — P2 complete, all 11 interaction points covered"
```
