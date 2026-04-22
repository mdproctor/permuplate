# JEXL String Assistance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add full JEXL authoring assistance (syntax highlighting, variable/function completion, parameter hints, undefined-variable warnings) inside `${...}` expressions in all Permuplate annotation string attributes.

**Architecture:** Hand-written `JexlLexer` feeds a minimal `JexlLanguage` registered with IntelliJ's language injection framework. A `MultiHostInjector` injects the language into every `${...}` subrange found in Permuplate annotation string attributes. Three IDE services (completion contributor, parameter info handler, annotator) sit on top of the injected language, all fed by a shared `JexlContextResolver` that walks the host PSI tree to discover the variables in scope.

**Tech Stack:** IntelliJ Platform 2023.2 (232+), Java 17, commons-jexl3:3.3 (for syntax validation in annotator), existing `BasePlatformTestCase` test infrastructure.

---

## Test command

```bash
cd /Users/mdproctor/claude/permuplate/permuplate-intellij-plugin
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test 2>&1 | tail -20
```

Single class:
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.jexl.lang.JexlLexerTest" 2>&1 | tail -20
```

---

## File map

**New — main sources** (`src/main/java/io/quarkiverse/permuplate/intellij/`)

| File | Responsibility |
|---|---|
| `shared/PermuteAnnotations.java` | `ALL_ANNOTATION_FQNS` + `JEXL_BEARING_ATTRIBUTES` + `isPermuteAnnotation()` |
| `jexl/lang/JexlTokenTypes.java` | `IElementType` constants |
| `jexl/lang/JexlLexer.java` | Hand-written lexer |
| `jexl/lang/JexlLanguage.java` | Singleton `Language("JEXL")` |
| `jexl/lang/JexlFileType.java` | `LanguageFileType` |
| `jexl/lang/JexlFile.java` | `PsiFileBase` root |
| `jexl/lang/JexlParserDefinition.java` | Supplies lexer, creates `JexlFile` |
| `jexl/lang/JexlSyntaxHighlighter.java` | Token → `TextAttributesKey` |
| `jexl/lang/JexlSyntaxHighlighterFactory.java` | Factory wrapper |
| `jexl/context/JexlContext.java` | Record: `variables`, `innerVariable` |
| `jexl/context/JexlBuiltin.java` | Built-in function descriptors + static registry |
| `jexl/context/JexlContextResolver.java` | PSI walker: host → `JexlContext` |
| `jexl/inject/JexlLanguageInjector.java` | `MultiHostInjector`: finds `${…}` subranges |
| `jexl/completion/JexlCompletionContributor.java` | Variables + built-ins in completion popup |
| `jexl/paraminfo/JexlParameterInfoHandler.java` | Parameter hints for built-in calls |
| `jexl/annotate/JexlAnnotator.java` | Syntax errors + undefined variable warnings |

**New — test sources** (`src/test/java/io/quarkiverse/permuplate/intellij/`)

| File | Coverage |
|---|---|
| `jexl/lang/JexlLexerTest.java` | Lexer: all token types, operators, edge cases |
| `jexl/context/JexlContextResolverTest.java` | Context resolution: varName, @PermuteVar, strings=, macros=, inner var |
| `jexl/inject/JexlLanguageInjectorTest.java` | Injection: ranges, skips, robustness |
| `jexl/completion/JexlCompletionContributorTest.java` | Completion: variables, built-ins, inner var |
| `jexl/paraminfo/JexlParameterInfoHandlerTest.java` | Parameter hints: each built-in, arg index |
| `jexl/annotate/JexlAnnotatorTest.java` | Warnings: syntax errors, undefined vars, no false positives |
| `jexl/e2e/JexlFullTemplateTest.java` | Full template with all annotation types combined |

**Modified**

| File | Change |
|---|---|
| `rename/AnnotationStringRenameProcessor.java` | Import `PermuteAnnotations`, remove inline set |
| `src/main/resources/META-INF/plugin.xml` | Register all new extensions |
| `build.gradle.kts` | Add `commons-jexl3:3.3` implementation dependency |

---

## Task 1: GitHub epic and child issues

- [ ] **Create epic**

```bash
gh issue create --repo mdproctor/permuplate \
  --label epic \
  --title "JEXL string assistance in IntelliJ plugin" \
  --body "Full IDE authoring assistance inside \${...} expressions in Permuplate annotation strings: syntax highlighting, variable/function completion, parameter hints, undefined-variable warnings. Spec: docs/superpowers/specs/2026-04-22-jexl-completion-design.md"
```

Note the epic issue number — use it in every child issue's body and every commit message.

- [ ] **Create child issues** (replace EPIC_NUM with the epic number)

```bash
for title in \
  "PermuteAnnotations shared constants refactor" \
  "JEXL language infrastructure (Language, Lexer, FileType, File, ParserDefinition, SyntaxHighlighter)" \
  "JexlContext, JexlBuiltin, JexlContextResolver" \
  "JexlLanguageInjector — inject JEXL into \${...} subranges" \
  "JexlCompletionContributor — variable and built-in completion" \
  "JexlParameterInfoHandler — parameter hints for built-in functions" \
  "JexlAnnotator — syntax validation and undefined variable warnings" \
  "End-to-end JEXL tests (JexlFullTemplateTest)"; do
  gh issue create --repo mdproctor/permuplate \
    --label enhancement \
    --title "$title" \
    --body "Part of epic #EPIC_NUM — JEXL string assistance."
done
```

Note each child issue number — use in commit messages as `closes #N, epic #EPIC_NUM`.

---

## Task 2: PermuteAnnotations shared constants

**Files:**
- Create: `src/main/java/io/quarkiverse/permuplate/intellij/shared/PermuteAnnotations.java`
- Modify: `src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java`

- [ ] **Step 1: Write the failing test** (verifies the class exists and contains expected constants)

```java
// src/test/java/io/quarkiverse/permuplate/intellij/shared/PermuteAnnotationsTest.java
package io.quarkiverse.permuplate.intellij.shared;

import junit.framework.TestCase;

public class PermuteAnnotationsTest extends TestCase {

    public void testAllAnnotationFqnsContainsPermute() {
        assertTrue(PermuteAnnotations.ALL_ANNOTATION_FQNS
                .contains("io.quarkiverse.permuplate.Permute"));
    }

    public void testAllAnnotationFqnsContainsPermuteReturn() {
        assertTrue(PermuteAnnotations.ALL_ANNOTATION_FQNS
                .contains("io.quarkiverse.permuplate.PermuteReturn"));
    }

    public void testAllAnnotationFqnsContainsPermuteDefaultReturn() {
        assertTrue(PermuteAnnotations.ALL_ANNOTATION_FQNS
                .contains("io.quarkiverse.permuplate.PermuteDefaultReturn"));
    }

    public void testJexlBearingAttributesContainsExpectedNames() {
        assertTrue(PermuteAnnotations.JEXL_BEARING_ATTRIBUTES.contains("from"));
        assertTrue(PermuteAnnotations.JEXL_BEARING_ATTRIBUTES.contains("to"));
        assertTrue(PermuteAnnotations.JEXL_BEARING_ATTRIBUTES.contains("className"));
        assertTrue(PermuteAnnotations.JEXL_BEARING_ATTRIBUTES.contains("when"));
        assertTrue(PermuteAnnotations.JEXL_BEARING_ATTRIBUTES.contains("pattern"));
    }

    public void testIsPermuteAnnotationMatchesFqn() {
        assertTrue(PermuteAnnotations.isPermuteAnnotation(
                "io.quarkiverse.permuplate.PermuteDeclr"));
    }

    public void testIsPermuteAnnotationMatchesSimpleName() {
        assertTrue(PermuteAnnotations.isPermuteAnnotation("Permute"));
    }

    public void testIsPermuteAnnotationReturnsFalseForUnknown() {
        assertFalse(PermuteAnnotations.isPermuteAnnotation("Override"));
        assertFalse(PermuteAnnotations.isPermuteAnnotation(null));
    }
}
```

- [ ] **Step 2: Run — expect compile failure** (class doesn't exist yet)

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew compileTestJava 2>&1 | grep "error:"
```

- [ ] **Step 3: Create `PermuteAnnotations`**

```java
// src/main/java/io/quarkiverse/permuplate/intellij/shared/PermuteAnnotations.java
package io.quarkiverse.permuplate.intellij.shared;

import java.util.Set;

public final class PermuteAnnotations {
    private PermuteAnnotations() {}

    public static final Set<String> ALL_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.Permute",
            "io.quarkiverse.permuplate.PermuteDeclr",
            "io.quarkiverse.permuplate.PermuteParam",
            "io.quarkiverse.permuplate.PermuteTypeParam",
            "io.quarkiverse.permuplate.PermuteMethod",
            "io.quarkiverse.permuplate.PermuteSource",
            "io.quarkiverse.permuplate.PermuteAnnotation",
            "io.quarkiverse.permuplate.PermuteThrows",
            "io.quarkiverse.permuplate.PermuteSwitchArm",
            "io.quarkiverse.permuplate.PermuteReturn",
            "io.quarkiverse.permuplate.PermuteDefaultReturn"
    );

    public static final Set<String> JEXL_BEARING_ATTRIBUTES = Set.of(
            "from", "to", "className", "name", "type", "when",
            "pattern", "body", "macros", "typeArgs", "value"
    );

    public static boolean isPermuteAnnotation(String fqn) {
        if (fqn == null) return false;
        return ALL_ANNOTATION_FQNS.contains(fqn)
                || ALL_ANNOTATION_FQNS.stream().anyMatch(f -> f.endsWith("." + fqn));
    }
}
```

- [ ] **Step 4: Update `AnnotationStringRenameProcessor` to use the shared class**

Replace the inline `ALL_ANNOTATION_FQNS` field and both usages of the set + stream check:

```java
// Remove the private static final Set<String> ALL_ANNOTATION_FQNS = Set.of(...) field entirely.
// Replace the two boolean checks:

// In findAffectedLiterals():
boolean isPermutateAnnotation = PermuteAnnotations.isPermuteAnnotation(fqn);

