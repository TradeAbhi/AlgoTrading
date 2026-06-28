package com.stockanalyzer.exception;

/**
 * Thrown when the supplied candle series doesn't have enough data to extract
 * a clean 10-before / 10-after window around the consolidation range.
 */
public class InsufficientDataException extends RuntimeException {
    public InsufficientDataException(String message) {
        super(message);
    }
}
