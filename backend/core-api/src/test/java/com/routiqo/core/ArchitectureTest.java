package com.routiqo.core;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
class ArchitectureTest {
 @Test void applicationDoesNotDependOnPersistenceAdapters() {
   noClasses().that().resideInAPackage("..application..").should().dependOnClassesThat()
       .resideInAnyPackage("..infrastructure..", "org.springframework.jdbc..", "org.flywaydb..")
       .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.routiqo.core"));
 }
 @Test void domainsAreIndependentOfFrameworkAndInfrastructure() {
   noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat()
       .resideInAnyPackage("org.springframework..","jakarta.persistence..","..api..","..infrastructure..")
       .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.routiqo.core"));
 }
 @Test void apiLayerCannotUseTheLowLevelSignalStorageService() {
   noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat()
       .haveFullyQualifiedName("com.routiqo.core.routeupdate.application.SignalStorageService")
       .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
           .importPackages("com.routiqo.core"));
 }
 @Test void apiLayerCannotReachTrustedRouteContextReplacement() {
   noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat()
       .haveFullyQualifiedName("com.routiqo.core.routeupdate.application.LiveRouteContextService")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.routeupdate.application.LiveRouteContextParticipant")
       .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
           .importPackages("com.routiqo.core"));
 }
 @Test void apiLayerCannotReachTrustedContributionRestrictionMutation() {
   noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat()
       .haveFullyQualifiedName("com.routiqo.core.moderation.application.ContributionRestrictionService")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.application.ContributionRestrictionParticipant")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.infrastructure.JdbcContributionRestrictionParticipant")
       .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
           .importPackages("com.routiqo.core"));
 }
}
