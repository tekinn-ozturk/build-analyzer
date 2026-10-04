import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import { Box, Button, Stack, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useState } from 'react';
import { monoFont } from '../theme';

interface Props {
  relevantLogSnippet: string | null;
  last200Lines: string | null;
  buildUrl: string;
}

const errorLine = /ERROR|FAILURE|FAILED|Exception|Caused by:/;

/** The two log views the backend provides. The full log stays on Jenkins. */
export default function LogViewer({ relevantLogSnippet, last200Lines, buildUrl }: Props) {
  const [view, setView] = useState<'snippet' | 'last200'>('snippet');
  const log = view === 'snippet' ? relevantLogSnippet : last200Lines;
  const lines = log ? log.split('\n') : [];

  return (
    <Box>
      <Stack direction="row" sx={{ mb: 1.5, flexWrap: 'wrap', gap: 1, alignItems: 'center', justifyContent: 'space-between' }}>
        <ToggleButtonGroup
          size="small"
          exclusive
          value={view}
          onChange={(_, value) => value && setView(value)}
        >
          <ToggleButton value="snippet">Relevant Snippet</ToggleButton>
          <ToggleButton value="last200">Last 200 Lines</ToggleButton>
        </ToggleButtonGroup>
        <Button size="small" endIcon={<OpenInNewIcon />} href={`${buildUrl}console`} target="_blank" rel="noreferrer">
          Tam log (Jenkins)
        </Button>
      </Stack>

      <Box
        sx={{
          bgcolor: '#1e1f24',
          color: '#d4d7dd',
          borderRadius: 1,
          fontFamily: monoFont,
          fontSize: 13,
          lineHeight: 1.6,
          maxHeight: 600,
          overflow: 'auto',
          py: 1,
        }}
      >
        {lines.length === 0 && <Box sx={{ px: 2, color: '#8b8f98' }}>Log yok.</Box>}
        {lines.map((line, index) => (
          <Box
            key={index}
            sx={{
              display: 'flex',
              bgcolor: errorLine.test(line) ? 'rgba(244, 67, 54, 0.18)' : 'transparent',
              width: 'max-content',
              minWidth: '100%',
            }}
          >
            <Box
              component="span"
              sx={{ width: 48, flexShrink: 0, pr: 1.5, textAlign: 'right', color: '#6b6f78', userSelect: 'none' }}
            >
              {index + 1}
            </Box>
            <Box component="span" sx={{ whiteSpace: 'pre', pr: 2 }}>
              {line}
            </Box>
          </Box>
        ))}
      </Box>
      <Typography variant="caption" color="text.secondary">
        Satır numaraları bu görünüme göredir, Jenkins konsolundaki satır numaraları değildir.
      </Typography>
    </Box>
  );
}
