package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

public class OOPathReflectionTest {

    private Ctx ctx;

    @Before
    public void setUp() {
        ctx = new Ctx(
                DataSource.of(),
                DataSource.of(),
                DataSource.of(),
                DataSource.of(),
                DataSource.of(),
                DataSource.of(
                        new Library("ScienceLib", java.util.List.of(
                                new Room("Physics",
                                        java.util.List.of(new Book("Relativity", true, java.util.List.of())),
                                        java.util.List.of()),
                                new Room("Biology",
                                        java.util.List.of(new Book("Evolution", true, java.util.List.of())),
                                        java.util.List.of())))));
    }

    @Test
    public void oopath_traversal_works_at_depth_2() {
        // Exercises the reflection-based createEmptyTuple rather than the former switch.
        // Library -> Room gives depth 2 (Tuple2<Library, Room>).
        RuleOOPathBuilder.Path2<JoinBuilder.Join2First<Void, Ctx, Library, BaseTuple.Tuple2<Library, Room>>, BaseTuple.Tuple1<Library>, Library, Room> path2Builder = new RuleBuilder<Ctx>()
                .from(c -> c.libraries()).path2();
        var result = path2Builder.path(
                (pc, lib) -> lib.rooms(),
                (pc, room) -> true)
                .fn((c, lib, t) -> {
                });

        result.run(ctx);

        assertThat(result.executionCount()).isGreaterThan(0);
    }
}
