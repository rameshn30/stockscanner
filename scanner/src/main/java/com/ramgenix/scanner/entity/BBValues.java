package com.ramgenix.scanner.entity;

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
public class BBValues {
	double ma_10;
	double ma_20;
	double ma_50;
	double ma_200;
	double upper;
	double lower;
	double bodyAvg20;
	double fiftyTwoWeekHigh;
	double fiftyTwoWeekLow;

	double twentyMaAverage;
	double fiftyMaAverage;
}
