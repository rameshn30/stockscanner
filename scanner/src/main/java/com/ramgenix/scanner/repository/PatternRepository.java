package com.ramgenix.scanner.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ramgenix.scanner.entity.Pattern;

public interface PatternRepository extends JpaRepository<Pattern, Long> {

	// Custom query method to delete patterns by date
	void deleteByDate(LocalDate date);

	// You can also add a query method to find patterns by date if needed
	List<Pattern> findByDate(Date date);

	@Query("SELECT DISTINCT p.date FROM Pattern p ORDER BY p.date DESC")
	List<String> findUniqueDates();

	@Query("SELECT DISTINCT p.patternName FROM Pattern p WHERE p.date = :date")
	List<String> findUniquePatternNamesByDate(@Param("date") Date date1);

	List<Pattern> findByDateAndPatternName(Date date, String patternName);

	Optional<Pattern> findBySymbolAndPatternNameAndDate(String symbol, String patternName, LocalDate date);

	List<Pattern> findBySymbolAndPatternName(String symbol, String patternName);

	List<Pattern> findBySymbol(String symbol);

	@Query("SELECT DISTINCT p FROM Pattern p WHERE p.patternName = :patternName AND p.date BETWEEN :startDate AND :endDate ORDER BY p.date ASC")
	List<Pattern> findDistinctByPatternNameAndDateRange(@Param("patternName") String patternName,
			@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

	@Query("SELECT DISTINCT p FROM Pattern p WHERE p.patternName IN :patternNames AND p.date BETWEEN :startDate AND :endDate ORDER BY p.date ASC")
	List<Pattern> findDistinctByPatternNameInAndDateRange(@Param("patternNames") List<String> patternNames,
			@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

	@Query("SELECT DISTINCT p FROM Pattern p WHERE p.patternName IN :patternNames AND p.country = :country AND p.date BETWEEN :startDate AND :endDate ORDER BY p.date DESC")
	List<Pattern> findDistinctByPatternNameInAndCountryEqualsAndDateRange(
			@Param("patternNames") List<String> patternNames, @Param("country") String country,
			@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

}
