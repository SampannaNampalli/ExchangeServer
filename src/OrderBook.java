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
        return Optional.ofNullable(bids.firstEntry().getValue());
    }

    public Optional<PriceLevel> bestAsk() {
        return Optional.ofNullable(asks.firstEntry().getValue());
    }

    public long getVolume(long price) {
        Optional<PriceLevel> pl = Optional.ofNullable(bids.get(price));
        return pl.map(PriceLevel::getTotalVolume).orElse(0L);
    }
}