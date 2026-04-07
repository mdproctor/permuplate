# Permuplate IntelliJ Plugin — Foundation & Rename — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Gradle scaffold, persistent index of Permuplate template families, and an annotation-string-aware rename processor — delivering working, atomically-undoable rename of annotation strings alongside Java renames.

**Architecture:** A `FileBasedIndexExtension` pair (forward: template→family, reverse: generated→template) persists the family map across IDE restarts. `AnnotationStringRenameProcessor` hooks IntelliJ's rename pipeline via `prepareRenaming()` and applies string updates in `renameElement()` using a thread-local to carry computed changes. `GeneratedFileRenameHandler` intercepts renames inside generated files and redirects to the template.

**Tech Stack:** Java 17, Gradle 8 + `intellij-platform-gradle-plugin` 2.2.0, IntelliJ Platform 2023.2, `permuplate-ide-support` (algorithm jar, bundled), JUnit4-style IntelliJ test fixtures (`BasePlatformTestCase`)

**Note on worktree:** No worktree was created during brainstorming. Either work on a feature branch (`git checkout -b feat/intellij-plugin`) or use `superpowers:using-git-worktrees` before starting.

---

## File Map

```
permuplate-intellij-plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/gradle-wrapper.properties
├── .gitignore
└── src/
    ├── main/
    │   ├── java/io/quarkiverse/permuplate/intellij/
    │   │   ├── index/
    │   │   │   ├── PermuteTemplateData.java          Task 2
    │   │   │   ├── PermuteTemplateDataExternalizer.java  Task 2
    │   │   │   ├── PermuteTemplateIndex.java          Task 3
    │   │   │   ├── PermuteGeneratedIndex.java         Task 4
    │   │   │   └── PermuteFileDetector.java           Task 5
    │   │   └── rename/
    │   │       ├── AnnotationStringRenameProcessor.java  Task 7 + 9
    │   │       ├── DisambiguationDialog.java          Task 8
    │   │       └── GeneratedFileRenameHandler.java    Task 10
    │   └── resources/META-INF/plugin.xml              Task 1 (stub), Task 12 (final)
    └── test/
        └── java/io/quarkiverse/permuplate/intellij/
            ├── index/PermuteTemplateIndexTest.java    Task 6
            └── rename/AnnotationStringRenameProcessorTest.java  Task 11
```

---

### Task 1: Gradle project scaffold

**Files:**
- Create: `permuplate-intellij-plugin/build.gradle.kts`
- Create: `permuplate-intellij-plugin/settings.gradle.kts`
- Create: `permuplate-intellij-plugin/gradle/wrapper/gradle-wrapper.properties`
- Create: `permuplate-intellij-plugin/.gitignore`
- Create: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "permuplate-intellij-plugin"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

- [ ] **Step 2: Create `build.gradle.kts`**

```kotlin
plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.0"
}

group = "io.quarkiverse.permuplate"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Algorithm library — bundled into plugin zip
    implementation(files("../permuplate-ide-support/target/quarkus-permuplate-ide-support-1.0.0-SNAPSHOT.jar"))

    intellijPlatform {
        intellijIdeaCommunity("2023.2")
        bundledPlugin("com.intellij.java")   // Java PSI APIs
        instrumentationTools()               // Required for form instrumentation
    }

    // Annotation types available in test fixtures
    testImplementation(files(
        "../permuplate-annotations/target/quarkus-permuplate-annotations-1.0.0-SNAPSHOT.jar"
    ))
}

intellijPlatform {
    pluginConfiguration {
        id = "io.quarkiverse.permuplate"
        name = "Permuplate"
        version = "1.0.0-SNAPSHOT"
        description = "IDE support for Permuplate annotation-driven code generation"
        ideaVersion {
            sinceBuild = "232"  // 2023.2
        }
    }
}
```

- [ ] **Step 3: Create `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.6-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 4: Bootstrap the Gradle wrapper (downloads `gradle-wrapper.jar`)**

Run from inside `permuplate-intellij-plugin/`:
```bash
cd permuplate-intellij-plugin
gradle wrapper --gradle-version 8.6
```

Expected: `gradle/wrapper/gradle-wrapper.jar` created, `gradlew` and `gradlew.bat` created.

- [ ] **Step 5: Create `.gitignore`**

```
.gradle/
build/
*.iml
.idea/
```

- [ ] **Step 6: Create stub `src/main/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
    <id>io.quarkiverse.permuplate</id>
    <name>Permuplate</name>
    <version>1.0.0-SNAPSHOT</version>
    <description>IDE support for Permuplate annotation-driven code generation</description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Registered in Task 12 -->
    </extensions>
</idea-plugin>
```

- [ ] **Step 7: Verify the build resolves**

```bash
cd permuplate-intellij-plugin
./gradlew dependencies --configuration compileClasspath
```

Expected: resolves without error; IntelliJ Platform jars listed.

- [ ] **Step 8: Commit**

```bash
git add permuplate-intellij-plugin/
git commit -m "feat(plugin): Gradle scaffold for IntelliJ plugin module"
```

---

### Task 2: PermuteTemplateData model and serializer

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateData.java`
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateDataExternalizer.java`

- [ ] **Step 1: Create `PermuteTemplateData.java`**

```java
package io.quarkiverse.permuplate.intellij.index;

import java.io.Serializable;
import java.util.List;

/**
 * Persisted data for one @Permute-annotated template class.
 * Stored in the forward index (key = template simple class name).
 */
public final class PermuteTemplateData implements Serializable {

    public final String varName;
    public final int from;
    public final int to;
    public final String classNameTemplate;        // e.g. "Join${i}"
    public final List<String> generatedNames;     // e.g. ["Join3","Join4",…,"Join10"]
    public final String templateFilePath;         // absolute path to template .java file
    /** All annotation string attribute values found on class members:
     *  @PermuteDeclr(type=…,name=…), @PermuteParam(type=…,name=…),
     *  @PermuteTypeParam(name=…), @PermuteMethod(name=…).
     *  Used by the rename processor to find affected strings in O(n) at rename time. */
    public final List<String> memberAnnotationStrings;

