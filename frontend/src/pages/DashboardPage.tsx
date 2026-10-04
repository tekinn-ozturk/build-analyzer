import { Alert, Box, LinearProgress, Paper, Typography } from '@mui/material';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { analyzeBuild } from '../api/analysisApi';
import AnalyzeForm from '../components/AnalyzeForm';
import HistoryList from '../components/HistoryList';
import type { JenkinsBuild } from '../utils/jenkinsUrl';

export default function DashboardPage() {
  const navigate = useNavigate();
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleAnalyze(build: JenkinsBuild) {
    setError(null);
    setRunning(true);
    try {
      const analysis = await analyzeBuild(build);
      navigate(`/analyses/${analysis.id}`);
    } catch (e) {
      setError((e as Error).message);
      setRunning(false);
    }
  }

  return (
    <Box>
      <Box sx={{ py: { xs: 2, md: 4 } }}>
        <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
          Jenkins AI Build Analyzer
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5, mb: 3 }}>
          Analyze Jenkins build failures using AI.
        </Typography>
        <AnalyzeForm onAnalyze={handleAnalyze} disabled={running} />
        {error && <Alert severity="error">{error}</Alert>}
        {running && (
          <Paper sx={{ p: 2.5 }}>
            <Typography sx={{ fontWeight: 600, mb: 1.5 }}>Build analiz ediliyor...</Typography>
            <LinearProgress sx={{ height: 6, borderRadius: 3 }} />
          </Paper>
        )}
      </Box>

      <Typography variant="h6" component="h2" sx={{ mb: 1 }}>
        Build History
      </Typography>
      <HistoryList />
    </Box>
  );
}
