CREATE TABLE presence_consent (
    actor_id UUID PRIMARY KEY REFERENCES routiqo_account(id) ON DELETE CASCADE,
    journey_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation >= 0),
    sharing BOOLEAN NOT NULL,
    journey_active BOOLEAN NOT NULL,
    CONSTRAINT presence_consent_sharing_requires_active CHECK (NOT sharing OR journey_active),
    CONSTRAINT presence_consent_owned_journey FOREIGN KEY (journey_id, actor_id)
        REFERENCES journey(id, owner_id) ON DELETE CASCADE
);

CREATE INDEX presence_consent_journey ON presence_consent(journey_id, actor_id);
