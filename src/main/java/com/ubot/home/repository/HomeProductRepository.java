package com.ubot.home.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ubot.home.entity.HomeProduct;

public interface HomeProductRepository extends JpaRepository<HomeProduct, Long> {
}
