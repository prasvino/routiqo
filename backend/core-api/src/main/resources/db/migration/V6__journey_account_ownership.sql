-- Fail on orphaned legacy rows; operators must resolve ownership explicitly.
ALTER TABLE journey ADD CONSTRAINT journey_owner_account
    FOREIGN KEY (owner_id) REFERENCES routiqo_account(id) ON DELETE CASCADE;
