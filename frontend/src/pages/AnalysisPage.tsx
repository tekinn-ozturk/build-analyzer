import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import { Alert, Box, Button, Link, Stack, Tab, Tabs, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router';
import { getAnalysis } from '../api/analysisApi';
import AiAnalysis from '../components/AiAnalysis';
import LogViewer from '../components/LogViewer';
import StatusChip from '../components/StatusChip';
import type { Analysis, TestSummary } from '../types/analysis';
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
        <Typography variant="body2">Job: {analysis.jobName}</Typography>
        <Typography variant="body2">Analyzed: {formatDateTime(analysis.analyzedAt)}</Typography>
        <Link href={analysis.buildUrl} target="_blank" rel="noreferrer" variant="body2" underline="hover">
          Jenkins'te aç <OpenInNewIcon sx={{ fontSize: 14, verticalAlign: 'middle' }} />
        </Link>
      </Stack>
      {analysis.testSummary && <TestResultBar summary={analysis.testSummary} />}

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

/** Failed / passed / skipped as one bar, with the counts the log printed ("20 başarısız · 10 başarılı · … · %33 başarı"). */
function TestResultBar({ summary }: { summary: TestSummary }) {
  const parts = [
    { label: 'başarısız', count: summary.failed, color: 'error.main' },
    { label: 'başarılı', count: summary.passed, color: 'success.main' },
    { label: 'atlanan', count: summary.skipped, color: 'grey.400' },
  ];
  // pytest prints no total: the bar is then drawn over the counts it did print
  const total = summary.total ?? parts.reduce((sum, part) => sum + (part.count ?? 0), 0);
  if (total === 0) {
    return null;
  }

  return (
    <Box sx={{ mt: 2 }}>
      <Box
        role="img"
        aria-label={parts.map((part) => `${part.count ?? '?'} ${part.label}`).join(', ')}
        sx={{ display: 'flex', height: 10, borderRadius: 5, overflow: 'hidden', bgcolor: 'grey.200' }}
      >
        {parts.map((part) => (
          <Box key={part.label} sx={{ width: `${((part.count ?? 0) / total) * 100}%`, bgcolor: part.color }} />
        ))}
      </Box>
      <Stack direction="row" sx={{ mt: 1, columnGap: 2.5, rowGap: 0.5, flexWrap: 'wrap', color: 'text.secondary' }}>
        {parts.filter((part) => part.count !== null).map((part) => (
          <Typography key={part.label} variant="body2" sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
            <Box component="span" sx={{ width: 10, height: 10, borderRadius: 0.5, bgcolor: part.color }} />
            {part.count} {part.label}
          </Typography>
        ))}
        {summary.total !== null && <Typography variant="body2">{summary.total} test</Typography>}
        {summary.total !== null && summary.total > 0 && summary.passed !== null && (
          <Typography variant="body2">%{Math.round((summary.passed / summary.total) * 100)} başarı</Typography>
        )}
      </Stack>
    </Box>
  );
}
