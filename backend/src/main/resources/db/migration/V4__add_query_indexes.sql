-- Default event ordering and the main service-based filters.
CREATE INDEX idx_events_timestamp_id
ON events (timestamp DESC, id DESC);

CREATE INDEX idx_events_service_timestamp_id
ON events (service, timestamp DESC, id DESC);

CREATE INDEX idx_events_service_type_timestamp_id
ON events (service, type, timestamp DESC, id DESC);

-- Default incident ordering and the main operational filters.
CREATE INDEX idx_incidents_created_at_id
ON incidents (created_at DESC, id DESC);

CREATE INDEX idx_incidents_service_created_at_id
ON incidents (service, created_at DESC, id DESC);

CREATE INDEX idx_incidents_service_status_created_at_id
ON incidents (service, status, created_at DESC, id DESC);

-- No standalone low-cardinality severity/status indexes or duplicate primary-key indexes.
