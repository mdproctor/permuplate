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
