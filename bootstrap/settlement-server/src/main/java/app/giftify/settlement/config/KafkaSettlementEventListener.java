package app.giftify.settlement.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.giftify.settlement.application.inbound.CancelSettlementCommand;
import app.giftify.settlement.application.inbound.CreateSettlementCommand;
import app.giftify.settlement.application.service.SettlementCancelService;
import app.giftify.settlement.application.service.SettlementItemService;
import app.giftify.settlement.domain.snapshot.OrderItemSnapshot;
import app.giftify.shared.domain.type.TargetType;
import app.giftify.shared.domain.vo.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaSettlementEventListener {

    private final SettlementItemService settlementItemService;
    private final SettlementCancelService settlementCancelService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.item-confirmed", groupId = "settlement-server")
    public void onOrderItemConfirmed(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());

            OrderItemSnapshot snapshot = new OrderItemSnapshot(
                    node.get("orderId").asLong(),
                    node.get("orderItemId").asLong(),
                    node.get("sellerId").asLong(),
                    node.get("targetId").asLong(),
                    TargetType.valueOf(node.get("targetType").asText()),
                    node.get("paymentId").asLong(),
                    Money.of(new BigDecimal(node.get("amount").asText())),
                    LocalDateTime.parse(node.get("confirmedAt").asText())
            );

            settlementItemService.create(new CreateSettlementCommand(snapshot));
            log.info("[Kafka] OrderItemConfirmed processed: orderItemId={}", snapshot.orderItemId());
        } catch (Exception e) {
            log.error("[Kafka] Failed to process OrderItemConfirmedEvent", e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(topics = "order.canceled", groupId = "settlement-server")
    public void onOrderCanceled(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());

            Long orderId = node.get("orderId").asLong();
            JsonNode itemsNode = node.get("items");
            List<Long> itemIds = new ArrayList<>();
            if (itemsNode != null && itemsNode.isArray()) {
                for (JsonNode item : itemsNode) {
                    itemIds.add(item.get("orderItemId").asLong());
                }
            }

            itemIds.forEach(itemId ->
                    settlementCancelService.cancel(new CancelSettlementCommand(orderId, itemId)));
            log.info("[Kafka] OrderCanceled processed: orderId={}, items={}", orderId, itemIds.size());
        } catch (Exception e) {
            log.error("[Kafka] Failed to process OrderCanceledEvent", e);
            throw new RuntimeException(e);
        }
    }
}
