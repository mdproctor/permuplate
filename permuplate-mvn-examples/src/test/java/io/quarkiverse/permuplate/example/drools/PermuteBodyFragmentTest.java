package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

public class PermuteBodyFragmentTest {

    @Test
    public void testPath2ClassExists() throws Exception {
        Class<?> path2 = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleOOPathBuilder$Path2");
        assertThat(path2).isNotNull();
    }

    @Test
    public void testPath3ClassExists() throws Exception {
        Class<?> path3 = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleOOPathBuilder$Path3");
        assertThat(path3).isNotNull();
    }

    @Test
    public void testPath2HasPathMethod() throws Exception {
        Class<?> path2 = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleOOPathBuilder$Path2");
        Method path = Arrays.stream(path2.getMethods())
                .filter(m -> m.getName().equals("path"))
                .findFirst().orElse(null);
        assertThat(path).isNotNull();
        assertThat(path.getReturnType()).isEqualTo(Object.class);
    }

    @Test
    public void testOOPathChainViaDsl() {
        var ctx = buildCtx();
        var builder = new RuleBuilder<Ctx>();
        int[] fired = { 0 };
        // Use typed intermediate variable to anchor type parameters for path3 chain.
        RuleOOPathBuilder.Path3<JoinBuilder.Join2First<Void, Ctx, Library, BaseTuple.Tuple3<Library, Room, Book>>, BaseTuple.Tuple2<Library, Room>, Library, Room, Book> path3Builder = builder
                .from(c -> c.libraries()).path3();
        var rule = path3Builder
                .path(
                        (pc, lib) -> lib.rooms(),
                        (pc, room) -> true)
                .path(
                        (pc, room) -> room.books(),
                        (pc, book) -> book.published())
                .fn((c, lib, tuple) -> fired[0]++);
        rule.run(ctx);
        assertThat(fired[0]).isGreaterThan(0);
    }

    private Ctx buildCtx() {
        return new Ctx(
                DataSource.of(new Person("Alice", 30)),
                DataSource.of(new Account("ACC1", 1000.0)),
                DataSource.of(new Order("ORD1", 150.0)),
                DataSource.of(new Product("PRD1", 99.0)),
                DataSource.of(new Transaction("TXN1", 200.0)),
                DataSource.of(new Library("Lib", java.util.List.of(
                        new Room("Room1", java.util.List.of(
                                new Book("Book1", true, java.util.List.of(new Page("p1")))),
                                java.util.List.of())))));
    }
}
