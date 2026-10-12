-- Create search_index table for global expense search
CREATE TABLE IF NOT EXISTS search_index (
    search_index_id BINARY(16) NOT NULL PRIMARY KEY COMMENT 'UUID primary key',
    report_id BINARY(16) NOT NULL COMMENT 'Foreign key to expense report',
    report_number VARCHAR(255) COMMENT 'Expense report number',
    employee_id VARCHAR(255) NOT NULL COMMENT 'Employee ID who submitted the report',
    employee_name VARCHAR(255) COMMENT 'Employee name',
    merchant VARCHAR(500) COMMENT 'Merchant/vendor name',
    category VARCHAR(255) COMMENT 'Expense category',
    cost_center VARCHAR(255) COMMENT 'Cost center',
    business_purpose TEXT COMMENT 'Business purpose of the expense',
    status VARCHAR(100) COMMENT 'Report status (DRAFT, SUBMITTED, APPROVED, etc)',
    amount DECIMAL(15, 2) NOT NULL COMMENT 'Total expense amount',
    search_query TEXT NOT NULL COMMENT 'Concatenated searchable text',
    search_type VARCHAR(100) COMMENT 'Type of search (REPORT, LINE_ITEM, etc)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',

    -- Indexes for common search patterns
    KEY idx_search_query (search_query(100)),
    KEY idx_employee_id (employee_id),
    KEY idx_report_id (report_id),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Search index for global expense search - stores denormalized expense data for fast retrieval';
