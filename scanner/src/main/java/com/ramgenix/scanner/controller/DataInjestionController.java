package com.ramgenix.scanner.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ramgenix.scanner.service.DataInjestionServiceNSEImpl;
import com.ramgenix.scanner.service.StockMasterService;

@RestController
@RequestMapping("/stockdata")
public class DataInjestionController {
	
	@Autowired
	DataInjestionServiceNSEImpl service;
	
	@Autowired
    private StockMasterService stockMasterService;
	
	@GetMapping("/loadStockMaster")
    public String loadStockMaster(@RequestParam String filename) {
        try {
            stockMasterService.loadStockMasterData(filename);
            return "Stock master data loaded successfully from " + filename;
        } catch (Exception e) {
            // Log the exception and return a meaningful error message
            e.printStackTrace();
            return "Failed to load stock master data from " + filename + ": " + e.getMessage();
        }
    }
	
	@GetMapping("/process")
	@Transactional
	public String processLatestData(@RequestParam(value = "watchlist", required = false) String watchlist , @RequestParam(value = "past", required = false) String past) {
	    if (watchlist != null) {
	        service.processLatestData(watchlist);
	    } else if(past!=null && past.equals("past")) {
	    	service.processLatestDataForPast();
	    }
	    else {
	    	service.processLatestData();
	    }
	    return "Success";
	}

	
	
	@GetMapping()
	public String hello() {
		return "Welcome";
	}
	
	@GetMapping("/welcome")
	public String welcome() {
		return "Welcome";
	}

}
