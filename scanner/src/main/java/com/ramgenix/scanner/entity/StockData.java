package com.ramgenix.scanner.entity;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
@ToString
@Entity
public class StockData {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String symbol;
	private String series;
	private double open;
	private double high;
	private double low;
	private double close;
	private double volume;
	String swingType;
	String purpleDotType;

	@Transient
	private double ma10;
	@Transient
	private double ma20;
	@Transient
	private double ma50;

	@Transient
	private LocalDate date; // Using LocalDate for better date representation
}
