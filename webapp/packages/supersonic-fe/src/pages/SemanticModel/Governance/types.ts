export type GovernanceStatus =
  | 'DRAFT'
  | 'PENDING'
  | 'APPROVED'
  | 'PUBLISHED'
  | 'REJECTED'
  | 'INACTIVE';

export interface GovernanceRecord {
  id: number;
  metricId: number;
  currentVersion: number;
  governanceStatus: GovernanceStatus;
  ownerDepartment?: string;
  sourceSystem?: string;
  businessDefinition?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
  updatedAt?: string;
  updatedBy?: string;
  metric?: any;
}

export interface MetricVersion {
  id: number;
  metricId: number;
  versionNo: number;
  snapshotJson: string;
  changeSummary?: string;
  approvalStatus: string;
  effectiveFrom?: string;
  effectiveTo?: string;
  createdAt?: string;
  createdBy?: string;
}

export interface MetricApproval {
  id: number;
  metricId: number;
  versionId: number;
  action: string;
  approvalStatus: string;
  commentText?: string;
  createdAt?: string;
  createdBy?: string;
  decidedAt?: string;
  decidedBy?: string;
}

export interface GovernanceDetail {
  metric: any;
  governance: GovernanceRecord;
  versions: MetricVersion[];
  approvals: MetricApproval[];
  organizationMappings: any[];
}

export interface MetricConflict {
  type: string;
  severity: string;
  key: string;
  message: string;
  metricIds: number[];
  mappingIds: number[];
}

export interface MetricLineage {
  metricId: number;
  modelId: number;
  upstreamMetricIds: number[];
  downstreamMetricIds: number[];
  relatedDimensionIds: number[];
  referencedDataSetIds: number[];
  organizationMappings: any[];
}

export interface ImportErrorItem {
  sheet?: string;
  row?: number;
  column?: string;
  code?: string;
  message?: string;
  value?: string;
}

export interface ImportReport {
  success: boolean;
  dryRun: boolean;
  fileName: string;
  checksum: string;
  modelId?: number;
  dataSetId?: number;
  minDate?: string;
  maxDate?: string;
  organizationCount: number;
  indicatorCount: number;
  derivedRuleCount: number;
  factCount: number;
  questionCount: number;
  created: Record<string, number>;
  updated: Record<string, number>;
  skipped: Record<string, number>;
  errors: ImportErrorItem[];
}
