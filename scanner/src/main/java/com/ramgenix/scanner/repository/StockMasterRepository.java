package com.ramgenix.scanner.repository;

import com.ramgenix.scanner.entity.StockMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for StockMaster entity.
 */
@Repository
public interface StockMasterRepository extends JpaRepository<StockMaster, Long> {

	 // Custom method to find a StockMaster by its Ticker
    Optional<StockMaster> findByTicker(String ticker);

    /**
     * Find all StockMaster entities that belong to a specific sub-sector.
     *
     * @param subSector the sub-sector name
     * @return the list of StockMaster entities in the given sub-sector
     */
    List<StockMaster> findBySubSector(String subSector);

    /**
     * Find all StockMaster entities with a market cap greater than a specified value.
     *
     * @param marketCap the minimum market cap
     * @return the list of StockMaster entities with a market cap greater than the specified value
     */
    List<StockMaster> findByMarketCapGreaterThan(Double marketCap);

    /**
     * Find all StockMaster entities with a PE ratio within a specific range.
     *
     * @param minPeRatio the minimum PE ratio
     * @param maxPeRatio the maximum PE ratio
     * @return the list of StockMaster entities within the specified PE ratio range
     */
    List<StockMaster> findByPeRatioBetween(Double minPeRatio, Double maxPeRatio);


}
