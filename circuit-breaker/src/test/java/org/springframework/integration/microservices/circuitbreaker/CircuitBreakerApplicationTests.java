package org.springframework.integration.microservices.circuitbreaker;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.http.inbound.HttpRequestHandlingMessagingGateway;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class CircuitBreakerApplicationTests {

	@Autowired
	@Qualifier("restService")
	HttpRequestHandlingMessagingGateway restService;

	@Autowired
	@Qualifier("circuitBreaker.input")
	MessageChannel circuitBreakerInput;

	@Test
	void circuitBreakerInAction() throws InterruptedException {
		MessagingTemplate messagingTemplate = new MessagingTemplate();
		String result = messagingTemplate.convertSendAndReceive(this.circuitBreakerInput, "world", String.class);
		assertThat(result)
				.contains("The REST service is not available at the moment: ")
				.contains("RequestHandlerCircuitBreakerAdvice$CircuitBreakerOpenException");

		this.restService.start();

		// Even if service has already started, the Circuit Breaker is still in open state
		result = messagingTemplate.convertSendAndReceive(this.circuitBreakerInput, "world", String.class);
		assertThat(result).contains("The REST service is not available at the moment: ");

		// Block the thread a little to let the 'halfOpenAfter' interval to pass in Circuit Breaker
		Thread.sleep(1000);

		result = messagingTemplate.convertSendAndReceive(this.circuitBreakerInput, "world", String.class);
		assertThat(result).contains("Hello world");
	}

}
