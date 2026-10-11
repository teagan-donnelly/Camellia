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
  acknowledgedAt?: string
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
  /** Structured patient traits (exact match per trait). */
  traits?: TraitCode[]
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
  /** Keyword matches in uploaded documents (never sent to Gemini). */
  documents?: DocumentHit[]
}

export interface DocumentHit {
  documentId: string
  patientId: string
  patientName: string
  patientDisplayId?: string
  title: string
  fileName: string
  fileType: DocumentFileType
  uploadedAt: string
  uploadedBy: string
  encounterId?: string
  snippet: string
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
  | 'IMPORT_RECORDS' | 'CLINICAL_BRIEFING' | 'VIEW_MONITORING' | 'NURSING_MONITORING' | 'DOCUMENT_NURSING_INTERVENTIONS'
  | 'SIMULATE_DEVICES' | 'UPLOAD_DOCUMENTS'

export type NavItem = 'DASHBOARD' | 'BRIEFING' | 'PATIENTS' | 'CLINICAL_SEARCH' | 'CLINICAL_ALERTS' | 'ANESTHESIA_OB_ALERTS'
  | 'NURSING_MONITORING'

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
    /** Why this database is in use. */
    state?: 'SNOWFLAKE_CONNECTED' | 'SNOWFLAKE_UNAVAILABLE' | 'LOCAL_FALLBACK_SELECTED' | 'CONFIGURATION_MISSING'
    explanation?: string
    requestedMode?: string
    snowflakeSettingsPresent?: boolean
    missingSettings?: string[]
    dataNote?: string
  }
  elevenlabs?: {
    configured: boolean
    modelId: string
    voiceId?: string
    voiceName?: string
    outputFormat: string
    callsSinceStartup: number
    failuresSinceStartup: number
    lastError?: string
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

// ===================================================================================== Camellia V3

export interface Reading { value: number; unit: string; at: string; quality: string }

export interface MonitoringCard {
  sessionId: string
  sessionStatus: 'ACTIVE' | 'STOPPED'
  deviceStatus: 'CONNECTED' | 'STALE' | 'DISCONNECTED' | 'STOPPED'
  simulatedNow: string
  walkingTodayMinutes: number
  lastActivityAt?: string
  lastActivityType?: string
  latestHeartRate?: Reading
  latestSpo2?: Reading
  latestSkinTemp?: Reading
  batteryPct?: number
  lastSyncAt?: string
  dataStale: boolean
  openAlerts: number
}

export interface EncounterView {
  encounterId: string
  encounterType: string
  inpatient: boolean
  orgId: string
  orgName?: string
  department?: string
  specialty?: string
  reason?: string
  providerName?: string
  startTime: string
  endTime?: string
  ongoing: boolean
  monitoring?: MonitoringCard
}

export interface MonitoringAlert {
  alertId: string
  sessionId?: string
  patientId: string
  encounterId: string
  alertType: 'MOBILITY_REVIEW' | 'REPOSITIONING_REVIEW' | 'DEVICE_CONNECTIVITY'
  status: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'
  message: string
  basis?: string
  triggeredAt: string
  updatedAt?: string
  updatedBy?: string
  resolution?: string
}

export interface CarePlan {
  planId: string
  mobilityReviewMinutes?: number
  repositioningIntervalMinutes?: number
  staleDataMinutes?: number
  note?: string
  createdBy?: string
}

export interface MonitoringDetail {
  patientId: string
  patientName: string
  displayId: string
  encounter: EncounterView
  device?: { deviceId: string; model: string; firmware: string; simulated: boolean }
  plan?: CarePlan
  card?: MonitoringCard
  alerts: MonitoringAlert[]
  timeZone: string
  notice: string
  simulatorActions: string[]
  canSimulate: boolean
  canDocument: boolean
}

export interface SeriesPoint { t: string; v: number; quality: string }

export interface ObservationSeries {
  range: '24h' | '7d' | 'stay'
  from: string
  to: string
  bucket: 'HOUR' | 'DAY'
  series: Record<'HEART_RATE' | 'SPO2' | 'SKIN_TEMP', SeriesPoint[]>
  units: Record<string, string>
  walking: { start: string; label: string; minutes: number }[]
  simulatedNow: string
  notice: string
}

export interface TimelineItem {
  id: string
  kind: string
  type: string
  label: string
  source: 'SENSOR' | 'NURSE' | 'SYSTEM'
  at: string
  endedAt?: string
  durationSeconds?: number
  detail?: string
  by?: string
}

export interface NursingQueue {
  patients: {
    patientId: string; patientName: string; displayId: string; encounterId: string; orgName?: string; department?: string
    admittedAt: string; card: MonitoringCard; nextRepositioningDueAt?: string; nextMobilityReviewAt?: string
  }[]
  alerts: { alert: MonitoringAlert; patientName: string; displayId?: string; orgName?: string; department?: string }[]
  notice: string
}

export interface BriefingSummary { briefingId: string; mode: 'FULL' | 'PRIORITY'; status: string; durationSeconds?: number; createdAt: string }

export interface ShiftView {
  shiftId: string
  shiftDate: string
  startAt: string
  endAt: string
  label: string
  department: string
  location: string
  orgName?: string
  assignedPatients: number
  authorizedPatients: number
  excludedPatients: number
  openItems: number
  patientNames: string[]
  latest: Record<string, BriefingSummary>
}

export interface BriefingSource { type: string; id: string; patientId: string; patientName?: string; label?: string; link?: string }

export interface BriefingSegment { index: number; heading: string; narration: string; patientIds: string[]; startSeconds?: number; sources: BriefingSource[] }

export interface BriefingView {
  briefingId: string
  shiftId: string
  shiftLabel?: string
  shiftDate?: string
  shiftStart?: string
  shiftEnd?: string
  location?: string
  providerName: string
  providerRole: string
  mode: 'FULL' | 'PRIORITY'
  status: 'QUEUED' | 'SCRIPTING' | 'SYNTHESIZING' | 'READY' | 'SCRIPT_ONLY' | 'FAILED'
  statusDetail?: string
  reused: boolean
  patientCount: number
  openAlertCount: number
  excludedPatients: number
  durationSeconds?: number
  estimatedSeconds?: number
  wordCount?: number
  title?: string
  geminiModel?: string
  ttsProvider?: string
  ttsModel?: string
  voiceId?: string
  audioAvailable: boolean
  createdAt: string
  completedAt?: string
  segments: BriefingSegment[]
  opening?: string
  closing?: string
  notice: string
}

export interface ImportPreview {
  valid: boolean
  errors: string[]
  warnings: string[]
  fileName: string
  fileSha256?: string
  format?: string
  organization?: { orgId: string; name?: string; known: boolean }
  patient?: { mrn: string; firstName: string; lastName: string; dateOfBirth?: string; sex?: string }
  counts: Record<string, number>
  totalRecords: number
  duplicateRecords: number
  items: { sourceId: string; recordType: string; title: string; date?: string; duplicate: boolean }[]
  matches: { patientId: string; displayId: string; name: string; dateOfBirth?: string; matchType: string; detail: string }[]
  suggestedDecision?: 'CREATE_NEW' | 'ATTACH_EXISTING'
  suggestedPatientId?: string
  previouslyImported: boolean
  notice: string
}

export interface ImportResult {
  importId: string
  patientId: string
  patientName: string
  displayId: string
  decision: string
  recordsInserted: number
  recordsDuplicate: number
  recordIds: string[]
  screeningRun: boolean
  screeningOutcomes: string[]
  notice: string
}

export interface ImportSample { file: string; title: string; description: string; format: string; scenario: string }

export interface ImportBatch {
  importId: string; fileName: string; sourceFormat: string; orgId: string; patientId: string; matchDecision: string
  recordsInserted: number; recordsDuplicate: number; status: string; importedBy: string; importedAt: string
}

// ---------------------------------------------------------------- V3.1: traits, documents, shift schedule

export type TraitCode = 'OBESITY' | 'MORBID_OBESITY'

export interface PatientTrait {
  traitId: string
  traitCode: TraitCode
  label: string
  status: 'ACTIVE' | 'INACTIVE'
  codeSystem?: string
  code?: string
  codeDisplay?: string
  encounterId?: string
  orgId?: string
  sourceRecordId?: string
  recordedAt?: string
  recordedBy?: string
}

export type DocumentFileType = 'PDF' | 'DOCX' | 'DOC'

export interface PatientDocument {
  documentId: string
  patientId: string
  encounterId?: string
  title: string
  fileName: string
  fileType: DocumentFileType
  sizeBytes: number
  /** EXTRACTED = searchable text; NO_TEXT = e.g. a scanned PDF (no OCR); FAILED = text could not be read. */
  textStatus: 'EXTRACTED' | 'NO_TEXT' | 'FAILED'
  textChars?: number
  pageCount?: number
  uploadedAt: string
  uploadedBy: string
  duplicateOf?: string
}

export interface ScheduleShift {
  scheduleId: string
  label: string
  orgName: string
  department: string
  unit: string
  startAt: string
  endAt: string
  status: 'SCHEDULED' | 'OPEN'
  timing: 'IN_PROGRESS' | 'UPCOMING' | 'COMPLETED'
  staffed?: number
  target?: number
  coverage?: 'FULL' | 'SHORT' | 'UNDERSTAFFED'
  note?: string
  linkedShiftId?: string
  briefingAvailable: boolean
}

export interface ShiftSchedule {
  timeZone: string
  now: string
  current?: ScheduleShift
  next?: ScheduleShift
  myShifts: ScheduleShift[]
  openShifts: ScheduleShift[]
}
