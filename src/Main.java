import java.time.Instant;
import java.util.Optional;

public class Main {
    public static void main(String[] args) {
        
    }
}

// public class Main {
//     public static void main(String[] args) {
//         test3();
//     }

//     // PriceLevel independency/responsibility separation check -> do its functions work
//     // properly, or does it have any upward dependency?
//     public static void test3() {
//         PriceLevel level = new PriceLevel(100);
//         level.addOrder(new Order(0, Side.BUY, 100, 100, Instant.now()));
//         long filledQuantity = level.fillHeadOrder(40);
//         System.out.println("filledQuantity: "  + filledQuantity);
//         System.out.println("Remaining Quantity: " + level.getTotalVolume());
//     }

//     // FIFO check.
//     public static void test2() {
//         OrderBook orderBook = new OrderBook();
//         long orderId = 0;
//         Order b1 = new Order(orderId++, Side.BUY, 105, 100, Instant.now());
//         Order b2 = new Order(orderId++, Side.BUY, 105, 50, Instant.now());

//         // Add orders to orderbook.
//         orderBook.addOrderToBook(b1);
//         orderBook.addOrderToBook(b2);

//         PriceLevel level = orderBook.bestBid().orElseThrow();
//         System.out.println(level.peekFirstOrder().getOrderId());
//     }

//     // Comprehensive Check - 1.
//     public static void test1() {
//         OrderBook orderBook = new OrderBook();
//         long orderId = 0;
//         Order b1 = new Order(orderId++, Side.BUY, 105, 100, Instant.now());
//         Order b2 = new Order(orderId++, Side.BUY, 105, 50, Instant.now());
//         Order b3 = new Order(orderId++, Side.BUY, 100, 75, Instant.now());
//         Order s1 = new Order(orderId++, Side.SELL, 110, 20, Instant.now());

//         // Add orders to orderbook.
//         orderBook.addOrderToBook(b1);
//         orderBook.addOrderToBook(b2);
//         orderBook.addOrderToBook(b3);
//         orderBook.addOrderToBook(s1);
//         Optional<PriceLevel> bestBid = orderBook.bestBid();
//         long bid = bestBid.map(PriceLevel::getPrice).orElse(0L);
//         System.out.println("Best bid: " + bid);

//         Optional<PriceLevel> bestAsk = orderBook.bestAsk();
//         long ask = bestAsk.map(PriceLevel::getPrice).orElse(0L);
//         System.out.println("Best Ask: " + ask);

//         long volAt105 = orderBook.getVolume(Side.BUY, 105);
//         System.out.println("Volume at 105: " + volAt105);
//     }

//     // Data Model check.
//     public static void initTest() {
//         PriceLevel priceLevel = new PriceLevel(100);
//         OrderBook orderBook = new OrderBook();
//         Order order = new Order(1, Side.BUY, 100, 1000, Instant.now());

//         System.out.println(priceLevel.getPrice());
//         System.out.println(order.getOriginalQuantity());
//     }
// }