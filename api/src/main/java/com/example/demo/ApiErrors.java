package com.example.demo;

import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {

    /*
    Example:
    {
      "type": "about:blank",
      "title": "Conflict",
      "status": 409,
      "detail": "Idempotency key was already used with a different request"
    }
    * */

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        var problem = ProblemDetail.forStatusAndDetail(
                status,
                "Request validation failed");

        problem.setProperty(
                "errors",
                exception.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .map(error -> Map.of(
                                "field", error.getField(),
                                "message", Objects.toString(
                                        error.getDefaultMessage(),
                                        "Invalid value")))
                        .toList());

        return handleExceptionInternal(
                exception,
                problem,
                headers,
                status,
                request);
    }
}