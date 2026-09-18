package com.routiqo.core.moderation.application;

import com.routiqo.core.moderation.domain.DirectionalBlock;
import java.util.UUID;

/** Trusted participant called only while both enabled account rows are locked. */
public interface DirectionalBlockParticipant {
    DirectionalBlock read(UUID blockerId, UUID targetId);
    int outgoingCount(UUID blockerId);
    void replace(DirectionalBlock prior, DirectionalBlock updated);
}