    public PermuteTemplateData(String varName, int from, int to,
                               String classNameTemplate, List<String> generatedNames,
                               String templateFilePath, List<String> memberAnnotationStrings) {
        this.varName = varName;
        this.from = from;
        this.to = to;
        this.classNameTemplate = classNameTemplate;
        this.generatedNames = List.copyOf(generatedNames);
        this.templateFilePath = templateFilePath;
        this.memberAnnotationStrings = List.copyOf(memberAnnotationStrings);
    }
}
```

- [ ] **Step 2: Create `PermuteTemplateDataExternalizer.java`**

```java
package io.quarkiverse.permuplate.intellij.index;

import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.IOUtil;
import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PermuteTemplateDataExternalizer implements DataExternalizer<PermuteTemplateData> {

    public static final PermuteTemplateDataExternalizer INSTANCE = new PermuteTemplateDataExternalizer();

    @Override
    public void save(@NotNull DataOutput out, PermuteTemplateData v) throws IOException {
        IOUtil.writeUTF(out, v.varName);
        out.writeInt(v.from);
        out.writeInt(v.to);
        IOUtil.writeUTF(out, v.classNameTemplate);
        out.writeInt(v.generatedNames.size());
        for (String name : v.generatedNames) IOUtil.writeUTF(out, name);
        IOUtil.writeUTF(out, v.templateFilePath);
        out.writeInt(v.memberAnnotationStrings.size());
        for (String s : v.memberAnnotationStrings) IOUtil.writeUTF(out, s);
    }

    @Override
    public PermuteTemplateData read(@NotNull DataInput in) throws IOException {
        String varName = IOUtil.readUTF(in);
        int from = in.readInt();
        int to = in.readInt();
        String classNameTemplate = IOUtil.readUTF(in);
        int gnSize = in.readInt();
        List<String> generatedNames = new ArrayList<>(gnSize);
        for (int i = 0; i < gnSize; i++) generatedNames.add(IOUtil.readUTF(in));
        String templateFilePath = IOUtil.readUTF(in);
        int masSize = in.readInt();
        List<String> memberAnnotationStrings = new ArrayList<>(masSize);
        for (int i = 0; i < masSize; i++) memberAnnotationStrings.add(IOUtil.readUTF(in));
        return new PermuteTemplateData(varName, from, to, classNameTemplate,
                generatedNames, templateFilePath, memberAnnotationStrings);
    }
}
```

- [ ] **Step 3: Verify the module compiles**

```bash
cd permuplate-intellij-plugin
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL, no errors.

- [ ] **Step 4: Commit**

```bash
git add permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/
git commit -m "feat(plugin): PermuteTemplateData model and externalizer"
```

---

### Task 3: PermuteTemplateIndex (forward index)

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateIndex.java`

- [ ] **Step 1: Create `PermuteTemplateIndex.java`**

```java
package io.quarkiverse.permuplate.intellij.index;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Forward index: template class simple name → PermuteTemplateData.
 * Key:   "Join2"
 * Value: PermuteTemplateData{varName="i", from=3, to=10, generatedNames=["Join3"…], …}
 */
public class PermuteTemplateIndex extends FileBasedIndexExtension<String, PermuteTemplateData> {

    public static final ID<String, PermuteTemplateData> NAME =
            ID.create("permuplate.template.forward");

