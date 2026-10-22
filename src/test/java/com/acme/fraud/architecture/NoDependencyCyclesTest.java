package com.acme.fraud.architecture;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.acme.fraud")
class NoDependencyCyclesTest {

  @ArchTest
  static final ArchRule noCyclesBetweenContexts =
      slices().matching("com.acme.fraud.(*)..").should().beFreeOfCycles();
}
