// Mirrors the backend DTOs (com.camellia.dto.Dtos, com.camellia.ai.AiDtos). Dates are ISO strings.

export type RecordType = 'NOTE' | 'LAB_RESULT' | 'CONDITION' | 'MEDICATION' | 'ENCOUNTER' | 'APPOINTMENT' | 'IMAGING'

export interface RecordDto {
  recordId: string
  patientId: string
  orgId: string
  orgName: string
  encounterId?: string
  recordType: RecordType
  title: string
  recordDate?: string
  author?: string
  specialty?: string
  contentText?: string
  sourceFormat: string
  sourceRecordId?: string
  tags: string[]
}

export interface ReferencedBy {
  kind: 'FLAG' | 'ALERT'
  id: string
  title: string
  role: string
}

export interface RecordDetail {
  record: RecordDto
  rawContent?: string
  sourceSystem?: string
  ingestedAt?: string
  patientName?: string
  patientDisplayId?: string
  referencedBy: ReferencedBy[]
}

export interface AppointmentDto {
  appointmentId: string
  recordId: string
  orgId: string
  orgName: string
  type: string
  department?: string
  scheduledStart: string
  daysUntil: number
  status?: string
  provider?: string
  description?: string
  obstetric: boolean
  deliveryRelated: boolean
}

export interface PatientSummary {
  patientId: string
  displayId: string
  name: string
  dateOfBirth?: string
  age?: number
  sex?: string
  obstetric: boolean
  estimatedDueDate?: string
  gestationalNote?: string
  primaryOrgName?: string
  demoScenario?: string
  summary?: string
  organizations: string[]
  keyConditions: string[]
  nextAppointment?: AppointmentDto
  openFlagCount: number
  openAlertCount: number
  recordCount: number
}

export interface Condition {
  conditionId: string
  recordId: string
  orgId: string
  code?: string
  codeSystem?: string
  display?: string
  clinicalStatus?: string
  onsetDate?: string
  recordedBy?: string
}

export interface Medication {
  medicationId: string
  recordId: string
  orgId: string
  name?: string
  dose?: string
  route?: string
  frequency?: string
  status?: string
  startDate?: string
  endDate?: string
  prescriber?: string
}

export interface LabResult {
  labId: string
  recordId: string
  orgId: string
  testCode?: string
  testName?: string
  valueNum?: number
  valueText?: string
  unit?: string
  refLow?: number
  refHigh?: number
  refText?: string
  abnormalFlag?: string
  collectedAt?: string
  belowReference?: boolean
  aboveReference?: boolean
  outOfReference?: boolean
}

export interface Encounter {
  encounterId: string
  recordId: string
  orgId: string
  encounterType?: string
  department?: string
  specialty?: string
  providerName?: string
  startTime?: string
  reason?: string
  symptomCategory?: string
  fractureRelated: boolean
}

export interface AiObservation {
  observationId: string
  patientId: string
  recordId: string
  category: string
  statement: string
  evidenceQuote: string
  model: string
  createdAt: string
}

export interface EvidenceDto {
  recordId: string
  role: 'TRIGGER' | 'SUPPORTING' | 'UPCOMING_ENCOUNTER' | string
  description: string
  record?: RecordDto
}

export interface Statement {
  statement: string
  sourceRecordIds: string[]
}

export interface Citation {
  recordId: string
  title: string
  recordType: RecordType
  recordDate?: string
  orgId: string
  orgName: string
  author?: string
  sourceFormat: string
}

export interface AiAnswer {
  mode: 'GEMINI' | 'RETRIEVAL_ONLY'
  aiGenerated: boolean
  model?: string
  generatedAt: string
  answer: string
  documentedFacts: Statement[]
  interpretation: Statement[]
  missingInformation: string[]
  suggestedFollowUpQuestions: string[]
  citations: Citation[]
  retrievedRecords: Citation[]
  validation: { citationsRemoved: number; uncitedFactsRemoved: number; recordsProvided: number }
  latencyMs?: number
  conversationId?: string
  notice?: string
}

export interface FlagDto {
  flagId: string
  patientId: string
  patientName: string
  patientDisplayId: string
  ruleId: string
  ruleVersion: string
  title: string
  detectionReason: string
  reviewPriority: 'EXPEDITED' | 'STANDARD'
  status: 'UNREVIEWED' | 'IN_REVIEW' | 'REVIEWED' | 'DISMISSED'
  statusReason?: string
  createdAt: string
  updatedAt: string
  updatedBy?: string
  lastEvaluatedAt?: string
  evidenceUpdatedAt?: string
  evidence: EvidenceDto[]
  alertId?: string
  aiExplanation?: AiAnswer
}

