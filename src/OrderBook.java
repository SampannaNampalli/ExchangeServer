import jdk.jfr.Description;

import java.util.*;

// For cancel/update - use HashMap<Long, Order> can be used.


public class OrderBook {
    private final TreeMap<Long, PriceLevel> bids;
    private final TreeMap<Long, PriceLevel> asks;

    public OrderBook() {
        this.bids = new TreeMap<>(Comparator.reverseOrder());
        this.asks = new TreeMap<>();
    }

    public void add(Order order) {
        long price = order.getPriceTicks();
        PriceLevel pl;

        if (order.getRemainingQuantity() <= 0) {
            throw new IllegalArgumentException("Cannot add an order with zero remaining quantity.");
        }

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

    public void removePriceLevel(Side side) {

        PriceLevel pl;

        if (side == Side.BUY) {

            if (bids.isEmpty()) {
                throw new IllegalStateException("The price-level is empty.");
            }
            pl = bids.firstEntry().getValue();

            if (!pl.isFullyConsumed()) {
                throw new IllegalStateException("Price-level is not empty.");
            }

            bids.remove(pl.getPrice());
        }
        else {

            if (asks.isEmpty()) {
                throw new IllegalStateException("The price-level is empty.");
            }
            pl = asks.firstEntry().getValue();

            if (!pl.isFullyConsumed()) {
                throw new IllegalStateException("Price Level is not empty.");
            }

            asks.remove(pl.getPrice());
        }
    }

    public long getNoOfBids() {
        return bids.size();
    }

    public long getNoOfAsks() {
        return asks.size();
    }
}
