package org.springframework.integration.microservices.distributedtracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.TracingObservationHandler;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.integration.metadata.MetadataStore;
import org.springframework.integration.metadata.SimpleMetadataStore;
import org.springframework.integration.support.locks.DefaultLockRegistry;
import org.springframework.integration.support.locks.LockRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Testcontainers(disabledWithoutDocker = true)
class DistributedTracingApplicationTests {

	@Container
	static LocalStackContainer LOCAL_STACK_CONTAINER =
			new LocalStackContainer(DockerImageName.parse("localstack/localstack:2.0.0"));

	@Autowired
	ObservationRegistry observationRegistry;

	@Autowired
	WebClient.Builder webClient;

	@LocalServerPort
	int port;

	@Autowired
	JmsTemplate jmsTemplate;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.cloud.aws.region.static", LOCAL_STACK_CONTAINER::getRegion);
	}

	@Test
	void distributedTracingInAction() throws JMSException {
		String testPayload = "test data";

		Observation parentObservation = Observation
				.createNotStarted("test-parent-observation", this.observationRegistry)
				.lowCardinalityKeyValue("junit.test", DistributedTracingApplicationTests.class.getName())
				.start();

		Mono<HttpStatusCode> responseMono =
				this.webClient.build()
						.post()
						.uri("http://localhost:{port}/tracingService", this.port)
						.bodyValue(testPayload)
						.retrieve()
						.toBodilessEntity()
						.map(ResponseEntity::getStatusCode)
						.contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, parentObservation))
						.doOnError(parentObservation::error)
						.doOnSuccess(r -> parentObservation.stop());

		StepVerifier.create(responseMono)
				.expectNext(HttpStatus.OK)
				.verifyComplete();

		this.jmsTemplate.setReceiveTimeout(60_000);
		Message receive = this.jmsTemplate.receive("SOME_QUEUE");
		assertThat(receive).isNotNull();
		String traceparent = receive.getStringProperty("traceparent");
		assertThat(traceparent).isNotNull();
		assertThat(receive.getBody(String.class)).isEqualTo(testPayload);

		String traceId =
				((TracingObservationHandler.TracingContext) parentObservation.getContextView()
						.getRequired(TracingObservationHandler.TracingContext.class))
						.getSpan()
						.context()
						.traceId();

		assertThat(traceparent).contains(traceId);

		String payload =
				this.jdbcTemplate.queryForObject("SELECT payload FROM traced_data WHERE traceId = ?",
						String.class, traceId);

		assertThat(payload).isEqualTo(testPayload);
	}

	@TestConfiguration
	public static class KinesisBinderServicesConfiguration {

		@Bean
		LockRegistry lockRegistry() {
			return new DefaultLockRegistry();
		}

		@Bean
		MetadataStore metadataStore() {
			return new SimpleMetadataStore();
		}

		@Bean
		KinesisAsyncClient kinesisClient() {
			return KinesisAsyncClient.builder()
					.region(Region.of(LOCAL_STACK_CONTAINER.getRegion()))
					.credentialsProvider(credentialsProvider())
					.endpointOverride(LOCAL_STACK_CONTAINER.getEndpointOverride(LocalStackContainer.Service.KINESIS))
					.build();
		}

		private static AwsCredentialsProvider credentialsProvider() {
			return StaticCredentialsProvider.create(
					AwsBasicCredentials.create(LOCAL_STACK_CONTAINER.getAccessKey(),
							LOCAL_STACK_CONTAINER.getSecretKey()));
		}

	}

}
