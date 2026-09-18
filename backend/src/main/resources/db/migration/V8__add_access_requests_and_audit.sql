CREATE TABLE access_requests (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES app_users(id),
    requested_role VARCHAR(16) NOT NULL CHECK (requested_role = 'OPERATOR'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    reviewed_by UUID REFERENCES app_users(id),
    review_reason VARCHAR(300),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_access_review CHECK (
        (status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by IS NULL AND review_reason IS NULL)
        OR (status <> 'PENDING' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL)),
    CONSTRAINT ck_access_no_self_review CHECK (reviewed_by IS NULL OR reviewed_by <> requester_id)
);
CREATE UNIQUE INDEX uq_access_pending_requester ON access_requests(requester_id) WHERE status = 'PENDING';
CREATE INDEX idx_access_status_created ON access_requests(status, created_at DESC, id DESC);
CREATE INDEX idx_access_requester_created ON access_requests(requester_id, created_at DESC, id DESC);

CREATE TABLE audit_records (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES app_users(id),
    actor_email VARCHAR(254) NOT NULL,
    action VARCHAR(40) NOT NULL CHECK (action IN (
        'ACCESS_REQUEST_CREATED', 'ACCESS_REQUEST_APPROVED', 'ACCESS_REQUEST_REJECTED',
        'USER_ROLE_CHANGED', 'INCIDENT_ACKNOWLEDGED', 'INCIDENT_RESOLVED')),
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('ACCESS_REQUEST', 'USER', 'INCIDENT')),
    target_id UUID NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    old_value VARCHAR(32),
    new_value VARCHAR(32)
);
CREATE INDEX idx_audit_time ON audit_records(timestamp DESC, id DESC);
CREATE INDEX idx_audit_action_time ON audit_records(action, timestamp DESC, id DESC);
