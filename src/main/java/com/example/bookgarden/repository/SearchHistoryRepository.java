package com.example.bookgarden.repository;

import com.example.bookgarden.entity.SearchHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

public interface SearchHistoryRepository extends MongoRepository<SearchHistory, String> {
    List<SearchHistory> findByUserId(String userId);

    Optional<SearchHistory> findByUserIdAndSearchQuery(String userId, String searchQuery);
    List<SearchHistory> findByUserIdOrderBySearchDateDesc(String userId, Pageable pageable);
}
