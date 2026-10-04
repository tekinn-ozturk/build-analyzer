import {
  Alert, Link, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router';
import { listAnalyses } from '../api/analysisApi';
import type { AnalysisSummary } from '../types/analysis';
import { formatDateTime } from '../utils/format';
import StatusChip from './StatusChip';

/** The build history (from PostgreSQL) as a Jenkins-style list; a row opens the build's analysis. */
export default function HistoryList() {
  const navigate = useNavigate();
  const [analyses, setAnalyses] = useState<AnalysisSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listAnalyses().then(setAnalyses).catch((e: Error) => setError(e.message));
  }, []);

  if (error) {
    return <Alert severity="error">{error}</Alert>;
  }
  if (!analyses) {
    return <Typography color="text.secondary">Yükleniyor...</Typography>;
  }
  if (analyses.length === 0) {
    return (
      <Paper sx={{ p: 3 }}>
        <Typography color="text.secondary">Henüz analiz yok. Bir Jenkins build URL'i yapıştırıp analiz edin.</Typography>
      </Paper>
    );
  }

  return (
    <TableContainer component={Paper}>
      <Table size="small" sx={{ minWidth: 520 }}>
        <TableHead>
          <TableRow sx={{ '& th': { fontWeight: 700, color: 'text.secondary', bgcolor: 'grey.50' } }}>
            <TableCell>Build</TableCell>
            <TableCell>Job</TableCell>
            <TableCell>Status</TableCell>
            <TableCell>Analysis Date</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {analyses.map((analysis) => (
            <TableRow
              key={analysis.id}
              hover
              onClick={() => navigate(`/analyses/${analysis.id}`)}
              sx={{ cursor: 'pointer', '& td': { py: 1.25 } }}
            >
              <TableCell sx={{ fontWeight: 700, width: 90 }}>
                <Link component={RouterLink} to={`/analyses/${analysis.id}`} underline="hover" color="inherit">
                  #{analysis.buildNumber}
                </Link>
              </TableCell>
              <TableCell>{analysis.jobPath}</TableCell>
              <TableCell sx={{ width: 130 }}>
                <StatusChip status={analysis.buildStatus} />
              </TableCell>
              <TableCell sx={{ width: 160, color: 'text.secondary' }}>{formatDateTime(analysis.analyzedAt)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
