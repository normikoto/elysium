-- Existing accounts stay regular users, so servers that already have accounts choose an admin with the set-role command.
ALTER TABLE account_credential
    ADD COLUMN role TEXT NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN'));
