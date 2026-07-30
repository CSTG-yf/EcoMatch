export type EvaluationSuite = {
  status?: string;
  caseCount?: number;
  testCount?: number;
  [key: string]: any;
};

export type Qa01aReport = {
  task?: string;
  status?: string;
  evaluatedAt?: string;
  evaluationMode?: string;
  summary?: Record<string, any>;
  suites?: Record<string, EvaluationSuite>;
};

export type MetricComparison = {
  path: string;
  baseline?: number;
  current?: number;
  delta?: number;
  relativeChange?: number;
  direction?: string;
  status?: string;
};

export type ErrorCase = {
  id?: string;
  suite?: string;
  category?: string;
  message?: string;
  scenario?: string;
  difficulty?: string;
};

export type Qa01bReport = {
  task?: string;
  status?: string;
  generatedAt?: string;
  releaseDecision?: string;
  versions?: {
    baseline?: string;
    current?: string;
  };
  summary?: Record<string, number>;
  metricComparison?: MetricComparison[];
  sourceComparison?: Array<Record<string, any>>;
  stageTimingComparison?: Array<Record<string, any>>;
  violations?: Array<Record<string, any>>;
  errorCases?: ErrorCase[];
};

export type SecurityReport = {
  task?: string;
  status?: string;
  generatedAt?: string;
  summary?: {
    controlCount?: number;
    passedControlCount?: number;
    testClassCount?: number;
    passedTestClassCount?: number;
    caseCount?: number;
    failureCount?: number;
  };
  controls?: Array<Record<string, any>>;
};

export type EvaluationDashboard = {
  schemaVersion?: string;
  status?: string;
  availableReportCount?: number;
  reports?: {
    qa01a?: Qa01aReport;
    qa01b?: Qa01bReport;
    qa02a?: SecurityReport;
    qa02b?: SecurityReport;
  };
};
