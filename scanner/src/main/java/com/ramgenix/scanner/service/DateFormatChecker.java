package com.ramgenix.scanner.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class DateFormatChecker {

    // Regular expression to check if a string contains alphabetic characters
    private static final Pattern ALPHABETIC_PATTERN = Pattern.compile(".*[a-zA-Z]+.*");

    /**
     * Checks if the given date is in the new format (yyyyMMdd) or old format (ddMMMyyyy).
     *
     * @param dateStr the date string to check
     * @return true if the date is in the new format (yyyyMMdd), false if in the old format (ddMMMyyyy)
     */
    public static boolean isNewDateFormat(String dateStr) {
        // Check if the length is 8 and contains no alphabetic characters
        if (dateStr.length() == 8 && !ALPHABETIC_PATTERN.matcher(dateStr).matches()) {
            return true;
        }
        return false;
    }
    
    public static LocalDate processDate(String date) {
        DateTimeFormatter oldFormatter = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("ddMMMyyyy")
                .toFormatter(Locale.ENGLISH);
        DateTimeFormatter newFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        String dateString = date;
        LocalDate localDate;
        LocalDate processingDate = null;

        if (isNewDateFormat(dateString)) {
            localDate = LocalDate.parse(dateString, newFormatter);
        } else {
            String lowercaseDateString = dateString.toLowerCase();
            localDate = LocalDate.parse(lowercaseDateString, oldFormatter);
        }

        return localDate;
    }

    
}
