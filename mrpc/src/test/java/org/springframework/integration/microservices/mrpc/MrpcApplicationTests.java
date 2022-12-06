package org.springframework.integration.microservices.mrpc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.fn.spel.SpelFunctionConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("uppercase")
@SpringBootTest
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
class MrpcApplicationTests {

	private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq").withExposedPorts(5672);

	@BeforeAll
	static void startContainer() {
		RABBITMQ.start();
		System.setProperty("spring.rabbitmq.port", "" + RABBITMQ.getMappedPort(5672));
	}

	@Autowired
	MrpcApplication.UpperCaseGateway upperCaseGateway;

	@Test
	void mrpcInAction() {
		assertThat(this.upperCaseGateway.toUpperCase("hello world")).isEqualTo("HELLO WORLD");
		assertThat(this.upperCaseGateway.toUpperCase("mrpc in action")).isEqualTo("MRPC IN ACTION");
	}

	@TestConfiguration
	@Import(SpelFunctionConfiguration.class)
	public static class TransformerProcessorConfiguration {

	}

}
