-- =====================================================
-- Add Ping Results Table for Status Tracking
-- =====================================================

-- Create table to store latest ping results for each IP
CREATE TABLE IF NOT EXISTS ping_results (
    id SERIAL PRIMARY KEY,
    ip_id INTEGER NOT NULL REFERENCES ips(id) ON DELETE CASCADE,
    ip_address VARCHAR(45) NOT NULL,
    is_success BOOLEAN NOT NULL,
    packet_loss INTEGER NOT NULL CHECK (packet_loss >= 0 AND packet_loss <= 100),
    min_rtt NUMERIC(10,3) CHECK (min_rtt >= -1),
    avg_rtt NUMERIC(10,3) CHECK (avg_rtt >= -1), 
    max_rtt NUMERIC(10,3) CHECK (max_rtt >= -1),
    pinged_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Index for fast lookups by IP ID
CREATE INDEX IF NOT EXISTS idx_ping_results_ip_id ON ping_results(ip_id);

-- Index for finding latest results per IP
CREATE INDEX IF NOT EXISTS idx_ping_results_pinged_at ON ping_results(pinged_at);

-- Unique constraint to ensure one result per IP per ping cycle (optional)
-- CREATE UNIQUE INDEX IF NOT EXISTS idx_ping_results_unique_cycle ON ping_results(ip_id, pinged_at);

-- =====================================================
-- Function to get latest ping result for an IP
-- =====================================================

CREATE OR REPLACE FUNCTION get_latest_ping_status(p_ip_id INTEGER)
RETURNS TABLE (
    is_success BOOLEAN,
    packet_loss INTEGER,
    avg_rtt NUMERIC(10,3),
    pinged_at TIMESTAMP
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        pr.is_success,
        pr.packet_loss,
        pr.avg_rtt,
        pr.pinged_at
    FROM ping_results pr
    WHERE pr.ip_id = p_ip_id
    ORDER BY pr.pinged_at DESC
    LIMIT 1;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- View to see IPs with their latest ping status
-- =====================================================

CREATE OR REPLACE VIEW ips_with_status AS
SELECT 
    i.id,
    i.ip,
    i.poll_interval,
    i.next_poll_time,
    i.created_at,
    i.updated_at,
    COALESCE(pr.is_success, false) as latest_ping_success,
    COALESCE(pr.packet_loss, 100) as latest_packet_loss,
    COALESCE(pr.avg_rtt, -1) as latest_avg_rtt,
    COALESCE(pr.pinged_at, i.created_at) as latest_pinged_at
FROM ips i
LEFT JOIN LATERAL (
    SELECT *
    FROM ping_results pr
    WHERE pr.ip_id = i.id
    ORDER BY pr.pinged_at DESC
    LIMIT 1
) pr ON true;

-- =====================================================
-- Sample Queries for Testing
-- =====================================================

-- Get all IPs with their latest status
-- SELECT * FROM ips_with_status ORDER BY id;

-- Get latest ping for specific IP
-- SELECT * FROM get_latest_ping_status(1);

-- Insert a ping result
-- INSERT INTO ping_results (ip_id, ip_address, is_success, packet_loss, avg_rtt, pinged_at)
-- VALUES (1, '192.168.1.1', true, 0, 25.5, NOW());