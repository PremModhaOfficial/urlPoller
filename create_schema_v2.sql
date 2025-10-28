-- =====================================================
-- URL Poller Database Schema V2
-- Timestamp-Based Polling System
-- =====================================================

-- Drop existing table if exists
DROP TABLE IF EXISTS ips CASCADE;

-- Create new schema with timestamp-based polling
CREATE TABLE ips (
    id             SERIAL PRIMARY KEY,
    ip             VARCHAR(45) UNIQUE NOT NULL,
    poll_interval  INTEGER NOT NULL CHECK (poll_interval > 0 AND poll_interval <= 3600),
    next_poll_time TIMESTAMP NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

-- =====================================================
-- Indexes for Performance
-- =====================================================

-- CRITICAL: Index on next_poll_time for fast "due for polling" queries
CREATE INDEX idx_next_poll_time ON ips(next_poll_time);

-- Secondary index on IP for lookups
CREATE INDEX idx_ip ON ips(ip);

-- =====================================================
-- Auto-Update Trigger for updated_at
-- =====================================================

-- Function to update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to automatically update updated_at on row updates
CREATE TRIGGER update_ips_updated_at
    BEFORE UPDATE ON ips
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- =====================================================
-- Sample Data (Optional - for testing)
-- =====================================================

-- Uncomment to insert test data:
-- INSERT INTO ips (ip, poll_interval, next_poll_time) VALUES
--     ('8.8.8.8', 5, NOW() + INTERVAL '5 seconds'),
--     ('1.1.1.1', 10, NOW() + INTERVAL '10 seconds'),
--     ('208.67.222.222', 30, NOW() + INTERVAL '30 seconds');

-- =====================================================
-- Verification Queries
-- =====================================================

-- View table structure
-- \d ips

-- View indexes
-- \di ips*

-- View all IPs
-- SELECT * FROM ips ORDER BY id;

-- View IPs due for polling
-- SELECT id, ip, poll_interval, next_poll_time 
-- FROM ips 
-- WHERE next_poll_time <= NOW() 
-- ORDER BY next_poll_time ASC;