export type AlertStatus = 'NEW' | 'UNDER_REVIEW' | 'COORDINATION_NEEDED' | 'PLAN_DOCUMENTED' | 'RESOLVED' | 'DISMISSED'

export interface AlertSummary {
  alertId: string
  flagId: string
  patientId: string
  patientName: string
  patientDisplayId: string
  alertType: string
  title: string
  assignedTeam: string
  status: AlertStatus
  priority: 'EXPEDITED' | 'STANDARD'
  encounterDate?: string
  daysUntilEncounter: number
  encounterType?: string
  encounterLocation?: string
  reviewDueDate?: string
  concernSummary?: string
  involvedSpecialties?: string
  sourceOrganizations: string[]
  evidenceCount: number
  acknowledgedBy?: string
  contactStatus: 'NOT_STARTED' | 'PLANNED' | 'COMPLETED'
  planningDiscussionOccurred: boolean
  infoNeeded: boolean
  updatedAt: string
  updatedBy?: string
  evidenceUpdatedAt?: string
  noteCount: number
}

export interface CoordinationNote {
  noteId: string
  alertId: string
  patientId: string
  authorName: string
  authorRole: string
  noteType: string
  body: string
  createdAt: string
}

export interface Activity {
  activityId: string
  entityType: string
  entityId?: string
  patientId?: string
  action: string
  detail: string
  actor: string
  createdAt: string
}

export interface AlertDetail {
  alert: AlertSummary
  patient: PatientSummary
  flag?: FlagDto
  encounter?: AppointmentDto
  evidence: EvidenceDto[]
  notes: CoordinationNote[]
  activity: Activity[]
  dismissReason?: string
  acknowledgedAt?: string
  allowedTransitions: AlertStatus[]
}

export interface PatientProfile {
  patient: PatientSummary
  identifiers: { orgId: string; orgName: string; sourceMrn: string }[]
  conditions: Condition[]
  medications: Medication[]
  labs: LabResult[]
  encounters: Encounter[]
  appointments: AppointmentDto[]
  timeline: RecordDto[]
  flags: FlagDto[]
  alerts: AlertSummary[]
  aiObservations: AiObservation[]
  organizationNames: Record<string, string>
}

export interface Dashboard {
  totalPatients: number
  openFlags: number
  unreviewedAnesthesiaAlerts: number
  alertsInProgress: number
  upcomingComplexEncounterCount: number
  upcomingComplexEncounters: AlertSummary[]
  patientsRequiringReview: {
    patientId: string
    name: string
    displayId: string
    flagTitles: string[]
    highestPriority: string
    openFlags: number
    openAlerts: number
  }[]
  recentActivity: Activity[]
  lastAnalysisRun?: Record<string, unknown>
  anchorDate?: string
  databaseMode: string
  geminiConfigured: boolean
  geminiModel: string
  /** False for roles without the Anesthesia OB workflow: OB metrics are always zero and should be hidden. */
  anesthesiaAccess: boolean
  upcomingEncounters: { patientId: string; patientName?: string; displayId?: string; appointment: AppointmentDto }[]
}

export interface SearchCriteria {
  text?: string | null
  keywords: string[]
  concepts: string[]
  recordTypes: string[]
  organizationIds: string[]
  patientId?: string | null
  dateFrom?: string | null
  dateTo?: string | null
  obstetricOnly?: boolean
  upcomingDeliveryOnly?: boolean
  multipleOrganizationsOnly?: boolean
  openFlagsOnly?: boolean
  minMatchesPerPatient?: number
  matchMode?: 'ANY' | 'ALL'
}

export interface Interpretation {
  /** RECORDS = clinical record search; FLAGS = clinical alert (flag) search using flagCriteria. */
  target: 'RECORDS' | 'FLAGS'
  criteria: SearchCriteria
  flagCriteria?: FlagSearchCriteria
  source: 'GEMINI' | 'RULE_BASED'
  model?: string
  explanation?: string
  latencyMs?: number
  notice?: string
}

