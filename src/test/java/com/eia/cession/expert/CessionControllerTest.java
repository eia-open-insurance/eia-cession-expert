package com.eia.cession.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CessionController.class)
@Import(CessionService.class)
class CessionControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID POLICY = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID CONTRACT = UUID.fromString("44444444-4444-7444-8444-444444444444");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CessionRepository repository;

    private static Cession cession(CessionStatus status, int version) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new Cession(ID, TENANT, "DIRECT", "CES-000000001", POLICY, CONTRACT, status,
                new BigDecimal("1000.0000"), new BigDecimal("250.0000"), "USD", version, now, now);
    }

    @Test
    void listsCessionsForTenant() throws Exception {
        when(repository.findAll(TENANT)).thenReturn(List.of(cession(CessionStatus.OPEN, 0)));

        mvc.perform(get("/internal/v1/cessions").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cessionNumber").value("CES-000000001"))
                .andExpect(jsonPath("$[0].businessBook").value("DIRECT"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void returnsStructured404ForUnknownCession() throws Exception {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/v1/cessions/{id}", ID).param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Cession not found: " + ID));
    }

    @Test
    void createsCessionInOpenStatus() throws Exception {
        when(repository.create(eq(TENANT), eq(POLICY), eq(CONTRACT), any(), any(), eq("USD")))
                .thenReturn(cession(CessionStatus.OPEN, 0));

        mvc.perform(post("/internal/v1/cessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "%s",
                                  "businessBook": "DIRECT",
                                  "policyId": "%s",
                                  "cessionContractId": "%s",
                                  "cededPremium": 1000.0000,
                                  "cededLoss": 250.0000,
                                  "currency": "usd"
                                }
                                """.formatted(TENANT, POLICY, CONTRACT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.cessionNumber").value("CES-000000001"));
    }

    @Test
    void rejectsNegativeCededPremium() throws Exception {
        mvc.perform(post("/internal/v1/cessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "%s",
                                  "businessBook": "DIRECT",
                                  "policyId": "%s",
                                  "cessionContractId": "%s",
                                  "cededPremium": -1.0000,
                                  "cededLoss": 0.0000,
                                  "currency": "USD"
                                }
                                """.formatted(TENANT, POLICY, CONTRACT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.details.cededPremium").value("must be zero or greater"));
    }

    @Test
    void rejectsNonDirectBook() throws Exception {
        mvc.perform(post("/internal/v1/cessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "%s",
                                  "businessBook": "ASSUMED",
                                  "policyId": "%s",
                                  "cessionContractId": "%s",
                                  "cededPremium": 1.0000,
                                  "cededLoss": 0.0000,
                                  "currency": "USD"
                                }
                                """.formatted(TENANT, POLICY, CONTRACT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.details.businessBook").value("must be DIRECT"));
    }

    @Test
    void advancesStatusAlongLifecycle() throws Exception {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.OPEN, 0)));
        when(repository.updateStatus(TENANT, ID, CessionStatus.RULE_APPLIED, 0))
                .thenReturn(Optional.of(cession(CessionStatus.RULE_APPLIED, 1)));

        mvc.perform(put("/internal/v1/cessions/{id}/status", ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "RULE_APPLIED", "version": 0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RULE_APPLIED"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void rejectsIllegalStatusTransition() throws Exception {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.CONFIRMED, 4)));

        mvc.perform(put("/internal/v1/cessions/{id}/status", ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "OPEN", "version": 4}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void rejectsStaleVersionWith409() throws Exception {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.OPEN, 2)));

        mvc.perform(put("/internal/v1/cessions/{id}/status", ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "RULE_APPLIED", "version": 1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void rejectsUnknownStatusValue() throws Exception {
        mvc.perform(put("/internal/v1/cessions/{id}/status", ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "POSTED", "version": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }
}
