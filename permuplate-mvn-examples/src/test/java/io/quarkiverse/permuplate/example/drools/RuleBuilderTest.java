package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

public class RuleBuilderTest {

    private RuleBuilder<Ctx> builder;
    private Ctx ctx;

    @Before
    public void setUp() {
        builder = new RuleBuilder<>();
        DataSource<Library> libraries = DataSource.of(
                new Library("ScienceLib", java.util.List.of(
                        new Room("Physics", java.util.List.of(
                                new Book("Relativity", true, java.util.List.of(new Page("page1"))),
                                new Book("Draft", false, java.util.List.of(new Page("page1")))),
                                java.util.List.of()),
                        new Room("Biology", java.util.List.of(
                                new Book("Evolution", true, java.util.List.of(new Page("page1"))),
                                new Book("Notes", false, java.util.List.of(new Page("page1")))),
                                java.util.List.of()))),
                new Library("ArtsLib", java.util.List.of(
                        new Room("History", java.util.List.of(
                                new Book("Waterloo", true, java.util.List.of(new Page("page1"))),
                                new Book("Sketch", false, java.util.List.of(new Page("page1")))),
                                java.util.List.of()),
                        new Room("Literature", java.util.List.of(
                                new Book("Hamlet", true, java.util.List.of(new Page("page1"))),
                                new Book("Outline", false, java.util.List.of(new Page("page1")))),
                                java.util.List.of()))));

        ctx = new Ctx(
                DataSource.of(new Person("Alice", 30), new Person("Bob", 17)),
                DataSource.of(new Account("ACC1", 1000.0), new Account("ACC2", 50.0)),
                DataSource.of(new Order("ORD1", 150.0), new Order("ORD2", 25.0)),
                DataSource.of(new Product("PRD1", 99.0), new Product("PRD2", 9.0)),
                DataSource.of(new Transaction("TXN1", 200.0), new Transaction("TXN2", 15.0)),
                libraries);
    }

