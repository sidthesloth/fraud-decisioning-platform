package com.acme.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@SpringBootApplication(scanBasePackages = "com.acme.fraud")
@Modulithic(systemName = "Fraud Decisioning Platform", additionalPackages = "com.acme.fraud")
public class FraudApplication {

  public static void main(String[] args) {
    SpringApplication.run(FraudApplication.class, args);
  }
}
