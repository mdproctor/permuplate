package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

public class FunctionalFamilyTest {

    @Test
    public void testConsumer2HasAcceptMethod() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Consumer2");
        Method m = Arrays.stream(cls.getMethods()).filter(x -> x.getName().equals("accept")).findFirst().orElse(null);
        assertThat(m).isNotNull();
        assertThat(m.getReturnType()).isEqualTo(void.class);
    }

    @Test
    public void testPredicate3HasTestMethod() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Predicate3");
        Method m = Arrays.stream(cls.getMethods()).filter(x -> x.getName().equals("test")).findFirst().orElse(null);
        assertThat(m).isNotNull();
        assertThat(m.getReturnType()).isEqualTo(boolean.class);
    }

    @Test
    public void testConsumer7Exists() throws Exception {
        assertThat(Class.forName("io.quarkiverse.permuplate.example.drools.Consumer7")).isNotNull();
    }

    @Test
    public void testPredicate7Exists() throws Exception {
        assertThat(Class.forName("io.quarkiverse.permuplate.example.drools.Predicate7")).isNotNull();
    }

    @Test
    public void testConsumer2ParameterCount() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Consumer2");
        Method m = Arrays.stream(cls.getMethods()).filter(x -> x.getName().equals("accept")).findFirst().orElseThrow();
        assertThat(m.getParameterCount()).isEqualTo(2); // DS ctx + A a
    }

    @Test
    public void testPredicate4ParameterCount() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Predicate4");
        Method m = Arrays.stream(cls.getMethods()).filter(x -> x.getName().equals("test")).findFirst().orElseThrow();
        assertThat(m.getParameterCount()).isEqualTo(4); // DS ctx + A + B + C
    }

    @Test
    public void testConsumer4IsFunctionalInterface() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Consumer4");
        assertThat(cls.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }

    @Test
    public void testPredicate5IsFunctionalInterface() throws Exception {
        Class<?> cls = Class.forName("io.quarkiverse.permuplate.example.drools.Predicate5");
        assertThat(cls.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }

    @Test
    public void testConsumer1IsFunctionalInterface() {
        assertThat(Consumer1.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }

    @Test
    public void testPredicate1IsFunctionalInterface() {
        assertThat(Predicate1.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }
}
