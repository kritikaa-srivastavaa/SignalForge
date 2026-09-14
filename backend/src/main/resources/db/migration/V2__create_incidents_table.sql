CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL,
    service VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    severity VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
