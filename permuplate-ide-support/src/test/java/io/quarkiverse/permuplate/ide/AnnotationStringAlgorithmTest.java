package io.quarkiverse.permuplate.ide;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AnnotationStringAlgorithmTest {

    // =========================================================
    // parse()
    // =========================================================

    @Test
    void parse_emptyString() {
        var t = AnnotationStringAlgorithm.parse("");
        assertTrue(t.hasNoVariables());
        assertTrue(t.hasNoLiteral());
    }

    @Test
    void parse_literalOnly() {
        var t = AnnotationStringAlgorithm.parse("Callable");
        assertTrue(t.hasNoVariables());
        assertFalse(t.hasNoLiteral());
        assertEquals(List.of("Callable"), t.staticLiterals());
    }

    @Test
    void parse_variableOnly() {
        var t = AnnotationStringAlgorithm.parse("${i}");
        assertFalse(t.hasNoVariables());
        assertTrue(t.hasNoLiteral());
        var vars = t.parts().stream().filter(AnnotationStringPart::isVariable).toList();
        assertEquals(1, vars.size());
        assertEquals("i", vars.get(0).text());
    }

    @Test
    void parse_mixed() {
        var t = AnnotationStringAlgorithm.parse("${v1}Callable${v2}");
        assertFalse(t.hasNoVariables());
        assertFalse(t.hasNoLiteral());
        assertEquals(List.of("Callable"), t.staticLiterals());
        var vars = t.parts().stream().filter(AnnotationStringPart::isVariable)
                .map(AnnotationStringPart::text).toList();
        assertEquals(List.of("v1", "v2"), vars);
    }

    @Test
    void parse_adjacentVariables() {
        var t = AnnotationStringAlgorithm.parse("${v1}${v2}Callable${v3}");
        assertEquals(List.of("Callable"), t.staticLiterals());
        var vars = t.parts().stream().filter(AnnotationStringPart::isVariable)
                .map(AnnotationStringPart::text).toList();
        assertEquals(List.of("v1", "v2", "v3"), vars);
    }

    @Test
    void parse_multipleLiterals() {
        var t = AnnotationStringAlgorithm.parse("Async${i}Handler");
        assertEquals(List.of("Async", "Handler"), t.staticLiterals());
    }

    @Test
    void parse_dollarSignWithoutBrace_isTreatedAsLiteral() {
        var t = AnnotationStringAlgorithm.parse("Callable$i");
        assertEquals(List.of("Callable$i"), t.staticLiterals());
    }

    @Test
    void parse_roundTrip() {
        String original = "${v1}Callable${v2}";
        assertEquals(original, AnnotationStringAlgorithm.parse(original).toLiteral());
    }

    // =========================================================
    // expandStringConstants()
    // =========================================================

    @Test
    void expand_noConstants() {
        var t = AnnotationStringAlgorithm.parse("${prefix}Callable${i}");
        var expanded = AnnotationStringAlgorithm.expandStringConstants(t, Map.of());
        assertEquals(List.of("Callable"), expanded.staticLiterals());
        assertTrue(expanded.parts().stream().anyMatch(p -> p.isVariable() && p.text().equals("prefix")));
    }

    @Test
    void expand_singleConstant() {
        var t = AnnotationStringAlgorithm.parse("${prefix}Callable${i}");
        var expanded = AnnotationStringAlgorithm.expandStringConstants(t, Map.of("prefix", "My"));
        assertEquals(List.of("MyCallable"), expanded.staticLiterals());
    }

    @Test
    void expand_constantComposesFullLiteral() {
        var t = AnnotationStringAlgorithm.parse("${prefix}${i}");
        var expanded = AnnotationStringAlgorithm.expandStringConstants(t, Map.of("prefix", "Callable"));
        assertEquals(List.of("Callable"), expanded.staticLiterals());
        assertFalse(expanded.hasNoLiteral());
    }

    @Test
    void expand_multipleConstants() {
        var t = AnnotationStringAlgorithm.parse("${a}${b}Callable${i}");
        var expanded = AnnotationStringAlgorithm.expandStringConstants(t, Map.of("a", "My", "b", "Good"));
        assertEquals(List.of("MyGoodCallable"), expanded.staticLiterals());
    }

    @Test
    void expand_emptyStringConstant() {
        var t = AnnotationStringAlgorithm.parse("${a}Callable${i}");
        var expanded = AnnotationStringAlgorithm.expandStringConstants(t, Map.of("a", ""));
        assertEquals(List.of("Callable"), expanded.staticLiterals());
    }

    // =========================================================
    // matches()
    // =========================================================

    @Test
    void matches_singleLiteralAtStart() {
        assertTrue(AnnotationStringAlgorithm.matches(parse("Callable${i}"), "Callable2"));
    }

    @Test
    void matches_singleLiteralInMiddle() {
        assertTrue(AnnotationStringAlgorithm.matches(parse("${v1}Callable${v2}"), "MyCallable2"));
    }

    @Test
    void matches_singleLiteralAtEnd() {
        assertTrue(AnnotationStringAlgorithm.matches(parse("${v1}Callable"), "MyCallable"));
    }

    @Test
    void matches_longPrefixAndSuffix() {
        assertTrue(AnnotationStringAlgorithm.matches(
                parse("${v1}Callable${v2}"), "ThisIsMyPrefixCallableThisIsMySuffix3"));
    }

    @Test
    void matches_noMatch() {
        assertFalse(AnnotationStringAlgorithm.matches(parse("Foo${i}"), "Callable2"));
    }

    @Test
    void matches_noMatchNumericOnly() {
        assertFalse(AnnotationStringAlgorithm.matches(parse("Callable${i}"), "2"));
    }

    @Test
    void matches_multipleLiteralsCorrectOrder() {
        assertTrue(AnnotationStringAlgorithm.matches(parse("Async${i}Handler"), "AsyncDiskHandler2"));
    }

    @Test
    void matches_multipleLiteralsWrongOrder() {
        assertFalse(AnnotationStringAlgorithm.matches(parse("Async${i}Handler"), "HandlerAsyncDisk2"));
    }

    @Test
    void matches_firstLiteralPresentSecondAbsent() {
        assertFalse(AnnotationStringAlgorithm.matches(parse("Async${i}Cache"), "AsyncDiskHandler2"));
    }

    @Test
    void matches_allVariablesNoLiteral_neverMatches() {
        assertFalse(AnnotationStringAlgorithm.matches(parse("${v1}${v2}"), "Callable2"));
        assertFalse(AnnotationStringAlgorithm.matches(parse("${v1}${v2}"), "anything"));
    }

    // =========================================================
    // computeRename()
    // =========================================================

    @Test
    void computeRename_singleLiteralOnlyLiteralChanges() {
        var result = AnnotationStringAlgorithm.computeRename(parse("Callable${i}"), "Callable2", "Handler2");
        assertInstanceOf(RenameResult.Updated.class, result);
        assertEquals("Handler${i}", ((RenameResult.Updated) result).newTemplate());
    }

    @Test
    void computeRename_longPrefixSuffixPreserved() {
        var result = AnnotationStringAlgorithm.computeRename(
                parse("${v1}Callable${v2}"),
                "ThisIsMyPrefixCallableThisIsMySuffix3",
                "ThisIsMyPrefixHookThisIsMySuffix3");
        assertInstanceOf(RenameResult.Updated.class, result);
        assertEquals("${v1}Hook${v2}", ((RenameResult.Updated) result).newTemplate());
    }

    @Test
    void computeRename_numericSuffixChangesVariableCaptures() {
        // "2" → "3": trailing ${i} captures the suffix, so suffix change is expected
        var result = AnnotationStringAlgorithm.computeRename(parse("Callable${i}"), "Callable2", "Handler3");
        assertInstanceOf(RenameResult.Updated.class, result);
        assertEquals("Handler${i}", ((RenameResult.Updated) result).newTemplate());
    }

    @Test
    void computeRename_multipleLiteralsSecondChanges() {
        var result = AnnotationStringAlgorithm.computeRename(
                parse("Async${i}Handler"), "AsyncDiskHandler2", "AsyncDiskProcessor2");
        assertInstanceOf(RenameResult.Updated.class, result);
        assertEquals("Async${i}Processor", ((RenameResult.Updated) result).newTemplate());
    }

    @Test
    void computeRename_multipleLiteralsBothChange_needsDisambiguation() {
        var result = AnnotationStringAlgorithm.computeRename(
                parse("Async${i}Handler"), "AsyncDiskHandler2", "SyncSSDProcessor2");
        assertInstanceOf(RenameResult.NeedsDisambiguation.class, result);
        var nd = (RenameResult.NeedsDisambiguation) result;
        assertFalse(nd.affectedLiterals().isEmpty());
    }

    @Test
    void computeRename_prefixAlsoChanged_needsDisambiguation() {
        var result = AnnotationStringAlgorithm.computeRename(
                parse("${v1}Callable${v2}"), "MyCallable2", "YourHook3");
        assertInstanceOf(RenameResult.NeedsDisambiguation.class, result);
        assertEquals(List.of("Callable"), ((RenameResult.NeedsDisambiguation) result).affectedLiterals());
    }

    @Test
    void computeRename_stringDoesNotMatchOldClass_noMatch() {
        var result = AnnotationStringAlgorithm.computeRename(parse("Callable${i}"), "Handler2", "Processor2");
        assertInstanceOf(RenameResult.NoMatch.class, result);
    }

    @Test
    void computeRename_noChange_returnsNoMatch() {
        // "Callable" didn't change — return NoMatch (no update needed)
        var result = AnnotationStringAlgorithm.computeRename(parse("Callable${i}"), "Callable2", "Callable3");
        assertInstanceOf(RenameResult.NoMatch.class, result);
    }

    // helper used in this test class
    private static AnnotationStringTemplate parse(String s) {
        return AnnotationStringAlgorithm.parse(s);
    }

    // =========================================================
    // validate()
    // =========================================================

    private static java.util.List<ValidationError> validate(String template, String targetName) {
        var t = AnnotationStringAlgorithm.expandStringConstants(
                AnnotationStringAlgorithm.parse(template), java.util.Map.of());
        return AnnotationStringAlgorithm.validate(t, targetName, java.util.Map.of());
    }

    private static java.util.List<ValidationError> validate(String template, String targetName,
            java.util.Map<String, String> constants) {
        var t = AnnotationStringAlgorithm.expandStringConstants(
                AnnotationStringAlgorithm.parse(template), constants);
        return AnnotationStringAlgorithm.validate(t, targetName, constants);
    }

    @Test
    void validate_r2_unmatchedSingleLiteral() {
        var errors = validate("Foo${i}", "Callable2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.UNMATCHED_LITERAL, errors.get(0).kind());
    }

    @Test
    void validate_r2_unmatchedSecondLiteral() {
        var errors = validate("Async${i}Cache", "AsyncDiskHandler2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.UNMATCHED_LITERAL, errors.get(0).kind());
    }

    @Test
    void validate_r2_shortCircuitsR3() {
        // "${v1}Foo${v2}" on "Callable2": "Foo" not in "Callable2" → only R2, no orphan error for v1
        var errors = validate("${v1}Foo${v2}", "Callable2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.UNMATCHED_LITERAL, errors.get(0).kind());
    }

    @Test
    void validate_r3_orphanSingleAtStart() {
        // "${v1}Callable${v2}" on "Callable2": prefix before "Callable" = "" → v1 orphan
        var errors = validate("${v1}Callable${v2}", "Callable2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.ORPHAN_VARIABLE, errors.get(0).kind());
        assertEquals("v1", errors.get(0).varName());
    }

    @Test
    void validate_r3_orphanMultipleAdjacent() {
        // "${v1}${v2}Callable${v3}" on "Callable2": prefix="" → v1 and v2 both orphan
        var errors = validate("${v1}${v2}Callable${v3}", "Callable2");
        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.kind() == ValidationError.ErrorKind.ORPHAN_VARIABLE));
        var varNames = errors.stream().map(ValidationError::varName).toList();
        assertTrue(varNames.contains("v1") && varNames.contains("v2"));
    }

    @Test
    void validate_r3_notOrphan_nonEmptyPrefix() {
        // "${v1}Callable${v2}" on "MyCallable2": prefix "My" non-empty → no errors
        var errors = validate("${v1}Callable${v2}", "MyCallable2");
        assertTrue(errors.isEmpty());
    }

    @Test
    void validate_r3_adjacentVariables_nonEmptyCollective_notOrphan() {
        // "${v1}${v2}Callable${v3}" on "MyCallable2": collective prefix "My" non-empty → neither orphan
        var errors = validate("${v1}${v2}Callable${v3}", "MyCallable2");
        assertTrue(errors.isEmpty());
    }

    @Test
    void validate_r3_suffixNotOrphan() {
        // "Callable${v1}" on "Callable2": suffix "2" non-empty → v1 not orphan
        var errors = validate("Callable${v1}", "Callable2");
        assertTrue(errors.isEmpty());
    }

    @Test
    void validate_r4_pureVariables() {
        var errors = validate("${v1}${v2}", "Callable2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.NO_ANCHOR, errors.get(0).kind());
    }

    @Test
    void validate_r4_noExpansion() {
        // "${prefix}${i}" with no constants for prefix → no anchor after expansion attempt
        var errors = validate("${prefix}${i}", "Callable2");
        assertEquals(1, errors.size());
        assertEquals(ValidationError.ErrorKind.NO_ANCHOR, errors.get(0).kind());
    }

    @Test
    void validate_valid_substrMatch() {
        var errors = validate("${v1}Callable${v2}", "ThisIsMyPrefixCallable3");
        assertTrue(errors.isEmpty());
    }

    @Test
    void validate_valid_multipleLiteralsInOrder() {
        var errors = validate("Async${i}Handler", "AsyncDiskHandler2");
        assertTrue(errors.isEmpty());
    }

    @Test
    void validate_valid_stringConstantComposesLiteral() {
        var errors = validate("${prefix}${i}", "Callable2", java.util.Map.of("prefix", "Callable"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void validate_r1_notChecked_noVariables_allowed() {
        // validate() does NOT enforce R1 (no-variables). The processor does that.
        // "Object" on "Object": literal found, no variables to be orphan → valid.
        var errors = validate("Object", "Object");
        assertTrue(errors.isEmpty());
    }
}
