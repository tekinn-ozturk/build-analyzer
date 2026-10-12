import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlineOutlined';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import ReportProblemOutlinedIcon from '@mui/icons-material/ReportProblemOutlined';
import { Alert, Box, Chip, List, ListItem, ListItemButton, ListItemText, Paper, Stack, Typography } from '@mui/material';
import { useState, type ReactNode } from 'react';
import { monoFont } from '../theme';
import type { Analysis, FailureGroup, TestFailure } from '../types/analysis';
import { simpleName } from '../utils/format';
import TestContextView from './TestContextView';

/**
 * The main screen: Hata Sebebi → where it failed → Çözüm Önerileri.
 * The only rule for "where": testContext present → IDE view, otherwise a plain error summary card.
 * A build with several failed tests shows the build summary instead, then one card per failure group.
 */
export default function AiAnalysis({ analysis }: { analysis: Analysis }) {
  const { ai } = analysis;
  if (!ai) {
    return <Alert severity="success">Build başarılı. Analiz edilecek bir hata yok.</Alert>;
  }

  const failedTests = analysis.failureGroups.reduce((sum, group) => sum + group.failureCount, 0);
  if (failedTests > 1) {
    return <FailureGroups analysis={analysis} summary={ai.cause} failedTests={failedTests} />;
  }

  return (
    <Stack spacing={2}>
      <Paper sx={{ p: 3, borderLeft: 4, borderLeftColor: 'error.main' }}>
        <SectionTitle icon={<ReportProblemOutlinedIcon color="error" />}>HATA SEBEBİ</SectionTitle>
        <Typography variant="h6" component="p" sx={{ fontWeight: 600, lineHeight: 1.45 }}>
          {ai.cause}
        </Typography>
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
          <Suggestions items={ai.suggestions} />
        </Paper>
      )}
    </Stack>
  );
}

/** Several failed tests: the AI's build summary, what was (not) analysed, then one card per failure group. */
function FailureGroups({ analysis, summary, failedTests }: { analysis: Analysis; summary: string; failedTests: number }) {
  const groups = analysis.failureGroups;
  const analysed = groups.filter((group) => group.aiStatus === 'ANALYZED').length;
  const reportedFailed = analysis.testSummary?.failed ?? null;

  return (
    <Stack spacing={2}>
      <Paper sx={{ p: 3, borderLeft: 4, borderLeftColor: 'error.main' }}>
        <SectionTitle icon={<ReportProblemOutlinedIcon color="error" />}>HATA SEBEBİ</SectionTitle>
        <Typography variant="h6" component="p" sx={{ fontWeight: 600, lineHeight: 1.45 }}>
          {summary}
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 1.5 }}>
          {failedTests} başarısız test · {groups.length} hata grubu · {analysed} grup AI ile ayrıntılı analiz edildi
        </Typography>
      </Paper>

      {reportedFailed !== null && reportedFailed > failedTests && (
        <Alert severity="info">
          Test özetinde {reportedFailed} başarısız test var; logdan {failedTests} tanesinin ayrıntısı çıkarılabildi.
        </Alert>
      )}
      {analysed < groups.length && (
        <Alert severity="warning">
          {groups.length - analysed} hata grubu AI ile ayrıntılı analiz edilmedi; nedeni grubun kartında yazıyor.
        </Alert>
      )}

      {/* keyed by analysis too: another analysis's G1 starts with its own first test selected */}
      {groups.map((group) => (
        <FailureGroupCard key={`${analysis.id}-${group.id}`} group={group} />
      ))}
    </Stack>
  );
}

/** One failure group: the AI's answer (or why there is none) and its failed tests; the selected one opens in the IDE view. */
function FailureGroupCard({ group }: { group: FailureGroup }) {
  const [selected, setSelected] = useState(0);
  const failure = group.failures[selected];

  return (
    <Paper sx={{ p: 3 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 1 }}>
        <Chip label={group.id} size="small" color="primary" />
        <Typography sx={{ fontWeight: 700 }}>
          {group.exceptionType ? simpleName(group.exceptionType) : 'Exception yok'}
        </Typography>
        <Chip label={`${group.failureCount} test`} size="small" variant="outlined" />
      </Stack>
      <Typography sx={{ fontFamily: monoFont, fontSize: 12, color: 'text.secondary', wordBreak: 'break-word', mb: 2 }}>
        {group.signature}
      </Typography>

      {group.aiStatus === 'ANALYZED' ? (
        <Box sx={{ mb: 2 }}>
          <SectionTitle icon={<ReportProblemOutlinedIcon color="error" />}>HATA SEBEBİ</SectionTitle>
          <Typography sx={{ fontWeight: 600, mb: 2 }}>{group.rootCause}</Typography>
          {group.actions.length > 0 && (
            <>
              <SectionTitle icon={<CheckCircleOutlineIcon color="success" />}>ÇÖZÜM ÖNERİLERİ</SectionTitle>
              <Suggestions items={group.actions} />
            </>
          )}
        </Box>
      ) : (
        <Alert severity="warning" sx={{ mb: 2 }}>
          AI analizi yok: {group.aiNote}
        </Alert>
      )}

      <Typography component="h4" sx={{ fontWeight: 700, fontSize: 14, mb: 1 }}>
        ETKİLENEN TESTLER ({group.failureCount})
      </Typography>
      <List dense disablePadding sx={{ maxHeight: 240, overflow: 'auto', border: 1, borderColor: 'divider', borderRadius: 1, mb: 2 }}>
        {group.failures.map((item, index) => (
          <ListItem key={index} disablePadding>
            <ListItemButton selected={index === selected} onClick={() => setSelected(index)}>
              <ListItemText primary={item.testContext?.scenario ?? 'Test adı logda yok'} secondary={place(item)} />
            </ListItemButton>
          </ListItem>
        ))}
      </List>

      {failure && (
        <TestContextView
          context={failure.testContext ?? {}}
          errorType={failure.exceptionType ? simpleName(failure.exceptionType) : null}
          errorMessage={failure.message}
        />
      )}
    </Paper>
  );
}

/** "Login.feature:11" — only what the log showed; nothing when the test has no feature file. */
function place(failure: TestFailure): string | null {
  const file = failure.testContext?.featureFile;
  if (!file) {
    return null;
  }
  const name = file.substring(file.search(/[^\\/]*$/));
  const line = failure.testContext?.featureLine;
  return line != null ? `${name}:${line}` : name;
}

function Suggestions({ items }: { items: string[] }) {
  return (
    <Stack spacing={1.5}>
      {items.map((suggestion, index) => (
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
