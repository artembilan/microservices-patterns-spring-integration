package org.springframework.integration.microservices.circuitbreaker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlowAdapter;
import org.springframework.integration.dsl.IntegrationFlowDefinition;
import org.springframework.integration.handler.advice.ErrorMessageSendingRecoverer;
import org.springframework.integration.handler.advice.RequestHandlerCircuitBreakerAdvice;
import org.springframework.integration.handler.advice.RequestHandlerRetryAdvice;
import org.springframework.integration.http.dsl.Http;
import org.springframework.integration.http.dsl.HttpMessageHandlerSpec;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.AlwaysRetryPolicy;
import org.springframework.retry.support.RetryTemplateBuilder;

@SpringBootApplication
public class CircuitBreakerApplication {

	public static void main(String[] args) {
		SpringApplication.run(CircuitBreakerApplication.class, args);
	}

	@Configuration(proxyBeanMethods = false)
	public static class RemoteServiceConfiguration {

		@Bean
		IntegrationFlow restServiceFlow() {
			return IntegrationFlow.from(
							Http.inboundGateway("/service")
									.id("restService")
									.requestMapping((request) -> request.methods(HttpMethod.GET))
									.payloadExpression("#requestParams.name[0]")
									.autoStartup(false))
					.transform(String.class, "Hello "::concat)
					.get();
		}

	}

	@Bean
	MessageChannel recoveryChannel() {
		return new DirectChannel();
	}

	@ServiceActivator(inputChannel = "recoveryChannel")
	Message<String> fallbackService(Message<MessagingException> errorMessage) {
		return MessageBuilder
				.withPayload("The REST service is not available at the moment: " + errorMessage.getPayload().getCause())
				.copyHeaders(errorMessage.getPayload().getFailedMessage().getHeaders())
				.build();
	}

	@Bean
	HttpMessageHandlerSpec httpService(Environment environment) {
		return Http.outboundGateway((m) -> environment.getProperty("rest-service.url"))
				.uriVariable("name", "payload")
				.httpMethod(HttpMethod.GET)
				.expectedResponseType(String.class);
	}

	@Bean
	CircuitBreaker circuitBreaker(@Qualifier("httpService") MessageHandler service,
			@Qualifier("recoveryChannel") MessageChannel recoveryChannel) {

		CircuitBreaker circuitBreaker = new CircuitBreaker(service);
		circuitBreaker.setThreshold(3);
		circuitBreaker.setHalfOpenAfter(500);
		circuitBreaker.setRecoveryChannel(recoveryChannel);
		circuitBreaker.setRetryPolicy(new AlwaysRetryPolicy());
		FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
		backOffPolicy.setBackOffPeriod(100L);
		circuitBreaker.setBackOffPolicy(backOffPolicy);
		return circuitBreaker;
	}

	public static class CircuitBreaker extends IntegrationFlowAdapter {

		private final RetryTemplateBuilder retryTemplateBuilder =
				new RetryTemplateBuilder()
						.notRetryOn(RequestHandlerCircuitBreakerAdvice.CircuitBreakerOpenException.class)
						.traversingCauses();

		private final RequestHandlerRetryAdvice retryAdvice = new RequestHandlerRetryAdvice();

		private final RequestHandlerCircuitBreakerAdvice circuitBreakerAdvice = new RequestHandlerCircuitBreakerAdvice();

		private final MessageHandler service;

		public CircuitBreaker(MessageHandler service) {
			this.service = service;
		}

		public void setThreshold(int threshold) {
			this.circuitBreakerAdvice.setThreshold(threshold);
		}

		public void setHalfOpenAfter(long halfOpenAfter) {
			this.circuitBreakerAdvice.setHalfOpenAfter(halfOpenAfter);
		}

		public void setRetryPolicy(RetryPolicy retryPolicy) {
			this.retryTemplateBuilder.customPolicy(retryPolicy);
		}

		public void setBackOffPolicy(BackOffPolicy backOffPolicy) {
			this.retryTemplateBuilder.customBackoff(backOffPolicy);
		}

		public void setRecoveryChannel(MessageChannel recoveryChannel) {
			this.retryAdvice.setRecoveryCallback(new ErrorMessageSendingRecoverer(recoveryChannel));
		}

		@Override
		protected IntegrationFlowDefinition<?> buildFlow() {
			this.retryAdvice.setRetryTemplate(this.retryTemplateBuilder.build());

			return from("circuitBreaker.input")
					.handle(this.service,
							(endpoint) -> endpoint.advice(this.retryAdvice, this.circuitBreakerAdvice));
		}

	}

}