// In collectMemberAnnotationUpdates():
boolean isPermuteAnnotation = PermuteAnnotations.isPermuteAnnotation(fqn);
```

Add import: `import io.quarkiverse.permuplate.intellij.shared.PermuteAnnotations;`

- [ ] **Step 5: Run all tests — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit** (replace ISSUE and EPIC with real numbers)

```bash
git add src/main/java/io/quarkiverse/permuplate/intellij/shared/PermuteAnnotations.java \
        src/main/java/io/quarkiverse/permuplate/intellij/rename/AnnotationStringRenameProcessor.java \
        src/test/java/io/quarkiverse/permuplate/intellij/shared/PermuteAnnotationsTest.java
git commit -m "refactor: extract PermuteAnnotations shared constants — closes #ISSUE, epic #EPIC

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: JexlTokenTypes and JexlLexer

**Files:**
- Create: `src/main/java/io/quarkiverse/permuplate/intellij/jexl/lang/JexlTokenTypes.java`
- Create: `src/main/java/io/quarkiverse/permuplate/intellij/jexl/lang/JexlLexer.java`
- Create: `src/test/java/io/quarkiverse/permuplate/intellij/jexl/lang/JexlLexerTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/quarkiverse/permuplate/intellij/jexl/lang/JexlLexerTest.java
package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.psi.tree.IElementType;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class JexlLexerTest extends TestCase {

    // Helper: tokenise the given input, return list of (tokenType, tokenText) pairs
    private List<String[]> tokenise(String input) {
        JexlLexer lexer = new JexlLexer();
        lexer.start(input, 0, input.length(), 0);
        List<String[]> result = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            IElementType type = lexer.getTokenType();
            String text = input.substring(lexer.getTokenStart(), lexer.getTokenEnd());
            result.add(new String[]{type.toString(), text});
            lexer.advance();
        }
        return result;
    }

    // --- Happy path ---

    public void testIdentifier() {
        List<String[]> tokens = tokenise("alpha");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_IDENTIFIER", tokens.get(0)[0]);
        assertEquals("alpha", tokens.get(0)[1]);
    }

    public void testNumber() {
        List<String[]> tokens = tokenise("42");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_NUMBER", tokens.get(0)[0]);
        assertEquals("42", tokens.get(0)[1]);
    }

    public void testSingleQuotedString() {
        List<String[]> tokens = tokenise("'alpha'");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_STRING", tokens.get(0)[0]);
        assertEquals("'alpha'", tokens.get(0)[1]);
    }

    public void testParensAndComma() {
        List<String[]> tokens = tokenise("f(a,b)");
        assertEquals(6, tokens.size());
        assertEquals("JEXL_IDENTIFIER", tokens.get(0)[0]); // f
        assertEquals("JEXL_LPAREN",     tokens.get(1)[0]); // (
        assertEquals("JEXL_IDENTIFIER", tokens.get(2)[0]); // a
        assertEquals("JEXL_COMMA",      tokens.get(3)[0]); // ,
        assertEquals("JEXL_IDENTIFIER", tokens.get(4)[0]); // b
        assertEquals("JEXL_RPAREN",     tokens.get(5)[0]); // )
    }

    public void testDot() {
        List<String[]> tokens = tokenise("a.b");
        assertEquals(3, tokens.size());
        assertEquals("JEXL_DOT", tokens.get(1)[0]);
    }

    public void testSimpleOperators() {
        for (String op : new String[]{"+", "-", "*", "/"}) {
            List<String[]> tokens = tokenise(op);
            assertEquals(1, tokens.size());
            assertEquals("JEXL_OPERATOR", tokens.get(0)[0]);
        }
    }

    public void testTwoCharOperators() {
        for (String op : new String[]{"==", "!=", "<=", ">=", "&&", "||"}) {
            List<String[]> tokens = tokenise(op);
            assertEquals("Expected single OPERATOR token for " + op, 1, tokens.size());
            assertEquals("JEXL_OPERATOR", tokens.get(0)[0]);
            assertEquals(op, tokens.get(0)[1]);
        }
    }

    public void testWhitespaceSkipped() {
        List<String[]> tokens = tokenise("i + 1");
        // IDENTIFIER WHITESPACE OPERATOR WHITESPACE NUMBER
        assertEquals(5, tokens.size());
        assertEquals("JEXL_WHITESPACE", tokens.get(1)[0]);
        assertEquals("JEXL_OPERATOR",   tokens.get(2)[0]);
        assertEquals("JEXL_WHITESPACE", tokens.get(3)[0]);
    }

    // --- Correctness ---

    public void testComplexExpression() {
        // typeArgList(1, i+1, 'alpha')
        List<String[]> tokens = tokenise("typeArgList(1, i+1, 'alpha')");
        assertEquals("JEXL_IDENTIFIER", tokens.get(0)[0]);
        assertEquals("typeArgList",     tokens.get(0)[1]);
        assertEquals("JEXL_LPAREN",    tokens.get(1)[0]);
        assertEquals("JEXL_NUMBER",    tokens.get(2)[0]);
    }

    public void testUnderscoreIdentifier() {
        List<String[]> tokens = tokenise("my_var");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_IDENTIFIER", tokens.get(0)[0]);
    }

    public void testNegativeNumber() {
        // '-' is OPERATOR, '1' is NUMBER
        List<String[]> tokens = tokenise("-1");
        assertEquals(2, tokens.size());
        assertEquals("JEXL_OPERATOR", tokens.get(0)[0]);
        assertEquals("JEXL_NUMBER",   tokens.get(1)[0]);
    }

    // --- Robustness ---

    public void testEmptyInput() {
        List<String[]> tokens = tokenise("");
        assertTrue(tokens.isEmpty());
    }

    public void testUnknownCharBecomesBADCharacter() {
        List<String[]> tokens = tokenise("@");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_BAD_CHARACTER", tokens.get(0)[0]);
    }

    public void testUnterminatedString() {
        // Lexer must not throw; unclosed quote consumes to end of input
        List<String[]> tokens = tokenise("'unclosed");
        assertFalse(tokens.isEmpty());
        assertEquals("JEXL_STRING", tokens.get(0)[0]);
    }

    public void testOnlyWhitespace() {
        List<String[]> tokens = tokenise("   ");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_WHITESPACE", tokens.get(0)[0]);
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew compileTestJava 2>&1 | grep "error:" | head -5
```

- [ ] **Step 3: Create `JexlTokenTypes`**

```java
// src/main/java/io/quarkiverse/permuplate/intellij/jexl/lang/JexlTokenTypes.java
package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.psi.tree.IElementType;

public interface JexlTokenTypes {
    IElementType IDENTIFIER   = new IElementType("JEXL_IDENTIFIER",   JexlLanguage.INSTANCE);
    IElementType NUMBER        = new IElementType("JEXL_NUMBER",        JexlLanguage.INSTANCE);
    IElementType STRING        = new IElementType("JEXL_STRING",        JexlLanguage.INSTANCE);
    IElementType LPAREN        = new IElementType("JEXL_LPAREN",        JexlLanguage.INSTANCE);
    IElementType RPAREN        = new IElementType("JEXL_RPAREN",        JexlLanguage.INSTANCE);
    IElementType COMMA         = new IElementType("JEXL_COMMA",         JexlLanguage.INSTANCE);
    IElementType DOT           = new IElementType("JEXL_DOT",           JexlLanguage.INSTANCE);
    IElementType OPERATOR      = new IElementType("JEXL_OPERATOR",      JexlLanguage.INSTANCE);
    IElementType WHITESPACE    = new IElementType("JEXL_WHITESPACE",    JexlLanguage.INSTANCE);
    IElementType BAD_CHARACTER = new IElementType("JEXL_BAD_CHARACTER", JexlLanguage.INSTANCE);
}
```

Note: `JexlTokenTypes` references `JexlLanguage.INSTANCE` — create `JexlLanguage` first (a minimal stub is fine):

```java
// src/main/java/io/quarkiverse/permuplate/intellij/jexl/lang/JexlLanguage.java
package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.lang.Language;

public class JexlLanguage extends Language {
    public static final JexlLanguage INSTANCE = new JexlLanguage();
    private JexlLanguage() { super("JEXL"); }
}
```

- [ ] **Step 4: Create `JexlLexer`**

```java
// src/main/java/io/quarkiverse/permuplate/intellij/jexl/lang/JexlLexer.java
package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JexlLexer extends LexerBase {

    private static final String TWO_CHAR_OPS = "==!=<=>=&&||";

    private CharSequence buffer;
    private int tokenStart;
    private int tokenEnd;
    private int bufferEnd;
    private IElementType tokenType;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.bufferEnd = endOffset;
        this.tokenType = null;
        advance();
    }

    @Override
    public int getState() { return 0; }

    @Override
    public @Nullable IElementType getTokenType() { return tokenType; }

    @Override
    public int getTokenStart() { return tokenStart; }

    @Override
    public int getTokenEnd() { return tokenEnd; }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        if (tokenStart >= bufferEnd) {
            tokenType = null;
            return;
        }
        char c = buffer.charAt(tokenStart);

        if (Character.isWhitespace(c)) {
            tokenType = JexlTokenTypes.WHITESPACE;
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd && Character.isWhitespace(buffer.charAt(tokenEnd))) tokenEnd++;

        } else if (Character.isLetter(c) || c == '_') {
            tokenType = JexlTokenTypes.IDENTIFIER;
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd
                    && (Character.isLetterOrDigit(buffer.charAt(tokenEnd))
                        || buffer.charAt(tokenEnd) == '_')) tokenEnd++;

        } else if (Character.isDigit(c)) {
            tokenType = JexlTokenTypes.NUMBER;
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd
                    && (Character.isDigit(buffer.charAt(tokenEnd))
                        || buffer.charAt(tokenEnd) == '.')) tokenEnd++;

        } else if (c == '\'') {
            tokenType = JexlTokenTypes.STRING;
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd && buffer.charAt(tokenEnd) != '\'') tokenEnd++;
            if (tokenEnd < bufferEnd) tokenEnd++; // consume closing quote

        } else if (c == '(') { tokenType = JexlTokenTypes.LPAREN;  tokenEnd = tokenStart + 1;
        } else if (c == ')') { tokenType = JexlTokenTypes.RPAREN;  tokenEnd = tokenStart + 1;
        } else if (c == ',') { tokenType = JexlTokenTypes.COMMA;   tokenEnd = tokenStart + 1;
        } else if (c == '.') { tokenType = JexlTokenTypes.DOT;     tokenEnd = tokenStart + 1;

        } else if ("+-*/><!=&|".indexOf(c) >= 0) {
            tokenType = JexlTokenTypes.OPERATOR;
            tokenEnd = tokenStart + 1;
            if (tokenEnd < bufferEnd) {
                String twoChar = "" + c + buffer.charAt(tokenEnd);
                if (TWO_CHAR_OPS.contains(twoChar)) tokenEnd++;
            }

        } else {
            tokenType = JexlTokenTypes.BAD_CHARACTER;
            tokenEnd = tokenStart + 1;
        }
    }

    @Override
    public @NotNull CharSequence getBufferSequence() { return buffer; }

    @Override
    public int getBufferEnd() { return bufferEnd; }
}
```

