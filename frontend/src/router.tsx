import { createBrowserRouter, Navigate } from 'react-router-dom'
import { AppLayout } from '@/layouts/AppLayout'
import { AuthGate, GuestOnly } from '@/features/auth/AuthGate'
import { AuthPage } from '@/features/auth/AuthPage'
import { DashboardPage } from '@/features/dashboard/DashboardPage'
import { BoardPage } from '@/features/board/BoardPage'
import { SettingsPage } from '@/features/settings/SettingsPage'
import { CompletedPage } from '@/features/completed/CompletedPage'
import { MeetingsPage } from '@/features/meetings/MeetingsPage'
import { MyWorkPage } from '@/features/my-work/MyWorkPage'

export const router = createBrowserRouter([
  {
    element: <GuestOnly />,
    children: [
      { path: '/login', element: <AuthPage mode="login" /> },
      { path: '/register', element: <AuthPage mode="register" /> },
    ],
  },
  {
    element: <AuthGate />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: '/', element: <DashboardPage /> },
          { path: '/my-work', element: <MyWorkPage /> },
          { path: '/meetings', element: <MeetingsPage /> },
          {
            path: '/reports',
            // recharts yalniz analitik/rapor sayfalarinda: ayri chunk olarak yuklenir.
            lazy: async () => ({ Component: (await import('@/features/reports/ReportsPage')).ReportsPage }),
          },
          { path: '/projects/:projectId', element: <BoardPage /> },
          { path: '/projects/:projectId/completed', element: <CompletedPage /> },
          {
            path: '/projects/:projectId/analytics',
            // recharts yalniz bu sayfada: ayri chunk olarak yuklenir.
            lazy: async () => ({ Component: (await import('@/features/analytics/AnalyticsPage')).AnalyticsPage }),
          },
          { path: '/settings', element: <SettingsPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
