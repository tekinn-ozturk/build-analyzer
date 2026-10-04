import CancelIcon from '@mui/icons-material/Cancel';
import InsertDriveFileOutlinedIcon from '@mui/icons-material/InsertDriveFileOutlined';
import PlaceOutlinedIcon from '@mui/icons-material/PlaceOutlined';
import { Box, Paper, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { monoFont } from '../theme';
import type { TestContext } from '../types/analysis';

// IntelliJ dark ("Darcula"-like) colours.
const colors = {
  background: '#1e1f22',
  tabBar: '#2b2d30',
  gutter: '#6f737a',
  text: '#bcbec4',
  keyword: '#cf8e6d',
  error: '#f75464',
  errorLine: 'rgba(247, 84, 100, 0.16)',
};

interface Props {
  context: TestContext;
  errorType: string | null;
  errorMessage: string | null;
}

/** Editor-like view of where a test failed. Every context field is optional; missing ones are simply not drawn. */
export default function TestContextView({ context, errorType, errorMessage }: Props) {
  const { scenario, step, featureFile, featureLine } = context;
  const error = [errorType, errorMessage].filter(Boolean).join(': ');

  return (
    <Paper sx={{ bgcolor: colors.background, color: colors.text, borderColor: colors.tabBar, overflow: 'hidden' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2.5, py: 1.5 }}>
        <PlaceOutlinedIcon sx={{ color: colors.error }} fontSize="small" />
        <Typography sx={{ fontWeight: 700, fontSize: 14, color: '#fff' }}>HATA KONUMU</Typography>
      </Box>

      {featureFile && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2.5, py: 1, bgcolor: colors.tabBar }}>
          <InsertDriveFileOutlinedIcon sx={{ fontSize: 16, color: colors.gutter }} />
          <Typography sx={{ fontFamily: monoFont, fontSize: 13, wordBreak: 'break-all' }}>{featureFile}</Typography>
        </Box>
      )}

      <Box sx={{ fontFamily: monoFont, fontSize: 13, lineHeight: 1.9, py: 1.5, overflowX: 'auto' }}>
        {featureFile && (
          <CodeLine>
            <Keyword>Feature:</Keyword> {fileName(featureFile)}
          </CodeLine>
        )}
        {scenario && (
          <CodeLine indent={1}>
            <Keyword>Scenario:</Keyword> {scenario}
          </CodeLine>
        )}
        {step && (
          <>
            <CodeLine indent={2}>⋮</CodeLine>
            <CodeLine indent={2} number={featureLine ?? undefined} failed>
              {highlightKeyword(step)}
            </CodeLine>
          </>
        )}
        {error && (
          <CodeLine indent={2}>
            <Box component="span" sx={{ color: colors.error, whiteSpace: 'normal' }}>
              {error}
            </Box>
          </CodeLine>
        )}
        {!scenario && !step && !featureFile && (
          <CodeLine>
            <Box component="span" sx={{ color: colors.gutter }}>Test konumu bilgisi yok.</Box>
          </CodeLine>
        )}
      </Box>
    </Paper>
  );
}

function CodeLine({ children, number, indent = 0, failed }: {
  children: ReactNode;
  number?: number;
  indent?: number;
  failed?: boolean;
}) {
  return (
    <Box sx={{ display: 'flex', bgcolor: failed ? colors.errorLine : 'transparent', minWidth: 'max-content' }}>
      <Box component="span" sx={{ width: 48, flexShrink: 0, textAlign: 'right', color: colors.gutter, userSelect: 'none' }}>
        {number}
      </Box>
      <Box component="span" sx={{ width: 28, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {failed && <CancelIcon sx={{ fontSize: 14, color: colors.error }} />}
      </Box>
      <Box component="span" sx={{ pl: indent * 2, pr: 2, whiteSpace: 'pre' }}>
        {children}
      </Box>
    </Box>
  );
}

function Keyword({ children }: { children: ReactNode }) {
  return (
    <Box component="span" sx={{ color: colors.keyword }}>
      {children}
    </Box>
  );
}

/** Colours a leading Gherkin keyword ("Then …"); anything else is shown as is. */
function highlightKeyword(step: string): ReactNode {
  const match = step.match(/^(Given|When|Then|And|But|\*)\s+(.*)$/);
  return match ? <><Keyword>{match[1]}</Keyword> {match[2]}</> : step;
}

/** "src/test/resources/features/Login.feature" → "Login.feature". */
function fileName(path: string): string {
  return path.substring(path.search(/[^\\/]*$/));
}
