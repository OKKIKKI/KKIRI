import { Route, Routes } from 'react-router-dom';
import Logo from './components/Logo';
import JoinPage from './pages/JoinPage';
import MePage from './pages/MePage';
import MusicConnectPage from './pages/MusicConnectPage';
import Placeholder from './pages/Placeholder';
import PlacesPage from './pages/PlacesPage';
import SessionHomePage from './pages/SessionHomePage';
import SessionLayout from './pages/SessionLayout';
import SummaryPage from './pages/SummaryPage';

// 명세 7절 웹 주소 규칙
export default function App() {
  return (
    <>
      <Logo />
      <Routes>
      <Route path="/j/:qrToken" element={<JoinPage />} /> {/* WEB-01 */}
      <Route path="/s/:sessionId/summary" element={<SummaryPage />} /> {/* WEB-04 */}
      <Route path="/s/:sessionId" element={<SessionLayout />}>
        <Route index element={<SessionHomePage />} /> {/* WEB-02 (탭: 세션 / 음악 WEB-05) */}
        <Route path="places" element={<PlacesPage />} /> {/* WEB-03 */}
        <Route path="music/connect" element={<MusicConnectPage />} /> {/* WEB-06 */}
      </Route>
      <Route path="/me" element={<MePage />} /> {/* WEB-07 */}
      <Route path="*" element={<Placeholder />} />
      </Routes>
    </>
  );
}
