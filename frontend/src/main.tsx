import '@fontsource-variable/inter'
import '@fontsource/quicksand/500.css'
import '@fontsource/quicksand/600.css'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { ApiError } from './api/client'
import './index.css'
import { AppProvider } from './state/AppContext'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Client errors (403 access denied, 404, 400) are definitive; only retry network/server failures once.
      retry: (count, err) => count < 1 && !(err instanceof ApiError && err.status >= 400 && err.status < 500),
      refetchOnWindowFocus: false,
      staleTime: 15_000,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AppProvider>
          <App />
        </AppProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
