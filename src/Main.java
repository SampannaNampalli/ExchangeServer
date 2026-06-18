import java.time.Instant;
import java.util.Optional;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
        test1();
    }

    public static void test1() {
        OrderBook orderBook = new OrderBook();
        long orderId = 0;
        Order b1 = new Order(orderId++, Side.BUY, 105, 100, Instant.now());
        Order b2 = new Order(orderId++, Side.BUY, 105, 50, Instant.now());
        Order b3 = new Order(orderId++, Side.BUY, 100, 75, Instant.now());
        Order s1 = new Order(orderId++, Side.SELL, 110, 20, Instant.now());

        // Add orders to orderbook.
        orderBook.add(b1);
        orderBook.add(b2);
        orderBook.add(b3);
        orderBook.add(s1);
        Optional<PriceLevel> bestBid = orderBook.bestBid();
        long bid = bestBid.map(PriceLevel::getPrice).orElse(0L);
        System.out.println("Best bid: " + bid);

        Optional<PriceLevel> bestAsk = orderBook.bestAsk();
        long ask = bestAsk.map(PriceLevel::getPrice).orElse(0L);
        System.out.println("Best Ask: " + ask);

        long volAt105 = orderBook.getVolume(105);
        System.out.println("Volume at 105: " + volAt105);
    }

    public static void initTest() {
        PriceLevel priceLevel = new PriceLevel(100);
        OrderBook orderBook = new OrderBook();
        Order order = new Order(1, Side.BUY, 100, 1000, Instant.now());

        System.out.println(priceLevel.getPrice());
        System.out.println(order.getOriginalQuantity());
    }
}