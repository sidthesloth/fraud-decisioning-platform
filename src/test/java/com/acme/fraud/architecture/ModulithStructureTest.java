package com.acme.fraud.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.fraud.FraudApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithStructureTest {

  private static final List<String> EXPECTED_MODULES =
      List.of(
          "ingestion",
          "ruledomain",
          "evaluation",
          "alert",
          "decisions",
          "api",
          "platform",
          "bootstrap");

  private final ApplicationModules modules = ApplicationModules.of("com.acme.fraud");

  @Test
  void detectsAllExpectedModules() {
    List<String> moduleNames = modules.stream().map(m -> m.getIdentifier().toString()).toList();

    modules.forEach(System.out::println);

    assertThat(moduleNames).containsExactlyInAnyOrderElementsOf(EXPECTED_MODULES);
    assertThat(moduleNames).hasSizeGreaterThanOrEqualTo(8);
  }

  @Test
  void verifiesModuleStructure() {
    assertThat(modules.stream()).hasSizeGreaterThanOrEqualTo(8);
    modules.verify();
  }

  @Test
  void printsDiscoveredModules() {
    System.out.println("Application class: " + FraudApplication.class.getName());
    System.out.println("Application package: " + FraudApplication.class.getPackageName());

    modules.forEach(System.out::println);
  }
}