export interface RecordHit {
  recordId: string
  patientId: string
  patientName: string
  patientDisplayId: string
  orgId: string
  orgName: string
  recordType: RecordType
  title: string
  recordDate?: string
  author?: string
  specialty?: string
  snippet: string
  tags: string[]
  sourceFormat: string
}

export interface SearchResult {
  criteria: SearchCriteria
  warnings: string[]
  totalRecords: number
  totalPatients: number
  patients: {
    patientId: string
    patientName: string
    displayId: string
    matchCount: number
    distinctDates: number
    organizations: string[]
    records: RecordHit[]
  }[]
  truncated: boolean
}

export interface RuleDescriptor {
  id: string
  version: string
  name: string
  description: string
  parameters: string[]
  routesTo: string
}

export interface RunResult {
  runId: string
  patientsEvaluated: number
  flagsCreated: number
  flagsUpdated: number
  flagsUnchanged: number
  alertsCreated: number
  outcomes: { patientId: string; patientName: string; ruleId: string; flagId: string; alertId?: string; result: string }[]
}

export type Permission =
  | 'VIEW_PATIENTS' | 'CLINICAL_SEARCH' | 'CLINICAL_ALERTS' | 'REVIEW_FLAGS' | 'GEMINI_ASSISTANT'
  | 'ANESTHESIA_OB_ALERTS' | 'UPDATE_COORDINATION' | 'ADD_COORDINATION_NOTES'

export type NavItem = 'DASHBOARD' | 'PATIENTS' | 'CLINICAL_SEARCH' | 'CLINICAL_ALERTS' | 'ANESTHESIA_OB_ALERTS'

/** A SIMULATED demo identity (not an authenticated user). */
export interface SessionUser {
  id: string
  name: string
  role: string
  roleLabel: string
  department: string
  practices: { practiceId: string; name: string }[]
  patientScope: string
}

export interface Session {
  user: SessionUser
  permissions: Permission[]
  navigation: NavItem[]
  simulated: boolean
  notice: string
}

export interface FlagSearchCriteria {
  text?: string | null
  categories?: string[]
  statusGroup?: 'OPEN' | 'CLOSED' | 'ANY'
  statuses?: string[]
  priorities?: string[]
  specialties?: string[]
  concepts?: string[]
  patientId?: string | null
}

export interface FlagHit {
  flagId: string
  patientId: string
  patientName: string
  displayId: string
  title: string
  ruleId: string
  category: string
  status: FlagDto['status']
  alertId?: string
  alertStatus?: AlertStatus
  unresolved: boolean
  reviewPriority: 'EXPEDITED' | 'STANDARD'
  evidenceCount: number
  specialties: string[]
  sourceRecords: { recordId: string; title: string; orgName: string; recordDate?: string; role: string }[]
  detectionReason: string
  updatedAt: string
  link: string
}

export interface FlagSearchResult {
  criteria: FlagSearchCriteria
  results: FlagHit[]
  totalFlags: number
  totalPatients: number
  warnings: string[]
  notice?: string
}

export interface Facets {
  categories: Record<string, string>
  statuses: string[]
  priorities: string[]
  specialties: string[]
}

export interface Organization {
  orgId: string
  name: string
  orgType: string
  sourceSystem: string
  sourceFormat: string
  city: string
  state: string
}

export interface Meta {
  users: { id: string; name: string; role: string }[]
  alertStatuses: AlertStatus[]
  contactStatuses: string[]
  noteTypes: string[]
  flagStatuses: string[]
  concepts: string[]
  recordTypes: string[]
  organizations: Organization[]
  accessControlNotice: string
}

export interface IntegrationStatus {
  database: {
    mode: 'SNOWFLAKE' | 'LOCAL_FALLBACK'
    label: string
    isSnowflake: boolean
    connected: boolean
    session?: Record<string, unknown>
    error?: string
  }
  gemini: {
    configured: boolean
    model: string
    sdk: string
    callsSinceStartup: number
    failuresSinceStartup: number
    lastSuccessAt?: string
    lastError?: string
    lastOperation?: string
  }
  anchorDate?: string
  /** False in public deployments: demo reset and simulated results are run by an operator instead. */
  demoAdminEnabled?: boolean
  syntheticDataNotice: string
}

export interface ExtractionResult {
  patientId: string
  model: string
  generatedAt: string
  recordsAnalyzed: number
  observationsAccepted: number
  observationsRejected: number
  rejectionReasons: string[]
  observations: AiObservation[]
}
