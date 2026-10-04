import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlineOutlined';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import ReportProblemOutlinedIcon from '@mui/icons-material/ReportProblemOutlined';
import { Alert, Box, Paper, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { monoFont } from '../theme';
import type { Analysis } from '../types/analysis';
import TestContextView from './TestContextView';

/**
 * The main screen: Hata Sebebi → where it failed → Çözüm Önerileri.
 * The only rule for "where": testContext present → IDE view, otherwise a plain error summary card.
 */
export default function AiAnalysis({ analysis }: { analysis: Analysis }) {
  const { ai } = analysis;
  if (!ai) {
    return <Alert severity="success">Build başarılı. Analiz edilecek bir hata yok.</Alert>;
  }

  return (
    <Stack spacing={2}>
      <Paper sx={{ p: 3, borderLeft: 4, borderLeftColor: 'error.main' }}>
        <SectionTitle icon={<ReportProblemOutlinedIcon color="error" />}>HATA SEBEBİ</SectionTitle>
        <Typography variant="h6" component="p" sx={{ fontWeight: 600, lineHeight: 1.45 }}>
          {ai.cause}
        </Typography>
        {ai.explanation && (
          <Typography color="text.secondary" sx={{ mt: 1.5 }}>
            {ai.explanation}
          </Typography>
        )}
      </Paper>

      {analysis.testContext ? (
        <TestContextView
          context={analysis.testContext}
          errorType={analysis.errorType}
          errorMessage={analysis.errorMessage}
        />
      ) : (
        <Paper sx={{ p: 3 }}>
          <SectionTitle icon={<InfoOutlinedIcon color="action" />}>HATA ÖZETİ</SectionTitle>
          {analysis.errorType && <Typography sx={{ fontWeight: 600 }}>{analysis.errorType}</Typography>}
          {analysis.errorMessage && (
            <Box sx={{ mt: 1, p: 1.5, bgcolor: 'grey.100', borderRadius: 1, fontFamily: monoFont, fontSize: 13, wordBreak: 'break-word' }}>
              {analysis.errorMessage}
            </Box>
          )}
        </Paper>
      )}

      {ai.suggestions.length > 0 && (
        <Paper sx={{ p: 3 }}>
          <SectionTitle icon={<CheckCircleOutlineIcon color="success" />}>ÇÖZÜM ÖNERİLERİ</SectionTitle>
          <Stack spacing={1.5}>
            {ai.suggestions.map((suggestion, index) => (
              <Box key={suggestion} sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start' }}>
                <Box
                  sx={{
                    width: 24, height: 24, flexShrink: 0, borderRadius: '50%', bgcolor: 'primary.main', color: '#fff',
                    fontSize: 13, fontWeight: 700, display: 'flex', alignItems: 'center', justifyContent: 'center',
                  }}
                >
                  {index + 1}
                </Box>
                <Typography sx={{ pt: 0.15 }}>{suggestion}</Typography>
              </Box>
            ))}
          </Stack>
        </Paper>
      )}
    </Stack>
  );
}

function SectionTitle({ icon, children }: { icon: ReactNode; children: string }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
      {icon}
      <Typography component="h3" sx={{ fontWeight: 700, fontSize: 14, letterSpacing: 0.3 }}>
        {children}
      </Typography>
    </Box>
  );
}
