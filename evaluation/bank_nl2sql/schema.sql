DROP TABLE IF EXISTS bank_indicator_fact;
DROP TABLE IF EXISTS bank_organization_dim;
DROP TABLE IF EXISTS bank_metric_dim;

CREATE TABLE bank_organization_dim (
    organization_code TEXT PRIMARY KEY,
    organization_name TEXT NOT NULL UNIQUE
);

CREATE TABLE bank_metric_dim (
    metric_code TEXT PRIMARY KEY,
    metric_name TEXT NOT NULL UNIQUE,
    metric_description TEXT NOT NULL,
    metric_unit TEXT NOT NULL
);

CREATE TABLE bank_indicator_fact (
    data_date TEXT NOT NULL,
    organization_code TEXT NOT NULL,
    metric_code TEXT NOT NULL,
    metric_value REAL NOT NULL,
    PRIMARY KEY (data_date, organization_code, metric_code),
    FOREIGN KEY (organization_code) REFERENCES bank_organization_dim(organization_code),
    FOREIGN KEY (metric_code) REFERENCES bank_metric_dim(metric_code)
);

CREATE INDEX idx_bank_fact_metric_date ON bank_indicator_fact(metric_code, data_date);
CREATE INDEX idx_bank_fact_org_date ON bank_indicator_fact(organization_code, data_date);
