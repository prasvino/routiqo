package com.routiqo.core;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
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
       .haveFullyQualifiedName("com.routiqo.core.moderation.application.AuditedContributionRestrictionService")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.application.AuditedContributionRestrictionParticipant")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.application.ContributionRestrictionAuditCleanup")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.application.ContributionRestrictionParticipant")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.infrastructure.JdbcContributionRestrictionParticipant")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.infrastructure.JdbcAuditedContributionRestrictionParticipant")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.infrastructure.JdbcContributionRestrictionAuditCleanup")
       .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
           .importPackages("com.routiqo.core"));
 }
 @Test void productionRestrictionMutationParticipantIsInfrastructureOnly() {
   noClasses().that().resideOutsideOfPackage("..moderation.infrastructure..").should()
       .dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.application.ContributionRestrictionParticipant")
       .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
           .importPackages("com.routiqo.core"));
 }
 @Test void auditedParticipantHasOnlyItsServiceAndInfrastructureAsCallers() {
   noClasses().that().resideOutsideOfPackages("..moderation.infrastructure..",
           "..moderation.application..").should().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.application.AuditedContributionRestrictionParticipant")
       .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
           .importPackages("com.routiqo.core"));
 }
 @Test void restrictionWritesAndAuditedEntryHaveSingleProductionOwners() {
   classes().should(restrictionWriteBoundary())
       .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
           .importPackages("com.routiqo.core"));
 }
 @Test void mutationBoundaryDetectsForbiddenCallsAndReferencesThroughEitherOwnerType() {
   for (Class<?> fixture : new Class<?>[] { ForbiddenRestrictionCalls.class,
       ForbiddenRestrictionReferences.class, ForbiddenAuditedCalls.class,
       ForbiddenAuditedReferences.class }) {
     var violations = classes().should(restrictionWriteBoundary())
         .evaluate(new ClassFileImporter().importClasses(fixture)).getFailureReport().getDetails();
     assertThat(violations).as(fixture.getSimpleName()).hasSize(2);
   }
 }
 private static ArchCondition<JavaClass> restrictionWriteBoundary() {
   return new ArchCondition<>("route restriction writes through the audited owner") {
     @Override public void check(JavaClass caller, ConditionEvents events) {
       caller.getMethodCallsFromSelf().forEach(call -> {
         checkTarget(caller, call.getTarget().getOwner().getName(),
             call.getTarget().getName(), events);
       });
       caller.getMethodReferencesFromSelf().forEach(reference -> {
         checkTarget(caller, reference.getTarget().getOwner().getName(),
             reference.getTarget().getName(), events);
       });
     }
     private void checkTarget(JavaClass caller, String owner, String method,
         ConditionEvents events) {
         boolean restrictionWrite = method.equals("replace") && (owner.equals(
             "com.routiqo.core.moderation.application.ContributionRestrictionParticipant")
             || owner.equals(
             "com.routiqo.core.moderation.infrastructure.JdbcContributionRestrictionParticipant"));
         boolean auditedEntry = method.equals("apply") && (owner.equals(
             "com.routiqo.core.moderation.application.AuditedContributionRestrictionParticipant")
             || owner.equals(
             "com.routiqo.core.moderation.infrastructure.JdbcAuditedContributionRestrictionParticipant"));
         boolean valid = !restrictionWrite && !auditedEntry
             || restrictionWrite && caller.getName().equals(
                 "com.routiqo.core.moderation.infrastructure.JdbcAuditedContributionRestrictionParticipant")
             || auditedEntry && caller.getName().equals(
                 "com.routiqo.core.moderation.application.AuditedContributionRestrictionService");
         if (!valid) events.add(SimpleConditionEvent.violated(caller,
             caller.getName() + " directly invokes " + owner + "." + method));
     }
   };
 }
 // Bytecode fixtures only: none of these deliberately forbidden paths is executed.
 private static final class ForbiddenRestrictionCalls {
   void throughInterface(com.routiqo.core.moderation.application.ContributionRestrictionParticipant value) {
     value.replace(null, null);
   }
   void throughConcrete(com.routiqo.core.moderation.infrastructure.JdbcContributionRestrictionParticipant value) {
     value.replace(null, null);
   }
 }
 private static final class ForbiddenRestrictionReferences {
   java.util.function.BiConsumer<com.routiqo.core.moderation.domain.ContributorAssessment,
       com.routiqo.core.moderation.domain.ContributorAssessment> throughInterface(
       com.routiqo.core.moderation.application.ContributionRestrictionParticipant value) {
     return value::replace;
   }
   java.util.function.BiConsumer<com.routiqo.core.moderation.domain.ContributorAssessment,
       com.routiqo.core.moderation.domain.ContributorAssessment> throughConcrete(
       com.routiqo.core.moderation.infrastructure.JdbcContributionRestrictionParticipant value) {
     return value::replace;
   }
 }
 private static final class ForbiddenAuditedCalls {
   void throughInterface(com.routiqo.core.moderation.application.AuditedContributionRestrictionParticipant value) {
     value.apply(null, null, null);
   }
   void throughConcrete(com.routiqo.core.moderation.infrastructure.JdbcAuditedContributionRestrictionParticipant value) {
     value.apply(null, null, null);
   }
 }
 private interface AuditedCall {
   com.routiqo.core.moderation.application.AuditedContributionRestrictionService.Receipt apply(
       java.util.UUID operator,
       com.routiqo.core.moderation.application.AuditedContributionRestrictionService.Command command,
       java.time.Clock clock);
 }
 private static final class ForbiddenAuditedReferences {
   AuditedCall throughInterface(
       com.routiqo.core.moderation.application.AuditedContributionRestrictionParticipant value) {
     return value::apply;
   }
   AuditedCall throughConcrete(
       com.routiqo.core.moderation.infrastructure.JdbcAuditedContributionRestrictionParticipant value) {
     return value::apply;
   }
 }
 @Test void apiLayerCannotReachTrustedBlockPolicyMutationOrPairAuthority() {
   noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat()
       .haveFullyQualifiedName("com.routiqo.core.moderation.application.DurableBlockPolicyService")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.application.DirectionalBlockParticipant")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.identity.application.EnabledAccountPairAuthority")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.identity.infrastructure.JdbcEnabledAccountPairAuthority")
       .orShould().dependOnClassesThat().haveFullyQualifiedName(
           "com.routiqo.core.moderation.infrastructure.JdbcDirectionalBlockParticipant")
       .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
           .importPackages("com.routiqo.core"));
 }
}
