# PermuteElementResolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend rename redirect to handle methods, fields, and parameters in generated files by introducing a shared `PermuteElementResolver` utility that consolidates duplicated template-resolution logic.

**Architecture:** New `PermuteElementResolver` in the `index` package owns "given a PSI element in a generated file, find the corresponding template element." `AnnotationStringRenameProcessor.substituteElementToRename()` becomes a 3-line delegate. `PermuteFamilyFindUsagesAction.stripTrailingDigits()` is removed in favour of the consolidated version. All changes are test-driven; each task produces a passing build.

**Tech Stack:** IntelliJ Platform SDK, `BasePlatformTestCase`, Java 17 pattern matching, `ProjectFileIndex` PSI scan fallback.

---

## File Map

| Action | Path |
|---|---|
| Create | `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteElementResolver.java` |
| Create | `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/index/PermuteElementResolverTest.java` |
| Modify | `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java` |
| Modify | `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/navigation/PermuteFamilyFindUsagesAction.java` |
| Modify | `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/navigation/PermuteFamilyFindUsagesActionTest.java` |

Build command (run from `permuplate-intellij-plugin/`): `./gradlew test`

---

## Task 1: Create `PermuteElementResolver` with `stripTrailingDigits()` — consolidate duplicates

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteElementResolver.java`
- Create: `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/index/PermuteElementResolverTest.java`
- Modify: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java`
- Modify: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/navigation/PermuteFamilyFindUsagesAction.java`
- Modify: `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/navigation/PermuteFamilyFindUsagesActionTest.java`

- [ ] **Step 1: Write failing tests for `stripTrailingDigits` in new test class**

Create `PermuteElementResolverTest.java`:

```java
package io.quarkiverse.permuplate.intellij.index;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class PermuteElementResolverTest extends BasePlatformTestCase {

    // --- stripTrailingDigits ---

    public void testStripTrailingDigitsRemovesDigits() {
        assertEquals("join", PermuteElementResolver.stripTrailingDigits("join2"));
        assertEquals("c",    PermuteElementResolver.stripTrailingDigits("c2"));
        assertEquals("Join", PermuteElementResolver.stripTrailingDigits("Join10"));
    }

    public void testStripTrailingDigitsNoDigits() {
        assertEquals("join", PermuteElementResolver.stripTrailingDigits("join"));
    }

    public void testStripTrailingDigitsAllDigits() {
        assertEquals("", PermuteElementResolver.stripTrailingDigits("123"));
    }

    public void testStripTrailingDigitsEmptyString() {
        assertEquals("", PermuteElementResolver.stripTrailingDigits(""));
    }
}
```

- [ ] **Step 2: Run tests — verify they fail with "cannot find symbol"**

```bash
cd permuplate-intellij-plugin && ./gradlew test --tests "*.PermuteElementResolverTest"
```

Expected: compilation failure — `PermuteElementResolver` does not exist yet.

- [ ] **Step 3: Create `PermuteElementResolver` with just `stripTrailingDigits()`**

Create `PermuteElementResolver.java`:

```java
package io.quarkiverse.permuplate.intellij.index;

import org.jetbrains.annotations.NotNull;

public final class PermuteElementResolver {

    private PermuteElementResolver() {}