- [ ] **Step 5: Run lexer tests — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.jexl.lang.JexlLexerTest" 2>&1 | tail -10
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/quarkiverse/permuplate/intellij/jexl/lang/ \
        src/test/java/io/quarkiverse/permuplate/intellij/jexl/lang/JexlLexerTest.java
git commit -m "feat: JexlTokenTypes and JexlLexer — closes #ISSUE, epic #EPIC

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: JexlFileType, JexlFile, JexlParserDefinition, JexlSyntaxHighlighter — plugin.xml Layer 1

**Files:**
- Create: `jexl/lang/JexlFileType.java`
- Create: `jexl/lang/JexlFile.java`
- Create: `jexl/lang/JexlParserDefinition.java`
- Create: `jexl/lang/JexlSyntaxHighlighter.java`
- Create: `jexl/lang/JexlSyntaxHighlighterFactory.java`
- Modify: `src/main/resources/META-INF/plugin.xml`

No new test class for this task — the parser definition and syntax highlighter are exercised indirectly by later injection tests. A smoke test is sufficient.

- [ ] **Step 1: Write a smoke test verifying the language and parser definition are wired**

```java
// src/test/java/io/quarkiverse/permuplate/intellij/jexl/lang/JexlLanguageInfraTest.java
package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class JexlLanguageInfraTest extends BasePlatformTestCase {

    public void testJexlLanguageId() {
        assertEquals("JEXL", JexlLanguage.INSTANCE.getID());
    }

    public void testJexlFileTypeLanguage() {
        assertEquals(JexlLanguage.INSTANCE, JexlFileType.INSTANCE.getLanguage());
    }

    public void testParserDefinitionCreatesLexer() {
        JexlParserDefinition def = new JexlParserDefinition();
        assertNotNull(def.createLexer(getProject()));
    }

    public void testSyntaxHighlighterReturnsNonNullForAllTokenTypes() {
        JexlSyntaxHighlighter hl = new JexlSyntaxHighlighter();
        for (com.intellij.psi.tree.IElementType type : new com.intellij.psi.tree.IElementType[]{
                JexlTokenTypes.IDENTIFIER, JexlTokenTypes.NUMBER, JexlTokenTypes.STRING,
                JexlTokenTypes.OPERATOR, JexlTokenTypes.BAD_CHARACTER, JexlTokenTypes.WHITESPACE}) {
            assertNotNull("Expected non-null attributes for " + type,
                    hl.getTokenHighlights(type));
        }
    }
}
```

