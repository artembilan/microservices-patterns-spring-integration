package org.springframework.integration.microservices.framer;

import java.util.List;
import java.util.Random;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.integration.store.MessageGroupStore;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.support.GenericMessage;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FramerApplicationTests {

	@Autowired
	@Qualifier("framer.input")
	MessageChannel framerInput;

	@Autowired
	@Qualifier("framer.output")
	PollableChannel framerOutput;

	@Autowired
	MessageGroupStore messageGroupStore;

	@Test
	void framerIsSlidingDataIntoWindows() {
		List<Integer> prices =
				new Random()
						.ints(5, 90, 120)
						.boxed()
						.toList();

		prices.forEach((price) -> this.framerInput.send(new GenericMessage<>(price)));

		for (int sequence = 0; sequence < 3; sequence++) {
			Message<?> receive = this.framerOutput.receive(10_000);
			assertThat(receive).isNotNull()
					.satisfies(System.out::println)
					.extracting(Message::getPayload)
					.asInstanceOf(InstanceOfAssertFactories.LIST)
					.containsExactly(prices.get(sequence), prices.get(sequence + 1), prices.get(sequence + 2));
		}

		assertThat(this.framerOutput.receive(100)).isNull();

		// Two groups for [P4, P5] and for [P5]
		assertThat(this.messageGroupStore.getMessageGroupCount()).isEqualTo(2);
	}

}
