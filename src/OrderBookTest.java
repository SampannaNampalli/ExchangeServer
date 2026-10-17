import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/*

Tests OrderBook on its own (no MatchingEngine). Every test starts from this book:

    bids  99: [B1(10), B2(5)]      asks  101: [S4(20)]
          98: [B3(7)]
    id map: {1, 2, 3, 4}

Invariants under test:
1. An id is in the map  <=>  its order rests in a level   (checked with containsOrder).
2. The book never holds an empty price level              (checked with getNoOfBids/Asks and bestBid/Ask,
                                                            NOT getVolume: an empty level also has volume 0).
3. add() is all-or-nothing: if it throws, the book is exactly as before.

 */
public class OrderBookTest {

    OrderBook book;
    Order b1, b2, b3, s4;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
        b1 = order(1, Side.BUY, 99, 10);
        b2 = order(2, Side.BUY, 99, 5);
        b3 = order(3, Side.BUY, 98, 7);
        s4 = order(4, Side.SELL, 101, 20);
        book.addOrderToBook(b1);
        book.addOrderToBook(b2);
        book.addOrderToBook(b3);
        book.addOrderToBook(s4);
    }

    static Order order(long id, Side side, long price, long quantity) {
        return new Order(id, side, price, quantity, Instant.EPOCH);
    }

    // Order ids at the best level of a side, in time priority.
    List<Long> idsAtBest(Side side) {
        return book.bestLevel(side).get().ordersHeadToTail().stream()
                .map(Order::getOrderId)
                .collect(Collectors.toList());
    }

    // The starting book, unchanged. Used after every operation that is supposed to have no effect.
    void assertStartingBook() {
        assertEquals(2, book.getNoOfBids());
        assertEquals(1, book.getNoOfAsks());
        assertEquals(15, book.getVolume(Side.BUY, 99));
        assertEquals(7, book.getVolume(Side.BUY, 98));
        assertEquals(20, book.getVolume(Side.SELL, 101));
        assertEquals(List.of(1L, 2L), idsAtBest(Side.BUY));
        for (long id = 1; id <= 4; id++) {
            assertTrue(book.containsOrder(id), "id " + id + " missing from map");
        }
    }

    @Nested
    class Add {

        @Test
        void ordersRestAtTheirLevels() {
            assertStartingBook();
            assertEquals(99, book.bestBid().get().getPrice());
            assertEquals(101, book.bestAsk().get().getPrice());
        }

        @Test
        void bidsAreBestHighestFirstAndAsksBestLowestFirst() {
            book.addOrderToBook(order(5, Side.BUY, 100, 1));
            book.addOrderToBook(order(6, Side.SELL, 102, 1));
            book.addOrderToBook(order(7, Side.SELL, 100, 1));

            assertEquals(100, book.bestBid().get().getPrice());
            assertEquals(100, book.bestAsk().get().getPrice());
        }

        @Test
        void partiallyFilledOrderRestsWithItsRemainingQuantity() {
            // An incoming order that matched 20 of 50 and rests the other 30.
            Order partial = order(5, Side.BUY, 99, 50);
            partial.fill(20);
            book.addOrderToBook(partial);

            assertEquals(15 + 30, book.getVolume(Side.BUY, 99));
            assertEquals(List.of(1L, 2L, 5L), idsAtBest(Side.BUY));
            assertTrue(book.containsOrder(5));
        }

        @Test
        void duplicateIdAtExistingPriceThrowsAndBookUnchanged() {
            assertThrows(IllegalStateException.class,
                    () -> book.addOrderToBook(order(1, Side.BUY, 99, 3)));
            assertStartingBook();
        }

        @Test
        void duplicateIdAtNewPriceThrowsAndCreatesNoLevel() {
            // The case all-or-nothing is about: a throw must not leave an empty level at 97.
            assertThrows(IllegalStateException.class,
                    () -> book.addOrderToBook(order(1, Side.BUY, 97, 3)));

            assertStartingBook();
            // The map still points at the ORIGINAL order 1, not the rejected one.
            assertSame(b1, book.bestBid().get().peekFirstOrder());
        }

        @Test
        void duplicateIdOnOtherSideThrows() {
            assertThrows(IllegalStateException.class,
                    () -> book.addOrderToBook(order(1, Side.SELL, 105, 3)));
            assertStartingBook();
        }

        @Test
        void zeroRemainingAtNewPriceThrowsAndCreatesNoLevel() {
            // Without OrderBook's own zero-remaining check, PriceLevel would reject this AFTER level 97 was created.
            Order exhausted = order(5, Side.BUY, 97, 5);
            exhausted.fill(5);

            assertThrows(IllegalArgumentException.class, () -> book.addOrderToBook(exhausted));
            assertStartingBook();
            assertFalse(book.containsOrder(5));
        }
    }

    @Nested
    class Cancel {

        @Test
        void cancelHeadOfMultiOrderLevelKeepsLevel() {
            CancelResult result = book.cancel(1);

            assertEquals(CancelStatus.CANCELLED, result.status());
            assertEquals(10, result.cancelledQuantity());
            assertFalse(book.containsOrder(1));
            assertEquals(List.of(2L), idsAtBest(Side.BUY));
            assertEquals(5, book.getVolume(Side.BUY, 99));
            assertEquals(2, book.getNoOfBids());
        }

        @Test
        void cancelTailOfMultiOrderLevelKeepsLevel() {
            book.cancel(2);

            assertFalse(book.containsOrder(2));
            assertEquals(List.of(1L), idsAtBest(Side.BUY));
            assertEquals(10, book.getVolume(Side.BUY, 99));
            assertEquals(2, book.getNoOfBids());
        }

        @Test
        void cancelEmptyingNonBestLevelRemovesIt() {
            // Level 98 is not the best bid: removal must work for any level, not just the best.
            CancelResult result = book.cancel(3);

            assertEquals(CancelStatus.CANCELLED, result.status());
            assertEquals(7, result.cancelledQuantity());
            assertFalse(book.containsOrder(3));
            assertEquals(1, book.getNoOfBids());
            assertEquals(99, book.bestBid().get().getPrice());
        }

        @Test
        void cancelEmptyingBestBidPromotesNextLevel() {
            book.cancel(1);
            book.cancel(2);

            assertEquals(1, book.getNoOfBids());
            assertEquals(98, book.bestBid().get().getPrice());
        }

        @Test
        void cancelEmptyingAskLevelRemovesIt() {
            CancelResult result = book.cancel(4);

            assertEquals(CancelStatus.CANCELLED, result.status());
            assertEquals(20, result.cancelledQuantity());
            assertFalse(book.containsOrder(4));
            assertEquals(0, book.getNoOfAsks());
            assertTrue(book.bestAsk().isEmpty());
            // The bid side is untouched.
            assertEquals(2, book.getNoOfBids());
        }

        @Test
        void cancelEverythingEmptiesBothSides() {
            for (long id = 1; id <= 4; id++) {
                assertEquals(CancelStatus.CANCELLED, book.cancel(id).status());
            }
            assertEquals(0, book.getNoOfBids());
            assertEquals(0, book.getNoOfAsks());
        }

        @Test
        void cancelOfPartiallyFilledOrderReturnsOnlyRemaining() {
            // B1 (10) filled 4 at the head, then cancelled: 6 is cancelled, 4 already traded.
            book.bestBid().get().fillHeadOrder(4);

            CancelResult result = book.cancel(1);

            assertEquals(CancelStatus.CANCELLED, result.status());
            assertEquals(6, result.cancelledQuantity());
            assertEquals(5, book.getVolume(Side.BUY, 99));
        }

        @Test
        void cancelOfUnknownIdRejectsAndBookUnchanged() {
            CancelResult result = book.cancel(999);

            assertEquals(CancelStatus.UNKNOWN_ORDER, result.status());
            assertEquals(0, result.cancelledQuantity());
            assertStartingBook();
        }

        @Test
        void secondCancelOfSameIdRejects() {
            book.cancel(3);
            CancelResult second = book.cancel(3);

            assertEquals(CancelStatus.UNKNOWN_ORDER, second.status());
            assertEquals(0, second.cancelledQuantity());
            assertEquals(1, book.getNoOfBids());
        }

        @Test
        void orderAddedAfterCancelGoesToBackOfLevel() {
            book.cancel(1);
            book.addOrderToBook(order(5, Side.BUY, 99, 3));

            assertEquals(List.of(2L, 5L), idsAtBest(Side.BUY));
            assertEquals(8, book.getVolume(Side.BUY, 99));
        }
    }

    @Nested
    class RemoveOrderFromBook {

        @Test
        void missingIdThrows() {
            // Internal path: a missing id means the engine is broken, so it throws instead of rejecting.
            assertThrows(IllegalStateException.class, () -> book.removeOrderFromBook(999));
            assertStartingBook();
        }

        @Test
        void filledHeadIsRemovedAndLevelKept() {
            // The fill path: fillHeadOrder exhausts B1, then the book removes it.
            book.bestBid().get().fillHeadOrder(10);
            book.removeOrderFromBook(1);

            assertFalse(book.containsOrder(1));
            assertEquals(List.of(2L), idsAtBest(Side.BUY));
            assertEquals(5, book.getVolume(Side.BUY, 99));
            assertEquals(2, book.getNoOfBids());
        }

        @Test
        void filledOnlyOrderIsRemovedWithItsLevel() {
            book.bestAsk().get().fillHeadOrder(20);
            book.removeOrderFromBook(4);

            assertFalse(book.containsOrder(4));
            assertEquals(0, book.getNoOfAsks());
        }
    }
}
