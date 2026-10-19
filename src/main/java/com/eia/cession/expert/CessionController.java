package com.eia.cession.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/cessions")
public class CessionController {
    private final CessionService service;

    public CessionController(CessionService service) {
        this.service = service;
    }

    @GetMapping
    public List<Cession> list(@RequestParam UUID tenantId) {
        return service.list(tenantId);
    }

    @GetMapping("/{id}")
    public Cession get(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return service.get(tenantId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Cession create(@Valid @RequestBody CreateCessionRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}/status")
    public Cession transition(@RequestParam UUID tenantId,
                              @PathVariable UUID id,
                              @Valid @RequestBody UpdateCessionStatusRequest request) {
        return service.transition(tenantId, id, request.status(), request.version());
    }
}