    private static final String PERMUTE_FQN =
            "io.quarkiverse.permuplate.annotations.Permute";
    private static final Set<String> MEMBER_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.annotations.PermuteDeclr",
            "io.quarkiverse.permuplate.annotations.PermuteParam",
            "io.quarkiverse.permuplate.annotations.PermuteTypeParam",
            "io.quarkiverse.permuplate.annotations.PermuteMethod"
    );

    @Override public @NotNull ID<String, PermuteTemplateData> getName() { return NAME; }

    @Override
    public @NotNull DataIndexer<String, PermuteTemplateData, FileContent> getIndexer() {
        return inputData -> {
            Map<String, PermuteTemplateData> result = new HashMap<>();
            PsiFile psiFile = inputData.getPsiFile();
            if (!(psiFile instanceof PsiJavaFile javaFile)) return result;

            for (PsiClass cls : javaFile.getClasses()) {
                PsiAnnotation permute = cls.getAnnotation(PERMUTE_FQN);
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

    private static String getStringAttr(PsiAnnotation ann, String attr) {
        PsiAnnotationMemberValue v = ann.findAttributeValue(attr);
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
        return null;
    }

    private static int getIntAttr(PsiAnnotation ann, String attr, int defaultVal) {
        PsiAnnotationMemberValue v = ann.findAttributeValue(attr);
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof Integer i) return i;
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
     *  @PermuteTypeParam / @PermuteMethod on class members. */
    private static List<String> collectMemberAnnotationStrings(PsiClass cls) {
        List<String> strings = new ArrayList<>();
        for (PsiMember member : getAllMembers(cls)) {
            for (PsiAnnotation ann : member.getAnnotations()) {
                if (!MEMBER_ANNOTATION_FQNS.contains(ann.getQualifiedName())) continue;
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

    @Override public int getVersion() { return 1; }

    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return (VirtualFile file) -> "java".equals(file.getExtension());
    }

    @Override public boolean dependsOnFileContent() { return true; }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateIndex.java
git commit -m "feat(plugin): PermuteTemplateIndex forward index"
```

---

### Task 4: PermuteGeneratedIndex (reverse index)

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteGeneratedIndex.java`

- [ ] **Step 1: Create `PermuteGeneratedIndex.java`**

```java
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
 * Reverse index: generated class simple name → template class simple name.
 * Key:   "Join4"
 * Value: "Join2"
 *
 * Emitted by scanning the same @Permute annotations as PermuteTemplateIndex
 * and expanding generatedNames. One file scan, two indexes.
 */
public class PermuteGeneratedIndex extends FileBasedIndexExtension<String, String> {

    public static final ID<String, String> NAME =
            ID.create("permuplate.template.reverse");

    private static final String PERMUTE_FQN =
            "io.quarkiverse.permuplate.annotations.Permute";

    @Override public @NotNull ID<String, String> getName() { return NAME; }

    @Override
    public @NotNull DataIndexer<String, String, FileContent> getIndexer() {
        return inputData -> {
            Map<String, String> result = new HashMap<>();
            PsiFile psiFile = inputData.getPsiFile();
            if (!(psiFile instanceof PsiJavaFile javaFile)) return result;

            for (PsiClass cls : javaFile.getClasses()) {
                PsiAnnotation permute = cls.getAnnotation(PERMUTE_FQN);
                if (permute == null) continue;

                String templateName = cls.getName();
                if (templateName == null) continue;

                String varName   = getStringAttr(permute, "varName");
                int    from      = getIntAttr(permute, "from", 1);
                int    to        = getIntAttr(permute, "to", 1);
                String className = getStringAttr(permute, "className");
                if (varName == null || className == null) continue;

                String placeholder = "${" + varName + "}";
                for (int v = from; v <= to; v++) {
                    String generatedName = className.replace(placeholder, String.valueOf(v));
                    result.put(generatedName, templateName);
                }
            }
            return result;
        };
    }

    private static String getStringAttr(PsiAnnotation ann, String attr) {
        PsiAnnotationMemberValue v = ann.findAttributeValue(attr);
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
        return null;
    }

    private static int getIntAttr(PsiAnnotation ann, String attr, int defaultVal) {
        PsiAnnotationMemberValue v = ann.findAttributeValue(attr);
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof Integer i) return i;
        return defaultVal;
    }

    @Override public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override public @NotNull DataExternalizer<String> getValueExternalizer() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override public int getVersion() { return 1; }

    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return (VirtualFile file) -> "java".equals(file.getExtension());
    }

    @Override public boolean dependsOnFileContent() { return true; }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteGeneratedIndex.java
git commit -m "feat(plugin): PermuteGeneratedIndex reverse index"
```

---

### Task 5: PermuteFileDetector (query utility)

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteFileDetector.java`

- [ ] **Step 1: Create `PermuteFileDetector.java`**

```java
package io.quarkiverse.permuplate.intellij.index;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * Utility facade over both indexes. All plugin components use this class
 * rather than calling FileBasedIndex directly.
 */
public final class PermuteFileDetector {

    private PermuteFileDetector() {}

    /** True if this class is a @Permute template. */
    public static boolean isTemplate(@NotNull PsiClass cls) {
        String name = cls.getName();
        if (name == null) return false;
        return !getForwardValues(name, cls.getProject()).isEmpty();
    }

    /** True if this class was generated by a @Permute template (lives in target/generated-sources). */
    public static boolean isGenerated(@NotNull PsiClass cls) {
        String name = cls.getName();
        if (name == null) return false;
        return !getReverseValues(name, cls.getProject()).isEmpty();
    }

    /** True if this virtual file is under a generated-sources directory. */
    public static boolean isGeneratedFile(@NotNull VirtualFile file) {
        String path = file.getPath();
        return path.contains("/target/generated-sources/") || path.contains("\\target\\generated-sources\\");
    }

    /**
     * For a template class name, returns its PermuteTemplateData (null if not a template).
     */
    @Nullable
    public static PermuteTemplateData templateDataFor(@NotNull String templateSimpleName,
                                                       @NotNull Project project) {
        List<PermuteTemplateData> values = getForwardValues(templateSimpleName, project);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * For a generated class name, returns the template class simple name (null if not generated).
     */
    @Nullable
    public static String templateNameFor(@NotNull String generatedSimpleName,
                                          @NotNull Project project) {
        List<String> values = getReverseValues(generatedSimpleName, project);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * Returns all template simple names whose memberAnnotationStrings contain the given literal
     * as a substring. Used by the rename processor to find cross-family annotation string refs.
     * Linear scan — acceptable since it only runs at rename time.
     */
    public static Collection<String> templatesReferencingLiteral(@NotNull String literal,
                                                                   @NotNull Project project) {
        List<String> result = new java.util.ArrayList<>();
        FileBasedIndex index = FileBasedIndex.getInstance();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        index.processAllKeys(PermuteTemplateIndex.NAME, key -> {
            List<PermuteTemplateData> values = index.getValues(PermuteTemplateIndex.NAME, key, scope);
            for (PermuteTemplateData data : values) {
                for (String s : data.memberAnnotationStrings) {
                    if (s.contains(literal)) {
                        result.add(key);
                        return true; // found in this template, move to next key
                    }
                }
            }
            return true;
        }, project);

        return result;
    }

    // --- private helpers ---

    private static List<PermuteTemplateData> getForwardValues(String key, Project project) {
        return FileBasedIndex.getInstance()
                .getValues(PermuteTemplateIndex.NAME, key, GlobalSearchScope.projectScope(project));
    }

    private static List<String> getReverseValues(String key, Project project) {
        return FileBasedIndex.getInstance()
                .getValues(PermuteGeneratedIndex.NAME, key, GlobalSearchScope.projectScope(project));
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/index/PermuteFileDetector.java
git commit -m "feat(plugin): PermuteFileDetector query facade over both indexes"
```

---

### Task 6: Index tests

**Files:**
- Create: `permuplate-intellij-plugin/src/test/java/io/quarkiverse/permuplate/intellij/index/PermuteTemplateIndexTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.quarkiverse.permuplate.intellij.index;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.List;

public class PermuteTemplateIndexTest extends BasePlatformTestCase {

    // Test: forward index contains the template class with correct data
    public void testForwardIndexContainsTemplate() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {}");

        FileBasedIndex index = FileBasedIndex.getInstance();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(getProject());

        List<PermuteTemplateData> values = index.getValues(PermuteTemplateIndex.NAME, "Join2", scope);
        assertEquals(1, values.size());

        PermuteTemplateData data = values.get(0);
        assertEquals("i", data.varName);
        assertEquals(3, data.from);
        assertEquals(5, data.to);
        assertEquals("Join${i}", data.classNameTemplate);
        assertEquals(List.of("Join3", "Join4", "Join5"), data.generatedNames);
    }

    // Test: reverse index maps each generated name back to the template
    public void testReverseIndexMapsGeneratedToTemplate() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {}");

        FileBasedIndex index = FileBasedIndex.getInstance();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(getProject());

        assertEquals(List.of("Join2"), index.getValues(PermuteGeneratedIndex.NAME, "Join3", scope));
        assertEquals(List.of("Join2"), index.getValues(PermuteGeneratedIndex.NAME, "Join4", scope));
        assertEquals(List.of("Join2"), index.getValues(PermuteGeneratedIndex.NAME, "Join5", scope));
        assertTrue(index.getValues(PermuteGeneratedIndex.NAME, "Join2", scope).isEmpty());
    }

    // Test: non-@Permute class is not indexed
    public void testNonTemplateClassNotIndexed() {
        myFixture.configureByText("Foo.java",
                "package io.example;\n" +
                "public class Foo {}");

        List<PermuteTemplateData> values = FileBasedIndex.getInstance()
                .getValues(PermuteTemplateIndex.NAME, "Foo",
                        GlobalSearchScope.projectScope(getProject()));
        assertTrue(values.isEmpty());
    }

    // Test: member annotation strings are collected
    public void testMemberAnnotationStringsCollected() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"c${i}\")\n" +
                "    private Object c2;\n" +
                "}");

        List<PermuteTemplateData> values = FileBasedIndex.getInstance()
                .getValues(PermuteTemplateIndex.NAME, "Join2",
                        GlobalSearchScope.projectScope(getProject()));
        assertEquals(1, values.size());

        List<String> memberStrings = values.get(0).memberAnnotationStrings;
        assertTrue(memberStrings.contains("Callable${i}"));
        assertTrue(memberStrings.contains("c${i}"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (indexes not registered yet)**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.index.PermuteTemplateIndexTest"
```

Expected: FAIL — index returns empty results (not registered in plugin.xml yet).

- [ ] **Step 3: Register the indexes in `plugin.xml`**

```xml
<idea-plugin>
    <id>io.quarkiverse.permuplate</id>
    <name>Permuplate</name>
    <version>1.0.0-SNAPSHOT</version>
    <description>IDE support for Permuplate annotation-driven code generation</description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>

    <extensions defaultExtensionNs="com.intellij">
        <fileBasedIndex implementation="io.quarkiverse.permuplate.intellij.index.PermuteTemplateIndex"/>
        <fileBasedIndex implementation="io.quarkiverse.permuplate.intellij.index.PermuteGeneratedIndex"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 4: Run tests again to verify they pass**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.index.PermuteTemplateIndexTest"
```

Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "test(plugin): PermuteTemplateIndex and PermuteGeneratedIndex tests passing"
```

---

### Task 7: AnnotationStringRenameProcessor — class rename

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java`

- [ ] **Step 1: Write the failing test first**

Add to `AnnotationStringRenameProcessorTest.java` (create the file):

```java
package io.quarkiverse.permuplate.intellij.rename;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class AnnotationStringRenameProcessorTest extends BasePlatformTestCase {

    public void testClassRenameUpdatesClassNameAttribute() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join<caret>2 {}");

        myFixture.renameElementAtCaret("Merge2");

        myFixture.checkResult(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Merge${i}\")\n" +
                "public class Merge2 {}");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testClassRenameUpdatesClassNameAttribute"
```

Expected: FAIL — `className` attribute unchanged after rename.

- [ ] **Step 3: Create `AnnotationStringRenameProcessor.java`**

```java
package io.quarkiverse.permuplate.intellij.rename;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.refactoring.rename.RenameUtil;
import com.intellij.usageView.UsageInfo;
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import io.quarkiverse.permuplate.ide.RenameResult;
import io.quarkiverse.permuplate.intellij.index.PermuteFileDetector;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Participates in IntelliJ's rename pipeline to update Permuplate annotation
 * string attributes atomically with the Java rename.
 *
 * Strategy: prepareRenaming() computes the new annotation string values and
 * stores them in a ThreadLocal. renameElement() applies them via
 * ElementManipulators.handleContentChange() in the same write action,
 * keeping everything in one undo group.
 */
public class AnnotationStringRenameProcessor extends RenamePsiElementProcessor {

    private static final String PERMUTE_FQN =
            "io.quarkiverse.permuplate.annotations.Permute";
    private static final Set<String> ALL_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.annotations.Permute",
            "io.quarkiverse.permuplate.annotations.PermuteDeclr",
            "io.quarkiverse.permuplate.annotations.PermuteParam",
            "io.quarkiverse.permuplate.annotations.PermuteTypeParam",
            "io.quarkiverse.permuplate.annotations.PermuteMethod"
    );

    /** Carries computed literal updates from prepareRenaming() to renameElement(). */
    private final ThreadLocal<List<Pair<PsiLiteralExpression, String>>> pendingUpdates =
            ThreadLocal.withInitial(ArrayList::new);

    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        // Broad check — prepareRenaming() returns fast if no annotation strings affected
        return element instanceof PsiClass
                || element instanceof PsiMethod
                || element instanceof PsiField
                || element instanceof PsiParameter
                || element instanceof PsiTypeParameter;
    }

    @Override
    public void prepareRenaming(@NotNull PsiElement element, @NotNull String newName,
                                @NotNull Map<PsiElement, String> allRenames,
                                @NotNull com.intellij.psi.search.SearchScope scope) {
        pendingUpdates.get().clear();

        if (!(element instanceof PsiClass cls)) return; // Task 9 extends for other types
        String oldName = cls.getName();
        if (oldName == null) return;

        // Extract the family literal by stripping trailing digits
        // e.g. "Join2" → "Join", "Merge2" → "Merge"
        String oldLiteral = stripTrailingDigits(oldName);

        // Find annotation string literals in this file that reference the old family
        List<PsiLiteralExpression> affected = findAffectedLiterals(cls, oldLiteral);

        List<Pair<PsiLiteralExpression, String>> updates = new ArrayList<>();

        for (PsiLiteralExpression literal : affected) {
            String currentValue = (String) literal.getValue();
            if (currentValue == null) continue;

            AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(currentValue);
            RenameResult result = AnnotationStringAlgorithm.computeRename(template, oldName, newName);

            if (result instanceof RenameResult.Updated updated) {
                updates.add(Pair.create(literal, updated.newTemplate()));
            }
            // NeedsDisambiguation: DisambiguationDialog wired in Task 8 — skipped here
            // NoMatch: skip
        }

        pendingUpdates.get().addAll(updates);
    }

    @Override
    public void renameElement(@NotNull PsiElement element, @NotNull String newName,
                               @NotNull UsageInfo[] usages,
                               @Nullable RefactoringElementListener listener)
            throws com.intellij.util.IncorrectOperationException {

        // Apply the main Java rename first
        RenameUtil.doRenameGenericNamedElement(element, newName, usages, listener);

        // Then apply annotation string updates in the same write action (same undo group)
        List<Pair<PsiLiteralExpression, String>> updates = pendingUpdates.get();
        for (Pair<PsiLiteralExpression, String> update : updates) {
            PsiLiteralExpression literal = update.first;
            if (literal.isValid()) {
                ElementManipulators.handleContentChange(literal, update.second);
            }
        }
        pendingUpdates.get().clear();
    }

    // --- private helpers ---

    private static String stripTrailingDigits(String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return name.substring(0, i);
    }

    /**
     * Find all PsiLiteralExpression nodes inside Permuplate annotation attributes
     * in the same file as cls, whose string value contains oldLiteral as a substring.
     */
    private static List<PsiLiteralExpression> findAffectedLiterals(PsiClass cls, String oldLiteral) {
        PsiFile file = cls.getContainingFile();
        List<PsiLiteralExpression> result = new ArrayList<>();

        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (!ALL_ANNOTATION_FQNS.contains(annotation.getQualifiedName())) return;

                for (PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
                    if (pair.getValue() instanceof PsiLiteralExpression lit
                            && lit.getValue() instanceof String s
                            && s.contains(oldLiteral)) {
                        result.add(lit);
                    }
                }
            }
        });

        return result;
    }
}
```

- [ ] **Step 4: Register the processor in `plugin.xml`**

Add inside `<extensions defaultExtensionNs="com.intellij">`:
```xml
<renamePsiElementProcessor
    implementation="io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessor"/>
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testClassRenameUpdatesClassNameAttribute"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): AnnotationStringRenameProcessor — class rename updates className string"
```

---

### Task 8: DisambiguationDialog

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/DisambiguationDialog.java`

- [ ] **Step 1: Create `DisambiguationDialog.java`**

```java
package io.quarkiverse.permuplate.intellij.rename;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiLiteralExpression;
import io.quarkiverse.permuplate.ide.RenameResult;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Shown when AnnotationStringAlgorithm.computeRename() returns NeedsDisambiguation.
 * Presents each affected annotation string with a pre-filled text field.
 * User confirms or adjusts; confirmed values are returned as resolved updates.
 */
public class DisambiguationDialog extends DialogWrapper {

    private final List<Pair<PsiLiteralExpression, RenameResult.NeedsDisambiguation>> cases;
    private final String oldName;
    private final String newName;
    private final List<JTextField> fields = new ArrayList<>();

    public DisambiguationDialog(
            @Nullable Project project,
            List<Pair<PsiLiteralExpression, RenameResult.NeedsDisambiguation>> cases,
            String oldName,
            String newName) {
        super(project, true);
        this.cases = cases;
        this.oldName = oldName;
        this.newName = newName;
        setTitle("Permuplate — Annotation String Update Required");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        panel.add(new JLabel(
                "<html>Renaming <b>" + oldName + "</b> → <b>" + newName + "</b> " +
                "requires updating the following annotation strings.<br>" +
                "Adjust the new values if needed:</html>"),
                withConstraints(gbc, 0, 0, 2, 1));

        for (int i = 0; i < cases.size(); i++) {
            Pair<PsiLiteralExpression, RenameResult.NeedsDisambiguation> c = cases.get(i);
            String currentValue = (String) c.first.getValue();

            panel.add(new JLabel("\"" + currentValue + "\"  →"),
                    withConstraints(gbc, 0, i + 1, 1, 1));

            // Pre-fill with best guess: replace old base literal with new base literal
            String oldLiteral = stripTrailingDigits(oldName);
            String newLiteral = stripTrailingDigits(newName);
            String suggested = currentValue != null
                    ? currentValue.replace(oldLiteral, newLiteral)
                    : "";

            JTextField field = new JTextField(suggested, 30);
            fields.add(field);
            panel.add(field, withConstraints(gbc, 1, i + 1, 1, 1));
        }

        return panel;
    }

    /** Returns the resolved literal → new-value pairs to add to the rename transaction. */
    public List<Pair<PsiLiteralExpression, String>> getResolvedUpdates() {
        List<Pair<PsiLiteralExpression, String>> result = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            String newValue = fields.get(i).getText().trim();
            if (!newValue.isEmpty()) {
                result.add(Pair.create(cases.get(i).first, newValue));
            }
        }
        return result;
    }

    private static GridBagConstraints withConstraints(
            GridBagConstraints gbc, int x, int y, int w, int h) {
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.gridwidth = w;
        gbc.gridheight = h;
        return (GridBagConstraints) gbc.clone();
    }

    private static String stripTrailingDigits(String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return name.substring(0, i);
    }
}
```

- [ ] **Step 2: Wire `DisambiguationDialog` into `AnnotationStringRenameProcessor.prepareRenaming()`**

Replace the `NeedsDisambiguation` comment in the class rename loop with real handling.
In `AnnotationStringRenameProcessor.java`, replace:

```java
        List<Pair<PsiLiteralExpression, String>> updates = new ArrayList<>();

        for (PsiLiteralExpression literal : affected) {
            String currentValue = (String) literal.getValue();
            if (currentValue == null) continue;

            AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(currentValue);
            RenameResult result = AnnotationStringAlgorithm.computeRename(template, oldName, newName);

            if (result instanceof RenameResult.Updated updated) {
                updates.add(Pair.create(literal, updated.newTemplate()));
            }
            // NeedsDisambiguation: DisambiguationDialog wired in Task 8 — skipped here
            // NoMatch: skip
        }

        pendingUpdates.get().addAll(updates);
```

With:

```java
        List<Pair<PsiLiteralExpression, String>> updates = new ArrayList<>();
        List<Pair<PsiLiteralExpression, RenameResult.NeedsDisambiguation>> disambig = new ArrayList<>();

        for (PsiLiteralExpression literal : affected) {
            String currentValue = (String) literal.getValue();
            if (currentValue == null) continue;

            AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(currentValue);
            RenameResult result = AnnotationStringAlgorithm.computeRename(template, oldName, newName);

            if (result instanceof RenameResult.Updated updated) {
                updates.add(Pair.create(literal, updated.newTemplate()));
            } else if (result instanceof RenameResult.NeedsDisambiguation nd) {
                disambig.add(Pair.create(literal, nd));
            }
            // NoMatch: skip
        }

        if (!disambig.isEmpty()) {
            DisambiguationDialog dialog = new DisambiguationDialog(
                    element.getProject(), disambig, oldName, newName);
            if (dialog.showAndGet()) {
                updates.addAll(dialog.getResolvedUpdates());
            }
        }

        pendingUpdates.get().addAll(updates);
```

- [ ] **Step 3: Verify compile**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/
git commit -m "feat(plugin): DisambiguationDialog wired into rename processor"
```

---

### Task 9: AnnotationStringRenameProcessor — method, field, parameter renames

- [ ] **Step 1: Write failing tests**

Add to `AnnotationStringRenameProcessorTest.java`:

```java
    public void testFieldRenameUpdatesPermuteDeclrName() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"c${i}\")\n" +
                "    private Object c<caret>2;\n" +
                "}");

        myFixture.renameElementAtCaret("d2");

        myFixture.checkResult(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"d${i}\")\n" +
                "    private Object d2;\n" +
                "}");
    }

    public void testMethodRenameUpdatesPermuteMethodName() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", to=\"${i-1}\", name=\"join${j}\")\n" +
                "    public void joi<caret>n2() {}\n" +
                "}");

        myFixture.renameElementAtCaret("merge2");

        myFixture.checkResult(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", to=\"${i-1}\", name=\"merge${j}\")\n" +
                "    public void merge2() {}\n" +
                "}");
    }
```

- [ ] **Step 2: Run to verify they fail**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest"
```

Expected: the two new tests FAIL; the class rename test still PASSES.

- [ ] **Step 3: Extend `prepareRenaming()` in `AnnotationStringRenameProcessor.java`**

Replace the method body (keep the class rename block, add member handling after it):

```java
    @Override
    public void prepareRenaming(@NotNull PsiElement element, @NotNull String newName,
                                @NotNull Map<PsiElement, String> allRenames,
                                @NotNull com.intellij.psi.search.SearchScope scope) {
        pendingUpdates.get().clear();

        // --- Class rename ---
        if (element instanceof PsiClass cls) {
            String oldName = cls.getName();
            if (oldName == null) return;
            String oldLiteral = stripTrailingDigits(oldName);

            List<PsiLiteralExpression> affected = findAffectedLiterals(cls, oldLiteral);
            collectUpdates(affected, oldName, newName, element);
            return;
        }

        // --- Member rename (method, field, parameter) ---
        if (element instanceof PsiMember || element instanceof PsiParameter) {
            PsiClass containingClass = element instanceof PsiMember m
                    ? m.getContainingClass()
                    : ((PsiParameter) element).getDeclarationScope() instanceof PsiMethod pm
                      ? pm.getContainingClass() : null;
            if (containingClass == null) return;

            String oldMemberName = element instanceof PsiNamedElement ne ? ne.getName() : null;
            if (oldMemberName == null) return;
            String oldLiteral = stripTrailingDigits(oldMemberName);

            // Find annotation on the element itself
            PsiModifierListOwner owner = element instanceof PsiModifierListOwner mlo ? mlo : null;
            if (owner == null) return;

            List<PsiLiteralExpression> affected = new ArrayList<>();
            for (PsiAnnotation ann : owner.getAnnotations()) {
                if (!ALL_ANNOTATION_FQNS.contains(ann.getQualifiedName())) continue;
                for (PsiNameValuePair pair : ann.getParameterList().getAttributes()) {
                    if (pair.getValue() instanceof PsiLiteralExpression lit
                            && lit.getValue() instanceof String s
                            && s.contains(oldLiteral)) {
                        affected.add(lit);
                    }
                }
            }
            collectUpdates(affected, oldMemberName, newName, element);
        }
    }

    private void collectUpdates(List<PsiLiteralExpression> literals,
                                 String oldName, String newName, PsiElement context) {
        List<Pair<PsiLiteralExpression, String>> updates = new ArrayList<>();
        List<Pair<PsiLiteralExpression, RenameResult.NeedsDisambiguation>> disambig = new ArrayList<>();

        for (PsiLiteralExpression literal : literals) {
            String currentValue = (String) literal.getValue();
            if (currentValue == null) continue;

            AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(currentValue);
            RenameResult result = AnnotationStringAlgorithm.computeRename(template, oldName, newName);

            if (result instanceof RenameResult.Updated updated) {
                updates.add(Pair.create(literal, updated.newTemplate()));
            } else if (result instanceof RenameResult.NeedsDisambiguation nd) {
                disambig.add(Pair.create(literal, nd));
            }
        }

        if (!disambig.isEmpty()) {
            DisambiguationDialog dialog = new DisambiguationDialog(
                    context.getProject(), disambig, oldName, newName);
            if (dialog.showAndGet()) {
                updates.addAll(dialog.getResolvedUpdates());
            }
        }

        pendingUpdates.get().addAll(updates);
    }
```

- [ ] **Step 4: Run all rename tests**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest"
```

Expected: all 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): AnnotationStringRenameProcessor — field and method rename support"
```

---

### Task 10: GeneratedFileRenameHandler

**Files:**
- Create: `permuplate-intellij-plugin/src/main/java/io/quarkiverse/permuplate/intellij/rename/GeneratedFileRenameHandler.java`

- [ ] **Step 1: Write the failing test**

Add to `AnnotationStringRenameProcessorTest.java`:

```java
    public void testRenameInGeneratedFileIsBlocked() {
        // Simulate a generated file (in target/generated-sources/)
        // The rename handler fires before IntelliJ's default handler;
        // we assert via the handler's isAvailableOnDataContext logic.
        // Full integration test requires runIde — this unit test checks the detector.
        com.intellij.openapi.vfs.VirtualFile generatedFile =
                myFixture.getTempDirFixture().createFile("target/generated-sources/permuplate/Join3.java",
                        "package io.example;\npublic class Join3 {}");
        assertTrue(io.quarkiverse.permuplate.intellij.index.PermuteFileDetector
                .isGeneratedFile(generatedFile));

        com.intellij.openapi.vfs.VirtualFile templateFile =
                myFixture.getTempDirFixture().createFile("src/main/java/io/example/Join2.java",
                        "package io.example;\npublic class Join2 {}");
        assertFalse(io.quarkiverse.permuplate.intellij.index.PermuteFileDetector
                .isGeneratedFile(templateFile));
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testRenameInGeneratedFileIsBlocked"
```

Expected: FAIL — `isGeneratedFile` not yet implemented to check that path.

Check `PermuteFileDetector.isGeneratedFile()` — it checks for `/target/generated-sources/`. Verify the path in the test fixture matches. If it passes already, that's fine — move to the handler itself.

- [ ] **Step 3: Create `GeneratedFileRenameHandler.java`**

```java
package io.quarkiverse.permuplate.intellij.rename;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.refactoring.rename.RenameHandler;
import io.quarkiverse.permuplate.intellij.index.PermuteFileDetector;
import io.quarkiverse.permuplate.intellij.index.PermuteTemplateData;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts rename actions inside generated files and redirects the user
 * to the template. Registered with order="first" to run before IntelliJ's
 * default rename handler.
 */
public class GeneratedFileRenameHandler implements RenameHandler {

    @Override
    public boolean isAvailableOnDataContext(@NotNull DataContext dataContext) {
        VirtualFile file = CommonDataKeys.VIRTUAL_FILE.getData(dataContext);
        return file != null && PermuteFileDetector.isGeneratedFile(file);
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor,
                       PsiFile file, @NotNull DataContext dataContext) {
        showRedirectDialog(project, file);
    }

    @Override
    public void invoke(@NotNull Project project, @NotNull PsiElement[] elements,
                       @NotNull DataContext dataContext) {
        Project p = CommonDataKeys.PROJECT.getData(dataContext);
        if (p == null) return;
        PsiFile file = elements.length > 0 ? elements[0].getContainingFile() : null;
        showRedirectDialog(p, file);
    }

    private static void showRedirectDialog(@NotNull Project project, PsiFile generatedFile) {
        String generatedName = generatedFile != null ? generatedFile.getName() : "this file";

        // Look up template name via reverse index
        String templateDisplay = "the template";
        if (generatedFile instanceof PsiJavaFile jf && !jf.getClasses().equals(new PsiClass[0])) {
            PsiClass cls = jf.getClasses()[0];
            if (cls.getName() != null) {
                String templateName = PermuteFileDetector.templateNameFor(cls.getName(), project);
                if (templateName != null) templateDisplay = templateName + ".java";
            }
        }

        int choice = Messages.showDialog(
                project,
                "<html><b>" + generatedName + "</b> is generated by Permuplate from <b>"
                        + templateDisplay + "</b>.<br>" +
                        "Edits will be overwritten on the next build.<br><br>" +
                        "To rename this element, rename it in the template instead.</html>",
                "Generated File — Rename Blocked",
                new String[]{"Go to Template", "Cancel"},
                0,
                Messages.getWarningIcon());

        if (choice == 0 && generatedFile != null) {
            navigateToTemplate(project, generatedFile);
        }
    }

    private static void navigateToTemplate(@NotNull Project project, @NotNull PsiFile generatedFile) {
        if (!(generatedFile instanceof PsiJavaFile jf)) return;
        if (jf.getClasses().length == 0) return;

        PsiClass generatedClass = jf.getClasses()[0];
        String templateName = generatedClass.getName() != null
                ? PermuteFileDetector.templateNameFor(generatedClass.getName(), project)
                : null;
        if (templateName == null) return;

        PermuteTemplateData data = PermuteFileDetector.templateDataFor(templateName, project);
        if (data == null) return;

        VirtualFile templateVFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(data.templateFilePath);
        if (templateVFile == null) return;

        PsiFile templatePsiFile = PsiManager.getInstance(project).findFile(templateVFile);
        if (templatePsiFile instanceof Navigatable nav) nav.navigate(true);
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

Add inside `<extensions>`:
```xml
<renameHandler
    implementation="io.quarkiverse.permuplate.intellij.rename.GeneratedFileRenameHandler"
    order="first"/>
```

- [ ] **Step 5: Run all tests**

```bash
./gradlew test
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): GeneratedFileRenameHandler — block rename in generated files"
```

---

### Task 11: Cross-family annotation string rename test

- [ ] **Step 1: Write the failing test**

Add to `AnnotationStringRenameProcessorTest.java`:

```java
    public void testCrossFileAnnotationStringUpdatedOnFamilyRename() {
        // Callable2 is a template referenced by type="Callable${i}" in Join2
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
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"c${i}\")\n" +
                "    private Object c2;\n" +
                "}");

        // Rename Callable2 → Task2 (rename happens in Callable2.java, affects Join2.java strings)
        PsiClass callable2 = myFixture.findClass("io.example.Callable2");
        assertNotNull(callable2);
        myFixture.renameElement(callable2, "Task2");

        // Join2.java annotation string should be updated
        PsiFile join2 = myFixture.findFileInTempDir("Join2.java");
        assertNotNull(join2);
        assertTrue(join2.getText().contains("type=\"Task${i}\""));
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew test --tests "io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessorTest.testCrossFileAnnotationStringUpdatedOnFamilyRename"
```

Expected: FAIL — annotation string in `Join2.java` not updated when `Callable2` is renamed.

- [ ] **Step 3: Extend `prepareRenaming()` for cross-file annotation string updates**

In `AnnotationStringRenameProcessor.java`, at the end of the class rename block, add a cross-file scan:

```java
        // --- Class rename (extend the existing block) ---
        if (element instanceof PsiClass cls) {
            String oldName = cls.getName();
            if (oldName == null) return;
            String oldLiteral = stripTrailingDigits(oldName);

            // 1. Update strings in the template's own file
            List<PsiLiteralExpression> affected = findAffectedLiterals(cls, oldLiteral);
            collectUpdates(affected, oldName, newName, element);

            // 2. Update cross-family annotation strings in OTHER templates that reference oldLiteral
            Project project = element.getProject();
            for (String templateName : PermuteFileDetector.templatesReferencingLiteral(oldLiteral, project)) {
                if (templateName.equals(oldName)) continue; // already handled above

                PermuteTemplateData data = PermuteFileDetector.templateDataFor(templateName, project);
                if (data == null) continue;

                com.intellij.openapi.vfs.VirtualFile vFile =
                        com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                                .findFileByPath(data.templateFilePath);
                if (vFile == null) continue;

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
                if (psiFile == null) continue;

                List<PsiLiteralExpression> crossAffected = new ArrayList<>();
                psiFile.accept(new JavaRecursiveElementVisitor() {
                    @Override
                    public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                        super.visitAnnotation(annotation);
                        if (!ALL_ANNOTATION_FQNS.contains(annotation.getQualifiedName())) return;
                        for (PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
                            if (pair.getValue() instanceof PsiLiteralExpression lit
                                    && lit.getValue() instanceof String s
                                    && s.contains(oldLiteral)) {
                                crossAffected.add(lit);
                            }
                        }
                    }
                });
                collectUpdates(crossAffected, oldName, newName, element);
            }
            return;
        }
