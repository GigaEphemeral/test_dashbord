package com.javalearn.lab.seckill.jdk8.catalog;

/**
 * Product read model served to the HTTP layer. The implementation adds the
 * two-level cache in front of the core query service.
 */
public interface ProductViewService {

    ProductResponse get(long id);
}
