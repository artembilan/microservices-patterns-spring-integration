package org.springframework.integration.microservices.distributedtracing;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.tracing.Tracer;
import net.ttddyy.observation.boot.autoconfigure.DataSourceNameResolver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.handler.LoggingHandler;
import org.springframework.integration.jdbc.JdbcMessageHandler;
import org.springframework.integration.jms.dsl.Jms;
import org.springframework.integration.webflux.dsl.WebFlux;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.messaging.Message;

@SpringBootApplication
public class DistributedTracingApplication {

	private static final Log LOGGER = LogFactory.getLog(DistributedTracingApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(DistributedTracingApplication.class, args);
	}

	@Configuration
	protected static class ProducerService {

		@Bean
		public Publisher<Message<String>> httpSupplierFlow() {
			return IntegrationFlow.from(
							WebFlux.inboundChannelAdapter("/tracingService")
									.requestPayloadType(String.class)
									.requestMapping(mapping -> mapping.methods(HttpMethod.POST)))
					.log(LoggingHandler.Level.TRACE, DistributedTracingApplication.class.getPackageName(),
							message -> "HTTP request to trace: " + message)
					.toReactivePublisher(true);
		}

		@Bean
		public Supplier<Flux<Message<String>>> kinesisSupplier(Publisher<Message<String>> httpRequestPublisher) {
			return () ->
					Flux.from(httpRequestPublisher)
							.doOnNext(message -> LOGGER.trace("Send message to Kinesis: " + message));
		}

	}

	@Configuration
	protected static class ConsumerService {

		@Bean
		Executor executorWithPropagation() {
			return ContextExecutorService.wrap(Executors.newCachedThreadPool());
		}

		@Bean
		public IntegrationFlow consumerFlow(Executor executorWithPropagation,
				IntegrationFlow jdbcFlow, IntegrationFlow jmsFlow) {

			return IntegrationFlow.from(MessageConsumer.class, gateway -> gateway.beanName("kinesisConsumer"))
					.log(LoggingHandler.Level.TRACE, DistributedTracingApplication.class.getPackageName(),
							message -> "Received message from Kinesis: " + message)
					.publishSubscribeChannel(executorWithPropagation,
							pubSub -> pubSub
									.subscribe(jdbcFlow)
									.subscribe(jmsFlow))
					.get();
		}

		@Bean
		public IntegrationFlow jdbcFlow(JdbcTemplate jdbcTemplate, Tracer tracer) {
			return f -> f
					.log(LoggingHandler.Level.TRACE, DistributedTracingApplication.class.getPackageName(),
							message -> "Save message to DB: " + message)
					.enrichHeaders(headers -> headers
							.headerFunction("traceId", m -> tracer.currentSpan().context().traceId())
							.headerFunction("spanId", m -> tracer.currentSpan().context().spanId()))
					.handle(new JdbcMessageHandler(jdbcTemplate,
							"INSERT INTO traced_data(traceId, spanId, payload) " +
									"VALUES(:headers[traceId], :headers[spanId], :payload)"));

		}

		@Bean
		public IntegrationFlow jmsFlow(JmsTemplate jmsTemplate) {
			return IntegrationFlow.from("jmsChannel")
					.log(LoggingHandler.Level.TRACE, DistributedTracingApplication.class.getPackageName(),
							message -> "Send message to JMS: " + message)
					.handle(Jms.outboundAdapter(jmsTemplate).destination("SOME_QUEUE"))
					.get();
		}

		@Bean
		DataSourceNameResolver dataSourceNameResolver(ApplicationContext applicationContext) {
			return (beanName, dataSource) -> applicationContext.getId();
		}

		public interface MessageConsumer extends Consumer<Message<String>> {

		}

	}

}