```

- [ ] **Step 4: Run all tests**

```bash
./gradlew test
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add permuplate-intellij-plugin/src/
git commit -m "feat(plugin): cross-family annotation string updates on class rename"
```

---

### Task 12: Final plugin.xml and runIde smoke test

**Files:**
- Modify: `permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Final `plugin.xml` (all components registered)**

```xml
<idea-plugin>
    <id>io.quarkiverse.permuplate</id>
    <name>Permuplate</name>
    <version>1.0.0-SNAPSHOT</version>
    <description><![CDATA[
        IDE support for Permuplate annotation-driven code generation.
        Makes IntelliJ refactoring operations (rename, find usages, safe delete)
        fully aware of the permutation chain between template classes and generated siblings.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Indexes -->
        <fileBasedIndex implementation="io.quarkiverse.permuplate.intellij.index.PermuteTemplateIndex"/>
        <fileBasedIndex implementation="io.quarkiverse.permuplate.intellij.index.PermuteGeneratedIndex"/>

        <!-- Rename -->
        <renamePsiElementProcessor
            implementation="io.quarkiverse.permuplate.intellij.rename.AnnotationStringRenameProcessor"/>
        <renameHandler
            implementation="io.quarkiverse.permuplate.intellij.rename.GeneratedFileRenameHandler"
            order="first"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 2: Run the full test suite**

```bash
./gradlew test
```

Expected: all tests PASS, BUILD SUCCESSFUL.

- [ ] **Step 3: Launch a sandboxed IntelliJ for manual smoke test**

```bash
./gradlew runIde
```

This downloads IntelliJ 2023.2 Community into the Gradle cache and launches it with the plugin installed.

**Manual smoke test checklist:**
1. Open or create a project with `permuplate-annotations` on the classpath
2. Create `Join2.java` annotated with `@Permute(varName="i", from=3, to=5, className="Join${i}")`
3. Rename `Join2` → `Merge2` (Shift+F6)
4. Verify: class renamed to `Merge2` AND `className="Merge${i}"` in the annotation
5. Press Ctrl+Z — verify both changes undo together
6. Create a file under `target/generated-sources/permuplate/Join3.java`
7. Open it, press Shift+F6 — verify the redirect dialog appears

- [ ] **Step 4: Commit**

```bash
git add permuplate-intellij-plugin/src/main/resources/META-INF/plugin.xml
git commit -m "feat(plugin): complete plugin.xml registrations for foundation + rename"
```

---

## Plan 2 Preview

Plan 2 (`docs/superpowers/plans/2026-04-07-intellij-plugin-p2-navigation-safety-inspections.md`) covers:
- **PermuteMethodNavigator** — go-to-definition from generated overloads → sentinel method
- **PermuteFamilyFindUsagesHandlerFactory** — family-aware find usages
- **PermuteSafeDeleteDelegate** — safe delete redirect
- **GeneratedFileNotification** — editor banner
- **PermutePackageMoveHandler** — package move import update
- **AnnotationStringInspection** — inline lint
- **StaleAnnotationStringInspection** — drift detection
- **BoundaryOmissionInspection** — boundary omission warning
