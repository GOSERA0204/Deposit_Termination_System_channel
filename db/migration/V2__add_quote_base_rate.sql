-- Execute once against the channel schema BEFORE starting this updated server.
-- Swagger's quote.baseRate needs to be persisted with the quote.
ALTER TABLE termination_quote ADD COLUMN base_rate DECIMAL(12,4) NULL AFTER elapsed_days;
