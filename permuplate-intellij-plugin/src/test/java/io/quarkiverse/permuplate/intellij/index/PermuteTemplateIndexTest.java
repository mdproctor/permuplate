package io.quarkiverse.permuplate.intellij.index;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.FileContentImpl;

import java.util.List;
import java.util.Map;

public class PermuteTemplateIndexTest extends BasePlatformTestCase {

    /**
     * Directly invoke the indexer on the currently configured file.
     *
     * Uses FileContentImpl.createByText() so that in-memory VFS content
     * (from configureByText) is read correctly without relying on the
     * asynchronous FileBasedIndex infrastructure. This approach exercises
     * the full indexer logic — PSI parsing, annotation extraction, name
     * generation — while keeping tests fast and deterministic.
     *
     * The indexers use a simple-name fallback for annotation lookup, so they
     * work even when the permuplate-annotations JAR is not on the test
     * project's module classpath and PSI cannot resolve imports to FQNs.
     */
    private Map<String, PermuteTemplateData> invokeForwardIndexer() {
        VirtualFile vf = myFixture.getFile().getVirtualFile();
        CharSequence text = myFixture.getFile().getViewProvider().getContents();
        FileContent fc = FileContentImpl.createByText(vf, text, getProject());
        return new PermuteTemplateIndex().getIndexer().map(fc);
    }

    private Map<String, String> invokeReverseIndexer() {
        VirtualFile vf = myFixture.getFile().getVirtualFile();
        CharSequence text = myFixture.getFile().getViewProvider().getContents();
        FileContent fc = FileContentImpl.createByText(vf, text, getProject());
        return new PermuteGeneratedIndex().getIndexer().map(fc);
    }

    // Test: forward indexer extracts the template class with correct data
    public void testForwardIndexContainsTemplate() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {}");

        Map<String, PermuteTemplateData> result = invokeForwardIndexer();
        assertEquals(1, result.size());

        PermuteTemplateData data = result.get("Join2");
        assertNotNull("Expected key 'Join2' in index", data);
        assertEquals("i", data.varName);
        assertEquals(3, data.from);
        assertEquals(5, data.to);
        assertEquals("Join${i}", data.classNameTemplate);
        assertEquals(List.of("Join3", "Join4", "Join5"), data.generatedNames);
    }

    // Test: reverse indexer maps each generated name back to the template
    public void testReverseIndexMapsGeneratedToTemplate() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {}");

        Map<String, String> result = invokeReverseIndexer();
        assertEquals("Join2", result.get("Join3"));
        assertEquals("Join2", result.get("Join4"));
        assertEquals("Join2", result.get("Join5"));
        assertNull("Template name itself should not appear as a generated key", result.get("Join2"));
    }

    // Test: non-@Permute class is not indexed
    public void testNonTemplateClassNotIndexed() {
        myFixture.configureByText("Foo.java",
                "package io.example;\n" +
                "public class Foo {}");

        Map<String, PermuteTemplateData> result = invokeForwardIndexer();
        assertTrue("Non-template class should produce no index entries", result.isEmpty());
    }

    // Test: member annotation strings are collected
    public void testMemberAnnotationStringsCollected() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"c${i}\")\n" +
                "    private Object c2;\n" +
                "}");

        Map<String, PermuteTemplateData> result = invokeForwardIndexer();
        assertEquals(1, result.size());

        List<String> memberStrings = result.get("Join2").memberAnnotationStrings;
        assertTrue("Expected Callable${i} in member strings", memberStrings.contains("Callable${i}"));
        assertTrue("Expected c${i} in member strings", memberStrings.contains("c${i}"));
    }
}
