import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlineOutlined';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import FlagOutlinedIcon from '@mui/icons-material/FlagOutlined';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import ReportProblemOutlinedIcon from '@mui/icons-material/ReportProblemOutlined';
import WarningAmberOutlinedIcon from '@mui/icons-material/WarningAmberOutlined';
import {
  Accordion, AccordionDetails, AccordionSummary, Alert, Box, Chip, List, ListItem, ListItemButton, ListItemText, Paper,
  Stack, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material';
import { useState, type ReactNode } from 'react';
import { monoFont } from '../theme';
import type { Analysis, FailureGroup, TestFailure } from '../types/analysis';
import { simpleName } from '../utils/format';
import TestContextView from './TestContextView';

/**
 * The main screen: Hata Sebebi → where it failed → Çözüm Önerileri.
 * The only rule for "where": testContext present → IDE view, otherwise a plain error summary card.
 * A build with several failed tests shows the build summary instead, then its failure groups as a list
 * with the selected group's details next to it.
 */
export default function AiAnalysis({ analysis }: { analysis: Analysis }) {
  const { ai } = analysis;
  if (!ai) {
    return <Alert severity="success">Build başarılı. Analiz edilecek bir hata yok.</Alert>;
  }

  const failedTests = analysis.failureGroups.reduce((sum, group) => sum + group.failureCount, 0);
  if (failedTests > 1) {
    // keyed by analysis: another analysis starts with its own first group and test selected
    return <FailureGroups key={analysis.id} analysis={analysis} summary={ai.cause} failedTests={failedTests} />;
  }

  // overflowWrap (here and in FailureGroups): AI text can hold long code tokens such as
  // "pages.LoginPage.clickForgotPassword(…)" that would otherwise widen the page on a narrow screen
  return (
    <Stack spacing={2} sx={{ overflowWrap: 'anywhere' }}>
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

/**
 * Several failed tests: the counts, the AI's build summary, then the failure groups (or every failed test) as a
 * searchable list on the left and the selected group's answer and tests on the right.
 */
function FailureGroups({ analysis, summary, failedTests }: { analysis: Analysis; summary: string; failedTests: number }) {
  const groups = analysis.failureGroups;
  const [view, setView] = useState('groups');
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState({ group: groups[0].id, test: 0 });

  const analysed = groups.filter((group) => group.aiStatus === 'ANALYZED').length;
  const reported = analysis.testSummary;
  const hint = focusHint(groups, failedTests);
  const largest = Math.max(...groups.map((group) => group.failureCount));
  const visibleGroups = groups.filter((group) => matches(query, group));
  const visibleTests = groups
    .flatMap((group) => group.failures.map((failure, test) => ({ group, failure, test })))
    .filter((item) => matches(query, item.group, [item.failure]));
  const current = groups.find((group) => group.id === selected.group) ?? groups[0];
  const nothingFound = view === 'groups' ? visibleGroups.length === 0 : visibleTests.length === 0;

  return (
    <Stack spacing={2} sx={{ overflowWrap: 'anywhere' }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, minmax(0, 1fr))' }, gap: 2 }}>
        <Stat label="Başarısız test" value={reported?.failed ?? failedTests} total={reported?.total} color="error.main" />
        <Stat label="Hata grubu" value={groups.length} />
        <Stat label="AI analizi" value={analysed} total={groups.length} unit="grup" />
      </Box>

      <Paper sx={{ p: 3, borderLeft: 4, borderLeftColor: 'error.main' }}>
        <SectionTitle icon={<ReportProblemOutlinedIcon color="error" />}>HATA SEBEBİ</SectionTitle>
        <Typography variant="h6" component="p" sx={{ fontWeight: 600, lineHeight: 1.45 }}>
          {summary}
        </Typography>
        {hint && (
          <Stack direction="row" spacing={1} sx={{ mt: 1.5, alignItems: 'center', color: 'text.secondary' }}>
            <FlagOutlinedIcon fontSize="small" />
            <Typography>{hint}</Typography>
          </Stack>
        )}
      </Paper>

      {reported?.failed != null && reported.failed > failedTests && (
        <Alert severity="info">
          Test özetinde {reported.failed} başarısız test var; logdan {failedTests} tanesinin ayrıntısı çıkarılabildi.
        </Alert>
      )}
      {analysed < groups.length && (
        <Alert severity="warning">
          {groups.length - analysed} hata grubu AI ile ayrıntılı analiz edilmedi; listede uyarı işaretiyle gösteriliyor,
          nedeni grubun ayrıntısında yazıyor.
        </Alert>
      )}

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: '300px minmax(0, 1fr)' }, gap: 2, alignItems: 'start' }}>
        <Paper sx={{ overflow: 'hidden', position: { md: 'sticky' }, top: { md: 16 } }}>
          <Box sx={{ p: 1.5, borderBottom: 1, borderColor: 'divider' }}>
            <ToggleButtonGroup size="small" exclusive fullWidth value={view} onChange={(_, value) => value && setView(value)}>
              <ToggleButton value="groups">Hata grupları ({groups.length})</ToggleButton>
              <ToggleButton value="tests">Testler ({failedTests})</ToggleButton>
            </ToggleButtonGroup>
            <TextField
              size="small"
              fullWidth
              placeholder="Senaryo, feature, exception ara"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              sx={{ mt: 1.5, '& input': { fontSize: 14 } }}
            />
          </Box>
          <List disablePadding sx={{ maxHeight: { md: 'calc(100vh - 170px)' }, overflow: 'auto' }}>
            {view === 'groups' && visibleGroups.map((group) => (
              <ListItem key={group.id} disablePadding divider>
                <ListItemButton
                  selected={group.id === current.id}
                  onClick={() => setSelected({ group: group.id, test: 0 })}
                  sx={{ display: 'block', py: 1.25 }}
                >
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Chip label={group.id} size="small" variant="outlined" />
                    <Typography noWrap sx={{ flex: 1, minWidth: 0, fontWeight: 600 }}>{exceptionName(group)}</Typography>
                    {group.aiStatus !== 'ANALYZED' && (
                      <WarningAmberOutlinedIcon color="warning" sx={{ fontSize: 18 }} titleAccess="AI analizi yok" />
                    )}
                    <Typography sx={{ fontWeight: 700 }}>{group.failureCount}</Typography>
                  </Stack>
                  <Box sx={{ mt: 1, height: 4, borderRadius: 2, bgcolor: 'grey.200' }}>
                    <Box sx={{ height: '100%', borderRadius: 2, bgcolor: 'error.main', width: `${(group.failureCount / largest) * 100}%` }} />
                  </Box>
                  <Typography noWrap sx={{ mt: 0.75, fontFamily: monoFont, fontSize: 12, color: 'text.secondary' }}>
                    {fileLine(group) ?? 'Kod konumu logda yok'}
                  </Typography>
                </ListItemButton>
              </ListItem>
            ))}
            {view === 'tests' && visibleTests.map(({ group, failure, test }) => (
              <ListItem key={`${group.id}-${test}`} disablePadding divider>
                <ListItemButton
                  selected={group.id === current.id && test === selected.test}
                  onClick={() => setSelected({ group: group.id, test })}
                >
                  <ListItemText
                    primary={failure.testContext?.scenario ?? 'Test adı logda yok'}
                    secondary={`${group.id} · ${place(failure) ?? exceptionName(group)}`}
                    slotProps={{ primary: { noWrap: true }, secondary: { noWrap: true } }}
                  />
                </ListItemButton>
              </ListItem>
            ))}
          </List>
          {nothingFound && <Typography color="text.secondary" sx={{ p: 2 }}>Eşleşen sonuç yok.</Typography>}
        </Paper>

        <GroupDetail
          group={current}
          share={Math.round((current.failureCount / failedTests) * 100)}
          selectedTest={current.id === selected.group ? selected.test : 0}
          onSelectTest={(test) => setSelected({ group: current.id, test })}
        />
      </Box>
    </Stack>
  );
}

