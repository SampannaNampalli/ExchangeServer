import java.util.*;

public record Response(long orderId, List<Trade> trades, OrderStatus orderStatus, long remainingQuantity) {}