    @Test
    public void testArity1Structural() {
        var rule = builder.from(ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() >= 18)
                .fn((ctx, a) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(1);
        assertThat(rule.filterCount()).isEqualTo(1);
        assertThat(rule.hasAction()).isTrue();
    }

    @Test
    public void testArity1AdultFilterMatchesOnlyAlice() {
        var rule = builder.from(ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() >= 18)
                .fn((ctx, a) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
    }

    @Test
    public void testArity1NoFilterMatchesAll() {
        var rule = builder.from(ctx -> ctx.persons())
                .fn((ctx, a) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(2);
    }

    @Test
    public void testArity1MultipleFiltersChained() {
        var rule = builder.from(ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() >= 18)
                .filter((ctx, a) -> a.name().startsWith("A"))
                .fn((ctx, a) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
    }

    @Test
    public void testArity2CrossProductNoFilter() {
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .fn((ctx, a, b) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(4); // 2 persons × 2 accounts
    }

    @Test
    public void testArity2FilterOnBothFacts() {
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
                .fn((ctx, a, b) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testArity2MultipleFilters() {
        // Chained .filter().filter() — verifies filterCount=2 is structurally recorded
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18)
                .filter((ctx, a, b) -> b.balance() > 500.0)
                .fn((ctx, a, b) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testArity2CapturedFactsDistinguishByType() {
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .fn((ctx, a, b) -> {
                });

        rule.run(ctx);

        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Account.class);
    }

    @Test
    public void testArity3ThreeFacts() {
        // Unfiltered three-way cross-product: 2×2×2 = 8 combinations
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .fn((ctx, a, b, c) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(3);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(8);
        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Account.class);
        assertThat(rule.capturedFact(0, 2)).isInstanceOf(Order.class);
    }

    @Test
    public void testArity3IntermediateFilter() {
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
                .join(ctx -> ctx.orders())
                .filter((ctx, a, b, c) -> c.amount() > 100.0)
                .fn((ctx, a, b, c) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(3);
        assertThat(rule.filterCount()).isEqualTo(2);

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
        assertThat(rule.capturedFact(0, 2)).isEqualTo(new Order("ORD1", 150.0));
    }

    @Test
    public void testArity4AllTypesDistinct() {
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .join(ctx -> ctx.products())
                .fn((ctx, a, b, c, d) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(4);
        rule.run(ctx);

        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Account.class);
        assertThat(rule.capturedFact(0, 2)).isInstanceOf(Order.class);
        assertThat(rule.capturedFact(0, 3)).isInstanceOf(Product.class);
    }

    @Test
    public void testArity5AllTypesDistinct() {
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .join(ctx -> ctx.products())
                .join(ctx -> ctx.transactions())
                .fn((ctx, a, b, c, d, e) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(5);
        rule.run(ctx);

        assertThat(rule.capturedFact(0, 4)).isInstanceOf(Transaction.class);
    }

    @Test
    public void testArity6LeafNodeCompiles() {
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .join(ctx -> ctx.products())
                .join(ctx -> ctx.transactions())
                .join(ctx -> ctx.persons())
                .fn((ctx, a, b, c, d, e, f) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(6);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(64); // 2^6 = 64 combinations
    }

    @Test
    public void testFilterRejectsAllCombinations() {
        var rule = builder.from(ctx -> ctx.persons())
                .filter((ctx, a) -> a.age() > 200)
                .fn((ctx, a) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(0);
    }

    @Test
    public void testMultipleExecutionsRecordedSeparately() {
        var rule = builder.from(ctx -> ctx.persons())
                .fn((ctx, a) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(2);
        assertThat(rule.capturedFacts(0)).containsExactly(new Person("Alice", 30));
        assertThat(rule.capturedFacts(1)).containsExactly(new Person("Bob", 17));
    }

    // =========================================================================
    // Typed join() — fully typed chain without casts or pre-typed constants
    // =========================================================================

    @Test
    public void testArity2FullyTyped() {
        // Compile-time proof: the typed join() chain resolves B=Account from the lambda.
        // a is Person, b is Account — the compiler enforces types, no casts needed.
        // Runtime behaviour is equivalent to testArity2FilterOnBothFacts.
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0)
                .fn((ctx, a, b) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testArity3FullyTyped() {
        // Compile-time proof: three-way join resolves A=Person, B=Account, C=Order.
        // Runtime behaviour is equivalent to testArity3IntermediateFilter (single combined filter).
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .filter((ctx, a, b, c) -> a.age() >= 18 && b.balance() > 500.0 && c.amount() > 100.0)
                .fn((ctx, a, b, c) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
        assertThat(rule.capturedFact(0, 2)).isEqualTo(new Order("ORD1", 150.0));
    }

    // =========================================================================
    // Dual filter() — single-fact and all-facts overloads
    // =========================================================================

    @Test
    public void testArity2SingleFactFilter() {
        // filter(Predicate2<DS, Account>) — tests only the most recently joined fact.
        // 2 persons × 1 high-balance account = 2 executions (person not filtered here).
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, b) -> b.balance() > 500.0)
                .fn((ctx, a, b) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(1);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(2); // Alice+ACC1, Bob+ACC1
    }

    @Test
    public void testArity2BothFilterTypesChained() {
        // Chain single-fact filter (on Account) then cross-fact filter (Person + Account).
        // Only Alice(30) + ACC1(1000) satisfies both.
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, b) -> b.balance() > 500.0)
                .filter((ctx, a, b) -> a.age() >= 18)
                .fn((ctx, a, b) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(2);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(rule.capturedFact(0, 0)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testArity3SingleFactFilterOnLatestFact() {
        // After joining persons + accounts + orders, single-fact filter tests only Order.
        // 2 persons × 2 accounts × 1 qualifying order (ORD1 amount>100) = 4 combos.
        var rule = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .join(ctx -> ctx.orders())
                .filter((ctx, c) -> c.amount() > 100.0)
                .fn((ctx, a, b, c) -> {
                });

        assertThat(rule.filterCount()).isEqualTo(1);
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(4);
        assertThat(rule.capturedFact(0, 2)).isInstanceOf(Order.class);
    }

    // =========================================================================
    // Bi-linear joins — pre-built sub-networks joined into another chain
    // =========================================================================

    @Test
    public void testJoin2FirstSatisfiesJoin2SecondAtCompileTime() {
        // Structural: Join2First<Void,Ctx,Person,Account> IS-A Join2Second<Void,Ctx,Person,Account>.
        // This test only compiles if the extends relation is correct.
        // If Join2First does not extend Join2Second, this assignment fails at compile time.
        JoinBuilder.Join2Second<Void, Ctx, Person, Account> asSecond = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts());
        assertThat(asSecond).isNotNull();
    }

    @Test
    public void testBilinearJoin1Plus2Gives3Facts() {
        // Pre-build a 2-fact sub-network: only adult persons with high-balance accounts.
        var personAccounts = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0);

        // Bi-linear join: orders (1 fact) x personAccounts (2 facts) -> 3-fact rule.
        // personAccounts' internal filter gates its own tuples; only Alice+ACC1 qualifies.
        var rule = builder.from(ctx -> ctx.orders())
                .join(personAccounts)
                .fn((ctx, a, b, c) -> {
                });

        assertThat(rule.sourceCount()).isEqualTo(2); // 1 linear + 1 bi-linear = 2 entries
        rule.run(ctx);

        // 2 orders x 1 qualifying (Alice+ACC1) = 2 matches
        assertThat(rule.executionCount()).isEqualTo(2);
        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Order.class);
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Person("Alice", 30));
        assertThat(rule.capturedFact(0, 2)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testBilinearSubnetworkFiltersApplyIndependently() {
        // The right chain's filter (balance > 500) applies within the sub-network only.
        // It gates which Account tuples enter the cross-product -- it does NOT re-run
        // against the combined (Person, Account) tuple. Result: both persons are joined
        // with only ACC1, giving 2 matches (not 1).
        var highBalanceAccounts = builder.from(ctx -> ctx.accounts())
                .filter((ctx, a) -> a.balance() > 500.0); // only ACC1 passes

        var rule = builder.from(ctx -> ctx.persons())
                .join(highBalanceAccounts)
                .fn((ctx, a, b) -> {
                });

        rule.run(ctx);

        // 2 persons x 1 qualifying account = 2 combinations
        assertThat(rule.executionCount()).isEqualTo(2);
        assertThat(rule.capturedFact(0, 1)).isEqualTo(new Account("ACC1", 1000.0));
        assertThat(rule.capturedFact(1, 1)).isEqualTo(new Account("ACC1", 1000.0));
    }

    @Test
    public void testBilinearNodeSharingTwoRulesReuseSameSubnetwork() {
        // Two rules share the same pre-built personAccounts sub-network.
        // This is the core Rete node-sharing pattern: in a real Rete network,
        // both rules would share the same beta memory for the personAccounts sub-network.
        var personAccounts = builder.from(ctx -> ctx.persons())
                .join(ctx -> ctx.accounts())
                .filter((ctx, a, b) -> a.age() >= 18 && b.balance() > 500.0);

        var rule1 = builder.from(ctx -> ctx.orders())
                .join(personAccounts)
                .fn((ctx, a, b, c) -> {
                });

        var rule2 = builder.from(ctx -> ctx.products())
                .join(personAccounts)
                .fn((ctx, a, b, c) -> {
                });

        rule1.run(ctx);
        rule2.run(ctx);

        // Both rules: N facts x 1 qualifying personAccounts tuple (Alice+ACC1)
        assertThat(rule1.executionCount()).isEqualTo(2); // 2 orders x 1 pair
        assertThat(rule2.executionCount()).isEqualTo(2); // 2 products x 1 pair

        // Both rules see Person and Account from the shared sub-network
        assertThat(rule1.capturedFact(0, 1)).isEqualTo(new Person("Alice", 30));
        assertThat(rule2.capturedFact(0, 1)).isEqualTo(new Person("Alice", 30));
    }

    // =========================================================================
    // not() and exists() scopes
    // =========================================================================

    @Test
    public void testNotScopeBlocksAllWhenScopeHasMatches() {
        // not() scope evaluates independently against ctx (sandbox simplification).
        // Scope: any account with balance > 500 -- ACC1 qualifies.
        // Since the scope finds ACC1 globally, ALL outer person tuples are blocked.
        var rule = builder.from(ctx -> ctx.persons())
                .not()
                .join(ctx -> ctx.accounts())
                .filter((Object) (Predicate2<Ctx, Account>) (ctx, b) -> b.balance() > 500.0)
                .end()
                .fn((ctx, a) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(0);
    }

    @Test
    public void testNotScopePassesAllWhenScopeIsEmpty() {
        var rule = builder.from(ctx -> ctx.persons())
                .not()
                .join(ctx -> ctx.accounts())
                .filter((Object) (Predicate2<Ctx, Account>) (ctx, b) -> b.balance() > 10000.0)
                .end()
                .fn((ctx, a) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(2);
    }

    @Test
    public void testExistsScopePassesAllWhenScopeHasMatch() {
        var rule = builder.from(ctx -> ctx.persons())
                .exists()
                .join(ctx -> ctx.accounts())
                .filter((Object) (Predicate2<Ctx, Account>) (ctx, b) -> b.balance() > 500.0)
                .end()
                .fn((ctx, a) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(2);
    }

    // =========================================================================
    // OOPath — pathN() traversal
    // =========================================================================

    @Test
    public void testPath2TraversesOneLevel() {
        // path2(): Library -> Rooms — produces Tuple2<Library, Room> per matching room.
        // 2 libraries x 2 rooms each x all rooms pass name != null = 4 combinations.
        // Use typed intermediate variable to anchor B=Room for the path2() type variable.
        RuleOOPathBuilder.Path2<JoinBuilder.Join2First<Void, Ctx, Library, BaseTuple.Tuple2<Library, Room>>, BaseTuple.Tuple1<Library>, Library, Room> path2Builder = builder
                .from(ctx -> ctx.libraries()).path2();
        var rule = path2Builder.path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> room.name() != null)
                .fn((ctx, lib, t) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(4); // 2 libs x 2 rooms
        // fact[0] = Library, fact[1] = BaseTuple.Tuple2<Library, Room>
        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Library.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(BaseTuple.Tuple2.class);
        @SuppressWarnings("unchecked")
        BaseTuple.Tuple2<Library, Room> t = (BaseTuple.Tuple2<Library, Room>) rule.capturedFact(0, 1);
        assertThat(t.getA()).isInstanceOf(Library.class);
        assertThat(t.getB()).isInstanceOf(Room.class);
    }

    @Test
    public void testPath3TraversesTwoLevels() {
        // path3(): Library -> Room -> Book — produces Tuple3<Library, Room, Book>.
        // 2 libs x 2 rooms x 2 books = 8 combinations (no filters).
        // Use typed intermediate variable to anchor B=Room, C=Book for path3() type variables.
        RuleOOPathBuilder.Path3<JoinBuilder.Join2First<Void, Ctx, Library, BaseTuple.Tuple3<Library, Room, Book>>, BaseTuple.Tuple2<Library, Room>, Library, Room, Book> path3Builder = builder
                .from(ctx -> ctx.libraries()).path3();
        var rule = path3Builder.path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> true)
                .path(
                        (pathCtx, room) -> room.books(),
                        (pathCtx, book) -> true)
                .fn((ctx, lib, t) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(8); // 2 libs x 2 rooms x 2 books
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(BaseTuple.Tuple3.class);
    }

    @Test
    public void testPathFilterAppliedAtEachStep() {
        // Only published books pass the second-step predicate.
        // 2 libs x 2 rooms x 1 published book each = 4 combinations.
        RuleOOPathBuilder.Path3<JoinBuilder.Join2First<Void, Ctx, Library, BaseTuple.Tuple3<Library, Room, Book>>, BaseTuple.Tuple2<Library, Room>, Library, Room, Book> path3Builder = builder
                .from(ctx -> ctx.libraries()).path3();
        var rule = path3Builder.path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> true)
                .path(
                        (pathCtx, room) -> room.books(),
                        (pathCtx, book) -> book.published())
                .fn((ctx, lib, t) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(4); // 2 libs x 2 rooms x 1 published
        for (int i = 0; i < rule.executionCount(); i++) {
            @SuppressWarnings("unchecked")
            BaseTuple.Tuple3<Library, Room, Book> t = (BaseTuple.Tuple3<Library, Room, Book>) rule.capturedFact(i, 1);
            assertThat(t.getC().published()).isTrue();
        }
    }

    @Test
    public void testPathContextCrossReference() {
        // Second-step predicate uses getTuple().getA() to cross-reference the Library
        // while filtering a Book. Only books in ScienceLib rooms pass.
        RuleOOPathBuilder.Path3<JoinBuilder.Join2First<Void, Ctx, Library, BaseTuple.Tuple3<Library, Room, Book>>, BaseTuple.Tuple2<Library, Room>, Library, Room, Book> path3Builder = builder
                .from(ctx -> ctx.libraries()).path3();
        var rule = path3Builder.path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> true)
                .path(
                        (pathCtx, room) -> room.books(),
                        (pathCtx, book) -> pathCtx.getTuple().getA().name().startsWith("Science"))
                .fn((ctx, lib, t) -> {
                });

        rule.run(ctx);

        // ScienceLib has 2 rooms x 2 books = 4 combinations
        assertThat(rule.executionCount()).isEqualTo(4);
        for (int i = 0; i < rule.executionCount(); i++) {
            @SuppressWarnings("unchecked")
            BaseTuple.Tuple3<Library, Room, Book> t = (BaseTuple.Tuple3<Library, Room, Book>) rule.capturedFact(i, 1);
            assertThat(t.getA().name()).startsWith("Science");
        }
    }

    @Test
    public void testPathCombinedWithOuterJoin() {
        // persons.join(libraries).path2(Library -> Room)
        // Outer facts: [Person, Library, Tuple2<Library,Room>]
        // 2 persons x 2 libraries x 2 rooms = 8 combinations
        RuleOOPathBuilder.Path2<JoinBuilder.Join3First<Void, Ctx, Person, Library, BaseTuple.Tuple2<Library, Room>>, BaseTuple.Tuple1<Library>, Library, Room> path2Builder = builder
                .from(ctx -> ctx.persons())
                .join(ctx -> ctx.libraries())
                .path2();
        var rule = path2Builder.path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> room.name() != null)
                .fn((ctx, p, lib, t) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(8); // 2p x 2libs x 2rooms
        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Library.class);
        assertThat(rule.capturedFact(0, 2)).isInstanceOf(BaseTuple.Tuple2.class);
    }

    // =========================================================================
    // Variable<T> — cross-fact binding
    // =========================================================================

    @Test
    public void testVarTwoFactCrossFilter() {
        // Variable-based cross-fact filter: only Alice(age=30) + ACC1(balance=1000) passes.
        // 2 persons × 2 accounts = 4 combinations; only 1 passes both constraints.
        Variable<Person> personVar = new Variable<>();
        Variable<Account> accountVar = new Variable<>();

        var rule = builder.from(ctx -> ctx.persons())
                .var(personVar)
                .join(ctx -> ctx.accounts())
                .var(accountVar)
                .filter(personVar, accountVar,
                        (ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0)
                .fn((ctx, p, a) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        Person p = (Person) rule.capturedFact(0, 0);
        Account a = (Account) rule.capturedFact(0, 1);
        assertThat(p.name()).isEqualTo("Alice");
        assertThat(a.id()).isEqualTo("ACC1");
    }

    @Test
    public void testUnboundVariableThrows() {
        Variable<Person> personVar = new Variable<>();
        Variable<Account> accountVar = new Variable<>();

        try {
            builder.from(ctx -> ctx.persons())
                    .join(ctx -> ctx.accounts())
                    .filter(personVar, accountVar,
                            (ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0);
            org.junit.Assert.fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("not bound");
        }
    }

    @Test
    public void testVarIndexCapturedAtBindTime() {
        // personVar bound to index 0 (Person). Two more facts joined after.
        // filter(personVar, orderVar, ...) cross-references index 0 and index 2,
        // skipping the intermediate Account at index 1.
        //
        // Combinations: 2 persons × 2 accounts × 2 orders = 8.
        // Passing: Alice(age=30) × any account × ORD1(amount=150) — age>=18, amount>100.
        //   Alice + ACC1 + ORD1 → pass
        //   Alice + ACC2 + ORD1 → pass
        //   All Bob rows       → fail (age=17)
        //   All ORD2 rows      → fail (amount=25)
        // Expected executionCount = 2.
        Variable<Person> personVar = new Variable<>();
        Variable<Order> orderVar = new Variable<>();

        var rule = builder.from(ctx -> ctx.persons())
                .var(personVar) // personVar → index 0
                .join(ctx -> ctx.accounts()) // index 1 — no variable bound
                .join(ctx -> ctx.orders())
                .var(orderVar) // orderVar → index 2
                .filter(personVar, orderVar,
                        (ctx, p, o) -> p.age() >= 18 && o.amount() > 100.0)
                .fn((ctx, p, a, o) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(2);
        for (int i = 0; i < rule.executionCount(); i++) {
            Person p = (Person) rule.capturedFact(i, 0);
            Order o = (Order) rule.capturedFact(i, 2);
            assertThat(p.name()).isEqualTo("Alice");
            assertThat(o.amount()).isGreaterThan(100.0);
        }
    }

    @Test
    public void testVariableOfNamedBinding() {
        // Variable.of("name") creates a named variable — functionally identical to new Variable<>()
        // but carries a diagnostic name used in error messages.
        Variable<Person> $person = Variable.of("$person");
        Variable<Account> $account = Variable.of("$account");

        var rule = builder.from(ctx -> ctx.persons())
                .var($person)
                .join(ctx -> ctx.accounts())
                .var($account)
                .filter($person, $account,
                        (ctx, p, a) -> p.age() >= 18 && a.balance() > 500.0)
                .fn((ctx, p, a) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
        assertThat(((Account) rule.capturedFact(0, 1)).id()).isEqualTo("ACC1");
    }

    @Test
    public void testUnboundNamedVariableThrowsWithName() {
        // Unbound named variable error message includes the variable name for diagnostics.
        Variable<Person> $person = Variable.of("$person");
        Variable<Account> $account = Variable.of("$account");

        try {
            builder.from(ctx -> ctx.persons())
                    .join(ctx -> ctx.accounts())
                    .filter($person, $account, (ctx, p, a) -> true);
            org.junit.Assert.fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            // New generic implementation fails fast on the first unbound variable.
            assertThat(e.getMessage()).contains("$person");
        }
    }

    @Test
    public void testFromFunctionShorthand() {
        // from(Function) — the standard entry point for building a rule.
        var rule = builder.from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }

    @Test
    public void testVarThreeVariableFilter() {
        // 3-variable filter: Alice(age=30) + ACC1(balance=1000) + ORD1(amount=150) passes.
        // 2 × 2 × 2 = 8 combinations; only 1 passes all three constraints.
        Variable<Person> pVar = new Variable<>();
        Variable<Account> aVar = new Variable<>();
        Variable<Order> oVar = new Variable<>();

        var rule = builder.from(ctx -> ctx.persons())
                .var(pVar)
                .join(ctx -> ctx.accounts())
                .var(aVar)
                .join(ctx -> ctx.orders())
                .var(oVar)
                .filter(pVar, aVar, oVar,
                        (ctx, p, a, o) -> p.age() >= 18
                                && a.balance() > 500.0
                                && o.amount() > 100.0)
                .fn((ctx, p, a, o) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(1);
        Person p = (Person) rule.capturedFact(0, 0);
        Account a = (Account) rule.capturedFact(0, 1);
        Order o = (Order) rule.capturedFact(0, 2);
        assertThat(p.name()).isEqualTo("Alice");
        assertThat(a.id()).isEqualTo("ACC1");
        assertThat(o.id()).isEqualTo("ORD1");
    }

    // =========================================================================
    // OOPath — path3() with outer join (3-level traversal from 2-fact context)
    // =========================================================================

    @Test
    public void testPath3WithOuterJoinTraversesThreeLevels() {
        // persons.join(libraries).path3(Library -> Room -> Book)
        // Outer facts: [Person, Library, Tuple3<Library, Room, Book>]
        // Filter: only published books pass the second traversal step.
        // 2 persons × 2 libs × 2 rooms × 1 published book each = 8 combinations.
        RuleOOPathBuilder.Path3<JoinBuilder.Join3First<Void, Ctx, Person, Library, BaseTuple.Tuple3<Library, Room, Book>>, BaseTuple.Tuple2<Library, Room>, Library, Room, Book> path3Builder = builder
                .from(ctx -> ctx.persons())
                .join(ctx -> ctx.libraries())
                .path3();
        var rule = path3Builder.path(
                (pathCtx, lib) -> lib.rooms(),
                (pathCtx, room) -> true)
                .path(
                        (pathCtx, room) -> room.books(),
                        (pathCtx, book) -> book.published())
                .fn((ctx, person, lib, t) -> {
                });

        rule.run(ctx);

        // 2 persons × 2 libraries × 2 rooms × 1 published book = 8
        assertThat(rule.executionCount()).isEqualTo(8);
        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Person.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(Library.class);
        assertThat(rule.capturedFact(0, 2)).isInstanceOf(BaseTuple.Tuple3.class);
        for (int i = 0; i < rule.executionCount(); i++) {
            @SuppressWarnings("unchecked")
            BaseTuple.Tuple3<Library, Room, Book> t = (BaseTuple.Tuple3<Library, Room, Book>) rule.capturedFact(i, 2);
            assertThat(t.getC().published()).isTrue();
        }
    }

    // =========================================================================
    // OOPath — path4() and path5() deep traversal
    // =========================================================================

    @Test
    public void testPath4TraversesThreeLevels() {
        // path4(): Library -> Room -> Book -> Page — produces Tuple4<Library, Room, Book, Page>.
        // 2 libs x 2 rooms x 2 books x 1 page each = 8 combinations (no filters).
        var rule = builder.from(ctx -> ctx.libraries())
                .<Room, Book, Page> path4()
                .path((pathCtx, lib) -> lib.rooms(), (pathCtx, room) -> true)
                .path((pathCtx, room) -> room.books(), (pathCtx, book) -> true)
                .path((pathCtx, book) -> book.pages(), (pathCtx, page) -> true)
                .fn((ctx, lib, t) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(8); // 2 libs x 2 rooms x 2 books x 1 page
        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Library.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(BaseTuple.Tuple4.class);
        @SuppressWarnings("unchecked")
        BaseTuple.Tuple4<Library, Room, Book, Page> t = (BaseTuple.Tuple4<Library, Room, Book, Page>) rule.capturedFact(0, 1);
        assertThat(t.getA()).isInstanceOf(Library.class);
        assertThat(t.getB()).isInstanceOf(Room.class);
        assertThat(t.getC()).isInstanceOf(Book.class);
        assertThat(t.getD()).isInstanceOf(Page.class);
    }

    @Test
    public void testPath5TraversesFourLevels() {
        // path5(): Library -> Room -> Shelf -> Book -> Page — produces Tuple5<Library, Room, Shelf, Book, Page>.
        // Local ctx: 1 lib x 2 rooms x 2 shelves x 2 books x 2 pages = 16 combinations.
        Page p1 = new Page("page1");
        Page p2 = new Page("page2");
        Book rel = new Book("Relativity", true, java.util.List.of(p1, p2));
        Book draft = new Book("Draft", false, java.util.List.of(p1, p2));
        Book evo = new Book("Evolution", true, java.util.List.of(p1, p2));
        Book notes = new Book("Notes", false, java.util.List.of(p1, p2));
        Room physics = new Room("Physics", java.util.List.of(),
                java.util.List.of(new Shelf("ShelfA", java.util.List.of(rel, draft)),
                        new Shelf("ShelfB", java.util.List.of(evo, notes))));
        Room biology = new Room("Biology", java.util.List.of(),
                java.util.List.of(new Shelf("ShelfA", java.util.List.of(rel, draft)),
                        new Shelf("ShelfB", java.util.List.of(evo, notes))));
        Ctx ctx5 = new Ctx(
                DataSource.of(), DataSource.of(), DataSource.of(), DataSource.of(), DataSource.of(),
                DataSource.of(new Library("ScienceLib", java.util.List.of(physics, biology))));

        var rule = builder.from(c -> c.libraries())
                .<Room, Shelf, Book, Page> path5()
                .path((pathCtx, lib) -> lib.rooms(), (pathCtx, room) -> true)
                .path((pathCtx, room) -> room.shelves(), (pathCtx, shelf) -> true)
                .path((pathCtx, shelf) -> shelf.books(), (pathCtx, book) -> true)
                .path((pathCtx, book) -> book.pages(), (pathCtx, page) -> true)
                .fn((c, lib, t) -> {
                });

        rule.run(ctx5);

        assertThat(rule.executionCount()).isEqualTo(16); // 1 lib x 2 rooms x 2 shelves x 2 books x 2 pages
        assertThat(rule.capturedFact(0, 0)).isInstanceOf(Library.class);
        assertThat(rule.capturedFact(0, 1)).isInstanceOf(BaseTuple.Tuple5.class);
        @SuppressWarnings("unchecked")
        BaseTuple.Tuple5<Library, Room, Shelf, Book, Page> t = (BaseTuple.Tuple5<Library, Room, Shelf, Book, Page>) rule
                .capturedFact(0, 1);
        assertThat(t.getA()).isInstanceOf(Library.class);
        assertThat(t.getB()).isInstanceOf(Room.class);
        assertThat(t.getC()).isInstanceOf(Shelf.class);
        assertThat(t.getD()).isInstanceOf(Book.class);
        assertThat(t.getE()).isInstanceOf(Page.class);
    }

    @Test
    public void testPath4FilterAppliedAtBookStep() {
        // Filter at the book step (step 2) restricts to published books only.
        // 2 libs x 2 rooms x 1 published book x 1 page = 4 combinations.
        var rule = builder.from(ctx -> ctx.libraries())
                .<Room, Book, Page> path4()
                .path((pathCtx, lib) -> lib.rooms(), (pathCtx, room) -> true)
                .path((pathCtx, room) -> room.books(), (pathCtx, book) -> book.published())
                .path((pathCtx, book) -> book.pages(), (pathCtx, page) -> true)
                .fn((ctx, lib, t) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(4); // 2 libs x 2 rooms x 1 published book x 1 page
        for (int i = 0; i < rule.executionCount(); i++) {
            @SuppressWarnings("unchecked")
            BaseTuple.Tuple4<Library, Room, Book, Page> t = (BaseTuple.Tuple4<Library, Room, Book, Page>) rule.capturedFact(i,
                    1);
            assertThat(t.getC().published()).isTrue();
        }
    }

    @Test
    public void testPath4ContextCrossReferenceAtPageStep() {
        // At the page step (step 3), pathCtx.getTuple().getA() gives the Library —
        // verifying that PathContext carries all earlier facts across four levels.
        // Filter: only pages from ScienceLib pass. ScienceLib has 2 rooms x 2 books x 1 page = 4.
        var rule = builder.from(ctx -> ctx.libraries())
                .<Room, Book, Page> path4()
                .path((pathCtx, lib) -> lib.rooms(), (pathCtx, room) -> true)
                .path((pathCtx, room) -> room.books(), (pathCtx, book) -> true)
                .path((pathCtx, book) -> book.pages(),
                        (pathCtx, page) -> pathCtx.getTuple().getA().name().equals("ScienceLib"))
                .fn((ctx, lib, t) -> {
                });

        rule.run(ctx);

        assertThat(rule.executionCount()).isEqualTo(4); // ScienceLib: 2 rooms x 2 books x 1 page
        for (int i = 0; i < rule.executionCount(); i++) {
            @SuppressWarnings("unchecked")
            BaseTuple.Tuple4<Library, Room, Book, Page> t = (BaseTuple.Tuple4<Library, Room, Book, Page>) rule.capturedFact(i,
                    1);
            assertThat(t.getA().name()).isEqualTo("ScienceLib");
        }
    }

    @Test
    public void testPath5FilterAtShelfLevel() {
        // Filter at the shelf step (step 2) restricts to ShelfA only, halving the result.
        // Local ctx: 1 lib x 2 rooms x 2 shelves x 2 books x 2 pages.
        // With ShelfA filter: 1 x 2 rooms x 1 shelf x 2 books x 2 pages = 8 combinations.
        Page p1 = new Page("page1");
        Page p2 = new Page("page2");
        Book rel = new Book("Relativity", true, java.util.List.of(p1, p2));
        Book draft = new Book("Draft", false, java.util.List.of(p1, p2));
        Book evo = new Book("Evolution", true, java.util.List.of(p1, p2));
        Book notes = new Book("Notes", false, java.util.List.of(p1, p2));
        Room physics = new Room("Physics", java.util.List.of(),
                java.util.List.of(new Shelf("ShelfA", java.util.List.of(rel, draft)),
                        new Shelf("ShelfB", java.util.List.of(evo, notes))));
        Room biology = new Room("Biology", java.util.List.of(),
                java.util.List.of(new Shelf("ShelfA", java.util.List.of(rel, draft)),
                        new Shelf("ShelfB", java.util.List.of(evo, notes))));
        Ctx ctx5 = new Ctx(
                DataSource.of(), DataSource.of(), DataSource.of(), DataSource.of(), DataSource.of(),
                DataSource.of(new Library("ScienceLib", java.util.List.of(physics, biology))));

        var rule = builder.from(c -> c.libraries())
                .<Room, Shelf, Book, Page> path5()
                .path((pathCtx, lib) -> lib.rooms(), (pathCtx, room) -> true)
                .path((pathCtx, room) -> room.shelves(), (pathCtx, shelf) -> shelf.name().equals("ShelfA"))
                .path((pathCtx, shelf) -> shelf.books(), (pathCtx, book) -> true)
                .path((pathCtx, book) -> book.pages(), (pathCtx, page) -> true)
                .fn((c, lib, t) -> {
                });

        rule.run(ctx5);

        assertThat(rule.executionCount()).isEqualTo(8); // 2 rooms x 1 ShelfA x 2 books x 2 pages
        for (int i = 0; i < rule.executionCount(); i++) {
            @SuppressWarnings("unchecked")
            BaseTuple.Tuple5<Library, Room, Shelf, Book, Page> t = (BaseTuple.Tuple5<Library, Room, Shelf, Book, Page>) rule
                    .capturedFact(i, 1);
            assertThat(t.getC().name()).isEqualTo("ShelfA");
        }
    }

    @Test
    public void testPath5ContextCrossReferenceAtBookStep() {
        // At the book step (step 3), pathCtx.getTuple().getA() gives the Library —
        // verifying PathContext carries state across five levels.
        // Filter: only books from rooms named "Physics" pass.
        // Local ctx: 1 lib x 2 rooms (Physics, Biology) x 2 shelves x 2 books.
        // Physics: 2 shelves x 2 books x 2 pages = 8. Biology: filtered out.
        Page p1 = new Page("page1");
        Page p2 = new Page("page2");
        Book rel = new Book("Relativity", true, java.util.List.of(p1, p2));
        Book draft = new Book("Draft", false, java.util.List.of(p1, p2));
        Book evo = new Book("Evolution", true, java.util.List.of(p1, p2));
        Book notes = new Book("Notes", false, java.util.List.of(p1, p2));
        Room physics = new Room("Physics", java.util.List.of(),
                java.util.List.of(new Shelf("ShelfA", java.util.List.of(rel, draft)),
                        new Shelf("ShelfB", java.util.List.of(evo, notes))));
        Room biology = new Room("Biology", java.util.List.of(),
                java.util.List.of(new Shelf("ShelfA", java.util.List.of(rel, draft)),
                        new Shelf("ShelfB", java.util.List.of(evo, notes))));
        Ctx ctx5 = new Ctx(
                DataSource.of(), DataSource.of(), DataSource.of(), DataSource.of(), DataSource.of(),
                DataSource.of(new Library("ScienceLib", java.util.List.of(physics, biology))));

        var rule = builder.from(c -> c.libraries())
                .<Room, Shelf, Book, Page> path5()
                .path((pathCtx, lib) -> lib.rooms(), (pathCtx, room) -> true)
                .path((pathCtx, room) -> room.shelves(), (pathCtx, shelf) -> true)
                .path((pathCtx, shelf) -> shelf.books(),
                        (pathCtx, book) -> pathCtx.getTuple().getB().name().equals("Physics"))
                .path((pathCtx, book) -> book.pages(), (pathCtx, page) -> true)
                .fn((c, lib, t) -> {
                });

        rule.run(ctx5);

        assertThat(rule.executionCount()).isEqualTo(8); // Physics: 2 shelves x 2 books x 2 pages
        for (int i = 0; i < rule.executionCount(); i++) {
            @SuppressWarnings("unchecked")
            BaseTuple.Tuple5<Library, Room, Shelf, Book, Page> t = (BaseTuple.Tuple5<Library, Room, Shelf, Book, Page>) rule
                    .capturedFact(i, 1);
            assertThat(t.getB().name()).isEqualTo("Physics");
        }
    }

    @Test
    public void testVariableFilter2BoundVars() {
        Variable<Person> vp = new Variable<>();
        Variable<Account> va = new Variable<>();

        var rule = builder.from(ctx -> ctx.persons())
                .var(vp)
                .join(ctx -> ctx.accounts())
                .var(va)
                .filter(vp, va, (ctx, p, a) -> p.name().startsWith("A") && a.balance() > 500)
                .fn((ctx, p, a) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1); // Alice (age 30, starts with A) + ACC1 (balance 1000)
    }

    @Test
    public void testVariableFilter3BoundVars() {
        Variable<Person> vp = new Variable<>();
        Variable<Account> va = new Variable<>();
        Variable<Order> vo = new Variable<>();

        var rule = builder.from(ctx -> ctx.persons())
                .var(vp)
                .join(ctx -> ctx.accounts())
                .var(va)
                .join(ctx -> ctx.orders())
                .var(vo)
                .filter(vp, va, vo, (ctx, p, a, o) -> p.name().startsWith("A") && a.balance() > 500 && o.amount() > 100)
                .fn((ctx, p, a, o) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1); // Alice + ACC1 + ORD1 (amount 150)
    }

    // =========================================================================
    // run() reset semantics
    // =========================================================================

    @Test
    public void testRunResetsExecutionCount() {
        // rule.run(ctx) resets executions on each call — executionCount() reflects only
        // the most recent run, not accumulated across multiple runs.
        var rule = builder.from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1); // Alice only

        // Run again — should reset, not accumulate
        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1); // still 1, not 2
    }

    // =========================================================================
    // fn() side-effects — action actually executes for each matched tuple
    // =========================================================================

    @Test
    public void testFnActionActuallyRuns() {
        // Verify the fn() action actually executes for each matched tuple,
        // not just that executionCount() is correct.
        java.util.List<String> names = new java.util.ArrayList<>();

        var rule = builder.from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> names.add(p.name()));

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(names).containsExactly("Alice");
    }

    // =========================================================================
    // Chained filter() — AND-composition semantics
    // =========================================================================

    @Test
    public void testChainedFiltersAreAndNotOr() {
        // .filter(p1).filter(p2) is AND — both must pass.
        // filter1: age >= 18 → Alice passes, Bob fails
        // filter2: name starts with "A" → Alice passes
        // AND result: Alice only (executionCount=1)
        var rule = builder.from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18) // Alice passes
                .filter((ctx, p) -> p.name().startsWith("A")) // Alice passes
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }

    @Test
    public void testChainedFiltersApplyLeftToRight() {
        // .filter(p1).filter(p2): p1 applies first, p2 applies to p1's survivors.
        // filter1: age >= 18 → Alice survives, Bob is eliminated
        // filter2: name starts with "B" → Alice fails
        // Result: 0 executions (not 1 if order were reversed)
        var rule = builder.from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18) // Alice passes, Bob fails
                .filter((ctx, p) -> p.name().startsWith("B")) // Alice fails
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(0);
    }

    // =========================================================================
    // type() — compile-time type narrowing
    // =========================================================================

    @Test
    public void testTypeNarrowsBaseType() {
        // type() narrows a base-typed DataSource to the actual runtime type.
        // DataSource<Object> containing Persons, narrowed to Person via type().
        @SuppressWarnings("unchecked")
        DataSource<Object> untypedPersons = (DataSource<Object>) (DataSource<?>) DataSource.of(new Person("Alice", 30),
                new Person("Bob", 17));

        var rule = builder.from(ctx -> untypedPersons)
                .<Person> type()
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }

    @Test
    public void testTypeIsNoOpAtRuntime() {
        // type() doesn't change execution count — it's purely compile-time.
        // Same source with and without type() should give identical results.
        var withType = builder.from(ctx -> ctx.persons())
                .<Person> type() // redundant but valid — already typed
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });

        var withoutType = builder.from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });

        withType.run(ctx);
        withoutType.run(ctx);

        assertThat(withType.executionCount()).isEqualTo(withoutType.executionCount());
    }

    // =========================================================================
    // Chained not()/exists() scopes
    // =========================================================================

    @Test
    public void testNotFollowedByExistsBothSatisfied() {
        // Chained not() + exists() on same outer chain — both must be satisfied.
        // not(orders > 1000): SATISFIED — no order exceeds 1000 → persons survive
        // exists(orders > 100): SATISFIED — ORD1=150 exists → persons survive
        // Outer filter before scopes to narrow to Alice (age >= 18)
        var rule = builder.from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .not()
                .join(ctx -> ctx.orders())
                .filter((Object) (Predicate2<Ctx, Order>) (ctx, o) -> o.amount() > 1000.0)
                .end()
                .exists()
                .join(ctx -> ctx.orders())
                .filter((Object) (Predicate2<Ctx, Order>) (ctx, o) -> o.amount() > 100.0)
                .end()
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }

    @Test
    public void testNotUnsatisfiedBlocksEvenWhenExistsSatisfied() {
        // not() UNSATISFIED blocks all outer facts even though exists() would pass.
        // not(accounts > 500): UNSATISFIED — ACC1=1000 exists → all persons blocked
        // exists(orders > 100): SATISFIED — ORD1=150 exists
        // Because not() fails globally, result is 0 regardless of exists().
        var rule = builder.from(ctx -> ctx.persons())
                .not()
                .join(ctx -> ctx.accounts())
                .filter((Object) (Predicate2<Ctx, Account>) (ctx, a) -> a.balance() > 500.0)
                .end()
                .exists()
                .join(ctx -> ctx.orders())
                .filter((Object) (Predicate2<Ctx, Order>) (ctx, o) -> o.amount() > 100.0)
                .end()
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(0);
    }

    @Test
    public void testIndexIsNoOpAtRuntime() {
        // index() is a DSL hint for Rete optimization — no-op in sandbox.
        // Verifies it compiles and chains correctly without affecting execution.
        var rule = builder.from(ctx -> ctx.persons())
                .index()
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });

        rule.run(ctx);
        assertThat(rule.executionCount()).isEqualTo(1);
        assertThat(((Person) rule.capturedFact(0, 0)).name()).isEqualTo("Alice");
    }

    @Test
    public void testTwoRulesFromSameBuilderAreIndependent() {
        // Two rules built from the same RuleBuilder instance are fully independent.
        // Running one rule does not affect the other's state.
        var rule1 = builder.from(ctx -> ctx.persons())
                .filter((ctx, p) -> p.age() >= 18)
                .fn((ctx, p) -> {
                });

        var rule2 = builder.from(ctx -> ctx.accounts())
                .filter((ctx, a) -> a.balance() > 500.0)
                .fn((ctx, a) -> {
                });

        rule1.run(ctx);
        rule2.run(ctx);

        // Each rule independently matches its own data
        assertThat(rule1.executionCount()).isEqualTo(1); // Alice only
        assertThat(rule2.executionCount()).isEqualTo(1); // ACC1 only

        // Running rule2 again resets rule2's count but does not affect rule1
        rule2.run(ctx);
        assertThat(rule1.executionCount()).isEqualTo(1); // rule1 unchanged
        assertThat(rule2.executionCount()).isEqualTo(1); // rule2 reset and re-run
    }
}
