package com.korleaguer.api;

import com.korleaguer.ingest.IngestAlreadyRunningException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PlayerNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse playerNotFound() {
        return new ErrorResponse("player not found");
    }

    @ExceptionHandler(IngestAlreadyRunningException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse ingestAlreadyRunning() {
        return new ErrorResponse("ingest already running");
    }
}
