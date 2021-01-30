package com.sailheader.middleware.chain;

/**
 * 动作链中的上下文
 * 用于维护所有动作的输出数据
 */
public interface ChainContext<C, K, I> {

    void setChainContextItem(K key, I context);

    I getItem(K key);

    C getCollection();

}
