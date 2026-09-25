package com.routiqo.core.security;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0065 regression guard: public, community and V3 moderation capabilities are gated only through the
 * fail-closed {@link ConditionalOnExactlyTrue}, never Spring's case-insensitive {@code havingValue}.
 */
class CommunityLiveFlagGuardTest {
    private static final List<String> GUARDED = List.of("COMMUNITY_TRAFFIC", "PUBLIC_SIGNAL_INTENT",
            "V3_ADMIN", "V3_GRANT_ADMIN");

    @Test void guardedFlagsNeverUseLenientPropertyConditions() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.routiqo.core");
        var gated = classes.stream().filter(type -> type.isAnnotatedWith(ConditionalOnExactlyTrue.class)).toList();
        assertThat(gated).extracting(JavaClass::getSimpleName).contains(
                "BrowserCommunityTrafficV3Controller", "BrowserCommunityTrafficController",
                "CommunityTrafficV3Configuration", "CommunityTrafficShareConfiguration",
                "CommunityTrafficV3JobConfiguration", "CommunityTrafficV3MaintenanceConfiguration",
                "BrowserPublicSignalIntentController", "BrowserPublicSignalIntentListController",
                "AdminTrafficController", "AdminTrafficConfiguration", "AdminTrafficGrantController",
                "AdminTrafficGrantConfiguration", "TrafficGrantMaintenanceConfiguration");
        for (JavaClass type : classes) {
            if (!type.isAnnotatedWith(ConditionalOnProperty.class)) continue;
            var annotation = type.getAnnotationOfType(ConditionalOnProperty.class);
            var names = Arrays.asList(annotation.name().length > 0 ? annotation.name() : annotation.value());
            assertThat(names).as(type.getName())
                    .noneMatch(name -> GUARDED.stream().anyMatch(name::contains));
        }
    }
}
