package com.acme.fraud.api;

import com.acme.fraud.alert.AlertStore.AlertNotFoundException;
import com.acme.fraud.alert.AlertStore.IllegalTransitionException;
import com.acme.fraud.alert.AlertStore.StaleAlertException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain/validation failures to RFC 9457 problem details (never a 500 for bad input). */
@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    p.setTitle("Invalid request");
    return p;
  }

  @ExceptionHandler(AlertNotFoundException.class)
  ProblemDetail notFound(AlertNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(IllegalTransitionException.class)
  ProblemDetail illegalTransition(IllegalTransitionException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(StaleAlertException.class)
  ProblemDetail stale(StaleAlertException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, e.getMessage());
  }
}
