To Gemini

Hey. I am trying to build a stock exchange API in Java from first principles. Currently, my project is small, since I am first establishing design and invariants and only then coding things. Here is my folder structure:


ExchangeServer

    Side.java
    Order.java
    Trade.java
    PriceLevel.java
    OrderBook.java
    MatchingEngine.java
    Main.java

With the files containing:

Side.java:
    
    public enum Side {
        BUY,
        SELL
    }

Order.java:

    import java.time.Instant;
    import java.util.*;
    
    public class Order {
    private final long orderId;
    private final Side side;
    private final long price;
    private final long originalQuantity;
    private final Instant timestamp; // exchangeTimestamp?
    
        private long filledQuantity;
    
        public Order(long orderId, Side side, long price, long originalQuantity, Instant timestamp) {
    
            if (orderId < 0) {
                throw new IllegalArgumentException("Order ID must be positive");
            }
            if (price < 0) {
                throw new IllegalArgumentException("Price must be positive");
            }
            if (originalQuantity < 0) {
                throw new IllegalArgumentException("Original quantity must be positive");
            }
            this.orderId = orderId;
            this.side = side;
            this.price = price;
            this.originalQuantity = originalQuantity;
            this.timestamp = timestamp;
        }
    
        public long getOrderId() {
            return this.orderId;
        }
    
        public Side getSide() {
            return this.side;
        }
    
        public long getPrice() {
            return this.price;
        }
    
        public long getOriginalQuantity() {
            return this.originalQuantity;
        }
    
        public Instant getTimestamp() {
            return this.timestamp;
        }
    
        public long getFilledQuantity() {
            return filledQuantity;
        }
    
        public void fill(long quantity) {
    
            if (quantity <= 0) {
                throw new IllegalArgumentException(
                        "Fill quantity must be positive"
                );
            }
    
            if (quantity > getRemainingQuantity()) {
                throw new IllegalArgumentException(
                        "Cannot overfill order"
                );
            }
            else {
                filledQuantity += quantity;
            }
        }
    
        public long getRemainingQuantity() {
            return originalQuantity - filledQuantity;
        }
    }

Trade.java:

    import java.util.*;
    import java.time.Instant;
    
    public class Trade {
    private final long buyOrderId;
    private final long sellOrderId;
    
        private final long quantity;
        private final long price;
    
        private final Instant timestamp;
    
        public Trade(long quantity, long price, long buyOrderId, long sellOrderId, Instant timestamp) {
    
            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive");
            }
            if (price <= 0) {
                throw new IllegalArgumentException("Price must be positive");
            }
    
            this.quantity = quantity;
            this.price = price;
            this.buyOrderId = buyOrderId;
            this.sellOrderId = sellOrderId;
            this.timestamp = timestamp;
        }
    
        // getters.
    }

PriceLevel.java:

    import java.util.*;

    public class PriceLevel {
    private final long price;
    private final Deque<Order> orders;
    private long totalVolume;
    
        public PriceLevel(long price) {
            this.price = price;
            orders = new ArrayDeque<>();
            totalVolume = 0;
        }
    
        public void addOrder(Order order) {
    
            if (order.getPrice() != price) {
                throw new IllegalArgumentException("Price not matching. Insert into appropriate price-level.");
            }
    
            orders.add(order);
            totalVolume += order.getRemainingQuantity();
            // totalVolume is total workable/tradable volume.
            // Since it is initialization, and orders cannot move across price levels,
            // order.getOriginalQuantity() may also work.
        }
    
        public Order peekFirstOrder() {
    
            if (orders.isEmpty()) {
                return null;
            }
            if (orders.peekFirst().getRemainingQuantity() == 0) {
                throw new IllegalStateException(
                        "Fully-filled order left in queue"
                );
            }
    
            return orders.peekFirst();
        }
    
        public Order removeFirstOrder() {
            Order order = this.orders.pollFirst();
    
            if (order != null) {
                totalVolume -= order.getRemainingQuantity();
            }
    
            return order;
        }
    
        public boolean isEmpty() {
            return (this.orders.isEmpty());
        }
    
        public long getTotalVolume() {
            return this.totalVolume;
        }
    
        public long getPrice() {
            return this.price;
        }
    
        public long fillHeadOrder(long quantity) {
            Order o = orders.peekFirst();
    
            if (o == null) {
                throw new NoSuchElementException("There is no order at the given price");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive");
            }
    
            long quantityToFill = Math.min(
                    o.getRemainingQuantity(),
                    quantity
            );
            o.fill(quantityToFill);
    
            totalVolume -= quantityToFill;
    
            if (o.getRemainingQuantity() == 0) {
                orders.pollFirst();
            }
    
            return quantityToFill;
        }
    
        public boolean isFullyConsumed() {
    //        return (totalVolume == 0);
    
            // Or
             return orders.isEmpty();
    
             // This is better since totalVolume is a derived state.
        }
    }