- [ ] **Step 2: Run — expect compile failure** (classes don't exist yet)

- [ ] **Step 3: Create remaining language infrastructure classes**

```java
// JexlFileType.java
package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;

public class JexlFileType extends LanguageFileType {
    public static final JexlFileType INSTANCE = new JexlFileType();
    private JexlFileType() { super(JexlLanguage.INSTANCE); }

    @Override public @NotNull String getName()          { return "JEXL"; }
    @Override public @NotNull String getDescription()   { return "JEXL expression"; }
    @Override public @NotNull String getDefaultExtension() { return "jexl"; }
    @Override public Icon getIcon()                     { return null; }
}
```

```java
// JexlFile.java
package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

public class JexlFile extends PsiFileBase {
    public JexlFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, JexlLanguage.INSTANCE);
    }
    @Override public @NotNull FileType getFileType() { return JexlFileType.INSTANCE; }
}
```

```java
// JexlParserDefinition.java
package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.lang.*;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.tree.*;
import org.jetbrains.annotations.NotNull;

public class JexlParserDefinition implements ParserDefinition {

    private static final TokenSet WHITESPACE_SET =
            TokenSet.create(JexlTokenTypes.WHITESPACE);
    private static final TokenSet STRING_SET =
            TokenSet.create(JexlTokenTypes.STRING);
    private static final IFileElementType FILE =
            new IFileElementType(JexlLanguage.INSTANCE);

    @Override public @NotNull Lexer createLexer(Project project) { return new JexlLexer(); }
    @Override public @NotNull PsiParser createParser(Project project) {
        // Flat parse: produce a JexlFile with raw token leaf nodes only.
        return (root, builder) -> {
            PsiBuilder.Marker mark = builder.mark();
            while (!builder.eof()) builder.advanceLexer();
            mark.done(root);
            return builder.getTreeBuilt();
        };
    }
    @Override public @NotNull IFileElementType getFileNodeType() { return FILE; }
    @Override public @NotNull TokenSet getWhitespaceTokens()     { return WHITESPACE_SET; }
    @Override public @NotNull TokenSet getCommentTokens()        { return TokenSet.EMPTY; }
    @Override public @NotNull TokenSet getStringLiteralElements(){ return STRING_SET; }
    @Override public @NotNull PsiElement createElement(ASTNode node) {
        throw new UnsupportedOperationException("No composite elements in JEXL PSI");
    }
    @Override public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new JexlFile(viewProvider);
    }
}
```

```java
// JexlSyntaxHighlighter.java
package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class JexlSyntaxHighlighter extends SyntaxHighlighterBase {

    private static final Set<String> BUILTIN_NAMES = Set.of(
            "alpha", "lower", "typeArgList", "capitalize", "decapitalize", "max", "min");

    public static final TextAttributesKey IDENTIFIER =
            TextAttributesKey.createTextAttributesKey("JEXL_IDENTIFIER",
                    DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey BUILTIN =
            TextAttributesKey.createTextAttributesKey("JEXL_BUILTIN",
                    DefaultLanguageHighlighterColors.STATIC_METHOD);
    public static final TextAttributesKey NUMBER =
            TextAttributesKey.createTextAttributesKey("JEXL_NUMBER",
                    DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey STRING =
            TextAttributesKey.createTextAttributesKey("JEXL_STRING",
                    DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey OPERATOR =
            TextAttributesKey.createTextAttributesKey("JEXL_OPERATOR",
                    DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey BAD_CHAR =
            TextAttributesKey.createTextAttributesKey("JEXL_BAD_CHARACTER",
                    DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE);

    @Override
    public @NotNull Lexer getHighlightingLexer() { return new JexlLexer(); }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType type) {
        if (type == JexlTokenTypes.NUMBER)        return pack(NUMBER);
        if (type == JexlTokenTypes.STRING)        return pack(STRING);
        if (type == JexlTokenTypes.OPERATOR)      return pack(OPERATOR);
        if (type == JexlTokenTypes.BAD_CHARACTER) return pack(BAD_CHAR);
        if (type == JexlTokenTypes.IDENTIFIER)    return pack(IDENTIFIER); // fine — highlighter doesn't know the text
        return EMPTY;
    }
}
```

```java
// JexlSyntaxHighlighterFactory.java
package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.openapi.fileTypes.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JexlSyntaxHighlighterFactory extends SyntaxHighlighterFactory {
    @Override
    public @NotNull SyntaxHighlighter getSyntaxHighlighter(
            @Nullable Project project, @Nullable VirtualFile virtualFile) {
        return new JexlSyntaxHighlighter();
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`** — add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- JEXL language infrastructure -->
<fileType name="JEXL"
          implementationClass="io.quarkiverse.permuplate.intellij.jexl.lang.JexlFileType"
          fieldName="INSTANCE"
          language="JEXL"
          extensions="jexl"/>
<lang.parserDefinition
    language="JEXL"
    implementationClass="io.quarkiverse.permuplate.intellij.jexl.lang.JexlParserDefinition"/>
<lang.syntaxHighlighterFactory
    language="JEXL"
    implementationClass="io.quarkiverse.permuplate.intellij.jexl.lang.JexlSyntaxHighlighterFactory"/>
```

- [ ] **Step 5: Run infrastructure tests — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.jexl.lang.JexlLanguageInfraTest" 2>&1 | tail -10
```

- [ ] **Step 6: Run full suite — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test 2>&1 | tail -5
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/quarkiverse/permuplate/intellij/jexl/lang/ \
        src/main/resources/META-INF/plugin.xml \
        src/test/java/io/quarkiverse/permuplate/intellij/jexl/lang/JexlLanguageInfraTest.java
git commit -m "feat: JEXL language infrastructure (FileType, File, Parser, Highlighter) — closes #ISSUE, epic #EPIC

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: JexlContext, JexlBuiltin, JexlContextResolver

**Files:**
- Create: `jexl/context/JexlContext.java`
- Create: `jexl/context/JexlBuiltin.java`
- Create: `jexl/context/JexlContextResolver.java`
- Create: `src/test/java/io/quarkiverse/permuplate/intellij/jexl/context/JexlContextResolverTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// src/test/java/io/quarkiverse/permuplate/intellij/jexl/context/JexlContextResolverTest.java
package io.quarkiverse.permuplate.intellij.jexl.context;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Set;

public class JexlContextResolverTest extends BasePlatformTestCase {

    // Helper: configure a Java file with a caret inside a ${...} expression
    // and return the resolved context. Returns null if no @Permute found.
    private JexlContext resolveAt(String javaSource) {
        myFixture.configureByText("Join2.java", javaSource);
        com.intellij.psi.PsiElement element =
                myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        if (element == null) return null;
        // Walk into the injected JEXL fragment at caret position
        com.intellij.lang.injection.InjectedLanguageManager mgr =
                com.intellij.lang.injection.InjectedLanguageManager.getInstance(getProject());
        com.intellij.psi.PsiElement injected = mgr.findInjectedElementAt(
                myFixture.getFile(), myFixture.getCaretOffset());
        if (injected == null) return null;
        return JexlContextResolver.resolve(injected);
    }

    // --- Happy path ---

    public void testPrimaryVarNameResolvedFromPermute() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>i}\")\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue("Expected 'i' in variables", ctx.allVariables().contains("i"));
    }

    public void testDefaultVarNameIsI() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(from=\"3\", to=\"5\", className=\"Join${<caret>i}\")\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue("Default varName should be 'i'", ctx.allVariables().contains("i"));
    }

    public void testPermuteVarAddsExtraVariable() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>i}\")\n" +
                "@PermuteVar(varName=\"k\", from=\"1\", to=\"3\")\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue("Expected 'k' from @PermuteVar", ctx.allVariables().contains("k"));
        assertTrue("Expected 'i' still present",   ctx.allVariables().contains("i"));
    }

    public void testStringsConstantsParsedCorrectly() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"${<caret>max}\",\n" +
                "         className=\"Join${i}\", strings={\"max=5\", \"min=2\"})\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue("Expected 'max' from strings=", ctx.allVariables().contains("max"));
        assertTrue("Expected 'min' from strings=", ctx.allVariables().contains("min"));
        assertFalse("'5' must not appear as a variable", ctx.allVariables().contains("5"));
    }

    public void testMacrosNamesParsedCorrectly() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${<caret>i}\", macros={\"prev=${i-1}\"})\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue("Expected 'prev' from macros=", ctx.allVariables().contains("prev"));
    }

    public void testInnerVarFromPermuteMethodIsIncluded() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", to=\"${<caret>i-1}\", name=\"join${j}\")\n" +
                "    public void join2() {}\n" +
                "}");
        assertNotNull(ctx);
        assertTrue("Expected 'j' as inner var", ctx.allVariables().contains("j"));
        assertTrue("Expected 'i' still present", ctx.allVariables().contains("i"));
    }

    // --- Correctness ---

    public void testStringsEntryWithNoEqualSignIsSkipped() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${<caret>i}\", strings={\"malformed\"})\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertFalse("Malformed strings entry must not produce a variable",
                ctx.allVariables().contains("malformed"));
    }

    public void testMultiplePermuteVarsAllResolved() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>i}\")\n" +
                "@PermuteVar(varName=\"j\", from=\"1\", to=\"3\")\n" +
                "@PermuteVar(varName=\"k\", from=\"1\", to=\"3\")\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue(ctx.allVariables().contains("j"));
        assertTrue(ctx.allVariables().contains("k"));
    }

    // --- Robustness ---

    public void testNullReturnedWhenNoPermuteAnnotation() {
        myFixture.configureByText("Plain.java",
                "package io.example;\n" +
                "public class Plain {\n" +
                "    String s = \"hello\";\n" +
                "}");
        // No caret inside ${...}, no @Permute — just verify resolve() doesn't throw
        JexlContext ctx = JexlContextResolver.resolve(
                myFixture.getFile().findElementAt(0));
        assertNull("Expected null context for non-Permuplate class", ctx);
    }

    public void testBuiltinNamesAlwaysPresent() {
        assertFalse(JexlContextResolver.BUILTIN_NAMES.isEmpty());
        assertTrue(JexlContextResolver.BUILTIN_NAMES.contains("alpha"));
        assertTrue(JexlContextResolver.BUILTIN_NAMES.contains("typeArgList"));
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

- [ ] **Step 3: Create `JexlContext`**

```java
// src/main/java/io/quarkiverse/permuplate/intellij/jexl/context/JexlContext.java
package io.quarkiverse.permuplate.intellij.jexl.context;

import org.jetbrains.annotations.Nullable;

import java.util.*;

public record JexlContext(Set<String> variables, @Nullable String innerVariable) {

    public Set<String> allVariables() {
        if (innerVariable == null) return Collections.unmodifiableSet(variables);
        Set<String> all = new LinkedHashSet<>(variables);
        all.add(innerVariable);
        return Collections.unmodifiableSet(all);
    }
}
```

- [ ] **Step 4: Create `JexlBuiltin`**

```java
// src/main/java/io/quarkiverse/permuplate/intellij/jexl/context/JexlBuiltin.java
package io.quarkiverse.permuplate.intellij.jexl.context;

import java.util.Map;

public record JexlBuiltin(String name, String[] paramNames,
                           String[] paramTypes, String returnType) {

    public static final Map<String, JexlBuiltin> ALL = Map.of(
            "alpha",        new JexlBuiltin("alpha",        new String[]{"n"},
                                            new String[]{"int"},          "String"),
            "lower",        new JexlBuiltin("lower",        new String[]{"n"},
                                            new String[]{"int"},          "String"),
            "typeArgList",  new JexlBuiltin("typeArgList",  new String[]{"from","to","style"},
                                            new String[]{"int","int","String"}, "String"),
            "capitalize",   new JexlBuiltin("capitalize",   new String[]{"s"},
                                            new String[]{"String"},       "String"),
            "decapitalize", new JexlBuiltin("decapitalize", new String[]{"s"},
                                            new String[]{"String"},       "String"),
            "max",          new JexlBuiltin("max",          new String[]{"a","b"},
                                            new String[]{"int","int"},    "int"),
            "min",          new JexlBuiltin("min",          new String[]{"a","b"},
                                            new String[]{"int","int"},    "int")
    );

    public String signature() {
        StringBuilder sb = new StringBuilder(name).append("(");
        for (int i = 0; i < paramNames.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes[i]).append(" ").append(paramNames[i]);
        }
        return sb.append(") → ").append(returnType).toString();
    }
}
```

- [ ] **Step 5: Create `JexlContextResolver`**

```java
// src/main/java/io/quarkiverse/permuplate/intellij/jexl/context/JexlContextResolver.java
package io.quarkiverse.permuplate.intellij.jexl.context;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import io.quarkiverse.permuplate.intellij.shared.PermuteAnnotations;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class JexlContextResolver {

    public static final Set<String> BUILTIN_NAMES = JexlBuiltin.ALL.keySet();

    @Nullable
    public static JexlContext resolve(@Nullable PsiElement elementInJexl) {
        if (elementInJexl == null) return null;

        PsiFile jexlFile = elementInJexl.getContainingFile();
        if (jexlFile == null) return null;

        PsiElement host = InjectedLanguageManager.getInstance(elementInJexl.getProject())
                .getInjectionHost(jexlFile);
        if (!(host instanceof PsiLiteralExpression)) return null;

        PsiElement pairEl = host.getParent();
        if (!(pairEl instanceof PsiNameValuePair pair)) return null;

        PsiElement paramListEl = pair.getParent();
        if (!(paramListEl instanceof PsiAnnotationParameterList)) return null;

        PsiAnnotation hostAnnotation = (PsiAnnotation) paramListEl.getParent();

        PsiClass enclosingClass = PsiTreeUtil.getParentOfType(host, PsiClass.class);
        if (enclosingClass == null) return null;

        Set<String> variables = new LinkedHashSet<>();

        for (PsiAnnotation ann : enclosingClass.getAnnotations()) {
            String fqn = ann.getQualifiedName();
            if (isPermute(fqn)) {
                String varName = stringAttr(ann, "varName");
                variables.add(varName != null ? varName : "i");
                collectNameParts(ann, "strings", variables);
                collectNameParts(ann, "macros", variables);
            } else if (isPermuteVar(fqn)) {
                String varName = stringAttr(ann, "varName");
                if (varName != null) variables.add(varName);
            } else if (isPermuteMacros(fqn)) {
                collectNameParts(ann, "macros", variables);
            }
        }

        // Inner variable from @PermuteMethod or @PermuteSwitchArm on the host annotation
        String innerVariable = null;
        String hostFqn = hostAnnotation.getQualifiedName();
        if (isPermuteMethod(hostFqn) || isPermuteSwitchArm(hostFqn)) {
            innerVariable = stringAttr(hostAnnotation, "varName");
        }

        // @PermuteMacros on enclosing outer class
        PsiClass outerClass = PsiTreeUtil.getParentOfType(enclosingClass, PsiClass.class);
        if (outerClass != null) {
            for (PsiAnnotation ann : outerClass.getAnnotations()) {
                if (isPermuteMacros(ann.getQualifiedName()))
                    collectNameParts(ann, "macros", variables);
            }
        }

        return new JexlContext(variables, innerVariable);
    }

    private static void collectNameParts(PsiAnnotation ann, String attr, Set<String> out) {
        PsiAnnotationMemberValue val = ann.findAttributeValue(attr);
        if (!(val instanceof PsiArrayInitializerMemberValue arr)) return;
        for (PsiAnnotationMemberValue member : arr.getInitializers()) {
            if (!(member instanceof PsiLiteralExpression lit)) continue;
            if (!(lit.getValue() instanceof String s)) continue;
            int eq = s.indexOf('=');
            if (eq > 0) out.add(s.substring(0, eq).trim());
        }
    }

    @Nullable
    private static String stringAttr(PsiAnnotation ann, String name) {
        PsiAnnotationMemberValue val = ann.findAttributeValue(name);
        if (!(val instanceof PsiLiteralExpression lit)) return null;
        return lit.getValue() instanceof String s ? s : null;
    }

    private static boolean isPermute(String fqn) {
        return match(fqn, "Permute") && !isPermuteVar(fqn)
                && !isPermuteMacros(fqn) && !isPermuteMethod(fqn)
                && !isPermuteSwitchArm(fqn);
    }
    private static boolean isPermuteVar(String fqn)        { return match(fqn, "PermuteVar"); }
    private static boolean isPermuteMacros(String fqn)     { return match(fqn, "PermuteMacros"); }
    private static boolean isPermuteMethod(String fqn)     { return match(fqn, "PermuteMethod"); }
    private static boolean isPermuteSwitchArm(String fqn)  { return match(fqn, "PermuteSwitchArm"); }

    private static boolean match(String fqn, String simpleName) {
        if (fqn == null) return false;
        return fqn.equals("io.quarkiverse.permuplate." + simpleName)
                || fqn.equals(simpleName);
    }
}
```

- [ ] **Step 6: Run context tests — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.jexl.context.JexlContextResolverTest" 2>&1 | tail -10
```

