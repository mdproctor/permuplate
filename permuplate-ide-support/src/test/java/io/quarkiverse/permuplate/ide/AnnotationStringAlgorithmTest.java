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
}
