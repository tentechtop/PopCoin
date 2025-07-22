package com.pop.popcoinsystem.exception;

import lombok.Data;

/**
 * Exception thrown when storing data on node fails
 */
@Data
public class FullBucketException extends Exception {
    private int bucketId;
    public FullBucketException() {
        super("Bucket is full");
    }
    public FullBucketException(int bucketId) {
        super("Bucket is full");
        this.bucketId = bucketId;
    }
}
