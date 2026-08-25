package com.example.metaautoreply.web;

import com.example.metaautoreply.domain.KeywordRule;
import com.example.metaautoreply.engine.RuleMatcher;
import com.example.metaautoreply.repo.KeywordRuleRepository;
import com.example.metaautoreply.web.dto.RuleRequest;
import com.example.metaautoreply.web.dto.RuleResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * CRUD for keyword rules, so they can be managed without touching SQL.
 *
 * <p>Behind HTTP Basic — see {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/rules")
public class RuleAdminController {

	private static final Logger log = LoggerFactory.getLogger(RuleAdminController.class);

	private final KeywordRuleRepository rules;
	private final RuleMatcher ruleMatcher;

	public RuleAdminController(KeywordRuleRepository rules, RuleMatcher ruleMatcher) {
		this.rules = rules;
		this.ruleMatcher = ruleMatcher;
	}

	@GetMapping
	public List<RuleResponse> list() {
		return rules.findAll(Sort.by("priority").ascending()).stream()
				.map(RuleResponse::from)
				.toList();
	}

	@GetMapping("/{id}")
	public RuleResponse get(@PathVariable Long id) {
		return RuleResponse.from(require(id));
	}

	@PostMapping
	public ResponseEntity<RuleResponse> create(@Valid @RequestBody RuleRequest request) {
		KeywordRule rule = new KeywordRule(request.name(), request.platform(), request.triggerType(),
				request.matchType(), request.keyword(), request.publicReply(), request.dmText(),
				request.enabledOrDefault(), request.priorityOrDefault());

		KeywordRule saved = rules.save(rule);
		log.info("Created rule {} ('{}'), enabled={}", saved.getId(), saved.getName(), saved.isEnabled());
		return ResponseEntity.status(HttpStatus.CREATED).body(RuleResponse.from(saved));
	}

	@PutMapping("/{id}")
	public RuleResponse update(@PathVariable Long id, @Valid @RequestBody RuleRequest request) {
		KeywordRule rule = require(id);
		rule.setName(request.name());
		rule.setPlatform(request.platform());
		rule.setTriggerType(request.triggerType());
		rule.setMatchType(request.matchType());
		rule.setKeyword(request.keyword());
		rule.setPublicReply(request.publicReply());
		rule.setDmText(request.dmText());
		rule.setEnabled(request.enabledOrDefault());
		rule.setPriority(request.priorityOrDefault());

		KeywordRule saved = rules.save(rule);
		// The matcher caches compiled patterns by pattern string. Editing a rule in place
		// leaves the old pattern cached, so it must be dropped or the edit appears to do
		// nothing until a restart.
		ruleMatcher.clearPatternCache();
		log.info("Updated rule {} ('{}')", saved.getId(), saved.getName());
		return RuleResponse.from(saved);
	}

	@PatchMapping("/{id}/toggle")
	public RuleResponse toggle(@PathVariable Long id) {
		KeywordRule rule = require(id);
		rule.setEnabled(!rule.isEnabled());
		KeywordRule saved = rules.save(rule);
		log.info("Toggled rule {} ('{}') to enabled={}", saved.getId(), saved.getName(), saved.isEnabled());
		return RuleResponse.from(saved);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		KeywordRule rule = require(id);
		rules.delete(rule);
		ruleMatcher.clearPatternCache();
		log.info("Deleted rule {} ('{}')", id, rule.getName());
		return ResponseEntity.noContent().build();
	}

	private KeywordRule require(Long id) {
		return rules.findById(id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "No rule with id " + id));
	}
}
