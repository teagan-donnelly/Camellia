import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, get, patch, post } from './client'
import type {
  AiAnswer, AlertDetail, AlertSummary, CoordinationNote, Dashboard, ExtractionResult, Facets, FlagDto, FlagSearchCriteria,
  FlagSearchResult, IntegrationStatus, Interpretation, Meta, PatientProfile, PatientSummary, RecordDetail, RuleDescriptor, RunResult,
  SearchCriteria, SearchResult, Session, SessionUser,
  PatientDocument, PatientTrait, ShiftSchedule,
  BriefingView, EncounterView, ImportBatch, ImportSample, MonitoringDetail, NursingQueue, ObservationSeries, ShiftView, TimelineItem,
} from './types'

export const keys = {
  session: ['session'] as const,
  sessionUsers: ['session-users'] as const,
  flagSearch: (params: string) => ['flag-search', params] as const,
  facets: ['flag-facets'] as const,
  dashboard: ['dashboard'] as const,
  patients: ['patients'] as const,
  patient: (id: string) => ['patient', id] as const,
  record: (id: string) => ['record', id] as const,
  flags: ['flags'] as const,
  flag: (id: string) => ['flag', id] as const,
  alerts: ['alerts'] as const,
  alert: (id: string) => ['alert', id] as const,
  rules: ['rules'] as const,
  meta: ['meta'] as const,
  status: ['integration-status'] as const,
}

export const useSession = () => useQuery({ queryKey: keys.session, queryFn: () => get<Session>('/session'), staleTime: Infinity, retry: 0 })
export const useSessionUsers = () => useQuery({ queryKey: keys.sessionUsers, queryFn: () => get<SessionUser[]>('/session/users'), staleTime: Infinity })
export const selectSession = (userId: string) => post<Session>('/session', { userId })

export const useDashboard = () => useQuery({ queryKey: keys.dashboard, queryFn: () => get<Dashboard>('/dashboard') })
export const usePatients = () => useQuery({ queryKey: keys.patients, queryFn: () => get<PatientSummary[]>('/patients') })
export const usePatient = (id: string) => useQuery({ queryKey: keys.patient(id), queryFn: () => get<PatientProfile>(`/patients/${id}`) })
export const useRecord = (id: string | null) =>
  useQuery({ queryKey: keys.record(id ?? ''), queryFn: () => get<RecordDetail>(`/records/${id}`), enabled: !!id })
export const useFlags = () => useQuery({ queryKey: keys.flags, queryFn: () => get<FlagDto[]>('/flags') })
export const useFlag = (id: string) => useQuery({ queryKey: keys.flag(id), queryFn: () => get<FlagDto>(`/flags/${id}`) })
export const useAlerts = (enabled = true) =>
  useQuery({ queryKey: keys.alerts, queryFn: () => get<AlertSummary[]>('/coordination-alerts'), enabled })
export const useAlert = (id: string) => useQuery({ queryKey: keys.alert(id), queryFn: () => get<AlertDetail>(`/coordination-alerts/${id}`) })
export const useRules = () => useQuery({ queryKey: keys.rules, queryFn: () => get<RuleDescriptor[]>('/analysis/rules'), staleTime: Infinity })
/** Structured clinical-alert (flag) search. `params` is a URL query string built by the filter bar. */
export const useFlagSearch = (params: string) =>
  useQuery({ queryKey: keys.flagSearch(params), queryFn: () => get<FlagSearchResult>(`/flags/search${params ? '?' + params : ''}`), placeholderData: (prev) => prev })
export const useFlagFacets = () => useQuery({ queryKey: keys.facets, queryFn: () => get<Facets>('/flags/facets'), staleTime: Infinity })
export const executeFlagSearch = (criteria: FlagSearchCriteria) => post<FlagSearchResult>('/flags/search', criteria)
export const useMeta = () => useQuery({ queryKey: keys.meta, queryFn: () => get<Meta>('/meta'), staleTime: Infinity })
// Header badges. Polled slowly: every poll reaches the database, and frequent polls from open tabs would keep a
// Snowflake warehouse from suspending. Actions that change status (checks, demo reset) refetch it immediately.
export const useIntegrationStatus = () =>
  useQuery({ queryKey: keys.status, queryFn: () => get<IntegrationStatus>('/integrations/status'), refetchInterval: 5 * 60_000 })

/** Invalidate everything that reflects workflow state after a write. */
export function useInvalidateWorkflow() {
  const qc = useQueryClient()
  return () => {
    for (const k of [keys.dashboard, keys.patients, keys.flags, keys.alerts, keys.facets]) qc.invalidateQueries({ queryKey: k })
    qc.invalidateQueries({ queryKey: ['flag-search'] })
    qc.invalidateQueries({ queryKey: ['alert'] })
    qc.invalidateQueries({ queryKey: ['flag'] })
    qc.invalidateQueries({ queryKey: ['patient'] })
    qc.invalidateQueries({ queryKey: ['record'] })
  }
}

export function useRunAnalysis() {
  const invalidate = useInvalidateWorkflow()
  return useMutation({ mutationFn: () => post<RunResult>('/analysis/run'), onSuccess: invalidate })
}

export interface AlertUpdate {
  acknowledge?: boolean
  status?: string
  contactStatus?: string
  planningDiscussionOccurred?: boolean
  infoNeeded?: boolean
  reason?: string
  note?: string
}

export function useUpdateAlert(alertId: string) {
  const invalidate = useInvalidateWorkflow()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (u: AlertUpdate) => patch<AlertDetail>(`/coordination-alerts/${alertId}/status`, u),
    // The PATCH returns the saved alert: show it immediately (a Snowflake round trip already took a few seconds),
    // then refresh the lists and boards that summarize it.
    onSuccess: (detail) => { qc.setQueryData(keys.alert(alertId), detail); invalidate() },
  })
}

