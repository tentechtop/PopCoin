package com.pop.popcoinsystem.data.vo.result;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AnyResult<T,E> {
    private T data; // 当前页数据
    private E lastKey; // 当前页最后一个键（用于下一页查询）
    private boolean isLastPage; // 是否为最后一页

    public AnyResult(T data, E lastKey, boolean isLastPage) {
        this.data = data;
        this.lastKey = lastKey;
        this.isLastPage = isLastPage;
    }
}
