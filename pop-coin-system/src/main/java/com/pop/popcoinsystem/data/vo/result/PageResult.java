package com.pop.popcoinsystem.data.vo.result;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PageResult<T> {
    private T data; // 当前页数据
    private String lastKey; // 当前页最后一个键（用于下一页查询）
    private boolean isLastPage; // 是否为最后一页

    public PageResult(T data, String lastKey, boolean isLastPage) {
        this.data = data;
        this.lastKey = lastKey;
        this.isLastPage = isLastPage;
    }
}
