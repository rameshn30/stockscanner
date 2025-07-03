package com.ramgenix.scanner.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ramgenix.scanner.service.DataInjestionServiceNSEImpl;
import com.ramgenix.scanner.service.DataInjestionServiceNSEImpl.Market;
import com.ramgenix.scanner.service.StockMasterService;

@RestController
@RequestMapping("/stockdata")
public class DataInjestionController {

	@Autowired
	DataInjestionServiceNSEImpl service;

	@Autowired
	private StockMasterService stockMasterService;

	@GetMapping("/loadStockMaster")
	public ResponseEntity<String> loadStockMaster(@RequestParam String filename) {
		try {
			stockMasterService.loadStockMasterData(filename);
			return ResponseEntity.ok("Stock master data loaded successfully from " + filename);
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body("Failed to load stock master data from " + filename + ": " + e.getMessage());
		}
	}

	@GetMapping("/process")
	@Transactional
	public ResponseEntity<String> processLatestData(
			@RequestParam(value = "watchlist", required = false) String watchlist,
			@RequestParam(value = "past", required = false) String past,
			@RequestParam(value = "market", required = false, defaultValue = "NSE") String market) {
		try {
			Market marketEnum;
			try {
				marketEnum = Market.valueOf(market.toUpperCase());
			} catch (IllegalArgumentException e) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST)
						.body("Invalid market: " + market + ". Use NSE or US.");
			}

			if (past != null && past.equals("past")) {
				service.processLatestDataForPast(marketEnum, watchlist);
			} else {
				service.processLatestData(marketEnum, watchlist);
			}
			return ResponseEntity.ok("Success");
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Processing failed: " + e.getMessage());
		}
	}

	@GetMapping
	public String hello() {
		return "Welcome";
	}

	@GetMapping("/welcome")
	public String welcome() {
		return "Welcome";
	}
}