OrderBook.java:

    import java.util.*;

    public class OrderBook {
    private final TreeMap<Long, PriceLevel> bids;
    private final TreeMap<Long, PriceLevel> asks;
    
        public OrderBook() {
            this.bids = new TreeMap<>(Comparator.reverseOrder());
            this.asks = new TreeMap<>();
        }
    
        public void add(Order order) {
            long price = order.getPrice();
            PriceLevel pl;
    
            if (order.getSide() == Side.BUY) {
                pl = bids.computeIfAbsent(price, PriceLevel::new);
            }
            else {
                pl = asks.computeIfAbsent(price, PriceLevel::new);
            }
            pl.addOrder(order);
        }
    
        public Optional<PriceLevel> bestBid() {
            Map.Entry<Long, PriceLevel> plEntry = bids.firstEntry();
            return ((plEntry == null) ? Optional.empty(): Optional.ofNullable(plEntry.getValue()));
        }
    
        public Optional<PriceLevel> bestAsk() {
            Map.Entry<Long, PriceLevel> plEntry = asks.firstEntry();
            return ((plEntry == null)? Optional.empty(): Optional.ofNullable(plEntry.getValue()));
        }
    
        public long getVolume(Side side, long price) {
            Map<Long, PriceLevel> map;
    
            if (side == Side.BUY) {
                map = bids;
            }
            else {
                map = asks;
            }
    
            Optional<PriceLevel> pl = Optional.ofNullable(map.get(price));
            return pl.map(PriceLevel::getTotalVolume).orElse(0L);
        }
    }

MatchingEngine.java:

    import java.util.*;

    public class MatchingEngine {
        private long nextOrderId = 1;
    
    }


Main.java:

    import java.time.Instant;
    import java.util.Optional;
    
    public class Main {
    public static void main(String[] args) {
    test3();
    }
    
        // PriceLevel independency/responsibility separation check -> do its functions work
        // properly, or does it have any upward dependency?
        public static void test3() {
            PriceLevel level = new PriceLevel(100);
            level.addOrder(new Order(0, Side.BUY, 100, 100, Instant.now()));
            long filledQuantity = level.fillHeadOrder(40);
            System.out.println("filledQuantity: "  + filledQuantity);
            System.out.println("Remaining Quantity: " + level.getTotalVolume());
        }
    
        // FIFO check.
        public static void test2() {
            OrderBook orderBook = new OrderBook();
            long orderId = 0;
            Order b1 = new Order(orderId++, Side.BUY, 105, 100, Instant.now());
            Order b2 = new Order(orderId++, Side.BUY, 105, 50, Instant.now());
    
            // Add orders to orderbook.
            orderBook.add(b1);
            orderBook.add(b2);
    
            PriceLevel level = orderBook.bestBid().orElseThrow();
            System.out.println(level.peekFirstOrder().getOrderId());
        }
    
        // Comprehensive Check - 1.
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
    
            long volAt105 = orderBook.getVolume(Side.BUY, 105);
            System.out.println("Volume at 105: " + volAt105);
        }
    
        // Data Model check.
        public static void initTest() {
            PriceLevel priceLevel = new PriceLevel(100);
            OrderBook orderBook = new OrderBook();
            Order order = new Order(1, Side.BUY, 100, 1000, Instant.now());
    
            System.out.println(priceLevel.getPrice());
            System.out.println(order.getOriginalQuantity());
        }
    }


## Claude

I want to remove the price argument since the best price is the first to be matched, and
hence should be the first to be removed. Though this changes if a client wants to cancel
or update their order.

For now, I want to focus on just limit orders for now.


### 22/07/26 - 9pm

What if a better order comes when an order is being matched?
If an incoming order is fulfilled before it is entered into the order-book, should it still be inserted into the order-book, or the log book?


### 11/10/26

Here are the functions: