package com.bankx.api;

import com.bankx.legacy.RiskRule;
import com.bankx.legacy.RiskRuleRepository;
import com.bankx.repo.AccountRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class TransactionControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    RiskRuleRepository riskRuleRepository; // JPA (bloqueante)

    @Autowired
    AccountRepository accountRepository; // Mongo reactivo

    @BeforeEach
    void setupData() {
        upsertRisk("PEN", new BigDecimal("5000"));

        accountRepository.findByNumber("001-0001").flatMap(acc -> {
            acc.setBalance(new BigDecimal("2000"));
            return accountRepository.save(acc);
        }).switchIfEmpty(Mono.empty()).block();

        accountRepository.findByNumber("001-0002").flatMap(acc -> {
          acc.setBalance(new BigDecimal("800"));
          return accountRepository.save(acc);
        }).switchIfEmpty(Mono.empty()).block();
  }

  private void upsertRisk(String currency, BigDecimal maxPerTx) {
    riskRuleRepository.findFirstByCurrency(currency).ifPresentOrElse(rule -> {
          rule.setMaxDebitPerTx(maxPerTx);
          riskRuleRepository.save(rule);
        }, () -> riskRuleRepository.save(
                RiskRule.builder().currency(currency).maxDebitPerTx(maxPerTx).build()));
  }

    @Test
    void createTransaction_ok() {
    webTestClient.post().uri("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
              {
                "accountNumber": "001-0001",
                "type": "DEBIT",
                "amount": 100
              }
            """).exchange().expectStatus().isCreated().expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON).expectBody().jsonPath("$.id")
                .isNotEmpty().jsonPath("$.status").isEqualTo("OK")
                .jsonPath("$.type").isEqualTo("DEBIT")
                .jsonPath("$.amount").isEqualTo(100);
  }

    @Test
    void createTransaction_insufficientFunds() {
        webTestClient.post().uri("/api/transactions").header("X-Risk-Fail", "false")
                .header("X-Risk-DelayMs", "0").contentType(MediaType.APPLICATION_JSON).bodyValue("""
              {"accountNumber":"001-0002","type":"DEBIT","amount":1000}
            """).exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error")
                .isEqualTo("insufficient_funds");
  }

    @Test
    void createTransaction_riskRejected() {
        // Forzamos riesgo bajo para que dispare "risk_rejected" aunque haya saldo
        upsertRisk("PEN", new BigDecimal("1500"));

        webTestClient.post().uri("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
              {
                "accountNumber": "001-0001",
                "type": "DEBIT",
                "amount": 2000
              }
            """).exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error")
                .isEqualTo("risk_rejected");
  }

    @Test
    void createTransaction_accountNotFound() {
        webTestClient.post().uri("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
              {
                "accountNumber": "001-9999",
                "type": "DEBIT",
                "amount": 100
              }
            """).exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error")
                .isEqualTo("account_not_found");
  }

    @Test
    void createTransaction_remoteError_usesFallback_allows() {
        upsertRisk("PEN", new BigDecimal("5000"));

        webTestClient.post().uri("/api/transactions")
                .header("X-Risk-Fail", "true")      // fuerza fallo remoto
                .header("X-Risk-DelayMs", "0").contentType(MediaType.APPLICATION_JSON).bodyValue("""
              {"accountNumber":"001-0001","type":"DEBIT","amount":100}
            """).exchange().expectStatus().isCreated().expectBody().jsonPath("$.status")
                .isEqualTo("OK");
  }

    @Test
    void createTransaction_timeout_usesFallback_allows() {
        upsertRisk("PEN", new BigDecimal("5000"));

        webTestClient.post().uri("/api/transactions").header("X-Risk-Fail", "false")
                .header("X-Risk-DelayMs", "1500")   // mayor que tu TimeLimiter (1s)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("""
              {"accountNumber":"001-0001","type":"DEBIT","amount":100}
            """).exchange().expectStatus().isCreated().expectBody().jsonPath("$.status")
                .isEqualTo("OK");
  }
}