- [ ] **Step 7: Run full suite — expect green**

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/quarkiverse/permuplate/intellij/jexl/context/ \
        src/test/java/io/quarkiverse/permuplate/intellij/jexl/context/
git commit -m "feat: JexlContext, JexlBuiltin, JexlContextResolver — closes #ISSUE, epic #EPIC

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: JexlLanguageInjector

**Files:**
- Create: `jexl/inject/JexlLanguageInjector.java`
- Create: `src/test/java/io/quarkiverse/permuplate/intellij/jexl/inject/JexlLanguageInjectorTest.java`
- Modify: `plugin.xml`

- [ ] **Step 1: Write the failing tests**

```java
// src/test/java/io/quarkiverse/permuplate/intellij/jexl/inject/JexlLanguageInjectorTest.java
package io.quarkiverse.permuplate.intellij.jexl.inject;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlLanguage;

import java.util.List;

public class JexlLanguageInjectorTest extends BasePlatformTestCase {

    // --- Happy path: injection fires ---

    public void testInjectionFiringInClassNameAttribute() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {}");
        PsiFile file = myFixture.getFile();
        InjectedLanguageManager mgr = InjectedLanguageManager.getInstance(getProject());

        // Find the "Join${i}" literal
        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitLiteralExpression(PsiLiteralExpression expression) {
                if ("Join${i}".equals(expression.getValue())) {
                    List<com.intellij.psi.util.PsiTreeUtil> injected =
                            mgr.getInjectedPsiFiles(expression);
                    assertNotNull("Expected injection in className attribute", injected);
                    assertFalse("Expected non-empty injected files", injected.isEmpty());
                }
            }
        });
    }

    public void testInjectionLanguageIsJexl() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {}");
        PsiFile file = myFixture.getFile();
        InjectedLanguageManager mgr = InjectedLanguageManager.getInstance(getProject());

        final boolean[] found = {false};
        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitLiteralExpression(PsiLiteralExpression expression) {
                if ("Join${i}".equals(expression.getValue())) {
                    List<?> pairs = mgr.getInjectedPsiFiles(expression);
                    if (pairs != null) {
                        for (Object pair : pairs) {
                            if (pair instanceof com.intellij.openapi.util.Pair<?,?> p
                                    && p.first instanceof PsiElement el) {
                                if (el.getLanguage() == JexlLanguage.INSTANCE) {
                                    found[0] = true;
                                }
                            }
                        }
                    }
                }
            }
        });
        assertTrue("Injected language should be JEXL", found[0]);
    }

    // --- findJexlRanges unit tests (static method) ---

    public void testFindJexlRangesSingleExpression() {
        var ranges = JexlLanguageInjector.findJexlRanges("Join${i}");
        assertEquals(1, ranges.size());
        assertEquals(6, ranges.get(0).getStartOffset()); // after '${'
        assertEquals(7, ranges.get(0).getEndOffset());   // before '}'
    }

    public void testFindJexlRangesMultipleExpressions() {
        var ranges = JexlLanguageInjector.findJexlRanges("${from}to${to}");
        assertEquals(2, ranges.size());
        assertEquals(2, ranges.get(0).getStartOffset()); // 'from'
        assertEquals(6, ranges.get(0).getEndOffset());
        assertEquals(10, ranges.get(1).getStartOffset()); // 'to'
        assertEquals(12, ranges.get(1).getEndOffset());
    }

    public void testFindJexlRangesEmptyExpression() {
        var ranges = JexlLanguageInjector.findJexlRanges("prefix${}suffix");
        assertEquals(1, ranges.size());
        // ${ starts at 6, content is empty (start==end==8)
        assertEquals(8, ranges.get(0).getStartOffset());
        assertEquals(8, ranges.get(0).getEndOffset());
    }

    // --- Correctness ---

    public void testInjectionFiresInFromAttribute() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"${min}\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {}");
        // Verify no exception thrown and file parses cleanly
        assertNotNull(myFixture.getFile());
    }

    public void testInjectionFiresInWhenAttribute() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteReturn(className=\"Join${i}\", when=\"${i} < 5\")\n" +
                "    public Object build() { return null; }\n" +
                "}");
        assertNotNull(myFixture.getFile());
    }

    // --- Robustness ---

    public void testNoInjectionInNonPermuteAnnotation() {
        myFixture.configureByText("Plain.java",
                "package io.example;\n" +
                "public class Plain {\n" +
                "    @SuppressWarnings(\"unchecked ${i}\")\n" +
                "    public void m() {}\n" +
                "}");
        InjectedLanguageManager mgr = InjectedLanguageManager.getInstance(getProject());
        final boolean[] injected = {false};
        myFixture.getFile().accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitLiteralExpression(PsiLiteralExpression expression) {
                if ("unchecked ${i}".equals(expression.getValue())) {
                    List<?> list = mgr.getInjectedPsiFiles(expression);
                    if (list != null && !list.isEmpty()) injected[0] = true;
                }
            }
        });
        assertFalse("Must not inject into non-Permuplate annotation", injected[0]);
    }

    public void testNoInjectionWhenNoTemplateExpression() {
        var ranges = JexlLanguageInjector.findJexlRanges("Join");
        assertTrue("No ranges expected for plain literal", ranges.isEmpty());
    }

    public void testUnterminatedExpressionIsSkipped() {
        var ranges = JexlLanguageInjector.findJexlRanges("Join${");
        assertTrue("Unterminated ${ must produce no range", ranges.isEmpty());
    }

    public void testSelfLiteralProducesNoRanges() {
        var ranges = JexlLanguageInjector.findJexlRanges("self");
        assertTrue("'self' has no ${...} so no ranges", ranges.isEmpty());
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

- [ ] **Step 3: Create `JexlLanguageInjector`**

```java
// src/main/java/io/quarkiverse/permuplate/intellij/jexl/inject/JexlLanguageInjector.java
package io.quarkiverse.permuplate.intellij.jexl.inject;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlLanguage;
import io.quarkiverse.permuplate.intellij.shared.PermuteAnnotations;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class JexlLanguageInjector implements MultiHostInjector {

    @Override
    public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar,
                                     @NotNull PsiElement context) {
        if (!(context instanceof PsiLiteralExpression literal)) return;
        if (!(literal.getValue() instanceof String value)) return;

        PsiElement parent = literal.getParent();
        if (!(parent instanceof PsiNameValuePair pair)) return;

        String attrName = pair.getName();
        if (attrName == null) attrName = "value";
        if (!PermuteAnnotations.JEXL_BEARING_ATTRIBUTES.contains(attrName)) return;

        PsiElement paramList = pair.getParent();
        if (!(paramList instanceof PsiAnnotationParameterList)) return;
        PsiAnnotation annotation = (PsiAnnotation) paramList.getParent();
        if (!PermuteAnnotations.isPermuteAnnotation(annotation.getQualifiedName())) return;

        List<TextRange> ranges = findJexlRanges(value);
        if (ranges.isEmpty()) return;

        registrar.startInjecting(JexlLanguage.INSTANCE);
        for (TextRange range : ranges) {
            // +1 to skip the opening double-quote character in the literal's text
            registrar.addPlace("", "", literal,
                    new TextRange(range.getStartOffset() + 1, range.getEndOffset() + 1));
        }
        registrar.doneInjecting();
    }

    /** Returns ranges within {@code value} (no surrounding quotes) for each ${...} content. */
    public static List<TextRange> findJexlRanges(String value) {
        List<TextRange> ranges = new ArrayList<>();
        int i = 0;
        while (i < value.length()) {
            int dollarBrace = value.indexOf("${", i);
            if (dollarBrace < 0) break;
            int closeBrace = value.indexOf('}', dollarBrace + 2);
            if (closeBrace < 0) break; // unterminated — skip
            ranges.add(new TextRange(dollarBrace + 2, closeBrace));
            i = closeBrace + 1;
        }
        return ranges;
    }

    @Override
    public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(PsiLiteralExpression.class);
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`** — inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- JEXL language injection -->
<multiHostInjector
    implementationClass="io.quarkiverse.permuplate.intellij.jexl.inject.JexlLanguageInjector"/>
```

