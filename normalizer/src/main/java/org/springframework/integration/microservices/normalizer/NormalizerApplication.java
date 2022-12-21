package org.springframework.integration.microservices.normalizer;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlowAdapter;
import org.springframework.integration.dsl.IntegrationFlowDefinition;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.file.FileHeaders;
import org.springframework.integration.file.dsl.Files;
import org.springframework.integration.handler.LoggingHandler;
import org.springframework.integration.http.support.DefaultHttpHeaderMapper;
import org.springframework.integration.support.json.Jackson2JsonObjectMapper;
import org.springframework.integration.webflux.dsl.WebFlux;
import org.springframework.integration.xml.transformer.UnmarshallingTransformer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.util.StringUtils;

@SpringBootApplication
public class NormalizerApplication {

	public static void main(String[] args) {
		SpringApplication.run(NormalizerApplication.class, args);
	}

	@Bean
	IntegrationFlow normalizerRest() {
		DefaultHttpHeaderMapper headerMapper = DefaultHttpHeaderMapper.outboundMapper();
		headerMapper.setExcludedOutboundStandardRequestHeaderNames(MessageHeaders.CONTENT_TYPE);

		return IntegrationFlow.from(WebFlux.inboundGateway("/normalize")
						.requestMapping(mapping -> mapping.methods(HttpMethod.POST))
						.requestPayloadType(String.class)
						.headerMapper(headerMapper))
				.channel("normalizer.input")
				.get();
	}

	@Bean
	IntegrationFlow normalizerFilesInput(@Value("cardTransactionInput") File inputDir) {
		return IntegrationFlow.from(Files.inboundAdapter(inputDir),
						endpoint -> endpoint.poller(poller -> poller.fixedDelay(1000, 1000)))
				.<File, Properties>transform(payload -> {
					try {
						return PropertiesLoaderUtils.loadProperties(new FileSystemResource(payload));
					}
					catch (IOException ex) {
						throw new UncheckedIOException(ex);
					}
				})
				.enrichHeaders(
						Map.of(MessageHeaders.CONTENT_TYPE, "application/properties",
								MessageHeaders.REPLY_CHANNEL, "normalizerFilesOutput.input"))
				.channel("normalizer.input")
				.get();
	}

	@Bean
	IntegrationFlow normalizerFilesOutput(@Value("cardTransactionOutput") File outputDir) {
		return f -> f
				.handle(Files.outboundAdapter(outputDir)
						.fileNameGenerator(message ->
								message.getHeaders().get(FileHeaders.FILENAME, String.class).split("\\.")[0] + ".json"));
	}

	@Bean
	Normalizer normalizer(ObjectMapper objectMapper) {
		return new Normalizer(objectMapper);
	}


	public static class Normalizer extends IntegrationFlowAdapter implements BeanClassLoaderAware, InitializingBean {

		private final ObjectMapper objectMapper;

		private final Jaxb2Marshaller jaxb2Marshaller = new Jaxb2Marshaller();

		public Normalizer(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
		}

		@Override
		public void setBeanClassLoader(ClassLoader classLoader) {
			this.jaxb2Marshaller.setBeanClassLoader(classLoader);
		}

		@Override
		public void afterPropertiesSet() throws Exception {
			this.jaxb2Marshaller.setClassesToBeBound(CreditCardTransaction.class);
			this.jaxb2Marshaller.afterPropertiesSet();
		}

		@Override
		protected IntegrationFlowDefinition<?> buildFlow() {
			return from("normalizer.input")
					.log(LoggingHandler.Level.DEBUG, "org.springframework.integration.microservices.normalizer", "payload")
					.route(Message.class, m -> m.getHeaders().get(MessageHeaders.CONTENT_TYPE),
							routeMapping -> routeMapping
									.subFlowMapping(MediaType.APPLICATION_XML_VALUE, xmlToJson())
									.subFlowMapping("text/csv", csvToJson())
									.subFlowMapping("application/properties", propertiesToJson()));
		}

		private IntegrationFlow xmlToJson() {
			return f -> f.transform(new UnmarshallingTransformer(this.jaxb2Marshaller));
		}

		private IntegrationFlow csvToJson() {
			return f -> f
					.<String, CreditCardTransaction>transform(payload -> {
						String[] attributes = StringUtils.commaDelimitedListToStringArray(payload);
						CreditCardTransaction cardTransaction = new CreditCardTransaction();
						cardTransaction.setId(Long.parseLong(attributes[0]));
						cardTransaction.setCardNumber(attributes[1]);
						cardTransaction.setTransactionDate(new Date(Long.parseLong(attributes[2])));
						cardTransaction.setAmount(new BigDecimal(attributes[3]));
						cardTransaction.setMerchant(attributes[4]);
						return cardTransaction;
					});
		}

		private IntegrationFlow propertiesToJson() {
			return f -> f
					.<Properties, CreditCardTransaction>transform(payload -> {
						CreditCardTransaction cardTransaction = new CreditCardTransaction();
						cardTransaction.setId(Long.parseLong(payload.getProperty("card-transaction.id")));
						cardTransaction.setCardNumber(payload.getProperty("card-transaction.card-number"));
						cardTransaction.setTransactionDate(new Date(Long.parseLong(payload.getProperty("card-transaction.date"))));
						cardTransaction.setAmount(new BigDecimal(payload.getProperty("card-transaction.amount")));
						cardTransaction.setMerchant(payload.getProperty("card-transaction.merchant"));
						return cardTransaction;
					})
					.transform(Transformers.toJson(new Jackson2JsonObjectMapper(this.objectMapper)));
		}

	}

}
