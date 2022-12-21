package org.springframework.integration.microservices.normalizer;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;

import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

@XmlType
@XmlRootElement(name = "card-transaction")
public class CreditCardTransaction {

	private Long id;

	private String cardNumber;

	private Date transactionDate;

	private BigDecimal amount;

	private String merchant;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getCardNumber() {
		return cardNumber;
	}

	public void setCardNumber(String cardNumber) {
		this.cardNumber = cardNumber;
	}

	public Date getTransactionDate() {
		return transactionDate;
	}

	public void setTransactionDate(Date transactionDate) {
		this.transactionDate = transactionDate;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public String getMerchant() {
		return merchant;
	}

	public void setMerchant(String merchant) {
		this.merchant = merchant;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		CreditCardTransaction that = (CreditCardTransaction) o;
		return Objects.equals(id, that.id)
				&& Objects.equals(cardNumber, that.cardNumber)
				&& Objects.equals(transactionDate, that.transactionDate)
				&& Objects.equals(amount, that.amount)
				&& Objects.equals(merchant, that.merchant);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, cardNumber, transactionDate, amount, merchant);
	}

}
