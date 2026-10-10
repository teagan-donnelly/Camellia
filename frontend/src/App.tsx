import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import type { Permission } from './api/types'
import { Layout } from './components/Layout'
import { EmptyState, Restricted } from './components/ui'
import AlertWorkspace from './pages/AlertWorkspace'
import AnesthesiaAlerts from './pages/AnesthesiaAlerts'
import ClinicalFlags from './pages/ClinicalFlags'
import ClinicalSearch from './pages/ClinicalSearch'
import Dashboard from './pages/Dashboard'
import FlagDetail from './pages/FlagDetail'
import PatientProfile from './pages/PatientProfile'
import Patients from './pages/Patients'
import SystemStatus from './pages/SystemStatus'
import { useApp } from './state/AppContext'

/** Hides pages the simulated role may not use. The backend enforces the same rule on every endpoint. */
function RequirePermission({ permission, message, children }: { permission: Permission; message: string; children: ReactNode }) {
  const { can } = useApp()
  return can(permission) ? children : <Restricted message={message} />
}

const ANESTHESIA_ONLY = 'Anesthesia OB Alerts are available to Labor & Delivery nurses, nurse anesthetists and anesthesiologists.'

export default function App() {
  const { user } = useApp()
  return (
    <Layout>
      {/* Keyed by user so switching identity remounts every page: no state, chat history or drafts carry over. */}
      <Routes key={user.id}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/patients" element={<Patients />} />
        <Route path="/patients/:id" element={<PatientProfile />} />
        <Route path="/search" element={<ClinicalSearch />} />
        <Route path="/flags" element={<ClinicalFlags />} />
        <Route path="/flags/:id" element={<FlagDetail />} />
        <Route path="/alerts" element={<RequirePermission permission="ANESTHESIA_OB_ALERTS" message={ANESTHESIA_ONLY}><AnesthesiaAlerts /></RequirePermission>} />
        <Route path="/alerts/:id" element={<RequirePermission permission="ANESTHESIA_OB_ALERTS" message={ANESTHESIA_ONLY}><AlertWorkspace /></RequirePermission>} />
        <Route path="/coordination" element={<Navigate to="/alerts?tab=board" replace />} />
        <Route path="/system" element={<SystemStatus />} />
        <Route path="*" element={<EmptyState title="Page not found">Use the navigation on the left.</EmptyState>} />
      </Routes>
    </Layout>
  )
}
