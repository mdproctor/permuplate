package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for BaseTuple.as() — projection of OOPath tuple results into typed records.
 * Mirrors the structure of TupleAsTest in Drools vol2.
 */
public class TupleAsTest {

    // Domain records used as projection targets — positional field order matches tuple order
    record LibRoom(Library library, Room room) {
    }

    record LibRoomBook(Library library, Room room, Book book) {
    }

    private Library scienceLib;
    private Room physicsRoom;
    private Book relativityBook;

    @Before
    public void setUp() {
        relativityBook = new Book("Relativity", true, List.of(new Page("p1")));
        physicsRoom = new Room("Physics", List.of(relativityBook, new Book("Draft", false, List.of())), List.of());
        scienceLib = new Library("ScienceLib", List.of(physicsRoom));
    }

    @Test
    public void testTuple2AsRecord() {
        // Tuple2<Library, Room> projected into LibRoom record
        BaseTuple.Tuple2<Library, Room> t = new BaseTuple.Tuple2<>(scienceLib, physicsRoom);
        LibRoom result = t.as();
        assertThat(result.library()).isSameInstanceAs(scienceLib);
        assertThat(result.room()).isSameInstanceAs(physicsRoom);
    }

    @Test
    public void testTuple3AsRecord() {
        // Tuple3<Library, Room, Book> projected into LibRoomBook record
        BaseTuple.Tuple3<Library, Room, Book> t = new BaseTuple.Tuple3<>(scienceLib, physicsRoom, relativityBook);
        LibRoomBook result = t.as();
        assertThat(result.library()).isSameInstanceAs(scienceLib);
        assertThat(result.room()).isSameInstanceAs(physicsRoom);
        assertThat(result.book()).isSameInstanceAs(relativityBook);
    }

    @Test
    public void testAsWithExplicitTypeParameter() {
        // as() can also be called with explicit type parameter instead of relying on inference
        BaseTuple.Tuple2<Library, Room> t = new BaseTuple.Tuple2<>(scienceLib, physicsRoom);
        LibRoom result = t.<LibRoom> as();
        assertThat(result.library()).isSameInstanceAs(scienceLib);
    }

    @Test
    public void testAsInOOPathFilter() {
        // Primary use case: projecting OOPath tuple in a filter expression
        // Tuple2<Library, Room>.as() gives access to both library and room by field name
        BaseTuple.Tuple2<Library, Room> t = new BaseTuple.Tuple2<>(scienceLib, physicsRoom);
        LibRoom lr = t.as();
        // Filter using projected record fields
        boolean passes = lr.library().name().equals("ScienceLib") && lr.room().name().equals("Physics");
        assertThat(passes).isTrue();
    }
}
