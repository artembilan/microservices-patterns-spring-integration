package org.springframework.integration.microservices.mrpc;

import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.dsl.HeaderEnricherSpec;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.support.channel.HeaderChannelRegistry;
import org.springframework.messaging.Message;

@SpringBootApplication
public class MrpcApplication {

	public static void main(String[] args) {
		SpringApplication.run(MrpcApplication.class, args);
	}

	@Bean
	IntegrationFlow requestFlow(StreamBridge streamBridge,
			@Value("${spring.cloud.stream.mrpc.request.destination}") String requests) {

		return IntegrationFlow.from(UpperCaseGateway.class)
				.enrichHeaders(HeaderEnricherSpec::headerChannelsToString)
				.handle((message) -> streamBridge.send(requests, message))
				.get();
	}

	@Bean
	IntegrationFlow repliesFlow(HeaderChannelRegistry channelRegistry) {
		return IntegrationFlow.from(MessageConsumer.class, gateway -> gateway.beanName("replies"))
				.filter(Message.class,
						(message) ->
								channelRegistry.channelNameToChannel(
										(message).getHeaders().getReplyChannel().toString()) != null)
				.get();
	}

	public interface MessageConsumer extends Consumer<Message<String>> {

	}

	public interface UpperCaseGateway {

		String toUpperCase(String payload);

	}

}
