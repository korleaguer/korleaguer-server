package com.korleaguer.api;

import com.korleaguer.ingest.IngestAlreadyRunningException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    // 400은 모두 같은 모양({"error": ...})으로 돌려준다.
    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse badRequest(BadRequestException e) {
        return new ErrorResponse(e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse missingParameter(MissingServletRequestParameterException e) {
        return new ErrorResponse("missing parameter: " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalidParameter(MethodArgumentTypeMismatchException e) {
        return new ErrorResponse("invalid parameter: " + e.getName());
    }
}
