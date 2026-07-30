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
    public void removePriceLevel(Side side) {

        PriceLevel pl;

        if (side == Side.BUY) {

            if (bids.isEmpty()) {
                throw new IllegalAccessError("The pricelevel is empty.");
            }
            pl = bids.firstEntry().getValue();

            if (!pl.isFullyConsumed()) {
                throw new IllegalStateException("Price Level is not empty.");
            }

            bids.remove(pl.getPrice());
        }
        else {

            if (asks.isEmpty()) {
                throw new IllegalAccessError("The pricelevel is empty.");
            }
            pl = asks.firstEntry().getValue();

            if (!pl.isFullyConsumed()) {
                throw new IllegalStateException("Price Level is not empty.");
            }

            asks.remove(pl.getPrice());
        }
    }
}


//    public Optional<PriceLevel> bestBid() {
//
//        Optional<Map.Entry<Long, PriceLevel>> plEntry = Optional.ofNullable(bids.firstEntry());
//        Optional<PriceLevel> bid = plEntry.map(Map.Entry<Long, PriceLevel> :: getValue);
//        bid = bid.flatMap()
//    }