export function useAddNote(alertId: string) {
  const invalidate = useInvalidateWorkflow()
  return useMutation({
    mutationFn: (n: { noteType: string; body: string }) => post<CoordinationNote>(`/coordination-alerts/${alertId}/notes`, n),
    onSuccess: invalidate,
  })
}

export function useUpdateFlagStatus(flagId: string) {
  const invalidate = useInvalidateWorkflow()
  return useMutation({
    mutationFn: (b: { status: string; reason?: string }) => patch<FlagDto>(`/flags/${flagId}/status`, b),
    onSuccess: invalidate,
  })
}

export function useExplainFlag(flagId: string | undefined) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (refresh: boolean) => post<AiAnswer>('/ai/explain-flag', { flagId, refresh }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['flag'] })
      qc.invalidateQueries({ queryKey: ['alert'] })
    },
  })
}

export function useExtraction(patientId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => post<ExtractionResult>(`/patients/${patientId}/ai-extraction`),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.patient(patientId) }),
  })
}

export const interpretSearch = (query: string, patientId?: string) => post<Interpretation>('/search/interpret', { query, patientId })
export const executeSearch = (criteria: SearchCriteria) => post<SearchResult>('/search/execute', criteria)
export const summarizeSearch = (query: string, recordIds: string[]) => post<AiAnswer>('/ai/search-summary', { query, recordIds })

// ===================================================================================== Camellia V3

export const useEncounters = (patientId?: string) =>
  useQuery({ queryKey: ['encounters', patientId], queryFn: () => get<EncounterView[]>(`/patients/${patientId}/encounters`), enabled: !!patientId })

export const useMonitoring = (patientId?: string, encounterId?: string) =>
  useQuery({
    queryKey: ['monitoring', patientId, encounterId],
    queryFn: () => get<MonitoringDetail>(`/patients/${patientId}/encounters/${encounterId}/monitoring`),
    enabled: !!patientId && !!encounterId,
  })

export const useObservations = (patientId?: string, encounterId?: string, range = '24h') =>
  useQuery({
    queryKey: ['monitoring-obs', patientId, encounterId, range],
    queryFn: () => get<ObservationSeries>(`/patients/${patientId}/encounters/${encounterId}/monitoring/observations?range=${range}`),
    enabled: !!patientId && !!encounterId,
    placeholderData: (prev) => prev,
  })

export const useTimeline = (patientId?: string, encounterId?: string) =>
  useQuery({
    queryKey: ['monitoring-events', patientId, encounterId],
    queryFn: () => get<TimelineItem[]>(`/patients/${patientId}/encounters/${encounterId}/monitoring/events`),
    enabled: !!patientId && !!encounterId,
  })

export const useNursingQueue = (enabled = true) =>
  useQuery({ queryKey: ['nursing-queue'], queryFn: () => get<NursingQueue>('/monitoring/alerts'), enabled })

/** Everything that shows monitoring state (cards, detail, charts, timeline, queue). */
export function useInvalidateMonitoring() {
  const qc = useQueryClient()
  return () => {
    for (const k of ['encounters', 'monitoring', 'monitoring-obs', 'monitoring-events', 'nursing-queue']) qc.invalidateQueries({ queryKey: [k] })
  }
}

export const useShifts = (enabled = true) =>
  useQuery({ queryKey: ['briefing-shifts'], queryFn: () => get<ShiftView[]>('/briefings/shifts'), enabled })

export const useBriefing = (id?: string) =>
  useQuery({
    queryKey: ['briefing', id],
    queryFn: () => get<BriefingView>(`/briefings/${id}`),
    enabled: !!id,
    // Poll while Gemini / ElevenLabs are working.
    refetchInterval: (q) => (q.state.data && ['QUEUED', 'SCRIPTING', 'SYNTHESIZING'].includes(q.state.data.status) ? 1500 : false),
  })

export const useImportSamples = (enabled = true) =>
  useQuery({ queryKey: ['import-samples'], queryFn: () => get<ImportSample[]>('/import/samples'), enabled, staleTime: Infinity })

export const useImportHistory = (enabled = true) =>
  useQuery({ queryKey: ['import-history'], queryFn: () => get<ImportBatch[]>('/import/history'), enabled })

// ---------------------------------------------------------------- V3.1

/** Diagnosis-derived traits. Only requested for clinical roles (the server enforces the same rule). */
export const usePatientTraits = (patientId: string, enabled: boolean) =>
  useQuery({ queryKey: ['patient-traits', patientId], queryFn: () => get<PatientTrait[]>(`/patients/${patientId}/traits`), enabled })

export const usePatientDocuments = (patientId: string) =>
  useQuery({ queryKey: ['patient-documents', patientId], queryFn: () => get<PatientDocument[]>(`/patients/${patientId}/documents`) })

export function useUploadDocument(patientId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ file, title, encounterId }: { file: File; title?: string; encounterId?: string }) => {
      const form = new FormData()
      form.append('file', file)
      if (title) form.append('title', title)
      if (encounterId) form.append('encounterId', encounterId)
      return api<PatientDocument>(`/patients/${patientId}/documents`, { method: 'POST', body: form })
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['patient-documents', patientId] })
      qc.invalidateQueries({ queryKey: keys.dashboard })
    },
  })
}

/** Authorized file URL (the server re-checks access on every request; nothing is cached). */
export const documentFileUrl = (documentId: string, download = false) =>
  `/api/documents/${encodeURIComponent(documentId)}/file${download ? '?download=true' : ''}`

export const useShiftSchedule = () =>
  useQuery({ queryKey: ['shift-schedule'], queryFn: () => get<ShiftSchedule>('/shifts/schedule'), staleTime: 5 * 60_000 })
