import { Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { EmptyState } from './components/ui'
import AlertWorkspace from './pages/AlertWorkspace'
import AnesthesiaAlerts from './pages/AnesthesiaAlerts'
import CareCoordination from './pages/CareCoordination'
import ClinicalFlags from './pages/ClinicalFlags'
import ClinicalSearch from './pages/ClinicalSearch'
import Dashboard from './pages/Dashboard'
import FlagDetail from './pages/FlagDetail'
import PatientProfile from './pages/PatientProfile'
import Patients from './pages/Patients'
import SystemStatus from './pages/SystemStatus'

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/patients" element={<Patients />} />
        <Route path="/patients/:id" element={<PatientProfile />} />
        <Route path="/search" element={<ClinicalSearch />} />
        <Route path="/flags" element={<ClinicalFlags />} />
        <Route path="/flags/:id" element={<FlagDetail />} />
        <Route path="/alerts" element={<AnesthesiaAlerts />} />
        <Route path="/alerts/:id" element={<AlertWorkspace />} />
        <Route path="/coordination" element={<CareCoordination />} />
        <Route path="/system" element={<SystemStatus />} />
        <Route path="*" element={<EmptyState title="Page not found">Use the navigation on the left.</EmptyState>} />
      </Routes>
    </Layout>
  )
}