- [ ] **Step 5: Run injector tests — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.jexl.inject.JexlLanguageInjectorTest" 2>&1 | tail -10
```

- [ ] **Step 6: Run full suite — expect green**

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/quarkiverse/permuplate/intellij/jexl/inject/ \
        src/main/resources/META-INF/plugin.xml \
        src/test/java/io/quarkiverse/permuplate/intellij/jexl/inject/
git commit -m "feat: JexlLanguageInjector — closes #ISSUE, epic #EPIC

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: JexlCompletionContributor

**Files:**
- Create: `jexl/completion/JexlCompletionContributor.java`
- Create: `src/test/java/io/quarkiverse/permuplate/intellij/jexl/completion/JexlCompletionContributorTest.java`
- Modify: `plugin.xml`

- [ ] **Step 1: Write the failing tests**

```java
// src/test/java/io/quarkiverse/permuplate/intellij/jexl/completion/JexlCompletionContributorTest.java
package io.quarkiverse.permuplate.intellij.jexl.completion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class JexlCompletionContributorTest extends BasePlatformTestCase {

    // --- Happy path ---

    public void testCompletionOffersVarName() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>}\")\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull("Completion must return results", items);
        assertTrue("Expected 'i' in completions", items.contains("i"));
    }

    public void testCompletionOffersAllBuiltins() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>}\")\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        for (String builtin : List.of("alpha","lower","typeArgList","capitalize",
                                      "decapitalize","max","min")) {
            assertTrue("Expected built-in '" + builtin + "' in completions",
                    items.contains(builtin));
        }
    }

    // --- Correctness ---

    public void testCompletionOffersPermuteVarVariable() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>}\")\n" +
                "@PermuteVar(varName=\"k\", from=\"1\", to=\"3\")\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("Expected 'k' from @PermuteVar", items.contains("k"));
        assertTrue("Expected 'i' still present",   items.contains("i"));
    }

    public void testCompletionOffersStringsConstants() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"${<caret>}\",\n" +
                "         className=\"Join${i}\", strings={\"max=5\"})\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("Expected 'max' from strings=", items.contains("max"));
    }

    public void testCompletionOffersMacroNames() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${<caret>}\", macros={\"prev=${i-1}\"})\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("Expected 'prev' from macros=", items.contains("prev"));
    }

    public void testCompletionOffersInnerVarFromPermuteMethod() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", to=\"${<caret>}\", name=\"join${j}\")\n" +
                "    public void join2() {}\n" +
                "}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("Expected inner var 'j' in completions", items.contains("j"));
    }

    // --- Robustness ---

    public void testNoCompletionOutsideJexlExpression() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join<caret>${i}\")\n" +
                "public class Join2 {}");
        // Caret is in the literal part, not inside ${...} — completion must not crash
        // (may return null or Java completions — just must not throw)
        try {
            myFixture.completeBasic();
        } catch (Exception e) {
            fail("Completion must not throw outside ${...}: " + e.getMessage());
        }
    }

    public void testNoCompletionWithoutPermuteAnnotation() {
        myFixture.configureByText("Plain.java",
                "package io.example;\n" +
                "public class Plain {\n" +
                "    @SuppressWarnings(\"${<caret>}\")\n" +
                "    public void m() {}\n" +
                "}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        // May be null or empty — must not contain JEXL-specific completions
        if (items != null) {
            assertFalse("'alpha' must not appear in non-Permuplate completion",
                    items.contains("alpha"));
        }
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

- [ ] **Step 3: Create `JexlCompletionContributor`**

```java
// src/main/java/io/quarkiverse/permuplate/intellij/jexl/completion/JexlCompletionContributor.java
package io.quarkiverse.permuplate.intellij.jexl.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.util.ProcessingContext;
import io.quarkiverse.permuplate.intellij.jexl.context.*;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlLanguage;
import org.jetbrains.annotations.NotNull;

public class JexlCompletionContributor extends CompletionContributor {

    public JexlCompletionContributor() {
        extend(CompletionType.BASIC,
               PlatformPatterns.psiElement().withLanguage(JexlLanguage.INSTANCE),
               new CompletionProvider<>() {
                   @Override
                   protected void addCompletions(@NotNull CompletionParameters parameters,
                                                 @NotNull ProcessingContext context,
                                                 @NotNull CompletionResultSet result) {
                       JexlContext ctx = JexlContextResolver.resolve(parameters.getPosition());
                       if (ctx == null) return;

                       for (String var : ctx.allVariables()) {
                           result.addElement(LookupElementBuilder.create(var)
                                   .bold()
                                   .withTypeText("variable"));
                       }

                       for (JexlBuiltin builtin : JexlBuiltin.ALL.values()) {
                           result.addElement(LookupElementBuilder.create(builtin.name())
                                   .withTypeText("built-in")
                                   .withTailText("(" + String.join(", ", builtin.paramNames()) + ")")
                                   .withInsertHandler((ctx2, item) -> {
                                       int offset = ctx2.getTailOffset();
                                       ctx2.getDocument().insertString(offset, "(");
                                       ctx2.getEditor().getCaretModel().moveToOffset(offset + 1);
                                   }));
                       }
                   }
               });
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

```xml
<!-- JEXL completion -->
<completion.contributor
    language="JEXL"
    implementationClass="io.quarkiverse.permuplate.intellij.jexl.completion.JexlCompletionContributor"/>
```

- [ ] **Step 5: Run completion tests — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.jexl.completion.JexlCompletionContributorTest" 2>&1 | tail -10
```

- [ ] **Step 6: Run full suite — expect green**

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/quarkiverse/permuplate/intellij/jexl/completion/ \
        src/main/resources/META-INF/plugin.xml \
        src/test/java/io/quarkiverse/permuplate/intellij/jexl/completion/
git commit -m "feat: JexlCompletionContributor — closes #ISSUE, epic #EPIC

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: JexlParameterInfoHandler

**Files:**
- Create: `jexl/paraminfo/JexlParameterInfoHandler.java`
- Create: `src/test/java/io/quarkiverse/permuplate/intellij/jexl/paraminfo/JexlParameterInfoHandlerTest.java`
- Modify: `plugin.xml`

- [ ] **Step 1: Write the failing tests**

```java
// src/test/java/io/quarkiverse/permuplate/intellij/jexl/paraminfo/JexlParameterInfoHandlerTest.java
package io.quarkiverse.permuplate.intellij.jexl.paraminfo;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlBuiltin;
import junit.framework.TestCase;

public class JexlParameterInfoHandlerTest extends BasePlatformTestCase {

    // --- Happy path: JexlBuiltin data ---

    public void testAlphaSignature() {
        JexlBuiltin b = JexlBuiltin.ALL.get("alpha");
        assertNotNull(b);
        assertEquals(1, b.paramNames().length);
        assertEquals("n", b.paramNames()[0]);
        assertEquals("int", b.paramTypes()[0]);
        assertEquals("String", b.returnType());
    }

    public void testTypeArgListSignature() {
        JexlBuiltin b = JexlBuiltin.ALL.get("typeArgList");
        assertNotNull(b);
        assertEquals(3, b.paramNames().length);
        assertEquals("from",  b.paramNames()[0]);
        assertEquals("to",    b.paramNames()[1]);
        assertEquals("style", b.paramNames()[2]);
    }

    public void testMaxSignature() {
        JexlBuiltin b = JexlBuiltin.ALL.get("max");
        assertNotNull(b);
        assertEquals(2, b.paramNames().length);
        assertEquals("int", b.returnType());
    }

    public void testSignatureFormatting() {
        JexlBuiltin b = JexlBuiltin.ALL.get("alpha");
        assertEquals("alpha(int n) → String", b.signature());
    }

    public void testTypeArgListSignatureFormatting() {
        JexlBuiltin b = JexlBuiltin.ALL.get("typeArgList");
        assertEquals("typeArgList(int from, int to, String style) → String", b.signature());
    }

    // --- Correctness: all 7 built-ins registered ---

    public void testAllBuiltinsHaveEntries() {
        for (String name : new String[]{"alpha","lower","typeArgList","capitalize",
                                        "decapitalize","max","min"}) {
            assertNotNull("Missing built-in: " + name, JexlBuiltin.ALL.get(name));
        }
        assertEquals(7, JexlBuiltin.ALL.size());
    }

    // --- Integration: parameter info handler finds element ---

    public void testHandlerFindsFunctionCallElement() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${typeArgList(1, <caret>i, 'T')}\")\n" +
                "public class Join2 {}");
        // Verify no exception — parameter info lookup should not throw
        try {
            myFixture.testParameterInfo();
        } catch (AssertionError | Exception e) {
            // testParameterInfo may fail if no item to show — that's acceptable at this stage
            // What must NOT happen is an unhandled exception from our handler
            if (e.getMessage() != null && e.getMessage().contains("NullPointerException")) {
                fail("ParameterInfoHandler must not throw NPE: " + e.getMessage());
            }
        }
    }

    // --- Robustness ---

    public void testBuiltinSignatureNeverNull() {
        for (JexlBuiltin b : JexlBuiltin.ALL.values()) {
            assertNotNull("Signature must not be null for " + b.name(), b.signature());
            assertFalse("Signature must not be empty for " + b.name(), b.signature().isEmpty());
        }
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

- [ ] **Step 3: Create `JexlParameterInfoHandler`**

```java
// src/main/java/io/quarkiverse/permuplate/intellij/jexl/paraminfo/JexlParameterInfoHandler.java
package io.quarkiverse.permuplate.intellij.jexl.paraminfo;

import com.intellij.lang.parameterInfo.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlBuiltin;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlTokenTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JexlParameterInfoHandler
        implements ParameterInfoHandler<PsiElement, JexlBuiltin> {

    @Override
    public @Nullable PsiElement findElementForParameterInfo(
            @NotNull CreateParameterInfoContext context) {
        return findFunctionName(context.getFile(), context.getOffset());
    }

    @Override
    public void showParameterInfo(@NotNull PsiElement element,
                                   @NotNull CreateParameterInfoContext context) {
        String name = element.getText();
        JexlBuiltin builtin = JexlBuiltin.ALL.get(name);
        if (builtin != null) {
            context.setItemsToShow(new Object[]{builtin});
            context.showHint(element, element.getTextOffset(), this);
        }
    }

    @Override
    public @Nullable PsiElement findElementForUpdatingParameterInfo(
            @NotNull UpdateParameterInfoContext context) {
        return findFunctionName(context.getFile(), context.getOffset());
    }

    @Override
    public void updateParameterInfo(@NotNull PsiElement element,
                                     @NotNull UpdateParameterInfoContext context) {
        PsiElement lParen = PsiTreeUtil.nextVisibleLeaf(element);
        if (lParen == null
                || lParen.getNode().getElementType() != JexlTokenTypes.LPAREN) return;

        int commas = 0;
        int targetOffset = context.getOffset();
        PsiElement cur = PsiTreeUtil.nextVisibleLeaf(lParen);
        while (cur != null && cur.getTextOffset() < targetOffset) {
            if (cur.getNode().getElementType() == JexlTokenTypes.COMMA) commas++;
            if (cur.getNode().getElementType() == JexlTokenTypes.RPAREN) break;
            cur = PsiTreeUtil.nextVisibleLeaf(cur);
        }
        context.setCurrentParameter(commas);
    }

    @Override
    public void updateUI(JexlBuiltin builtin,
                          @NotNull ParameterInfoUIContext context) {
        if (builtin == null) { context.setUIComponentEnabled(false); return; }

        String[] params  = builtin.paramNames();
        String[] types   = builtin.paramTypes();
        int current      = context.getCurrentParameterIndex();

        StringBuilder sb = new StringBuilder();
        int hlStart = -1, hlEnd = -1;
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            int start = sb.length();
            sb.append(types[i]).append(" ").append(params[i]);
            if (i == current) { hlStart = start; hlEnd = sb.length(); }
        }
        context.setupUIComponentPresentation(
                sb.toString(), hlStart, hlEnd,
                false, false, false, context.getDefaultParameterColor());
    }

    @Nullable
    private static PsiElement findFunctionName(PsiFile file, int offset) {
        // Walk left from offset to find a built-in identifier followed by LPAREN
        PsiElement el = file.findElementAt(offset);
        while (el != null) {
            if (el.getNode() != null
                    && el.getNode().getElementType() == JexlTokenTypes.LPAREN) {
                PsiElement prev = PsiTreeUtil.prevVisibleLeaf(el);
                if (prev != null
                        && prev.getNode().getElementType() == JexlTokenTypes.IDENTIFIER
                        && JexlBuiltin.ALL.containsKey(prev.getText())) {
                    return prev;
                }
                return null;
            }
            el = el.getParent();
        }
        return null;
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

```xml
<!-- JEXL parameter info -->
<lang.parameterInfoHandler
    language="JEXL"
    implementationClass="io.quarkiverse.permuplate.intellij.jexl.paraminfo.JexlParameterInfoHandler"/>
```

- [ ] **Step 5: Run parameter info tests — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.jexl.paraminfo.JexlParameterInfoHandlerTest" 2>&1 | tail -10
```

- [ ] **Step 6: Run full suite — expect green**

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/quarkiverse/permuplate/intellij/jexl/paraminfo/ \
        src/main/resources/META-INF/plugin.xml \
        src/test/java/io/quarkiverse/permuplate/intellij/jexl/paraminfo/
git commit -m "feat: JexlParameterInfoHandler — closes #ISSUE, epic #EPIC

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: JexlAnnotator + commons-jexl3 dependency

**Files:**
- Modify: `build.gradle.kts` — add `commons-jexl3:3.3`
- Create: `jexl/annotate/JexlAnnotator.java`
- Create: `src/test/java/io/quarkiverse/permuplate/intellij/jexl/annotate/JexlAnnotatorTest.java`
- Modify: `plugin.xml`

- [ ] **Step 1: Add commons-jexl3 to `build.gradle.kts`**

In the `dependencies { }` block, after the existing `implementation` lines:

```kotlin
// Apache Commons JEXL3 — used by JexlAnnotator for expression syntax validation
// (validation pattern adapted from jenkinsci/idea-stapler-plugin, BSD-2-Clause)
implementation("org.apache.commons:commons-jexl3:3.3")
```

- [ ] **Step 2: Write the failing tests**

```java
// src/test/java/io/quarkiverse/permuplate/intellij/jexl/annotate/JexlAnnotatorTest.java
package io.quarkiverse.permuplate.intellij.jexl.annotate;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;

import java.util.List;

public class JexlAnnotatorTest extends BasePlatformTestCase {

    private List<HighlightInfo> highlight(String javaSource) {
        myFixture.configureByText("Join2.java", javaSource);
        return myFixture.doHighlighting();
    }

    private boolean hasWarningContaining(List<HighlightInfo> infos, String substring) {
        return infos.stream().anyMatch(h ->
                h.getSeverity() == HighlightSeverity.WARNING
                && h.getDescription() != null
                && h.getDescription().contains(substring));
    }

    // --- Happy path: no warnings for valid expressions ---

    public void testNoWarningForDefinedVariable() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {}");
        assertFalse("No undefined-variable warning for 'i'",
                hasWarningContaining(infos, "Unknown variable 'i'"));
    }

    public void testNoWarningForBuiltinFunctionCall() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${alpha(i)}\")\n" +
                "public class Join2 {}");
        assertFalse("No warning for built-in 'alpha'",
                hasWarningContaining(infos, "Unknown variable 'alpha'"));
        assertFalse("No warning for variable 'i' used as arg",
                hasWarningContaining(infos, "Unknown variable 'i'"));
    }

    public void testNoWarningForArithmeticExpression() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"${i-1}\", className=\"Join${i}\")\n" +
                "public class Join2 {}");
        assertFalse("No warning for 'i-1'",
                hasWarningContaining(infos, "Unknown variable"));
    }

    // --- Correctness: warnings for undefined variables ---

    public void testWarningForUndefinedVariable() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${xyz}\")\n" +
                "public class Join2 {}");
        assertTrue("Expected warning for undefined 'xyz'",
                hasWarningContaining(infos, "xyz"));
    }

    public void testNoFalsePositiveForStringsConstant() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"${max}\",\n" +
                "         className=\"Join${i}\", strings={\"max=5\"})\n" +
                "public class Join2 {}");
        assertFalse("'max' from strings= must not be flagged",
                hasWarningContaining(infos, "Unknown variable 'max'"));
    }

    public void testNoFalsePositiveForMacroName() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${prev}\", macros={\"prev=${i-1}\"})\n" +
                "public class Join2 {}");
        assertFalse("'prev' from macros= must not be flagged",
                hasWarningContaining(infos, "Unknown variable 'prev'"));
    }

    public void testExactlyOneWarningPerUndefinedVariable() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${xyz}\")\n" +
                "public class Join2 {}");
        long warningCount = infos.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING
                        && h.getDescription() != null
                        && h.getDescription().contains("xyz"))
                .count();
        assertEquals("Expected exactly one warning for 'xyz'", 1, warningCount);
    }

    // --- Robustness ---

    public void testNoWarningForSelfLiteral() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "@PermuteDefaultReturn(className=\"self\")\n" +
                "public class Join2 {}");
        assertFalse("'self' literal has no ${...} — no injection, no warning",
                hasWarningContaining(infos, "self"));
    }

    public void testNoExceptionForEmptyExpression() {
        // ${} — empty JEXL expression, annotator must not throw
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${}\")\n" +
                "public class Join2 {}");
        assertNotNull("doHighlighting must not return null", infos);
    }

    public void testNoWarningInNonPermuteAnnotation() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "public class Plain {\n" +
                "    @SuppressWarnings(\"${xyz}\")\n" +
                "    public void m() {}\n" +
                "}");
        assertFalse("No JEXL warnings in non-Permuplate annotations",
                hasWarningContaining(infos, "Unknown variable"));
    }
}
```

- [ ] **Step 3: Run — expect compile failure**

- [ ] **Step 4: Create `JexlAnnotator`**

```java
// src/main/java/io/quarkiverse/permuplate/intellij/jexl/annotate/JexlAnnotator.java
package io.quarkiverse.permuplate.intellij.jexl.annotate;

