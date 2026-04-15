# Generated Family Rename Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a method or field is renamed in a Permuplate template class, automatically add the corresponding elements from all generated sibling classes to IntelliJ's `allRenames` map so call sites across the whole family are updated atomically.

**Architecture:** Add a private `addGeneratedFamilyRenames()` helper to `AnnotationStringRenameProcessor` called from `prepareRenaming()`. It looks up generated sibling class names from `PermuteFileDetector.templateDataFor()`, resolves each to a `PsiClass` via `JavaPsiFacade`, and adds the matching method or field to `allRenames`. IntelliJ does the rest.

**Tech Stack:** IntelliJ Platform SDK (PSI, FileBasedIndex, RenamePsiElementProcessor), JUnit 4, BasePlatformTestCase.

**GitHub:** Epic #24, implementation issue #25.

---

## File Map

| File | Change |
|---|---|
| `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java` | Add `addGeneratedFamilyRenames()` private helper; call from `prepareRenaming()` |
| `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessorTest.java` | Add 6 new tests (5 unit + 1 integration) |

---

## Task 1: Failing test — method rename propagates to generated siblings

**Files:**
- Test: `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessorTest.java`

- [ ] **Step 1.1: Write the failing test**

Add this test to `AnnotationStringRenameProcessorTest` after the existing cascade tests:

```java
// =========================================================================
// Generated family rename propagation — addGeneratedFamilyRenames()
// =========================================================================

public void testMethodRenameInTemplateAddsGeneratedSiblingsToAllRenames() {
    // Template: Join2 with @Permute generating Join3 and Join4
    myFixture.addFileToProject("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.Permute;\n" +
            "@Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Join${i}\")\n" +
            "public class Join2 {\n" +
            "    public void join() {}\n" +
            "}");

    // Generated siblings (plain source files so JavaPsiFacade can find them by FQN)
    myFixture.addFileToProject("Join3.java",
            "package io.example;\n" +
            "public class Join3 {\n" +
            "    public void join() {}\n" +
            "}");
    myFixture.addFileToProject("Join4.java",
            "package io.example;\n" +
            "public class Join4 {\n" +
            "    public void join() {}\n" +
            "}");

    PsiClass join2 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join2", GlobalSearchScope.allScope(getProject()));
    assertNotNull(join2);
    PsiMethod joinMethod = join2.findMethodsByName("join", false)[0];

    AnnotationStringRenameProcessor processor = new AnnotationStringRenameProcessor();
    Map<PsiElement, String> allRenames = new java.util.HashMap<>();
    processor.prepareRenaming(joinMethod, "combine",
            allRenames, GlobalSearchScope.allScope(getProject()));

    PsiClass join3 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join3", GlobalSearchScope.allScope(getProject()));
    PsiClass join4 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join4", GlobalSearchScope.allScope(getProject()));
    assertNotNull("Join3 must be resolvable", join3);
    assertNotNull("Join4 must be resolvable", join4);
    PsiMethod join3Method = join3.findMethodsByName("join", false)[0];
    PsiMethod join4Method = join4.findMethodsByName("join", false)[0];

    assertTrue("Join3.join() must be in allRenames", allRenames.containsKey(join3Method));
    assertEquals("combine", allRenames.get(join3Method));
    assertTrue("Join4.join() must be in allRenames", allRenames.containsKey(join4Method));
    assertEquals("combine", allRenames.get(join4Method));
}
```

- [ ] **Step 1.2: Run test to confirm it fails**

```bash
cd permuplate-intellij-plugin
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testMethodRenameInTemplateAddsGeneratedSiblingsToAllRenames"
```

Expected: FAIL — `allRenames` is empty because `addGeneratedFamilyRenames()` doesn't exist yet.

---

## Task 2: Implement `addGeneratedFamilyRenames()` for methods

**Files:**
- Modify: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java`

- [ ] **Step 2.1: Add imports**

At the top of `AnnotationStringRenameProcessor.java`, add:

```java
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import io.quarkiverse.permuplate.intellij.index.PermuteFileDetector;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateData;
```

(Some may already be present — add only what's missing.)

- [ ] **Step 2.2: Add the call in `prepareRenaming()`**

In `prepareRenaming()`, add one line at the very end, just before closing the method — after `pendingUpdates.get().addAll(updates)`:

```java
        pendingUpdates.get().addAll(updates);

        // Propagate rename to corresponding elements in all generated sibling classes
        addGeneratedFamilyRenames(element, newName, allRenames);
    }
