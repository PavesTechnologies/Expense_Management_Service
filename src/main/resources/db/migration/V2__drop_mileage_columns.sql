-- Mileage-category expense line items are out of scope for V1 — distance/rate_per_unit
-- were added speculatively and are now removed from the entity. Dropping the columns here
-- rather than leaving them as dead weight, consistent with V1's "no dead columns" approach.
ALTER TABLE expense_line_item
    DROP COLUMN distance,
    DROP COLUMN rate_per_unit;
