CREATE TABLE traced_data (traceId VARCHAR(32) NOT NULL, spanId VARCHAR(16) NOT NULL, createdAt TIMESTAMP DEFAULT NOW() NOT NULL, payload VARCHAR(255), PRIMARY KEY (traceId));