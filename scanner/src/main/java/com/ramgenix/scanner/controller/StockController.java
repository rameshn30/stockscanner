package com.ramgenix.scanner.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ramgenix.scanner.dto.SearchCriteria;
import com.ramgenix.scanner.entity.Stock;
import com.ramgenix.scanner.service.DataInjestionServiceNSEImpl;
import com.ramgenix.scanner.service.StockService;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

	@Autowired
	private StockService stockService;

	@Autowired
	DataInjestionServiceNSEImpl service;

	@GetMapping("/{pattern}")
	@Transactional
	public List<Stock> getStocksByPattern(@PathVariable String pattern) {
		return service.getStocksByPattern(pattern);
	}

	@PostMapping("/search")
	public List<Stock> searchStocks(@RequestBody SearchCriteria criteria) {
		return service.findStocksByPatternAndCriteria(criteria.getPattern(), criteria.getAdr(), criteria.getMinPrice(),
				criteria.getMaxPrice(), criteria.getMinVolume());
	}
}