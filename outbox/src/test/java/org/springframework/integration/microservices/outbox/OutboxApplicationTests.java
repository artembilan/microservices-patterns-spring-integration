package org.springframework.integration.microservices.outbox;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.test.context.EmbeddedKafka;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EmbeddedKafka(bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class OutboxApplicationTests {

	@Autowired
	OutboxApplication.OrderGateway orderGateway;

	@Autowired
	EntityManager entityManager;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	BlockingQueue<ShoppingOrder> consumedOrders;

	@Test
	void verifyOutboxPatternOutputs() throws InterruptedException {
		ShoppingOrder testOrder = new ShoppingOrder();
		testOrder.setName("test order");
		testOrder.setAmount(new BigDecimal("124.31"));
		orderGateway.placeOrder(testOrder);

		ShoppingOrder shoppingOrder = this.consumedOrders.poll(10, TimeUnit.SECONDS);
		assertThat(shoppingOrder)
				.hasFieldOrPropertyWithValue("name", testOrder.getName())
				.hasFieldOrPropertyWithValue("amount", testOrder.getAmount())
				.extracting("id")
				.isNotNull();


		List<ShoppingOrder> queryResult =
				this.entityManager.createQuery("SELECT o FROM ShoppingOrder o", ShoppingOrder.class)
						.getResultList();

		assertThat(queryResult).hasSize(1)
				.containsExactly(shoppingOrder);

		assertThat(this.jdbcTemplate.queryForObject("SELECT COUNT(MESSAGE_ID) FROM int_channel_message", Integer.class))
				.isEqualTo(0);
	}

	@TestConfiguration
	public static class KafkaListenerConfiguration {

		private final BlockingQueue<ShoppingOrder> consumedOrders = new LinkedBlockingQueue<>();

		@Bean
		BlockingQueue<ShoppingOrder> consumedOrders() {
			return this.consumedOrders;
		}


		@KafkaListener(topics = "orders", groupId = "ordersGroup")
		void consumeOrder(ShoppingOrder order) {
			this.consumedOrders.offer(order);
		}

	}

}
