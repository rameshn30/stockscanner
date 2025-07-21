package com.ramgenix.scanner.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Entity
@Builder
public class Pattern {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Transient
	private StockData stockData;

	@Transient
	private Boolean isNFO;
	private String patternName;
	private String symbol;
	private LocalDate date;
	private BigDecimal close;
	private BigDecimal gapupPct;
	private BigDecimal changePct;
	private BigDecimal adr;
	private String country;
	@Transient
	private double rank;
	@Transient
	private String rankStr;
	@Transient
	private double maDistance;
	private double rangePct;
	@Transient
	private double head;
	@Transient
	private double tail;
	@Transient
	private double body0;
	@Transient
	private double body1;

	@Transient
	private BBValues bbValues;

	@Override
	public int hashCode() {
		return Objects.hash(patternName, symbol);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Pattern other = (Pattern) obj;
		return Objects.equals(patternName, other.patternName) && Objects.equals(symbol, other.symbol);
	}

}
