import { Chip } from '@mui/material';

const colors = {
  SUCCESS: 'success',
  FAILURE: 'error',
  UNSTABLE: 'warning',
} as const;

/** Jenkins build status with Jenkins' colours; anything else (ABORTED, unknown) is grey. */
export default function StatusChip({ status }: { status: string | null }) {
  const label = status ?? 'UNKNOWN';
  const color = colors[label as keyof typeof colors] ?? 'default';
  return <Chip label={label} color={color} size="small" sx={{ fontWeight: 600 }} />;
}
