package com.example.metaautoreply.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Turns validation failures into a 400 that names what is wrong.
 *
 * <p>The default response reports that binding failed without saying which field or why, which
 * is unhelpful when the rule you just posted has an unclosed bracket in its regex.
 */
@RestControllerAdvice(assignableTypes = RuleAdminController.class)
public class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail onValidationFailure(MethodArgumentNotValidException e) {
		List<String> errors = e.getBindingResult().getAllErrors().stream()
				.map(error -> error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage())
				.sorted()
				.toList();

		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		problem.setTitle("Invalid rule");
		problem.setDetail(String.join("; ", errors));
		problem.setProperty("errors", errors);
		return problem;
	}

	/** An unparseable enum value arrives here rather than as a binding error. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		problem.setTitle("Malformed request body");
		problem.setDetail("Could not parse the request. Check that platform is IG or FB, "
				+ "triggerType is COMMENT or MESSAGE, and matchType is EXACT, CONTAINS or REGEX.");
		return problem;
	}
}
