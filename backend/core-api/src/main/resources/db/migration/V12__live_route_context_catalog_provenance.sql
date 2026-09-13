ALTER TABLE live_route_context
    ADD COLUMN catalog_version UUID NULL,
    ADD CONSTRAINT live_route_context_catalog_version_non_nil CHECK (
        catalog_version IS NULL OR catalog_version <> '00000000-0000-0000-0000-000000000000');
