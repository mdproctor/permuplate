package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class SealedFamilyTest {

    @Test
    public void joinBuilderSecond_is_sealed_with_six_permitted_subclasses() throws Exception {
        Class<?> iface = Class.forName(
                "io.quarkiverse.permuplate.example.drools.JoinBuilder$JoinBuilderGate");
        assertThat(iface.isInterface()).isTrue();
        assertThat(iface.isSealed()).isTrue();
        assertThat(iface.getPermittedSubclasses()).hasLength(6);
    }

    @Test
    public void join1Second_implements_joinBuilderSecond() throws Exception {
        Class<?> iface = Class.forName(
                "io.quarkiverse.permuplate.example.drools.JoinBuilder$JoinBuilderGate");
        Class<?> cls = Class.forName(
                "io.quarkiverse.permuplate.example.drools.JoinBuilder$Join1Gate");
        assertThat(iface.isAssignableFrom(cls)).isTrue();
    }

    @Test
    public void joinBuilderFirst_is_sealed_with_six_permitted_subclasses() throws Exception {
        Class<?> iface = Class.forName(
                "io.quarkiverse.permuplate.example.drools.JoinBuilder$JoinBuilderFirst");
        assertThat(iface.isInterface()).isTrue();
        assertThat(iface.isSealed()).isTrue();
        assertThat(iface.getPermittedSubclasses()).hasLength(6);
    }
}
