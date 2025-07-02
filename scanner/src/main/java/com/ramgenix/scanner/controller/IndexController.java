package com.ramgenix.scanner.controller;

import java.sql.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.ramgenix.scanner.entity.Pattern;
import com.ramgenix.scanner.service.PatternService;

@Controller
public class IndexController {

    @Autowired
    private PatternService patternService;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("uniqueDates", patternService.getUniqueDates());
        return "index"; // This will look for index.html in the templates directory
    }
    
    @GetMapping("/patterns")
    public String getPatternsOfADay(@RequestParam String date, Model model) {
        List<String> patternNames = patternService.getUniquePatternNames(Date.valueOf(date));
        model.addAttribute("patternNames", patternNames);
        model.addAttribute("date", date);
        return "patternsofaday"; // Returns the template name without the extension
    }
    
    @GetMapping("/patternRecords")
    public String getPatternRecords(@RequestParam String date, @RequestParam String patternName, Model model) {
        List<Pattern> patternRecords = patternService.getPatternRecords(Date.valueOf(date), patternName);
        model.addAttribute("patternName", patternName);
        model.addAttribute("patternRecords", patternRecords);
        return "patternRecords"; // Thymeleaf template name
    }
}
