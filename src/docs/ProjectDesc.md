### Folder Structure:

ExchangeServer

    Side.java
    Order.java
    Trade.java
    PriceLevel.java
    OrderBook.java
    MatchingEngine.java
    Main.java

### File Content:

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
    
        public long getBuyOrderId() {
            return buyOrderId;
        }
    
        public long getQuantity() {
            return quantity;
        }
    
        public long getPrice() {
            return price;
        }
    
        public long getSellOrderId() {
            return sellOrderId;
        }
    
        public Instant getTimestamp() {
            return timestamp;
        }
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
    
        // Current reasons for removal of a Price Level:
        //      1. It has become empty --> add conditional
        public void removePriceLevel(Side side, long price) {
    
            PriceLevel pl;
    
            if (side == Side.BUY) {
                pl = bids.firstEntry().getValue();
    
                if (!pl.isFullyConsumed()) {
                    throw new IllegalStateException("Price Level is not empty.");
                }
    
                bids.remove(price);
            }
            else {
                pl = asks.firstEntry().getValue();
    
                if (!pl.isFullyConsumed()) {
                    throw new IllegalStateException("Price Level is not empty.");
                }
    
                asks.remove(price);
            }
        }
    }
    
    
    //    public Optional<PriceLevel> bestBid() {
    //
    //        Optional<Map.Entry<Long, PriceLevel>> plEntry = Optional.ofNullable(bids.firstEntry());
    //        Optional<PriceLevel> bid = plEntry.map(Map.Entry<Long, PriceLevel> :: getValue);
    //        bid = bid.flatMap()
    //    }