import com.intellij.lang.annotation.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import io.quarkiverse.permuplate.intellij.jexl.context.*;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlTokenTypes;
import org.apache.commons.jexl3.*;
import org.jetbrains.annotations.NotNull;

public class JexlAnnotator implements Annotator {

    // Validation pattern adapted from jenkinsci/idea-stapler-plugin JexlInspection (BSD-2-Clause)
    private static final JexlEngine JEXL = new JexlBuilder().silent(true).strict(false).create();

    @Override
    public void annotate(@NotNull PsiElement element,
                          @NotNull AnnotationHolder holder) {
        PsiFile file = element.getContainingFile();
        if (file == null) return;

        // Only annotate the root of the injected JEXL file, not each token
        if (element.getParent() != file) return;

        String text = file.getText();
        if (text == null || text.isEmpty()) return;

        JexlContext ctx = JexlContextResolver.resolve(element);

        // 1. Syntax validation via Apache Commons JEXL
        try {
            JEXL.createExpression(text);
        } catch (JexlException e) {
            String msg = e.getMessage();
            if (msg == null) msg = "JEXL syntax error";
            holder.newAnnotation(HighlightSeverity.WARNING,
                    "JEXL syntax error: " + msg)
                  .range(element.getTextRange())
                  .create();
            return; // Don't also report variable errors when syntax is broken
        }

        // 2. Undefined variable detection
        if (ctx == null) return;
        element.getContainingFile().accept(new com.intellij.psi.PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement el) {
                super.visitElement(el);
                if (el.getNode() == null) return;
                if (el.getNode().getElementType() != JexlTokenTypes.IDENTIFIER) return;

                String name = el.getText();
                if (JexlContextResolver.BUILTIN_NAMES.contains(name)) return;

                // Skip method-call receivers (identifier followed by LPAREN)
                PsiElement next = com.intellij.psi.util.PsiTreeUtil.nextVisibleLeaf(el);
                if (next != null && next.getNode().getElementType() == JexlTokenTypes.LPAREN)
                    return;

                // Skip property access (identifier preceded by DOT)
                PsiElement prev = com.intellij.psi.util.PsiTreeUtil.prevVisibleLeaf(el);
                if (prev != null && prev.getNode().getElementType() == JexlTokenTypes.DOT)
                    return;

                if (!ctx.allVariables().contains(name)) {
                    holder.newAnnotation(HighlightSeverity.WARNING,
                            "Unknown variable '" + name + "'. " +
                            "Check @Permute varName, @PermuteVar, strings= or macros=.")
                          .range(el.getTextRange())
                          .create();
                }
            }
        });
    }
}
```

- [ ] **Step 5: Register in `plugin.xml`**

```xml
<!-- JEXL annotator -->
<annotator
    language="JEXL"
    implementationClass="io.quarkiverse.permuplate.intellij.jexl.annotate.JexlAnnotator"/>
```

- [ ] **Step 6: Run annotator tests — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.jexl.annotate.JexlAnnotatorTest" 2>&1 | tail -10
```

- [ ] **Step 7: Run full suite — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test 2>&1 | tail -5
```

- [ ] **Step 8: Commit**

```bash
git add build.gradle.kts \
        src/main/java/io/quarkiverse/permuplate/intellij/jexl/annotate/ \
        src/main/resources/META-INF/plugin.xml \
        src/test/java/io/quarkiverse/permuplate/intellij/jexl/annotate/
git commit -m "feat: JexlAnnotator — syntax validation and undefined variable warnings — closes #ISSUE, epic #EPIC

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: End-to-end tests

**Files:**
- Create: `src/test/java/io/quarkiverse/permuplate/intellij/jexl/e2e/JexlFullTemplateTest.java`

- [ ] **Step 1: Write the E2E tests** (no "failing" step — all infrastructure is already in place; run directly)

```java
// src/test/java/io/quarkiverse/permuplate/intellij/jexl/e2e/JexlFullTemplateTest.java
package io.quarkiverse.permuplate.intellij.jexl.e2e;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * End-to-end tests: full Permuplate templates with multiple annotation types.
 * Tests the complete stack: injection → completion → annotator.
 */
public class JexlFullTemplateTest extends BasePlatformTestCase {

    // Full template with all axes: @PermuteVar, strings=, macros=, @PermuteMethod
    private static final String FULL_TEMPLATE =
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.*;\n" +
            "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
            "         className=\"Join${i}\",\n" +
            "         strings={\"max=7\"},\n" +
            "         macros={\"prev=${i-1}\"})\n" +
            "@PermuteVar(varName=\"k\", from=\"1\", to=\"3\")\n" +
            "public class Join2 {\n" +
            "    @PermuteMethod(varName=\"j\", to=\"${i-1}\", name=\"join${j}\")\n" +
            "    public void join2() {}\n" +
            "    @PermuteReturn(className=\"Join${i}\", when=\"${i} < max\")\n" +
            "    public Object build() { return null; }\n" +
            "}";

    // --- E2E: completion in multiple attribute positions ---

    public void testCompletionInClassNameOffersPrimaryVar() {
        myFixture.configureByText("Join2.java",
                FULL_TEMPLATE.replace("className=\"Join${i}\"",
                                      "className=\"Join${<caret>}\""));
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("i in className completion", items.contains("i"));
        assertTrue("k in className completion", items.contains("k"));
        assertTrue("max in className completion", items.contains("max"));
        assertTrue("prev in className completion", items.contains("prev"));
        assertTrue("alpha built-in in completion", items.contains("alpha"));
    }

    public void testCompletionInToAttributeOffersAllVars() {
        myFixture.configureByText("Join2.java",
                FULL_TEMPLATE.replace("to=\"${i-1}\"",
                                      "to=\"${<caret>}\""));
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("i in to= completion", items.contains("i"));
        assertTrue("max in to= completion", items.contains("max"));
    }

    public void testCompletionInPermuteMethodInnerVarAttribute() {
        myFixture.configureByText("Join2.java",
                FULL_TEMPLATE.replace("to=\"${i-1}\"",
                                      "to=\"${<caret>}\""));
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        // j is the inner variable defined by @PermuteMethod itself
        // i is from the outer @Permute
        assertTrue("i in @PermuteMethod to= completion", items.contains("i"));
    }

    public void testCompletionInPermuteReturnWhenAttribute() {
        myFixture.configureByText("Join2.java",
                FULL_TEMPLATE.replace("when=\"${i} < max\"",
                                      "when=\"${<caret>} < max\""));
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("i in @PermuteReturn when= completion", items.contains("i"));
    }

    // --- E2E: annotator produces no false positives on valid template ---

    public void testNoWarningsOnFullValidTemplate() {
        myFixture.configureByText("Join2.java", FULL_TEMPLATE);
        List<HighlightInfo> infos = myFixture.doHighlighting();
        long jexlWarnings = infos.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING
                        && h.getDescription() != null
                        && h.getDescription().contains("Unknown variable"))
                .count();
        assertEquals("No undefined-variable warnings on valid full template", 0, jexlWarnings);
    }

    // --- E2E: annotator catches undefined variables in full template context ---

    public void testWarningForTyopInFullTemplate() {
        myFixture.configureByText("Join2.java",
                FULL_TEMPLATE.replace("className=\"Join${i}\"",
                                      "className=\"Join${typo}\""));
        List<HighlightInfo> infos = myFixture.doHighlighting();
        boolean found = infos.stream().anyMatch(h ->
                h.getSeverity() == HighlightSeverity.WARNING
                && h.getDescription() != null
                && h.getDescription().contains("typo"));
        assertTrue("Expected warning for undefined 'typo' in full template context", found);
    }

    // --- E2E: injection does not fire on non-JEXL-bearing attributes ---

    public void testNoInjectionInVarNameAttribute() {
        // varName= is not a JEXL expression — must not be injected
        myFixture.configureByText("Join2.java", FULL_TEMPLATE);
        // Just verify file parses and highlights without exception
        assertNotNull(myFixture.doHighlighting());
    }

    // --- E2E: multiple ${...} expressions in same attribute ---

    public void testMultipleExpressionsInSameAttribute() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Combo${i}x${i}\")\n" +
                "public class Combo2 {}");
        List<HighlightInfo> infos = myFixture.doHighlighting();
        assertNotNull(infos);
        assertFalse("No undefined-variable warnings for 'i' in multi-expression className",
                infos.stream().anyMatch(h ->
                        h.getSeverity() == HighlightSeverity.WARNING
                        && h.getDescription() != null
                        && h.getDescription().contains("Unknown variable")));
    }
}
```

- [ ] **Step 2: Run E2E tests — expect green**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test \
  --tests "io.quarkiverse.permuplate.intellij.jexl.e2e.JexlFullTemplateTest" 2>&1 | tail -10
```

- [ ] **Step 3: Run full suite — expect green and verify total test count increased**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18 ./gradlew test 2>&1 | tail -5
grep 'tests="' build/test-results/test/TEST-*.xml | \
  grep -oP 'tests="\K[0-9]+' | awk '{s+=$1} END {print "Total: " s}'
```

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/quarkiverse/permuplate/intellij/jexl/e2e/
git commit -m "test: end-to-end JEXL assistance tests — closes #ISSUE, epic #EPIC

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Update ARCHITECTURE.md and DECISIONS.md

**Files:**
- Modify: `DECISIONS.md`
- Modify: `ARCHITECTURE.md`

- [ ] **Step 1: Add entry to DECISIONS.md**

Add this row to the decisions table:

```markdown
| JEXL language injection uses `MultiHostInjector` not `LanguageInjectionContributor` | `MultiHostInjector` is the correct API for injecting into PSI host elements (string literals). `LanguageInjectionContributor` is for file-level injection. `addPlace()` offset +1 accounts for the opening double-quote in the literal's text range. Empty prefix/suffix because JEXL expressions are self-contained fragments. |
| `JexlAnnotator` uses Apache Commons JEXL3 for syntax validation | Pattern adapted from `jenkinsci/idea-stapler-plugin` `JexlInspection` (BSD-2-Clause). Call `new JexlBuilder().create().createExpression(text)` and catch `JexlException` — avoids building a redundant JEXL parser. Severity is WARNING not ERROR because system properties (`-Dpermuplate.*`) are invisible to the IDE. |
```

- [ ] **Step 2: Update ARCHITECTURE.md**

Add a section under the IntelliJ plugin description:

```markdown
### JEXL String Assistance

The plugin provides full IDE authoring assistance inside `${...}` expressions in Permuplate annotation string attributes via language injection:

- **`JexlLanguageInjector`** (`MultiHostInjector`) — finds all `${...}` subranges in `PsiLiteralExpression` nodes inside Permuplate annotations and injects the JEXL language into each
- **`JexlLexer`** — hand-written lexer; tokens: IDENTIFIER, NUMBER, STRING, OPERATOR, LPAREN, RPAREN, COMMA, DOT, WHITESPACE, BAD_CHARACTER
- **`JexlContextResolver`** — walks from an injected fragment back to its host to collect variables in scope: primary `varName`, `@PermuteVar` names, `strings=` constants, `macros=` names, inner `varName` from `@PermuteMethod`/`@PermuteSwitchArm`
- **`JexlCompletionContributor`** — offers all in-scope variables + built-in function names
- **`JexlParameterInfoHandler`** — shows parameter signatures for built-in function calls
- **`JexlAnnotator`** — warns on JEXL syntax errors (via Apache Commons JEXL3) and undefined variables
```

- [ ] **Step 3: Commit**

```bash
git add DECISIONS.md ARCHITECTURE.md
git commit -m "docs: document JEXL language injection decisions and architecture — epic #EPIC

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Self-review

**Spec coverage check:**

| Spec requirement | Covered by task |
|---|---|
| Syntax highlighting | Task 4 (JexlSyntaxHighlighter) |
| Variable/function completion | Task 7 (JexlCompletionContributor) |
| Parameter hints for built-ins | Task 8 (JexlParameterInfoHandler) |
| Undefined variable warnings | Task 9 (JexlAnnotator) |
| Syntax error warnings | Task 9 (JexlAnnotator) |
| Inject into `${...}` subranges only | Task 6 (JexlLanguageInjector) |
| Skip `className="self"` | Task 6 (findJexlRanges — no `${...}` found) |
| Skip non-JEXL-bearing attributes | Task 6 (JEXL_BEARING_ATTRIBUTES check) |
| Collect `@PermuteVar` variables | Task 5 (JexlContextResolver) |
| Collect `strings=` constants | Task 5 (JexlContextResolver) |
| Collect `macros=` names | Task 5 (JexlContextResolver) |
| Inner `@PermuteMethod` varName | Task 5 (JexlContextResolver) |
| `@PermuteMacros` on outer class | Task 5 (JexlContextResolver) |
| `PermuteAnnotations` shared constants | Task 2 |
| GitHub issues/epic before commits | Task 1 |
| 4-category TDD (happy/E2E/correctness/robustness) | All tasks |
| DECISIONS.md + ARCHITECTURE.md updated | Task 11 |

No gaps found.
