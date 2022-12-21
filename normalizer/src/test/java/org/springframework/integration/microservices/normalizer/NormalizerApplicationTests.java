package org.springframework.integration.microservices.normalizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Properties;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.xml.transform.StringResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DirtiesContext
class NormalizerApplicationTests {

	@Autowired
	WebTestClient webTestClient;

	@Value("cardTransactionInput")
	File inputDir;

	@Value("cardTransactionOutput")
	File outputDir;

	@Autowired
	ObjectMapper objectMapper;

	CreditCardTransaction cardTransaction;

	@BeforeAll
	static void cleanup() throws IOException {
		FileUtils.cleanDirectory(new File("cardTransactionInput"));
		FileUtils.cleanDirectory(new File("cardTransactionOutput"));
	}

	@BeforeEach
	void setup() {
		this.cardTransaction = new CreditCardTransaction();
		this.cardTransaction.setId(2134L);
		this.cardTransaction.setCardNumber("4576-0120-5553-5675");
		this.cardTransaction.setTransactionDate(new Date());
		this.cardTransaction.setAmount(new BigDecimal("3456.97"));
		this.cardTransaction.setMerchant("amazon.com");
	}

	@Test
	void normalizeXml() {
		StringResult stringResult = new StringResult();
		Jaxb2Marshaller jaxb2Marshaller = new Jaxb2Marshaller();
		jaxb2Marshaller.setClassesToBeBound(CreditCardTransaction.class);
		jaxb2Marshaller.marshal(this.cardTransaction, stringResult);

		CreditCardTransaction creditCardTransaction =
				this.webTestClient.post()
						.uri("/normalize")
						.contentType(MediaType.APPLICATION_XML)
						.bodyValue(stringResult.toString())
						.exchange()
						.expectHeader().contentType(MediaType.APPLICATION_JSON)
						.expectBody(CreditCardTransaction.class)
						.returnResult()
						.getResponseBody();

		assertThat(creditCardTransaction).isEqualTo(this.cardTransaction);
	}

	@Test
	void normalizeCsv() {
		String csvRecord =
				String.valueOf(this.cardTransaction.getId()) +
						',' +
						this.cardTransaction.getCardNumber() +
						',' +
						this.cardTransaction.getTransactionDate().getTime() +
						',' +
						this.cardTransaction.getAmount() +
						',' +
						this.cardTransaction.getMerchant();

		CreditCardTransaction creditCardTransaction =
				this.webTestClient.post()
						.uri("/normalize")
						.contentType(MediaType.parseMediaType("text/csv"))
						.bodyValue(csvRecord)
						.exchange()
						.expectHeader().contentType(MediaType.APPLICATION_JSON)
						.expectBody(CreditCardTransaction.class)
						.returnResult()
						.getResponseBody();

		assertThat(creditCardTransaction).isEqualTo(this.cardTransaction);
	}

	@Test
	void normalizePropertiesFile() throws IOException {
		Properties cardTransactionProperties = new Properties();
		cardTransactionProperties.setProperty("card-transaction.id", "" + this.cardTransaction.getId());
		cardTransactionProperties.setProperty("card-transaction.card-number", this.cardTransaction.getCardNumber());
		cardTransactionProperties.setProperty("card-transaction.date", "" + this.cardTransaction.getTransactionDate().getTime());
		cardTransactionProperties.setProperty("card-transaction.amount", this.cardTransaction.getAmount().toPlainString());
		cardTransactionProperties.setProperty("card-transaction.merchant", this.cardTransaction.getMerchant());

		String fileName = "testCardTransaction";

		File propertiesFile = new File(this.inputDir, fileName + ".properties");

		try (FileOutputStream out = new FileOutputStream(propertiesFile)) {
			cardTransactionProperties.store(out, "The test card transaction");
		}

		File jsonFile = new File(this.outputDir, fileName + ".json");

		await().until(jsonFile::exists);

		CreditCardTransaction creditCardTransaction = this.objectMapper.readValue(jsonFile, CreditCardTransaction.class);
		assertThat(creditCardTransaction).isEqualTo(this.cardTransaction);
	}

}
