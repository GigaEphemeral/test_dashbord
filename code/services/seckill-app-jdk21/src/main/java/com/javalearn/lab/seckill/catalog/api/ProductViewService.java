package com.javalearn.lab.seckill.catalog.api;

/**
 * Product read model served to the HTTP layer. The implementation adds the
 * two-level cache in front of the core query service.
 */
public interface ProductViewService {

    ProductResponse get(long id);
}
