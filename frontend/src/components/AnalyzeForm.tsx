import LinkIcon from '@mui/icons-material/Link';
import { Box, Button, Chip, InputAdornment, Stack, TextField } from '@mui/material';
import { useState, type FormEvent } from 'react';
import { parseJenkinsBuildUrl, type JenkinsBuild } from '../utils/jenkinsUrl';

interface Props {
  onAnalyze: (build: JenkinsBuild) => void;
  disabled: boolean;
}

export default function AnalyzeForm({ onAnalyze, disabled }: Props) {
  const [url, setUrl] = useState('');
  const parsed = url.trim() ? parseJenkinsBuildUrl(url) : null;
  const build = typeof parsed === 'object' ? parsed : null;
  const error = typeof parsed === 'string' ? parsed : null;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (build && !disabled) {
      onAnalyze(build);
    }
  }

  return (
    <Box component="form" onSubmit={handleSubmit}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ alignItems: 'flex-start' }}>
        <TextField
          fullWidth
          autoFocus
          placeholder="Paste Jenkins Build URL"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          disabled={disabled}
          error={error !== null}
          helperText={error ?? ' '}
          sx={{ '& .MuiOutlinedInput-root': { bgcolor: 'background.paper' } }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <LinkIcon />
                </InputAdornment>
              ),
            },
            htmlInput: { 'aria-label': 'Jenkins Build URL' },
          }}
        />
        <Button
          type="submit"
          variant="contained"
          size="large"
          disabled={!build || disabled}
          sx={{ height: 56, px: 4, whiteSpace: 'nowrap', flexShrink: 0 }}
        >
          Analyze Build
        </Button>
      </Stack>
      {build && (
        <Stack direction="row" spacing={1} sx={{ mt: -1, flexWrap: 'wrap' }}>
          <Chip size="small" label={`Job: ${build.jobName}`} />
          <Chip size="small" label={`Build: #${build.buildNumber}`} />
        </Stack>
      )}
    </Box>
  );
}
