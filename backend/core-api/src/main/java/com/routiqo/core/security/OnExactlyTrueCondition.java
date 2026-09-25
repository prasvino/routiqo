package com.routiqo.core.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class OnExactlyTrueCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var attributes = metadata.getAnnotationAttributes(ConditionalOnExactlyTrue.class.getName());
        if (attributes == null) return false;
        String[] names = (String[]) attributes.get("value");
        if (names == null || names.length == 0) return false;
        for (String name : names)
            if (!FeatureFlags.enabled(context.getEnvironment().getProperty(name))) return false;
        return true;
    }
}
