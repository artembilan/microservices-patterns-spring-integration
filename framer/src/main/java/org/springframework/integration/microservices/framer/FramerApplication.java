package org.springframework.integration.microservices.framer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.annotation.Splitter;
import org.springframework.integration.dsl.IntegrationFlowAdapter;
import org.springframework.integration.dsl.IntegrationFlowDefinition;
import org.springframework.integration.store.MessageGroupStore;
import org.springframework.integration.store.SimpleMessageStore;
import org.springframework.integration.support.MessageBuilder;

@SpringBootApplication
public class FramerApplication {

	public static void main(String[] args) {
		SpringApplication.run(FramerApplication.class, args);
	}

	@Bean
	MessageGroupStore messageGroupStore() {
		return new SimpleMessageStore();
	}

	@Bean
	Framer framer(MessageGroupStore messageGroupStore) {
		return new Framer(messageGroupStore);
	}

	public static class Framer extends IntegrationFlowAdapter {

		private final MessageGroupStore messageGroupStore;

		public Framer(MessageGroupStore messageGroupStore) {
			this.messageGroupStore = messageGroupStore;
		}

		@Override
		protected IntegrationFlowDefinition<?> buildFlow() {
			return from("framer.input")
					.split(this, "slidingWindows", (splitter) -> splitter.applySequence(false))
					.aggregate((aggregatorSpec) ->
							aggregatorSpec.messageStore(this.messageGroupStore)
									.expireGroupsUponCompletion(true))
					.channel((channels) -> channels.queue("framer.output"));
		}

		private final AtomicLong messageSequence = new AtomicLong();

		@Splitter
		public List<MessageBuilder<Object>> slidingWindows(Object payload) {
			Long correlationKey = this.messageSequence.getAndIncrement();
			return IntStream.range(0, 3)
					.boxed()
					.map((sequenceNumber) -> Map.entry(correlationKey - sequenceNumber, sequenceNumber))
					.filter((sequenceDetails) -> sequenceDetails.getKey() >= 0)
					.map((sequenceDetails) ->
							MessageBuilder.withPayload(payload)
									.setCorrelationId(sequenceDetails.getKey())
									.setSequenceNumber(sequenceDetails.getValue())
									.setSequenceSize(3))
					.toList();
		}

	}

}
