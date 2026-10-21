import java.util.*;
import java.time.Instant;

public class MatchingEngine {
    private final OrderBook orderBook;
    private long prevOrderId;

    public MatchingEngine(OrderBook orderBook) {
        this.orderBook = orderBook;
        prevOrderId = 0;
    }

    Order createOrder(long orderId, Side side, long price, long quantity, OrderType orderType, Instant timestampInstant) {

        return new Order(orderId, side, price, quantity, timestampInstant, orderType);
    }

    Order createOrder(long orderId, OrderRequest orderRequest, Instant timeStampInstant) {

        return createOrder(orderId, orderRequest.getSide(), orderRequest.getPrice(), orderRequest.getQuantity(), orderRequest.getOrderType(), timeStampInstant);
    }

    // Entry point 1.
    public Response submitNewOrderCommand(NewOrderCommand command) {
        
        long orderId = command.getOrderId();
        if (orderId <= prevOrderId) {
            throw new IllegalStateException("ILLEGAL STATE: order ids are not in order");
        }
        prevOrderId = orderId;
        
        return process(createOrder(orderId, command.getOrderRequest(), command.getTimeStamp()));
    }

    // Entry point 2.
    public CancelResult submitCancelOrderCommand(CancelOrderCommand command) {
        return orderBook.cancel(command.getOrderId());        
    }

    Response process(Order incomingOrder) {
        List<Trade> trades = new ArrayList<>();
        Side restingSide = (incomingOrder.getSide() == Side.BUY) ? Side.SELL : Side.BUY;
        OrderStatus orderStatus;
        long remainingQuantity;
        boolean canBeFilled = true;
        boolean canBeRested = true;
        Optional<PriceLevel> restingLevelOpt;

        // If FOK
        if (incomingOrder.getOrderType() == OrderType.FOK) {
            canBeFilled = canBeFilled(incomingOrder);
        }

        // If POST_ONLY.
        if (incomingOrder.getOrderType() == OrderType.POST_ONLY) {
            if ((restingLevelOpt = orderBook.bestLevel(restingSide)).isPresent()
                && crosses(incomingOrder, restingLevelOpt.get()))
            canBeRested = false;
        }

        while (incomingOrder.getRemainingQuantity() > 0
                && (restingLevelOpt = orderBook.bestLevel(restingSide)).isPresent()
                && crosses(incomingOrder, restingLevelOpt.get())
                && (canBeFilled)
                && (incomingOrder.getOrderType() != OrderType.POST_ONLY)) {

            Trade trade = match(incomingOrder, restingLevelOpt.get());
            trades.add(trade);
        }

        remainingQuantity = incomingOrder.getRemainingQuantity();
        orderStatus = switch (incomingOrder.getOrderType()) {
            case LIMIT -> {
                if (remainingQuantity > 0) {
                    orderBook.addOrderToBook(incomingOrder);
                    yield OrderStatus.RESTED;
                }
                yield OrderStatus.FILLED;
            }
            case FOK -> {
                if (canBeFilled) {
                    if (remainingQuantity != 0) {
                        throw new IllegalStateException("ILLEGAL STATE: Fillable FOK not completely filled");
                    }
                    yield OrderStatus.FILLED;
                }
                yield OrderStatus.CANCELLED;
            }
            case IOC, MARKET -> (remainingQuantity > 0) ? OrderStatus.CANCELLED : OrderStatus.FILLED;
            case POST_ONLY -> {
                if (canBeRested) {
                    orderBook.addOrderToBook(incomingOrder);
                    yield OrderStatus.RESTED;
                }
                yield OrderStatus.REJECTED;
            }
        };


        return new Response(incomingOrder.getOrderId(), trades, orderStatus, remainingQuantity);
    }

    // For FOK.
    boolean canBeFilled(Order incomingOrder) {

        Side incomingSide = incomingOrder.getSide();
        Iterator<PriceLevel> it = (incomingSide == Side.BUY) ? orderBook.askPriceLevelIterator() : orderBook.bidPriceLevelIterator();
        long remainingQuantity = incomingOrder.getOriginalQuantity();
        
        while (it.hasNext()) {
            PriceLevel restingPL = it.next();

            // Price does not cross.
            if (!crosses(incomingOrder, restingPL)) {
                return false;
            }

            remainingQuantity -= restingPL.getTotalVolume();
            // Quantity has been filled.
            if (remainingQuantity <= 0) {
                return true;
            }
        }

        return false;
    }

    Trade match(Order incomingOrder, PriceLevel restingPL) {

        // Protect empty restingPL.

        // Store reference of the resting order and order IDs.
        Order restingOrder = restingPL.peekFirstOrder();
        long buyOrderId, sellOrderId;

        // Update the resting order.
        // quantity is calculated at the source - fillHeadOrder() method.
        long quantity = restingPL.fillHeadOrder(incomingOrder.getRemainingQuantity());

        // Update the incoming order.
        incomingOrder.fill(quantity);

        if (restingOrder.getRemainingQuantity() == 0) {
            orderBook.removeOrderFromBook(restingOrder.getOrderId());
        }

        // Identify BUY and SELL order.
        if (incomingOrder.getSide() == Side.BUY) {
            buyOrderId = incomingOrder.getOrderId();
            sellOrderId = restingOrder.getOrderId();
        }
        else {
            buyOrderId = restingOrder.getOrderId();
            sellOrderId = incomingOrder.getOrderId();
        }

        // Construct the Trade.
        Trade trade = new Trade(quantity, restingPL.getPrice(), buyOrderId, sellOrderId, incomingOrder.getTimestamp());

        // Return the Trade object.
        return trade;
    }

    boolean crosses(Order incomingOrder, PriceLevel restingPL) {

        // For MARKET orders.
        if (incomingOrder.getOrderType() == OrderType.MARKET) {
            return true;
        }

        Side restingSide = (incomingOrder.getSide() == Side.BUY) ? Side.SELL : Side.BUY;

        return crosses(incomingOrder.getPrice(), restingPL.getPrice(), restingSide);
    }

    boolean crosses(long incomingPrice, long restingPrice, Side restingSide) {

        if (restingSide == Side.BUY) {
            return (incomingPrice <= restingPrice);
        }
        else {
            return (incomingPrice >= restingPrice);
        }
    }

    // Methods required for testing.
    
    // Open only for testing. Specially to query on the status of a specific order which will not
    // be accessible through any other means. Remove afterward.
    public Response submit(Order incomingOrder) {

        return process(incomingOrder);
    }
}
