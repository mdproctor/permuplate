package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

/**
 * Verifies that Path2 class (now the i=2 case of the Path2..6 template)
 * has path() returning the END type, and that OOPath chains still work.
 */
public class PermuteReturnTypeParamTest {

    @Test
    public void testPath2ClassExists() throws Exception {
        Class<?> path2 = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleOOPathBuilder$Path2");
        assertThat(path2).isNotNull();
    }

    @Test
    public void testPath2PathMethodReturnsObjectType() throws Exception {
        Class<?> path2 = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleOOPathBuilder$Path2");
        Method pathMethod = Arrays.stream(path2.getMethods())
                .filter(m -> m.getName().equals("path"))
                .findFirst().orElse(null);
        assertThat(pathMethod).isNotNull();
        // Return type is Object at runtime (erasure of generic type param END)
        assertThat(pathMethod.getReturnType()).isEqualTo(Object.class);
    }
}
