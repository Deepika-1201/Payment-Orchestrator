package com.payments.gateway;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Module and layer boundaries of the modular monolith (ADR-001, LLD §1). */
@AnalyzeClasses(packages = "com.payments.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainHasNoFrameworkOrInfrastructureDependencies = classes()
            .that().resideInAPackage("..payment.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("..payment.domain..", "..shared.model..", "..shared.error..", "java..");

    @ArchTest
    static final ArchRule providerSpiIsSelfContained = classes()
            .that().resideInAPackage("..provider.spi..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("..provider.spi..", "..shared.model..", "java..");

    @ArchTest
    static final ArchRule sharedKernelDependsOnNoBusinessModule = noClasses()
            .that().resideInAPackage("..shared..")
            .should().dependOnClassesThat().resideInAnyPackage("..payment..", "..merchant..", "..provider..", "..routing..",
                    "..risk..", "..webhook..", "..idempotency..", "..platform..");

    @ArchTest
    static final ArchRule paymentPersistenceIsPrivateToPaymentModule = noClasses()
            .that().resideOutsideOfPackages("..payment.application..", "..payment.infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("..payment.infrastructure..");

    @ArchTest
    static final ArchRule mockAdaptersAreNotReferencedByCoreCode = noClasses()
            .that().resideOutsideOfPackage("..provider.mock..")
            .should().dependOnClassesThat().resideInAPackage("..provider.mock..");

    @ArchTest
    static final ArchRule webhookModuleUsesOnlyPaymentApplicationApi = noClasses()
            .that().resideInAPackage("..webhook..")
            .should().dependOnClassesThat().resideInAnyPackage("..payment.domain..", "..payment.infrastructure..", "..payment.web..");

    @ArchTest
    static final ArchRule routingAndRiskDoNotDependOnPayments = noClasses()
            .that().resideInAnyPackage("..routing..", "..risk..")
            .should().dependOnClassesThat().resideInAPackage("..payment..");
}