    /**
     * Strip trailing digits. "c3" → "c", "join2" → "join", "Join10" → "Join".
     */
    static String stripTrailingDigits(@NotNull String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return name.substring(0, i);
    }
}
```

- [ ] **Step 4: Run `stripTrailingDigits` tests — verify they pass**

```bash
./gradlew test --tests "*.PermuteElementResolverTest"
```

Expected: 4 tests PASS.

- [ ] **Step 5: Update `PermuteFamilyFindUsagesAction` to delegate `stripTrailingDigits`**

In `PermuteFamilyFindUsagesAction.java`, replace the private method (line 175):

```java
// DELETE this entire method:
static String stripTrailingDigits(@NotNull String name) {
    int i = name.length();
    while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
    return name.substring(0, i);
}
```

Add import and replace call sites — `findMatchingMember` uses it at line 161 and 166:

```java
import io.quarkiverse.permuplate.intellij.index.PermuteElementResolver;
```

Replace both usages of `stripTrailingDigits(` in `findMatchingMember`:

```java
@Nullable
static PsiElement findMatchingMember(@NotNull PsiClass cls,
                                      @NotNull String memberName,
                                      @NotNull PsiMember original) {
    String baseName = PermuteElementResolver.stripTrailingDigits(memberName);
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
```

- [ ] **Step 6: Update `AnnotationStringRenameProcessor` to delegate `stripTrailingDigits`**

In `AnnotationStringRenameProcessor.java`, the `static String stripTrailingDigits(String name)` method is at the bottom. Replace it with a delegation:

```java
// REPLACE the entire method body:
static String stripTrailingDigits(String name) {
    return PermuteElementResolver.stripTrailingDigits(name);
}
```

Add import at top:

```java
import io.quarkiverse.permuplate.intellij.index.PermuteElementResolver;
```

- [ ] **Step 7: Update `PermuteFamilyFindUsagesActionTest` — point `stripTrailingDigits` tests at resolver**

In `PermuteFamilyFindUsagesActionTest.java`, replace all four `stripTrailingDigits` test bodies to call `PermuteElementResolver.stripTrailingDigits(...)` instead of `PermuteFamilyFindUsagesAction.stripTrailingDigits(...)`:

```java
public void testStripTrailingDigitsRemovesDigits() {
    assertEquals("join", PermuteElementResolver.stripTrailingDigits("join2"));
    assertEquals("c",    PermuteElementResolver.stripTrailingDigits("c2"));
    assertEquals("Join", PermuteElementResolver.stripTrailingDigits("Join10"));
}

public void testStripTrailingDigitsNoDigits() {
    assertEquals("join", PermuteElementResolver.stripTrailingDigits("join"));
}

public void testStripTrailingDigitsAllDigits() {
    assertEquals("", PermuteElementResolver.stripTrailingDigits("123"));
}

public void testStripTrailingDigitsEmptyString() {
    assertEquals("", PermuteElementResolver.stripTrailingDigits(""));
}
```

Add import:

```java
import io.quarkiverse.permuplate.intellij.index.PermuteElementResolver;
```

- [ ] **Step 8: Run all tests — verify full suite still passes**

```bash
./gradlew test
```

Expected: 48 tests PASS (same count as before — no new tests yet, strip tests moved).

- [ ] **Step 9: Commit**

```bash
git add -p
git commit -m "refactor(plugin): consolidate stripTrailingDigits into PermuteElementResolver"
```

---

## Task 2: Move `findTemplateByPsiScan` → `PermuteElementResolver.findTemplateClass()`

**Files:**
- Modify: `PermuteElementResolver.java`
- Modify: `AnnotationStringRenameProcessor.java`
- Modify: `PermuteElementResolverTest.java`

- [ ] **Step 1: Write failing test for `findTemplateClass`**

Add to `PermuteElementResolverTest.java`:

```java
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;

public void testFindTemplateClassViaFallbackPsiScan() throws Exception {
    // A @Permute template exists in the project
    myFixture.addFileToProject("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.Permute;\n" +
            "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
            "public class Join2 {}");

    // Ask resolver to find the template for generated name "Join3"
    PsiClass template = PermuteElementResolver.findTemplateClass("Join3", getProject());

    assertNotNull("findTemplateClass must find Join2 for generated name Join3", template);
    assertEquals("Join2", template.getName());
}

public void testFindTemplateClassReturnsNullWhenNoTemplate() throws Exception {
    // No @Permute template in project
    PsiClass result = PermuteElementResolver.findTemplateClass("Unknown3", getProject());
    assertNull("No template → must return null", result);
}
```

- [ ] **Step 2: Run new tests — verify they fail**

```bash
./gradlew test --tests "*.PermuteElementResolverTest.testFindTemplateClass*"
```

Expected: FAIL — `findTemplateClass` method does not exist.

- [ ] **Step 3: Add `findTemplateClass()` to `PermuteElementResolver`**

Add these imports and methods to `PermuteElementResolver.java`:

```java
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;
```

```java
/**
 * Find the template PsiClass for a given generated class name.
 * Fast path: FileBasedIndex reverse lookup via PermuteFileDetector.
 * Fallback: PSI scan (used when index is not yet populated, e.g. in tests).
 */
@Nullable
public static PsiClass findTemplateClass(@NotNull String generatedName,
                                          @NotNull Project project) {
    // Fast path
    String templateName = PermuteFileDetector.templateNameFor(generatedName, project);
    if (templateName != null) {
        PermuteTemplateData data = PermuteFileDetector.templateDataFor(templateName, project);
        if (data != null) {
            VirtualFile vFile = LocalFileSystem.getInstance()
                    .findFileByPath(data.templateFilePath);
            if (vFile != null) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
                if (psiFile instanceof PsiJavaFile javaFile) {
                    for (PsiClass cls : javaFile.getClasses()) {
                        if (templateName.equals(cls.getName())) return cls;
                    }
                }
            }
        }
    }

    // Fallback: PSI scan
    PsiManager psiManager = PsiManager.getInstance(project);
    AtomicReference<PsiClass> found = new AtomicReference<>();

    ProjectFileIndex.getInstance(project).iterateContent(vFile -> {
        if (!vFile.getName().endsWith(".java")) return true;
        PsiFile psiFile = psiManager.findFile(vFile);
        if (!(psiFile instanceof PsiJavaFile javaFile)) return true;

        for (PsiClass cls : javaFile.getClasses()) {
            if (cls.isAnnotationType()) continue;
            for (PsiAnnotation ann : cls.getAnnotations()) {
                PsiJavaCodeReferenceElement ref = ann.getNameReferenceElement();
                if (ref == null || !"Permute".equals(ref.getReferenceName())) continue;

                PsiAnnotationMemberValue varVal = ann.findAttributeValue("varName");
                PsiAnnotationMemberValue classVal = ann.findAttributeValue("className");
                PsiAnnotationMemberValue fromVal = ann.findAttributeValue("from");
                PsiAnnotationMemberValue toVal = ann.findAttributeValue("to");

                String varName = varVal instanceof PsiLiteralExpression vlit
                        && vlit.getValue() instanceof String vs ? vs : null;
                String className = classVal instanceof PsiLiteralExpression clit
                        && clit.getValue() instanceof String cs ? cs : null;
                int from = fromVal instanceof PsiLiteralExpression flit
                        && flit.getValue() instanceof Integer fi ? fi : 1;
                int to = toVal instanceof PsiLiteralExpression tlit
                        && tlit.getValue() instanceof Integer ti ? ti : 1;

                if (varName == null || className == null) continue;
                String placeholder = "${" + varName + "}";
                for (int v = from; v <= to; v++) {
                    if (generatedName.equals(className.replace(placeholder, String.valueOf(v)))) {
                        found.set(cls);
                        return false;
                    }
                }
            }
        }
        return true;
    });

    return found.get();
}
```

- [ ] **Step 4: Run new tests — verify they pass**

```bash
./gradlew test --tests "*.PermuteElementResolverTest"
```

Expected: 6 tests PASS.

- [ ] **Step 5: Update `AnnotationStringRenameProcessor` — remove `findTemplateByPsiScan`, delegate to resolver**

In `AnnotationStringRenameProcessor.java`:

1. Delete the entire `findTemplateByPsiScan()` private method (lines ~124–160) and its two helper methods `getAnnotationStringAttr` and `getAnnotationIntAttr`.

2. In `substituteElementToRename()`, replace the two lookup blocks with a call to the resolver. The new body of `substituteElementToRename()` should be:

```java
@Override
public @Nullable PsiElement substituteElementToRename(@NotNull PsiElement element,
                                                        @Nullable Editor editor) {
    if (!(element instanceof PsiClass cls)) return element;
    VirtualFile vFile = cls.getContainingFile() != null
            ? cls.getContainingFile().getVirtualFile() : null;
    if (vFile == null || !PermuteFileDetector.isGeneratedFile(vFile)) return element;

    String name = cls.getName();
    if (name == null) return element;

    PsiClass templateClass = PermuteElementResolver.findTemplateClass(name, cls.getProject());
    return templateClass != null ? templateClass : element;
}
```

Remove the now-unused import for `LocalFileSystem` if it is no longer referenced elsewhere in the file.

- [ ] **Step 6: Run full suite**

```bash
./gradlew test
```

Expected: 48 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add -p
git commit -m "refactor(plugin): move findTemplateByPsiScan to PermuteElementResolver.findTemplateClass"
```

---

## Task 3: Add `resolveToTemplateElement()` for `PsiClass` — wire up rename processor

**Files:**
- Modify: `PermuteElementResolver.java`
- Modify: `AnnotationStringRenameProcessor.java`
- Modify: `PermuteElementResolverTest.java`

- [ ] **Step 1: Write failing test for class-level resolution**

Add to `PermuteElementResolverTest.java`:

```java
public void testResolvesGeneratedClassToTemplate() throws Exception {
    myFixture.addFileToProject("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.Permute;\n" +
            "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
            "public class Join2 {}");

    VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
            "target/generated-sources/permuplate/Join3.java",
            "package io.example;\npublic class Join3 {}");

    PsiClass join3 = ((PsiJavaFile) PsiManager.getInstance(getProject())
            .findFile(generatedVFile)).getClasses()[0];

    PsiElement result = PermuteElementResolver.resolveToTemplateElement(join3, null);

    assertTrue("Expected PsiClass redirect", result instanceof PsiClass);
    assertEquals("Expected Join2", "Join2", ((PsiClass) result).getName());
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
./gradlew test --tests "*.PermuteElementResolverTest.testResolvesGeneratedClassToTemplate"
```

Expected: FAIL — `resolveToTemplateElement` does not exist.

- [ ] **Step 3: Add `resolveToTemplateElement()` to `PermuteElementResolver` — class case only**

Add to `PermuteElementResolver.java`:

```java
import com.intellij.openapi.editor.Editor;

/**
 * Given a PSI element in a generated file, returns the corresponding template element.
 * Returns the original element unchanged if it is not in a generated file or no
 * template match is found (graceful fallthrough for all cases).
 *
 * Handles: PsiClass, PsiMethod, PsiField, PsiParameter.
 */
@Nullable
public static PsiElement resolveToTemplateElement(@NotNull PsiElement element,
                                                   @Nullable Editor editor) {
    PsiClass containingClass = getContainingClass(element);
    if (containingClass == null) return element;

    VirtualFile vFile = containingClass.getContainingFile() != null
            ? containingClass.getContainingFile().getVirtualFile() : null;
    if (vFile == null || !PermuteFileDetector.isGeneratedFile(vFile)) return element;

    String generatedClassName = containingClass.getName();
    if (generatedClassName == null) return element;

    PsiClass templateClass = findTemplateClass(generatedClassName, element.getProject());
    if (templateClass == null) return element;

    if (element instanceof PsiClass) return templateClass;

    return findMatchingTemplateElement(element, templateClass);
}

@Nullable
private static PsiClass getContainingClass(@NotNull PsiElement element) {
    if (element instanceof PsiClass cls) return cls;
    if (element instanceof PsiMember member) return member.getContainingClass();
    if (element instanceof PsiParameter param) {
        PsiElement scope = param.getDeclarationScope();
        if (scope instanceof PsiMethod method) return method.getContainingClass();
    }
    return null;
}

// Placeholder — extended in Task 4 and 5
@NotNull
private static PsiElement findMatchingTemplateElement(@NotNull PsiElement element,
                                                       @NotNull PsiClass templateClass) {
    return element; // graceful fallthrough until member resolution is added
}
```

- [ ] **Step 4: Update `AnnotationStringRenameProcessor.substituteElementToRename()` to fully delegate**

Replace the current body with:

```java
@Override
public @Nullable PsiElement substituteElementToRename(@NotNull PsiElement element,
                                                        @Nullable Editor editor) {
    return PermuteElementResolver.resolveToTemplateElement(element, editor);
}
```

- [ ] **Step 5: Run full suite**

```bash
./gradlew test
```

Expected: 48 tests PASS. The existing `testSubstituteElementToRenameRedirectsGeneratedToTemplate` and `testEndToEndRenameFromGeneratedFileUpdatesTemplateAnnotation` must still pass.

- [ ] **Step 6: Commit**

```bash
git add -p
git commit -m "feat(plugin): wire resolveToTemplateElement into substituteElementToRename"
```

---

## Task 4: Extend to `PsiMethod` and `PsiField` — with tests

**Files:**
- Modify: `PermuteElementResolver.java`
- Modify: `PermuteElementResolverTest.java`

- [ ] **Step 1: Write failing tests for method and field resolution**

Add to `PermuteElementResolverTest.java`:

```java
public void testResolvesGeneratedMethodToTemplateMethod() throws Exception {
    myFixture.addFileToProject("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.*;\n" +
            "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
            "public class Join2 {\n" +
            "    public void join2() {}\n" +
            "}");

    VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
            "target/generated-sources/permuplate/Join3.java",
            "package io.example;\n" +
            "public class Join3 {\n" +
            "    public void join3() {}\n" +
            "}");

    PsiJavaFile generatedFile = (PsiJavaFile) PsiManager.getInstance(getProject())
            .findFile(generatedVFile);
    PsiClass join3 = generatedFile.getClasses()[0];
    PsiMethod join3Method = join3.getMethods()[0];
    assertEquals("join3", join3Method.getName());

    PsiElement result = PermuteElementResolver.resolveToTemplateElement(join3Method, null);

    assertTrue("Expected PsiMethod", result instanceof PsiMethod);
    assertEquals("Expected join2 in template", "join2", ((PsiMethod) result).getName());
}

public void testResolvesGeneratedFieldToTemplateField() throws Exception {
    myFixture.addFileToProject("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.*;\n" +
            "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
            "public class Join2 {\n" +
            "    @PermuteDeclr(type=\"Callable${i}\", name=\"c${i}\")\n" +
            "    private Object c2;\n" +
            "}");

    VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
            "target/generated-sources/permuplate/Join3.java",
            "package io.example;\n" +
            "public class Join3 {\n" +
            "    private Object c3;\n" +
            "}");

    PsiJavaFile generatedFile = (PsiJavaFile) PsiManager.getInstance(getProject())
            .findFile(generatedVFile);
    PsiClass join3 = generatedFile.getClasses()[0];
    PsiField c3 = join3.getFields()[0];
    assertEquals("c3", c3.getName());

    PsiElement result = PermuteElementResolver.resolveToTemplateElement(c3, null);

    assertTrue("Expected PsiField", result instanceof PsiField);
    assertEquals("Expected c2 in template", "c2", ((PsiField) result).getName());
}
```

- [ ] **Step 2: Run new tests — verify they fail**

```bash
./gradlew test --tests "*.PermuteElementResolverTest.testResolvesGenerated*"
```

Expected: class test PASS (from Task 3), method and field tests FAIL — `findMatchingTemplateElement` returns original element.

- [ ] **Step 3: Implement method and field resolution in `findMatchingTemplateElement`**

Replace the placeholder `findMatchingTemplateElement` in `PermuteElementResolver.java`:

```java
@NotNull
private static PsiElement findMatchingTemplateElement(@NotNull PsiElement element,
                                                       @NotNull PsiClass templateClass) {
    if (element instanceof PsiMethod method) {
        String name = method.getName();
        String baseName = stripTrailingDigits(name);
        for (PsiMethod m : templateClass.getMethods()) {
            String mBase = stripTrailingDigits(m.getName());
            if (name.equals(m.getName()) || baseName.equals(mBase)) return m;
        }

    } else if (element instanceof PsiField field) {
        String name = field.getName();
        String baseName = stripTrailingDigits(name);
        for (PsiField f : templateClass.getFields()) {
            String fName = f.getName();
            if (fName == null) continue;
            if (name.equals(fName) || baseName.equals(stripTrailingDigits(fName))) return f;
        }

    } else if (element instanceof PsiParameter param) {
        // Handled in Task 5
    }

    return element; // graceful fallthrough
}
```

- [ ] **Step 4: Run full suite**

```bash
./gradlew test
```

Expected: 50 tests PASS (2 new method/field tests added).

- [ ] **Step 5: Commit**

```bash
git add -p
git commit -m "feat(plugin): resolve PsiMethod and PsiField from generated file to template"
```

---

## Task 5: Extend to `PsiParameter` — with test

**Files:**
- Modify: `PermuteElementResolver.java`
- Modify: `PermuteElementResolverTest.java`

- [ ] **Step 1: Write failing test for parameter resolution**

Add to `PermuteElementResolverTest.java`:

```java
public void testResolvesGeneratedParameterToTemplateParameter() throws Exception {
    // Template has sentinel parameter o1 (from @PermuteParam, generates o1..o(i-1))
    myFixture.addFileToProject("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.*;\n" +
            "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
            "public class Join2 {\n" +
            "    public void join2(@PermuteParam(varName=\"j\", from=\"1\", to=\"${i-1}\", " +
            "type=\"Object\", name=\"o${j}\") Object o1) {}\n" +
            "}");

    // Generated Join3 has expanded params o1, o2
    VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
            "target/generated-sources/permuplate/Join3.java",
            "package io.example;\n" +
            "public class Join3 {\n" +
            "    public void join3(Object o1, Object o2) {}\n" +
            "}");

    PsiJavaFile generatedFile = (PsiJavaFile) PsiManager.getInstance(getProject())
            .findFile(generatedVFile);
    PsiClass join3 = generatedFile.getClasses()[0];
    PsiMethod join3Method = join3.getMethods()[0];
    // o2 is the second parameter
    PsiParameter o2 = join3Method.getParameterList().getParameters()[1];
    assertEquals("o2", o2.getName());

    PsiElement result = PermuteElementResolver.resolveToTemplateElement(o2, null);

    assertTrue("Expected PsiParameter", result instanceof PsiParameter);
    assertEquals("Expected o1 sentinel in template", "o1", ((PsiParameter) result).getName());
}
```

- [ ] **Step 2: Run new test — verify it fails**

```bash
./gradlew test --tests "*.PermuteElementResolverTest.testResolvesGeneratedParameterToTemplateParameter"
```

Expected: FAIL — parameter branch returns original element.

- [ ] **Step 3: Implement parameter resolution in `findMatchingTemplateElement`**

Replace the `else if (element instanceof PsiParameter param)` placeholder block:

```java
} else if (element instanceof PsiParameter param) {
    // Find the template method by base name match on the containing generated method
    PsiElement scope = param.getDeclarationScope();
    if (!(scope instanceof PsiMethod generatedMethod)) return element;

    String methodBaseName = stripTrailingDigits(generatedMethod.getName());
    PsiMethod templateMethod = null;
    for (PsiMethod m : templateClass.getMethods()) {
        if (methodBaseName.equals(stripTrailingDigits(m.getName()))) {
            templateMethod = m;
            break;
        }
    }
    if (templateMethod == null) return element;

    // Find matching parameter by base name
    String paramName = param.getName();
    if (paramName == null) return element;
    String paramBase = stripTrailingDigits(paramName);
    for (PsiParameter p : templateMethod.getParameterList().getParameters()) {
        String pName = p.getName();
        if (pName != null && paramBase.equals(stripTrailingDigits(pName))) return p;
    }
}
```

- [ ] **Step 4: Run full suite**

```bash
./gradlew test
```

Expected: 51 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -p
git commit -m "feat(plugin): resolve PsiParameter from generated file to template sentinel"
```

---

## Task 6: Negative paths + run full suite

**Files:**
- Modify: `PermuteElementResolverTest.java`

- [ ] **Step 1: Write negative path tests**

Add to `PermuteElementResolverTest.java`:

```java
public void testReturnsElementForNonGeneratedFile() {
    // A class in src/main/java — NOT a generated file
    myFixture.configureByText("Join2.java",
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.Permute;\n" +
            "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
            "public class Join2 {}");

    PsiClass join2 = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
    PsiElement result = PermuteElementResolver.resolveToTemplateElement(join2, null);

    assertSame("Non-generated file: must return element unchanged", join2, result);
}

public void testReturnsElementWhenNoTemplateInProject() throws Exception {
    // Generated file path, but no @Permute template exists anywhere in project
    VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
            "target/generated-sources/permuplate/Unknown3.java",
            "package io.example;\npublic class Unknown3 {}");

    PsiClass unknown3 = ((PsiJavaFile) PsiManager.getInstance(getProject())
            .findFile(generatedVFile)).getClasses()[0];

    PsiElement result = PermuteElementResolver.resolveToTemplateElement(unknown3, null);

    assertSame("No template: must return element unchanged", unknown3, result);
}
```

- [ ] **Step 2: Run new tests — verify they pass immediately (no production change needed)**

```bash
./gradlew test --tests "*.PermuteElementResolverTest.testReturns*"
```

Expected: 2 tests PASS — graceful fallthrough is already implemented.

- [ ] **Step 3: Run full suite — final verification**

```bash
./gradlew test
```

Expected: 53 tests PASS. No regressions.

- [ ] **Step 4: Commit**

```bash
git add -p
git commit -m "test(plugin): add negative path tests for PermuteElementResolver"
```

---

## Self-Review

**Spec coverage:**
- ✅ `PermuteElementResolver` created in `index` package
- ✅ `resolveToTemplateElement()` public entry point — handles PsiClass, PsiMethod, PsiField, PsiParameter
- ✅ `findTemplateClass()` public helper — fast path + PSI fallback
- ✅ `stripTrailingDigits()` consolidated — private copy removed from `AnnotationStringRenameProcessor` and `PermuteFamilyFindUsagesAction`
- ✅ `AnnotationStringRenameProcessor.substituteElementToRename()` delegates to resolver
- ✅ All 7 specified tests present: class, method, field, parameter, non-generated, no-template, stripTrailingDigits
- ✅ `PermuteMethodNavigator` untouched — resolver is ready for it

**Placeholder scan:** No TBDs, no "similar to above", all code blocks complete.

**Type consistency:**
- `resolveToTemplateElement` signature matches across Tasks 3–6
- `findMatchingTemplateElement` returns `@NotNull PsiElement` (graceful fallthrough, never null) — consistent with caller in `resolveToTemplateElement`
- `findTemplateClass` returns `@Nullable PsiClass` — consistent with null-check in `resolveToTemplateElement`
- `stripTrailingDigits` takes `@NotNull String`, returns `String` — consistent across all call sites
