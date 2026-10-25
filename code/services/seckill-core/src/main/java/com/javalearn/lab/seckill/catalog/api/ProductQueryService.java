package com.javalearn.lab.seckill.catalog.api;

import com.javalearn.lab.seckill.catalog.domain.Product;

/**
 * Catalog query use case exposed to inbound adapters (web, messaging, ...).
 */
public interface ProductQueryService {

    Product get(long id);
}