/** The selected group: the AI's answer (or why there is none), its failed tests and the selected one in the IDE view. */
function GroupDetail({ group, share, selectedTest, onSelectTest }: {
  group: FailureGroup;
  share: number;
  selectedTest: number;
  onSelectTest: (test: number) => void;
}) {
  const failure = group.failures[selectedTest];

  return (
    <Paper sx={{ p: 3, minWidth: 0 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Chip label={group.id} size="small" color="primary" />
        <Typography variant="h6" component="h3" sx={{ fontWeight: 700 }}>{exceptionName(group)}</Typography>
        <Typography color="text.secondary">{group.failureCount} test · %{share}</Typography>
      </Stack>
      <Typography sx={{ mt: 0.5, mb: 2.5, fontFamily: monoFont, fontSize: 13, color: 'text.secondary', wordBreak: 'break-word' }}>
        {codeFrame(group) ?? 'Kod konumu logda yok'}
      </Typography>

      {group.aiStatus === 'ANALYZED' ? (
        <Box sx={{ mb: 2.5 }}>
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
        <Alert severity="warning" sx={{ mb: 2.5 }}>
          AI analizi yok: {group.aiNote}
        </Alert>
      )}

      <Typography component="h4" sx={{ fontWeight: 700, fontSize: 14, mb: 1 }}>
        ETKİLENEN TESTLER ({group.failureCount})
      </Typography>
      <List dense disablePadding sx={{ maxHeight: 280, overflow: 'auto', border: 1, borderColor: 'divider', borderRadius: 1, mb: 2 }}>
        {group.failures.map((item, index) => (
          <ListItem key={index} disablePadding>
            <ListItemButton selected={index === selectedTest} onClick={() => onSelectTest(index)}>
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

      <Accordion disableGutters sx={{ mt: 2 }}>
        <AccordionSummary expandIcon={<ExpandMoreIcon />}>
          <Typography sx={{ fontWeight: 700, fontSize: 14 }}>TEKNİK DETAY</Typography>
          <Typography color="text.secondary" sx={{ fontSize: 14, ml: 1 }}>
            hata imzası ve grubun ilk testinin stack trace'i
          </Typography>
        </AccordionSummary>
        <AccordionDetails>
          <Box
            component="pre"
            sx={{ m: 0, fontFamily: monoFont, fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 360, overflow: 'auto' }}
          >
            {group.stackTrace ? `${group.signature}\n\n${group.stackTrace}` : group.signature}
          </Box>
        </AccordionDetails>
      </Accordion>
    </Paper>
  );
}

/** One count tile, e.g. "Başarısız test 20 / 30". */
function Stat({ label, value, total, unit, color }: {
  label: string;
  value: number;
  total?: number | null;
  unit?: string;
  color?: string;
}) {
  return (
    <Paper sx={{ px: 2.5, py: 1.75 }}>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography sx={{ fontSize: 26, fontWeight: 700, lineHeight: 1.3, color }}>
        {value}
        {total != null && (
          <Typography component="span" color="text.secondary" sx={{ fontSize: 15, fontWeight: 400 }}>
            {' '}/ {total}{unit ? ` ${unit}` : ''}
          </Typography>
        )}
      </Typography>
    </Paper>
  );
}

/**
 * "20 başarısız testten 13 tanesi G1 ve G2'de toplanıyor; önce bu gruplara bakın." Only when the largest group,
 * or the largest two, hold at least half of the failed tests and are clearly the largest (no tie with the next).
 */
function focusHint(groups: FailureGroup[], failedTests: number): string | null {
  const [first, second, third] = groups;
  if (second && first.failureCount * 2 >= failedTests && first.failureCount > second.failureCount) {
    return `${failedTests} başarısız testten ${first.failureCount} tanesi ${first.id}'de toplanıyor; önce bu gruba bakın.`;
  }
  const topTwo = first.failureCount + (second?.failureCount ?? 0);
  if (third && topTwo * 2 >= failedTests && second.failureCount > third.failureCount) {
    return `${failedTests} başarısız testten ${topTwo} tanesi ${first.id} ve ${second.id}'de toplanıyor; önce bu gruplara bakın.`;
  }
  return null;
}

/** Search over what a user remembers of a failure: group id, exception, scenario, feature file, step. */
function matches(query: string, group: FailureGroup, failures: TestFailure[] = group.failures): boolean {
  const words = [
    group.id,
    group.exceptionType,
    ...failures.flatMap((failure) => [failure.testContext?.scenario, failure.testContext?.featureFile, failure.testContext?.step]),
  ];
  return fold(words.filter(Boolean).join(' ')).includes(fold(query.trim()));
}

/** Case- and accent-insensitive, so "Şifremi", "sifremi" and "ŞİFREMİ" match each other. */
function fold(text: string): string {
  return text.toLocaleLowerCase('tr').replace(/ı/g, 'i').normalize('NFD').replace(/[̀-ͯ]/g, '');
}

function exceptionName(group: FailureGroup): string {
  return group.exceptionType ? simpleName(group.exceptionType) : 'Exception yok';
}

/** "LoginPage.java:50" — only what the log showed. */
function fileLine(group: FailureGroup): string | null {
  if (!group.exceptionFile) {
    return null;
  }
  return group.exceptionLine != null ? `${group.exceptionFile}:${group.exceptionLine}` : group.exceptionFile;
}

/** "pages.LoginPage.clickForgotPassword(LoginPage.java:50)": the location part of the signature, when the log had a code frame. */
function codeFrame(group: FailureGroup): string | null {
  const at = group.signature.lastIndexOf(' @ ');
  return group.exceptionFile && at >= 0 ? group.signature.substring(at + 3) : fileLine(group);
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
