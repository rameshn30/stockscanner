package com.ramgenix.scanner.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ramgenix.scanner.entity.Pattern;
import com.ramgenix.scanner.service.PatternService;

import java.sql.Date;
import java.util.List;

@RestController
@RequestMapping("/patterns")
public class PatternController {

    @Autowired
    private PatternService patternService;

    @GetMapping("/unique-dates")
    public List<String> getUniqueDates() {
        return patternService.getUniqueDates();
    }

    @GetMapping("/unique-pattern-names/{date}")
    public List<String> getUniquePatternNames(@PathVariable String date) {
    	Date date1 = Date.valueOf(date);
        return patternService.getUniquePatternNames(date1);
    }

    @GetMapping("/pattern-records/by-date-and-name")
    public List<Pattern> getPatternRecords(@RequestParam String dateString, @RequestParam String patternName) {
    	Date date = Date.valueOf(dateString);
        return patternService.getPatternRecords(date, patternName);
    }
}
