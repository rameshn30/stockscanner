package com.ramgenix.scanner.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ramgenix.scanner.entity.Pattern;
import com.ramgenix.scanner.repository.PatternRepository;

import java.sql.Date;
import java.util.List;

@Service
public class PatternService {

    @Autowired
    private PatternRepository patternRepository;

    public List<String> getUniqueDates() {
        return patternRepository.findUniqueDates();
    }

    public List<String> getUniquePatternNames(Date date1) {
        return patternRepository.findUniquePatternNamesByDate(date1);
    }

    public List<Pattern> getPatternRecords(Date date, String patternName) {
        return patternRepository.findByDateAndPatternName(date, patternName);
    }
}
