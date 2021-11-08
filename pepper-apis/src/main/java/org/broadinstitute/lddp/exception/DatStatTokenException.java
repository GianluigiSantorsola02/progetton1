package org.broadinstitute.lddp.exception;

/**
 *
 */
public class DatStatTokenException extends RuntimeException
{

    public DatStatTokenException(String message) {
        super(message);
    }

    public DatStatTokenException(String message, Throwable cause) {
        super(message,cause);
    }
}