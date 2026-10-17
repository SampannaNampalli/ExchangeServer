import jdk.jfr.Description;

import java.util.*;

public class OrderBook {
    private final TreeMap<Long, PriceLevel> bids;
    private final TreeMap<Long, PriceLevel> asks;
    private final HashMap<Long, OrderNode> orderMap;

    public OrderBook() {
        this.bids = new TreeMap<>(Comparator.reverseOrder());
        this.asks = new TreeMap<>();
        this.orderMap = new HashMap<>();
    }

    public void addOrderToBook(Order order) {
        long price = order.getPrice();
        long orderId = order.getOrderId();
        PriceLevel pl;

        if (order.getRemainingQuantity() <= 0) {
            throw new IllegalArgumentException("Cannot add an order with zero or less remaining quantity.");
        }
        if (orderMap.containsKey(orderId)) {
            throw new IllegalStateException("Illegal State: Duplicate Order ID");
        }

        if (order.getSide() == Side.BUY) {
            pl = bids.computeIfAbsent(price, PriceLevel::new);
        }
        else {
            pl = asks.computeIfAbsent(price, PriceLevel::new);
        }
        OrderNode orderNode = pl.addOrder(order);
        orderMap.put(orderId, orderNode);
    }

    void removeOrderFromBook(long orderId) {

        OrderNode orderNode = orderMap.get(orderId);
        if (orderNode == null) {
            throw new IllegalStateException("Illegal State: resting order is not in id map");
        }

        Order order = orderNode.order;
        PriceLevel pl = orderNode.level;
        pl.unlink(orderNode);
        orderMap.remove(orderId);
        if (pl.isFullyConsumed()) {
            removePriceLevel(pl, order.getSide());
        }
    }

    public Optional<PriceLevel> bestBid() {
        Map.Entry<Long, PriceLevel> plEntry = bids.firstEntry();
        return ((plEntry == null) ? Optional.empty(): Optional.ofNullable(plEntry.getValue()));
    }

    public Optional<PriceLevel> bestAsk() {
        Map.Entry<Long, PriceLevel> plEntry = asks.firstEntry();
        return ((plEntry == null)? Optional.empty(): Optional.ofNullable(plEntry.getValue()));
    }

    @Description("Takes the actual side, not the opposite side of the incoming order")
    public Optional<PriceLevel> bestLevel(Side side) {
        return (side == Side.BUY) ? (bestBid()) : (bestAsk());
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

    private void removePriceLevel(PriceLevel priceLevel, Side side) {

        long price = priceLevel.getPrice();
        PriceLevel pl;

        if (side == Side.BUY) {

            if (bids.isEmpty()) {
                throw new IllegalStateException("Illegal Request: The BUY side is empty. Cannot delete.");
            }
            if (!bids.containsKey(price)) {
                throw new IllegalStateException("Illegal State: Remove called on absent price level");
            }
            if (!priceLevel.isFullyConsumed()) {
                throw new IllegalStateException("Illegal Request: Price-level is not empty.");
            }

            pl = bids.remove(price);
        }
        else {

            if (asks.isEmpty()) {
                throw new IllegalStateException("Illegal Request: The SELL side is empty. Cannot delete.");
            }
            if (!asks.containsKey(price)) {
                throw new IllegalStateException("Illegal State: Remove called on absent price level");
            }
            if (!priceLevel.isFullyConsumed()) {
                throw new IllegalStateException("Illegal Request: Price Level is not empty.");
            }

            pl = asks.remove(price);
        }
        if (pl != priceLevel) {
            throw new IllegalStateException("Illegal Action/State: Removed the wrong price level.");
        }
    }

    public CancelResult cancel(long orderId) {

        OrderNode orderNode = orderMap.get(orderId);
        if (orderNode == null) {
            return new CancelResult(CancelStatus.UNKNOWN_ORDER, 0);
        }

        long cancelledQuantity = orderNode.order.getRemainingQuantity();
        removeOrderFromBook(orderId);

        return new CancelResult(CancelStatus.CANCELLED, cancelledQuantity);
    }

    public long getNoOfBids() {
        return bids.size();
    }

    public long getNoOfAsks() {
        return asks.size();
    }

    
    // For testing.

    boolean containsOrder(long orderId) { return orderMap.containsKey(orderId); }

}