```

- [ ] **Step 2.3: Add the `addGeneratedFamilyRenames()` private method**

Add this method to `AnnotationStringRenameProcessor`, after the `collectMemberAnnotationUpdates()` method and before `getMemberName()`:

```java
    /**
     * When renaming a method or field in a Permuplate template class, adds the corresponding
     * element from every generated sibling class to allRenames. IntelliJ then renames all of
     * them and updates all their call sites atomically in one undo step.
     *
     * Skips @PermuteMethod sentinel methods — their generated names are controlled by the
     * name attribute string (handled by annotation string update), not the sentinel name.
     * Skips generated classes that don't contain the named element (boundary omission).
     */
    private void addGeneratedFamilyRenames(@NotNull PsiElement element,
                                            @NotNull String newName,
                                            @NotNull Map<PsiElement, String> allRenames) {
        if (!(element instanceof PsiMethod) && !(element instanceof PsiField)) return;

        PsiClass containingClass = element instanceof PsiMember m ? m.getContainingClass() : null;
        if (containingClass == null) return;

        if (!PermuteFileDetector.isTemplate(containingClass)) return;

        // Skip @PermuteMethod sentinel methods
        if (element instanceof PsiMethod method) {
            for (PsiAnnotation ann : method.getModifierList().getAnnotations()) {
                String fqn = ann.getQualifiedName();
                if (fqn != null && (fqn.equals("io.quarkiverse.permuplate.PermuteMethod")
                        || fqn.endsWith(".PermuteMethod"))) {
                    return;
                }
            }
        }

        String templateName = containingClass.getName();
        if (templateName == null) return;

        PermuteTemplateData data = PermuteFileDetector.templateDataFor(
                templateName, element.getProject());
        if (data == null || data.generatedNames.isEmpty()) return;

        PsiFile containingFile = containingClass.getContainingFile();
        if (!(containingFile instanceof PsiJavaFile javaFile)) return;
        String pkg = javaFile.getPackageName();

        Project project = element.getProject();
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        String elementName = ((PsiNamedElement) element).getName();
        if (elementName == null) return;

        for (String generatedSimpleName : data.generatedNames) {
            String fqn = pkg.isEmpty() ? generatedSimpleName : pkg + "." + generatedSimpleName;
            PsiClass generatedClass = JavaPsiFacade.getInstance(project).findClass(fqn, scope);
            if (generatedClass == null) continue;

            if (element instanceof PsiMethod) {
                for (PsiMethod m : generatedClass.findMethodsByName(elementName, false)) {
                    allRenames.put(m, newName);
                }
            } else {
                PsiField f = generatedClass.findFieldByName(elementName, false);
                if (f != null) allRenames.put(f, newName);
            }
        }
    }
```

- [ ] **Step 2.4: Run the test — expect PASS**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testMethodRenameInTemplateAddsGeneratedSiblingsToAllRenames"
```

Expected: PASS.

- [ ] **Step 2.5: Run full suite — expect all 58 still pass**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test
```

Expected: BUILD SUCCESSFUL, 59 tests, 0 failures.

- [ ] **Step 2.6: Commit**

```bash
git add permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java \
        permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessorTest.java
git commit -m "feat(plugin): propagate method rename to generated sibling classes

Closes part of #25.

When a method in a template class is renamed, addGeneratedFamilyRenames()
looks up generated sibling class names from PermuteTemplateData, resolves
each to a PsiClass, and adds the matching method to allRenames. IntelliJ
then renames all elements and call sites atomically.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Failing test + passing — field rename propagation

**Files:**
- Test: `AnnotationStringRenameProcessorTest.java`

- [ ] **Step 3.1: Write the failing test**

Add after the method rename test:

```java
public void testFieldRenameInTemplateAddsGeneratedSiblingsToAllRenames() {
    myFixture.addFileToProject("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.Permute;\n" +
            "@Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Join${i}\")\n" +
            "public class Join2 {\n" +
            "    public Object c2;\n" +
            "}");
    myFixture.addFileToProject("Join3.java",
            "package io.example;\n" +
            "public class Join3 {\n" +
            "    public Object c2;\n" +
            "}");
    myFixture.addFileToProject("Join4.java",
            "package io.example;\n" +
            "public class Join4 {\n" +
            "    public Object c2;\n" +
            "}");

    PsiClass join2 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join2", GlobalSearchScope.allScope(getProject()));
    assertNotNull(join2);
    PsiField c2Field = join2.findFieldByName("c2", false);
    assertNotNull(c2Field);

    AnnotationStringRenameProcessor processor = new AnnotationStringRenameProcessor();
    Map<PsiElement, String> allRenames = new java.util.HashMap<>();
    processor.prepareRenaming(c2Field, "d2",
            allRenames, GlobalSearchScope.allScope(getProject()));

    PsiClass join3 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join3", GlobalSearchScope.allScope(getProject()));
    PsiClass join4 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join4", GlobalSearchScope.allScope(getProject()));
    assertNotNull(join3);
    assertNotNull(join4);

    assertTrue("Join3.c2 must be in allRenames",
            allRenames.containsKey(join3.findFieldByName("c2", false)));
    assertEquals("d2", allRenames.get(join3.findFieldByName("c2", false)));
    assertTrue("Join4.c2 must be in allRenames",
            allRenames.containsKey(join4.findFieldByName("c2", false)));
    assertEquals("d2", allRenames.get(join4.findFieldByName("c2", false)));
}
```

- [ ] **Step 3.2: Run test — expect PASS (field branch already implemented in Task 2)**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testFieldRenameInTemplateAddsGeneratedSiblingsToAllRenames"
```

Expected: PASS — the `PsiField` branch in `addGeneratedFamilyRenames()` was already written in Task 2.

- [ ] **Step 3.3: Commit**

```bash
git add permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessorTest.java
git commit -m "test(plugin): add field rename propagation test

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Edge case tests — boundary omission, non-template, @PermuteMethod

**Files:**
- Test: `AnnotationStringRenameProcessorTest.java`

- [ ] **Step 4.1: Write three failing edge-case tests**

Add after the field rename test:

```java
public void testMethodAbsentInBoundaryOmittedClassIsSkipped() {
    // Join4 exists as a generated class but does NOT have a join() method (boundary omission)
    myFixture.addFileToProject("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.Permute;\n" +
            "@Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Join${i}\")\n" +
            "public class Join2 {\n" +
            "    public void join() {}\n" +
            "}");
    myFixture.addFileToProject("Join3.java",
            "package io.example;\n" +
            "public class Join3 {\n" +
            "    public void join() {}\n" +
            "}");
    myFixture.addFileToProject("Join4.java",
            "package io.example;\n" +
            "public class Join4 {\n" +
            "    // join() intentionally absent — boundary omission\n" +
            "}");

    PsiClass join2 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join2", GlobalSearchScope.allScope(getProject()));
    PsiMethod joinMethod = join2.findMethodsByName("join", false)[0];

    AnnotationStringRenameProcessor processor = new AnnotationStringRenameProcessor();
    Map<PsiElement, String> allRenames = new java.util.HashMap<>();
    // Must not throw — gracefully skips Join4 since join() is absent
    processor.prepareRenaming(joinMethod, "combine",
            allRenames, GlobalSearchScope.allScope(getProject()));

    PsiClass join4 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join4", GlobalSearchScope.allScope(getProject()));
    // Join3.join() IS in allRenames; Join4 has nothing to add — no NPE, no error
    PsiClass join3 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join3", GlobalSearchScope.allScope(getProject()));
    assertTrue("Join3.join() must still be in allRenames",
            allRenames.containsKey(join3.findMethodsByName("join", false)[0]));
    // Join4 has no join() — nothing from Join4 in allRenames
    for (PsiElement key : allRenames.keySet()) {
        if (key instanceof PsiMethod m) {
            assertFalse("No method from Join4 should be in allRenames",
                    join4.equals(m.getContainingClass()));
        }
    }
}

public void testRenameInNonTemplateClassProducesNoFamilyRenames() {
    // PlainClass has no @Permute annotation — renaming its method must not trigger propagation
    myFixture.configureByText("PlainClass.java",
            "package io.example;\n" +
            "public class PlainClass {\n" +
            "    public void join<caret>() {}\n" +
            "}");

    PsiClass plainClass = ((com.intellij.psi.PsiJavaFile) myFixture.getFile()).getClasses()[0];
    PsiMethod joinMethod = plainClass.findMethodsByName("join", false)[0];

    AnnotationStringRenameProcessor processor = new AnnotationStringRenameProcessor();
    Map<PsiElement, String> allRenames = new java.util.HashMap<>();
    processor.prepareRenaming(joinMethod, "combine",
            allRenames, GlobalSearchScope.allScope(getProject()));

    assertTrue("Non-template class must produce no family renames", allRenames.isEmpty());
}

public void testPermuteMethodAnnotatedSentinelIsSkipped() {
    myFixture.addFileToProject("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.*;\n" +
            "@Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Join${i}\")\n" +
            "public class Join2 {\n" +
            "    @PermuteMethod(varName=\"j\", from=\"1\", name=\"join${j}\")\n" +
            "    public void joinSentinel() {}\n" +
            "}");
    myFixture.addFileToProject("Join3.java",
            "package io.example;\n" +
            "public class Join3 {\n" +
            "    public void join1() {}\n" +
            "}");

    PsiClass join2 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join2", GlobalSearchScope.allScope(getProject()));
    assertNotNull(join2);
    PsiMethod sentinel = join2.findMethodsByName("joinSentinel", false)[0];

    AnnotationStringRenameProcessor processor = new AnnotationStringRenameProcessor();
    Map<PsiElement, String> allRenames = new java.util.HashMap<>();
    processor.prepareRenaming(sentinel, "mergeSentinel",
            allRenames, GlobalSearchScope.allScope(getProject()));

    // @PermuteMethod sentinel must NOT add any generated methods to allRenames
    PsiClass join3 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join3", GlobalSearchScope.allScope(getProject()));
    assertNotNull(join3);
    for (PsiElement key : allRenames.keySet()) {
        if (key instanceof PsiMethod m) {
            assertFalse("@PermuteMethod sentinel must not add Join3 methods to allRenames",
                    join3.equals(m.getContainingClass()));
        }
    }
}
```

