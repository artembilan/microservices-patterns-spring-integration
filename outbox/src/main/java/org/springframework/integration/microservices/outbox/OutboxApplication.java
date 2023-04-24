package org.springframework.integration.microservices.outbox;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.annotation.Gateway;
import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.dsl.IntegrationFlowAdapter;
import org.springframework.integration.dsl.IntegrationFlowDefinition;
import org.springframework.integration.jdbc.store.JdbcChannelMessageStore;
import org.springframework.integration.jdbc.store.channel.H2ChannelMessageStoreQueryProvider;
import org.springframework.integration.jpa.dsl.Jpa;
import org.springframework.integration.jpa.dsl.JpaUpdatingOutboundEndpointSpec;
import org.springframework.integration.jpa.outbound.JpaOutboundGateway;
import org.springframework.integration.jpa.support.PersistMode;
import org.springframework.integration.kafka.dsl.Kafka;
import org.springframework.integration.kafka.dsl.KafkaProducerMessageHandlerSpec;
import org.springframework.integration.kafka.outbound.KafkaProducerMessageHandler;
import org.springframework.integration.store.ChannelMessageStore;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.MessageHandler;

@SpringBootApplication
public class OutboxApplication {

	public static void main(String[] args) {
		SpringApplication.run(OutboxApplication.class, args);
	}

	@Bean
	JpaUpdatingOutboundEndpointSpec ordersJpaHandler(EntityManager entityManager) {
		return Jpa.outboundAdapter(entityManager).persistMode(PersistMode.PERSIST);
	}

	@Bean
	KafkaProducerMessageHandlerSpec<?, ?, ?> ordersKafkaProducer(KafkaTemplate<?, ?> kafkaTemplate) {
		return Kafka.outboundChannelAdapter(kafkaTemplate).topic("orders");
	}

	@Bean
	JdbcChannelMessageStore jdbcChannelMessageStore(DataSource dataSource) {
		JdbcChannelMessageStore jdbcChannelMessageStore = new JdbcChannelMessageStore(dataSource);
		jdbcChannelMessageStore.setChannelMessageStoreQueryProvider(new H2ChannelMessageStoreQueryProvider());
		return jdbcChannelMessageStore;
	}

	@Bean
	Outbox outbox(JpaOutboundGateway ordersJpaHandler, KafkaProducerMessageHandler<?, ?> kafkaMessageHandler,
			ChannelMessageStore channelMessageStore) {

		return new Outbox(ordersJpaHandler, kafkaMessageHandler, channelMessageStore);
	}

	@MessagingGateway
	public interface OrderGateway {

		@Gateway(requestChannel = "outbox.input")
		void placeOrder(ShoppingOrder order);

	}

	public static class Outbox extends IntegrationFlowAdapter {

		private final MessageHandler businessDataHandler;

		private final MessageHandler messagePublisherHandler;

		private final ChannelMessageStore channelMessageStore;


		public Outbox(MessageHandler businessDataHandler, MessageHandler messagePublisherHandler,
				ChannelMessageStore channelMessageStore) {

			this.businessDataHandler = businessDataHandler;
			this.messagePublisherHandler = messagePublisherHandler;
			this.channelMessageStore = channelMessageStore;
		}

		@Override
		protected IntegrationFlowDefinition<?> buildFlow() {
			return from("outbox.input")
					.routeToRecipients(routes -> routes
							.transactional()
							.recipientFlow(businessData -> businessData.handle(this.businessDataHandler))
							.recipientFlow(messagingMiddleware -> messagingMiddleware
									.channel(c -> c.queue(this.channelMessageStore, "outbox"))
									.handle(this.messagePublisherHandler,
											e -> e.poller(poller -> poller.fixedDelay(1000).transactional()))));
		}

	}

}
