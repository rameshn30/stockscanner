package com.ramgenix.scanner.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Entity class representing the stock_master table.
 */
@Entity
@Table(name = "stock_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class StockMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Assumes an auto-increment primary key
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "ticker", nullable = false, unique = true, length = 100)
    private String ticker;

    @Column(name = "sub_sector", length = 100)
    private String subSector;

    @Column(name = "market_cap", nullable = false)
    private Double marketCap;

    @Column(name = "ttm_pe_ratio", nullable = false)
    private Double ttmPeRatio;

    @Column(name = "pb_ratio", nullable = false)
    private Double pbRatio;

    @Column(name = "dividend_yield", nullable = false)
    private Double dividendYield;

    @Column(name = "pe_ratio", nullable = false)
    private Double peRatio;

    // Default constructor, getters, setters, and toString are provided by Lombok

    // Additional logic or methods can be added here if needed
}
