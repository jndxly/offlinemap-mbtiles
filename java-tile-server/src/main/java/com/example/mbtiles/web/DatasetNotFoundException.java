package com.example.mbtiles.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class DatasetNotFoundException extends RuntimeException {

    public DatasetNotFoundException(String dataset) {
        super("数据集不存在: " + dataset);
    }
}