- [ ] **Step 4.2: Run the three edge-case tests — expect PASS**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testMethodAbsentInBoundaryOmittedClassIsSkipped" \
  --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testRenameInNonTemplateClassProducesNoFamilyRenames" \
  --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testPermuteMethodAnnotatedSentinelIsSkipped"
```

Expected: all PASS — the implementation in Task 2 already handles all three cases correctly.

If any fail, diagnose and fix in `addGeneratedFamilyRenames()` before proceeding.

- [ ] **Step 4.3: Commit**

```bash
git add permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessorTest.java
git commit -m "test(plugin): add edge case tests for generated family rename propagation

- boundary omission: method absent in leaf class skipped gracefully
- non-template class: no propagation triggered
- @PermuteMethod sentinel: not propagated to differently-named generated methods

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Integration test — end-to-end atomic rename

**Files:**
- Test: `AnnotationStringRenameProcessorTest.java`

- [ ] **Step 5.1: Write the integration test**

Add after the edge-case tests:

```java
public void testEndToEndMethodRenameUpdatesGeneratedFileAndCallSite() {
    // Template
    PsiFile templateFile = myFixture.addFileToProject("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.Permute;\n" +
            "@Permute(varName=\"i\", from=\"3\", to=\"3\", className=\"Join${i}\")\n" +
            "public class Join2 {\n" +
            "    public void join() {}\n" +
            "}");

    // Generated sibling with a call site consumer
    PsiFile generatedFile = myFixture.addFileToProject("Join3.java",
            "package io.example;\n" +
            "public class Join3 {\n" +
            "    public void join() {}\n" +
            "}");

    PsiClass join2 = JavaPsiFacade.getInstance(getProject())
            .findClass("io.example.Join2", GlobalSearchScope.allScope(getProject()));
    assertNotNull(join2);
    PsiMethod joinMethod = join2.findMethodsByName("join", false)[0];

    // Full rename via fixture — invokes prepareRenaming + renameElement
    myFixture.renameElement(joinMethod, "combine");

    // Template method renamed
    assertTrue("Template method must be renamed to combine",
            templateFile.getText().contains("public void combine()"));
    assertFalse("Old method name must not remain in template",
            templateFile.getText().contains("public void join()"));

    // Generated sibling method renamed atomically
    assertTrue("Join3.join() must be renamed to combine",
            generatedFile.getText().contains("public void combine()"));
    assertFalse("Old method name must not remain in Join3",
            generatedFile.getText().contains("public void join()"));
}
```

- [ ] **Step 5.2: Run the integration test**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testEndToEndMethodRenameUpdatesGeneratedFileAndCallSite"
```

Expected: PASS — the implementation in Task 2 already handles this.

- [ ] **Step 5.3: Run the full test suite**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test
```

Expected: BUILD SUCCESSFUL, 64 tests, 0 failures (58 existing + 6 new).

- [ ] **Step 5.4: Commit and close issue**

```bash
git add permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessorTest.java
git commit -m "test(plugin): add end-to-end integration test for atomic family rename

Closes #25.

myFixture.renameElement() on a template method renames the method in the
generated sibling class atomically — verifies the full prepareRenaming()
pipeline fires correctly.

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

Then close the epic when done:

```bash
gh issue close 25 --repo mdproctor/permuplate --comment "Implemented and tested. 6 new tests (5 unit + 1 integration), all passing. addGeneratedFamilyRenames() in AnnotationStringRenameProcessor handles method and field rename propagation to generated sibling classes."
```

---

## Self-Review

**Spec coverage:**
- ✅ Method rename propagation → Task 1–2
- ✅ Field rename propagation → Task 3
- ✅ Boundary omission → Task 4 (testMethodAbsentInBoundaryOmittedClassIsSkipped)
- ✅ Non-template class → Task 4 (testRenameInNonTemplateClassProducesNoFamilyRenames)
- ✅ @PermuteMethod sentinel → Task 4 (testPermuteMethodAnnotatedSentinelIsSkipped)
- ✅ End-to-end integration → Task 5

**No placeholders** — all steps contain complete code.

**Type consistency** — `addGeneratedFamilyRenames()` signature is used identically across Tasks 2 and all test references.
