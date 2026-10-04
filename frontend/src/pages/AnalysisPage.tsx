import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import { Alert, Box, Button, Link, Stack, Tab, Tabs, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router';
import { getAnalysis } from '../api/analysisApi';
import AiAnalysis from '../components/AiAnalysis';
import LogViewer from '../components/LogViewer';
import StatusChip from '../components/StatusChip';
import type { Analysis } from '../types/analysis';
import { formatDateTime } from '../utils/format';

export default function AnalysisPage() {
  const { id } = useParams();
  const [tab, setTab] = useState(0);
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (id) {
      getAnalysis(id).then(setAnalysis).catch((e: Error) => setError(e.message));
    }
  }, [id]);

  if (error) {
    return (
      <Alert severity="warning" action={<Button component={RouterLink} to="/history">Build History</Button>}>
        {error}
      </Alert>
    );
  }
  if (!analysis) {
    return <Typography color="text.secondary">Yükleniyor...</Typography>;
  }

  return (
    <Box>
      <Typography variant="body2" color="text.secondary">
        <Link component={RouterLink} to="/history" color="inherit" underline="hover">
          Build History
        </Link>
        {' › '}
        {analysis.jobName}
      </Typography>
      <Stack direction="row" spacing={1.5} sx={{ mt: 1, alignItems: 'center' }}>
        <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
          Build #{analysis.buildNumber}
        </Typography>
        <StatusChip status={analysis.status} />
      </Stack>
      <Stack direction="row" spacing={3} sx={{ mt: 1, color: 'text.secondary', flexWrap: 'wrap' }}>
        <Typography variant="body2">Project: {analysis.jobName}</Typography>
        <Typography variant="body2">Analyzed: {formatDateTime(analysis.analyzedAt)}</Typography>
        <Link href={analysis.buildUrl} target="_blank" rel="noreferrer" variant="body2" underline="hover">
          Jenkins'te aç <OpenInNewIcon sx={{ fontSize: 14, verticalAlign: 'middle' }} />
        </Link>
      </Stack>

      <Tabs value={tab} onChange={(_, value: number) => setTab(value)} sx={{ my: 3, borderBottom: 1, borderColor: 'divider' }}>
        <Tab label="AI Analysis" />
        <Tab label="Relevant Logs" />
      </Tabs>

      {tab === 0 && <AiAnalysis analysis={analysis} />}
      {tab === 1 && (
        <LogViewer
          relevantLogSnippet={analysis.relevantLogs}
          last200Lines={analysis.last200Lines}
          buildUrl={analysis.buildUrl}
        />
      )}
    </Box>
  );
}
