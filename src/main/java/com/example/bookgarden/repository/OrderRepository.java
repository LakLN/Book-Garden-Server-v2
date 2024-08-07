package com.example.bookgarden.repository;

import com.example.bookgarden.dto.CustomerOrderCount;
import com.example.bookgarden.entity.Order;
import com.example.bookgarden.entity.User;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;


import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends MongoRepository<Order, ObjectId> {
    @Override
    Optional<Order> findById(ObjectId objectId);
    List<Order> findByUser(ObjectId objectId);
    List<Order> findByPaymentStatus(String paymentStatus);
    List<Order> findTop10ByOrderByOrderDateDesc();
    List<Order> findByPaymentMethodAndPaymentStatus(String paymentMethod, String paymentStatus);
    @Aggregation(pipeline = {
            "{ $group: { _id: '$user', orderCount: { $sum: 1 } } }",
            "{ $sort: { orderCount: -1 } }",
            "{ $limit: 10 }"
    })
    List<CustomerOrderCount> findTopCustomers();
    Page<Order> findAllByUser(ObjectId userId, Pageable pageable);
    Page<Order> findAll(Pageable pageable);

    List<Order> findAll(Sort sort);
    List<Order> findAllByUser(ObjectId userId, Sort sort);
}
